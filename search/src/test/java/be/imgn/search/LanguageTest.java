package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class LanguageTest {

    private final List<String> detected = new ArrayList<>();

    // French when the text has "le ", sure of it; English otherwise, unsure.
    private final LanguageDetector detector = text -> {
        detected.add(text.toString());
        return text.toString().contains("le ")
                ? List.of(new LanguageDetector.Guess(Locale.FRENCH, 0.9))
                : List.of(new LanguageDetector.Guess(Locale.ENGLISH, 0.5));
    };

    private final Schema schema = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .keyword("tags", Stored.YES)
            .languages(List.of(ToyAnalyzer.FRENCH, ToyAnalyzer.ENGLISH))
            .languageDetector(detector, 0.8)
            .build();

    @Test
    void routesAValueInItsOwnLanguage() throws IOException {
        try (var index = Index.inMemory(schema)) {
            add(
                    index,
                    Document.builder("x")
                            .text("title", "renards", Locale.FRENCH)
                            .build());

            assertThat(search(index, Locale.FRENCH, "renard")).containsExactly("x");
            assertThat(search(index, Locale.CANADA_FRENCH, "renard")).containsExactly("x");
            assertThat(search(index, null, "renard")).isEmpty();
            assertThat(search(index, Locale.ENGLISH, "renard")).isEmpty();
            assertThat(detected).isEmpty();
        }
    }

    @Test
    void routesAValueInTheDocumentsLanguageUnlessItHasItsOwn() throws IOException {
        try (var index = Index.inMemory(schema)) {
            add(
                    index,
                    Document.builder("x")
                            .language(Locale.FRENCH)
                            .text("title", "renards")
                            .text("title", "foxes", Locale.ENGLISH)
                            .build());

            assertThat(search(index, Locale.FRENCH, "renard")).containsExactly("x");
            assertThat(search(index, Locale.ENGLISH, "fox")).containsExactly("x");
            assertThat(search(index, Locale.FRENCH, "foxe")).isEmpty();
            assertThat(search(index, Locale.ENGLISH, "renard")).isEmpty();
        }
    }

    @Test
    void detectsTheLanguageOfADocumentFromItsValuesWithoutOne() throws IOException {
        try (var index = Index.inMemory(schema)) {
            add(
                    index,
                    Document.builder("x")
                            .text("title", "le chat")
                            .text("title", "renards")
                            .text("title", "the foxes", Locale.ENGLISH)
                            .keyword("tags", "le tag")
                            .build());

            assertThat(detected).containsExactly("le chat\nrenards\n");
            assertThat(search(index, Locale.FRENCH, "renard")).containsExactly("x");
            try (var searcher = index.searcher()) {
                var stored = searcher.document(
                        searcher.search(Query.term("title", "chat"), 1).getFirst());
                assertThat(stored.language()).contains(Locale.FRENCH);
                assertThat(stored.texts("title"))
                        .containsExactly(
                                new StoredText("le chat", Locale.FRENCH),
                                new StoredText("renards", Locale.FRENCH),
                                new StoredText("the foxes", Locale.ENGLISH));
                assertThat(stored.values("tags")).containsExactly("le tag");
            }
        }
    }

    @Test
    void givesNoLanguageBelowTheThreshold() throws IOException {
        try (var index = Index.inMemory(schema)) {
            add(index, Document.builder("x").text("title", "the foxes").build());

            assertThat(search(index, Locale.ENGLISH, "fox")).isEmpty();
            try (var searcher = index.searcher()) {
                var stored = searcher.document(
                        searcher.search(Query.term("title", "foxes"), 1).getFirst());
                assertThat(stored.language()).isEmpty();
                assertThat(stored.texts("title")).containsExactly(new StoredText("the foxes", null));
            }
        }
    }

    @Test
    void keepsALanguageWithoutAnAnalyzerInTheNeutralFieldOnly() throws IOException {
        try (var index = Index.inMemory(schema)) {
            add(
                    index,
                    Document.builder("x")
                            .language(Locale.GERMAN)
                            .text("title", "Füchse")
                            .build());

            assertThat(search(index, Locale.GERMAN, "füchse")).containsExactly("x");
            assertThat(search(index, Locale.GERMAN, "fuchs")).isEmpty();
            try (var searcher = index.searcher()) {
                var stored = searcher.document(
                        searcher.search(Query.term("title", "füchse"), 1).getFirst());
                assertThat(stored.language()).contains(Locale.GERMAN);
            }
        }
    }

    @Test
    void scoresTheBestOfTheNeutralAndLanguageVariants() throws IOException {
        try (var index = Index.inMemory(schema)) {
            add(
                    index,
                    Document.builder("one")
                            .language(Locale.FRENCH)
                            .text("title", "renard")
                            .build(),
                    Document.builder("two")
                            .language(Locale.FRENCH)
                            .text("title", "renards")
                            .build());

            try (var searcher = index.searcher()) {
                var neutral = searcher.search(Query.term("title", "renard"), 10);
                var french = searcher.search(Locale.FRENCH, Query.term("title", "renard"), 10);

                // Neutral: N = 2, df = 1, both lengths 1, so the length part is k1 and the score is the idf.
                assertThat(neutral).extracting(Hit::key).containsExactly("one");
                assertThat(neutral.getFirst().score()).isCloseTo((float) Math.log(2), within(1e-6f));
                // French: df = 2 in title.fr; "one" keeps its better neutral score.
                assertThat(french).extracting(Hit::key).containsExactly("one", "two");
                assertThat(french.get(0).score()).isCloseTo((float) Math.log(2), within(1e-6f));
                assertThat(french.get(1).score()).isCloseTo((float) Math.log(1.2), within(1e-6f));
            }
        }
    }

    @Test
    void searchesATermOfSeveralWordsInTheNeutralFieldOnly() throws IOException {
        try (var index = Index.inMemory(schema)) {
            add(
                    index,
                    Document.builder("x")
                            .language(Locale.FRENCH)
                            .text("title", "renards bruns")
                            .build());

            assertThat(search(index, Locale.FRENCH, "renards bruns")).containsExactly("x");
            assertThat(search(index, Locale.FRENCH, "renard brun")).isEmpty();
            assertThat(search(index, Locale.FRENCH, "bruns renards")).isEmpty();
            assertThat(search(index, Locale.FRENCH, "brun")).containsExactly("x");
        }
    }

    private static void add(Index index, Document... documents) throws IOException {
        try (var writer = index.writer()) {
            for (var document : documents) {
                writer.add(document);
            }
        }
    }

    private static List<String> search(Index index, @Nullable Locale language, String text) {
        try (var searcher = index.searcher()) {
            var query = Query.term("title", text);
            var hits = language == null ? searcher.search(query, 10) : searcher.search(language, query, 10);
            return hits.stream().map(Hit::key).toList();
        }
    }
}
