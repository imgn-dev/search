package be.imgn.search.langdetect.training;

import be.imgn.search.langdetect.internal.Text;
import be.imgn.search.langdetect.training.Corpus.Sentence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.jspecify.annotations.Nullable;

/// The sentences of the corpus the tool uses, split into training, validation and test sentences, with a
/// deterministic share of each kept per language, so that the large languages do not drown the small ones.
///
/// @param training the normalized training sentences of each language the detector learns
/// @param validation the validation sentences, by name of language ([Language#name()], or the code of a
///     language the detector does not learn), in the order samples are built in
/// @param test the test sentences, likewise
record Pools(
        SequencedMap<Language, List<Text>> training,
        Map<String, List<String>> validation,
        Map<String, List<String>> test) {

    /// The most chars of training sentences per language.
    static final int TRAINING_CHARS = 1_000_000;

    /// The most validation sentences per language.
    static final int VALIDATION_SENTENCES = 6_000;

    /// The most test sentences per language.
    static final int TEST_SENTENCES = 2_000;

    /// Reads the pools from the corpus at `path`, in two passes: the first counts what there is, the second keeps
    /// a share of it.
    static Pools read(Path path) throws IOException {
        var known = new HashMap<String, List<Language>>();
        for (var language : Language.ALL) {
            for (var code : language.codes()) {
                known.computeIfAbsent(code, c -> new ArrayList<>()).add(language);
            }
        }
        var codes = new HashSet<>(known.keySet());
        codes.addAll(Language.UNKNOWN);

        var counts = new HashMap<Part, Long>();
        Corpus.read(path, codes, sentence -> {
            var labelled = label(sentence, known);
            if (labelled != null) {
                var split = labelled.split();
                counts.merge(
                        new Part(labelled.name(), split),
                        split == Split.TRAINING ? (long) sentence.text().length() : 1L,
                        Long::sum);
            }
        });

        var kept = new Kept(counts);
        Corpus.read(path, codes, sentence -> {
            var labelled = label(sentence, known);
            if (labelled != null) {
                kept.add(labelled, sentence, known.containsKey(sentence.code()));
            }
        });

        var byLanguage = new LinkedHashMap<Language, List<Text>>();
        for (var language : Language.ALL) {
            byLanguage.put(language, kept.training.getOrDefault(language.name(), List.of()));
        }
        return new Pools(byLanguage, ordered(kept.validation), ordered(kept.test));
    }

    /// The name of the sentence's language, its normalized text and its split, or `null` if it has no letters,
    /// or is not in the script of a language the detector learns in its code.
    private static @Nullable Labelled label(Sentence sentence, Map<String, List<Language>> known) {
        var text = Text.of(sentence.text());
        var script = text.normalize();
        if (script == null) {
            return null;
        }
        var name = sentence.code();
        var languages = known.get(sentence.code());
        if (languages != null) {
            var language = languages.stream()
                    .filter(l -> l.script() == script)
                    .findFirst()
                    .orElse(null);
            if (language == null) {
                return null;
            }
            name = language.name();
        }
        return new Labelled(name, text, Split.of(text.toString()));
    }

    /// The texts of each language, ordered by a hash of their ids so that the samples mix the whole corpus.
    private static Map<String, List<String>> ordered(Map<String, List<Sentence>> sentences) {
        var ordered = new HashMap<String, List<String>>();
        sentences.forEach((name, list) -> ordered.put(
                name,
                list.stream()
                        .sorted(Comparator.comparingLong((Sentence s) -> Hashes.order(s.id()))
                                .thenComparingLong(Sentence::id))
                        .map(Sentence::text)
                        .toList()));
        return ordered;
    }

    /// A sentence with the name of its language, its normalized text and its split.
    private record Labelled(String name, Text text, Split split) {}

    /// The sentences of a language in a part of the split.
    private record Part(String name, Split split) {}

    /// The sentences the second pass keeps.
    private static final class Kept {

        private final Map<Part, Long> counts;
        private final Map<String, List<Text>> training = new HashMap<>();
        private final Map<String, List<Sentence>> validation = new HashMap<>();
        private final Map<String, List<Sentence>> test = new HashMap<>();

        /// @param counts the training chars, validation sentences and test sentences of each language
        Kept(Map<Part, Long> counts) {
            this.counts = counts;
        }

        void add(Labelled labelled, Sentence sentence, boolean learnt) {
            var name = labelled.name();
            var split = labelled.split();
            var count = counts.getOrDefault(new Part(name, split), 1L);
            switch (split) {
                case TRAINING -> {
                    if (learnt && Hashes.selected(sentence.id(), (double) TRAINING_CHARS / count)) {
                        training.computeIfAbsent(name, n -> new ArrayList<>()).add(labelled.text());
                    }
                }
                case VALIDATION -> {
                    if (Hashes.selected(sentence.id(), (double) VALIDATION_SENTENCES / count)) {
                        validation.computeIfAbsent(name, n -> new ArrayList<>()).add(sentence);
                    }
                }
                default -> {
                    if (Hashes.selected(sentence.id(), (double) TEST_SENTENCES / count)) {
                        test.computeIfAbsent(name, n -> new ArrayList<>()).add(sentence);
                    }
                }
            }
        }
    }
}
