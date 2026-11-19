package be.imgn.search.langdetect.training;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/// Builds samples of given lengths from sentences, and writes them for the tests.
final class Samples {

    /// The lengths of the samples, in chars.
    static final List<Integer> LENGTHS = List.of(50, 200, 1_000);

    private Samples() {}

    /// The samples of every language, learnt or not, with at most `counts.get(i)` samples of `LENGTHS.get(i)`
    /// chars per language. The lengths take disjoint slices of a language's sentences, and when the sentences are
    /// too few for all the samples, each length gets an equal share of their chars, the share a length
    /// does not need going to the others.
    ///
    /// @param sentences the sentences of each language by name, in the order to use them
    static List<Sample> of(Map<String, List<String>> sentences, List<Integer> counts) {
        var samples = new ArrayList<Sample>();
        for (var language : Language.ALL) {
            add(samples, sentences, counts, language.name(), language);
        }
        for (var code : Language.UNKNOWN) {
            add(samples, sentences, counts, code, null);
        }
        return samples;
    }

    private static void add(
            List<Sample> samples,
            Map<String, List<String>> sentences,
            List<Integer> counts,
            String name,
            @Nullable Language language) {
        var ofLanguage = sentences.getOrDefault(name, List.<String>of());
        var chars =
                ofLanguage.stream().mapToLong(sentence -> sentence.length() + 1).sum();
        var allowed = allowed(chars, counts);
        var remaining = ofLanguage.iterator();
        // The longest first: a sample cut short wastes the rest of its last sentence, and the short samples, which
        // waste the most, can best do without.
        for (var i = LENGTHS.size() - 1; i >= 0; i--) {
            var length = LENGTHS.get(i);
            var count = (int) (allowed[i] / length);
            for (var text : build(remaining, length, count)) {
                samples.add(new Sample(
                        name,
                        language == null ? null : language.tag(),
                        language == null ? null : language.script(),
                        length,
                        text));
            }
        }
    }

    /// The chars each length may take from `chars`: what its samples need, or an equal share of the chars when
    /// they are too few, the share a length does not need going to the others.
    private static long[] allowed(long chars, List<Integer> counts) {
        var needed = new long[LENGTHS.size()];
        for (var i = 0; i < needed.length; i++) {
            needed[i] = (long) LENGTHS.get(i) * counts.get(i);
        }
        var allowed = new long[needed.length];
        var left = chars;
        var lengthsLeft = needed.length;
        // From the length that needs the least.
        var order = IntStream.range(0, needed.length)
                .boxed()
                .sorted(Comparator.comparingLong(i -> needed[i]))
                .toList();
        for (var i : order) {
            allowed[i] = Math.min(needed[i], left / lengthsLeft);
            left -= allowed[i];
            lengthsLeft--;
        }
        return allowed;
    }

    /// Joins the next `sentences` into at most `max` samples of about `length` chars each, taking no more
    /// sentences than they need. A sample is cut at the last space before `length` chars, or at `length` chars if
    /// that space would leave less than half of it; the rest of its last sentence is dropped. Control chars become
    /// spaces, so that a sample fits on one line.
    static List<String> build(Iterator<String> sentences, int length, int max) {
        var samples = new ArrayList<String>();
        var sample = new StringBuilder();
        while (samples.size() < max && sentences.hasNext()) {
            var sentence = sentences.next();
            if (!sample.isEmpty()) {
                sample.append(' ');
            }
            sample.append(clean(sentence));
            if (sample.length() >= length) {
                var end = length;
                if (sample.length() > length) {
                    var space = sample.lastIndexOf(" ", length);
                    end = space >= length / 2 ? space : length;
                }
                samples.add(sample.substring(0, end).strip());
                sample.setLength(0);
            }
        }
        return samples;
    }

    /// Writes the samples as the tests read them, separated by tabs: a few comment lines; the validation counts of
    /// each language and length, each as `#validation`, the language name, the length, the number of samples
    /// detected right (rejected for an unknown language) and the number of samples; then one sample per line, as
    /// its expected language (`-` for none), its name, its length and its text.
    ///
    /// @param validated the number of validation samples and of those detected right, by language name, then length
    static void write(Path path, List<Sample> samples, Map<String, int[]> validated) throws IOException {
        var lines = new ArrayList<String>();
        lines.add("# Held-out samples of the language detector's tests, written by its training tool.");
        lines.add("# Sentences from Tatoeba (https://tatoeba.org), CC BY 2.0 FR"
                + " (https://creativecommons.org/licenses/by/2.0/fr/), none of which the model learnt from.");
        lines.add("# The validation counts the tests derive their minimums from, then the samples.");
        validated.forEach((name, counts) -> {
            for (var i = 0; i < LENGTHS.size(); i++) {
                lines.add(String.join(
                        "\t",
                        "#validation",
                        name,
                        LENGTHS.get(i).toString(),
                        Integer.toString(counts[2 * i + 1]),
                        Integer.toString(counts[2 * i])));
            }
        });
        lines.add("# Columns: expected language (- for none), corpus language, sample length, text.");
        for (var sample : samples) {
            var language = sample.language();
            lines.add(String.join(
                    "\t",
                    language == null ? "-" : language,
                    sample.name(),
                    Integer.toString(sample.length()),
                    sample.text()));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String clean(String sentence) {
        var chars = sentence.toCharArray();
        for (var i = 0; i < chars.length; i++) {
            var type = Character.getType(chars[i]);
            if (Character.isISOControl(chars[i])
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                chars[i] = ' ';
            }
        }
        return new String(chars).strip();
    }
}
