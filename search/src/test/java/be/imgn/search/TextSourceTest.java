package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.gaia.core.io.CharSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass
@EnumSource(StorageKind.class)
class TextSourceTest {

    // The chars at the start of a document's values without a language that the detector reads.
    private static final int DETECTED_LENGTH = 16_384;

    private final List<String> detected = new ArrayList<>();

    // French when the text has "le ", English otherwise.
    private final LanguageDetector detector = text -> {
        detected.add(text.toString());
        return text.toString().contains("le ")
                ? List.of(new LanguageDetector.Guess(Locale.FRENCH, 0.9))
                : List.of(new LanguageDetector.Guess(Locale.ENGLISH, 0.9));
    };

    private final Schema schema = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.KEEP, Accents.REMOVE, Stored.NO)
            .keyword("tags", Stored.YES)
            .defaultFields("title", "body")
            .languages(List.of(ToyAnalyzer.FRENCH, ToyAnalyzer.ENGLISH))
            .languageDetector(detector, 0.8)
            .build();
    private final StorageKind storage;
    private final Path directory;

    TextSourceTest(StorageKind storage, @TempDir Path directory) {
        this.storage = storage;
        this.directory = directory;
    }

    @Test
    void findsAndScoresASourceAsTheTextItGives() throws IOException {
        String[][] documents = {
            {"a", "Le renard brun", "le renard saute par-dessus les chiens; l’arbre est grand"},
            {"b", "The quick foxes", "foxes jump over the lazy dogs ".repeat(2000)},
            {"c", "Élèves et maîtres", "Les élèves écoutent. ".repeat(50) + "😀 x\uD800 fin"},
            {"d", "Straße", "STRASSE straße aujourd'hui Donau­dampf"}
        };
        try (var texts = storage.open(schema, directory);
                var sources = storage.open(schema, directory)) {
            try (var textWriter = texts.writer();
                    var sourceWriter = sources.writer()) {
                for (var document : documents) {
                    textWriter.add(Document.builder(document[0])
                            .text("title", document[1])
                            .text("body", document[2])
                            .text("title", document[1], Locale.ENGLISH)
                            .keyword("tags", document[0])
                            .build());
                    sourceWriter.add(Document.builder(document[0])
                            .text("title", new FakeSource(document[1]))
                            .text("body", new FakeSource(document[2]))
                            .text("title", new FakeSource(document[1]), Locale.ENGLISH)
                            .keyword("tags", document[0])
                            .build());
                }
            }

            for (var query : List.of(
                    "renard",
                    "renards",
                    "fox",
                    "foxes",
                    "eleves",
                    "élèves",
                    "strasse",
                    "\"lazy dogs\"",
                    "chiens OR dogs",
                    "title:straße",
                    "arbre",
                    "dampf",
                    "donaudampf",
                    "fin")) {
                for (var language : new @Nullable Locale[] {null, Locale.FRENCH, Locale.ENGLISH}) {
                    assertThat(hits(sources, query, language))
                            .as("%s in %s", query, language)
                            .isEqualTo(hits(texts, query, language));
                }
            }
            for (var document : documents) {
                assertThat(stored(sources, document[0])).isEqualTo(stored(texts, document[0]));
            }
        }
    }

    @Test
    void detectsFromTheStartOfASourceThenReadsItAgain() throws IOException {
        var start = "le renard ".repeat(DETECTED_LENGTH / 10 + 1).substring(0, DETECTED_LENGTH);
        var source = new FakeSource(start + " foxes");
        try (var index = storage.open(schema, directory)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("x")
                        .keyword("tags", "x")
                        .text("title", "un titre")
                        .text("title", source)
                        .build());
            }

            assertThat(detected).containsExactly(("un titre\n" + start).substring(0, DETECTED_LENGTH));
            assertThat(source.opened).isEqualTo(2);
            assertThat(source.closed).isEqualTo(2);
            // The value is indexed whole, in the detected language.
            assertThat(keys(index, Locale.FRENCH, "foxe")).containsExactly("x");
            assertThat(language(index, "x")).contains(Locale.FRENCH);
        }
    }

    @Test
    void detectsATextAndASourceOfTheSameContentAlike() throws IOException {
        // French only past the detected start.
        var content = "the foxes ".repeat(2000) + "le renard";
        try (var index = storage.open(schema, directory)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("text")
                        .keyword("tags", "text")
                        .text("title", content)
                        .build());
                writer.add(Document.builder("source")
                        .keyword("tags", "source")
                        .text("title", new FakeSource(content))
                        .build());
            }

            assertThat(detected).hasSize(2).allMatch(text -> text.equals(content.substring(0, DETECTED_LENGTH)));
            assertThat(language(index, "text")).contains(Locale.ENGLISH);
            assertThat(language(index, "source")).contains(Locale.ENGLISH);
        }
    }

    @Test
    void opensNoSourceForDetectionOnceTheDetectedTextIsFull() throws IOException {
        var text = "le renard ".repeat(DETECTED_LENGTH / 10);
        var sources = List.of(new FakeSource("the foxes"), new FakeSource("the foxes"), new FakeSource("the foxes"));
        try (var index = storage.open(schema, directory)) {
            try (var writer = index.writer()) {
                var builder = Document.builder("x").keyword("tags", "x").text("title", text);
                sources.forEach(source -> builder.text("title", source));
                writer.add(builder.build());
            }

            // The text and its line feed leave room for 3 chars of the first source only.
            assertThat(detected).containsExactly(text + "\nthe");
            assertThat(sources.getFirst().opened).isEqualTo(2);
            assertThat(sources.get(1).opened).isEqualTo(1);
            assertThat(sources.get(2).opened).isEqualTo(1);
            assertThat(language(index, "x")).contains(Locale.FRENCH);
        }
    }

    @Test
    void readsASourceOnceWhenNothingIsDetected() throws IOException {
        var ownLanguage = new FakeSource("le renard");
        var documentLanguage = new FakeSource("le renard");
        var noDetector = new FakeSource("le renard");
        try (var index = storage.open(schema, directory)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("own")
                        .text("title", ownLanguage, Locale.FRENCH)
                        .build());
                writer.add(Document.builder("document")
                        .language(Locale.FRENCH)
                        .text("title", documentLanguage)
                        .build());
            }
            assertThat(detected).isEmpty();
        }
        var withoutDetector = Schema.builder()
                .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
                .build();
        try (var index = storage.open(withoutDetector, directory);
                var writer = index.writer()) {
            writer.add(Document.builder("none").text("title", noDetector).build());
        }

        for (var source : List.of(ownLanguage, documentLanguage, noDetector)) {
            assertThat(source.opened).isEqualTo(1);
            assertThat(source.closed).isEqualTo(1);
        }
    }

    @Test
    void detectsFromTheFirstOpeningAndIndexesTheSecond() throws IOException {
        var source = new FakeSource("le renard", "foxes");
        try (var index = storage.open(schema, directory)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("x")
                        .keyword("tags", "x")
                        .text("title", source)
                        .build());
            }

            assertThat(detected).containsExactly("le renard\n");
            assertThat(language(index, "x")).contains(Locale.FRENCH);
            assertThat(keys(index, Locale.FRENCH, "foxe")).containsExactly("x");
            assertThat(keys(index, null, "renard")).isEmpty();
            assertThat(stored(index, "x")).containsExactly(new StoredText("foxes", Locale.FRENCH));
        }
    }

    @Test
    void leavesTheWriterUsableWhenASourceFails() throws IOException {
        // Failing on opening or after some chars, on the first opening (detection) or the second (indexing).
        List<FakeSource> failing = List.of(
                new FakeSource("le renard").failingAt(1, -1),
                new FakeSource("le renard").failingAt(2, -1),
                new FakeSource("le renard ".repeat(5000)).failingAt(1, 100),
                new FakeSource("le renard ".repeat(5000)).failingAt(2, 20_000),
                new FakeSource("le renard ".repeat(5000)).failingAt(2, 0));
        try (var index = storage.open(schema, directory)) {
            try (var writer = index.writer()) {
                for (var source : failing) {
                    assertThatThrownBy(() -> writer.add(Document.builder("x")
                                    .text("title", "un titre")
                                    .text("title", source)
                                    .keyword("tags", "failed")
                                    .build()))
                            .isSameAs(source.failure);
                    assertThat(source.closed).isEqualTo(source.readers);
                }
                writer.add(Document.builder("y")
                        .text("title", new FakeSource("le renard"))
                        .build());
                writer.refresh();
            }

            assertThat(keys(index, null, "renard")).containsExactly("y");
            assertThat(keys(index, null, "titre")).isEmpty();
            try (var searcher = index.searcher()) {
                assertThat(searcher.search(Query.term("tags", "failed"), 10)).isEmpty();
            }
        }
    }

    private static List<String> hits(Index index, String query, @Nullable Locale language) {
        try (var searcher = index.searcher()) {
            var parsed = index.schema().parseLenient(query);
            var hits = language == null ? searcher.search(parsed, 10) : searcher.search(language, parsed, 10);
            return hits.stream().map(hit -> hit.key() + "=" + hit.score()).toList();
        }
    }

    private static List<String> keys(Index index, @Nullable Locale language, String word) {
        try (var searcher = index.searcher()) {
            var query = Query.term("title", word);
            var hits = language == null ? searcher.search(query, 10) : searcher.search(language, query, 10);
            return hits.stream().map(Hit::key).toList();
        }
    }

    // The stored titles of the document tagged with its key.
    private static List<StoredText> stored(Index index, String key) {
        try (var searcher = index.searcher()) {
            return searcher.document(searcher.search(Query.term("tags", key), 1).getFirst())
                    .texts("title");
        }
    }

    // The language of the document tagged with its key.
    private static Optional<Locale> language(Index index, String key) {
        try (var searcher = index.searcher()) {
            return searcher.document(searcher.search(Query.term("tags", key), 1).getFirst())
                    .language();
        }
    }

    /// A source that counts its openings and closings, gives a content per opening, the last one from then on, and
    /// may fail.
    private static final class FakeSource implements CharSource {

        private final String[] contents;
        private int opened;
        private int readers;
        private int closed;
        private int failingOpening;
        private int failingAt;
        private @Nullable IOException failure;

        FakeSource(String... contents) {
            this.contents = contents;
        }

        // Fails on an opening: on opening itself when `at` is negative, else after giving `at` chars.
        FakeSource failingAt(int opening, int at) {
            failingOpening = opening;
            failingAt = at;
            failure = new IOException("Broken source");
            return this;
        }

        @Override
        public Reader openStream() throws IOException {
            opened++;
            var failing = opened == failingOpening;
            if (failing && failingAt < 0 && failure != null) {
                throw failure;
            }
            var content = contents[Math.min(opened, contents.length) - 1];
            var failsAt = failing ? failingAt : Integer.MAX_VALUE;
            readers++;
            return new Reader() {
                private int at;

                @Override
                public int read(char[] buffer, int offset, int length) throws IOException {
                    if (at >= failsAt && failure != null) {
                        throw failure;
                    }
                    if (at == content.length()) {
                        return -1;
                    }
                    var count = Math.min(Math.min(length, content.length() - at), Math.max(1, failsAt - at));
                    content.getChars(at, at + count, buffer, offset);
                    at += count;
                    return count;
                }

                @Override
                public void close() {
                    closed++;
                }
            };
        }
    }
}
