package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass
@EnumSource(StorageKind.class)
class SearchTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.DROP, Accents.REMOVE, Stored.NO)
            .keyword("tags", Stored.YES)
            .build();

    // N = 3 titles of lengths 1, 3 and 2: average 2. "fox" and "dog" are in 2 titles each.
    private static final double IDF = Math.log(1 + (3 - 2 + 0.5) / (2 + 0.5));
    private final StorageKind storage;
    private final Path directory;

    SearchTest(StorageKind storage, @TempDir Path directory) {
        this.storage = storage;
        this.directory = directory;
    }

    @Test
    void scoresTermsWithBm25() throws IOException {
        try (var index = index(false)) {
            var hits = search(index, Query.term("title", "fox"));

            assertThat(hits).extracting(Hit::key).containsExactly("a", "b");
            // a: f = 1, length 1: idf × 2.2 × 1 / (1 + 1.2 × (0.25 + 0.75 × 1 / 2)).
            assertThat(hits.get(0).score()).isCloseTo((float) (IDF * 2.2 / 1.75), within(1e-6f));
            assertThat(hits.get(0).score()).isCloseTo(0.590862f, within(1e-6f));
            // b: f = 2, length 3: idf × 2.2 × 2 / (2 + 1.2 × (0.25 + 0.75 × 3 / 2)).
            assertThat(hits.get(1).score()).isCloseTo((float) (IDF * 4.4 / 3.65), within(1e-6f));
        }
    }

    @Test
    void sumsTheStatisticsOfEverySegment() throws IOException {
        try (var single = index(false);
                var several = index(true)) {
            var query = Query.any(Query.term("title", "fox"), Query.term("title", "dog"));

            assertThat(search(several, query))
                    .usingRecursiveFieldByFieldElementComparatorOnFields("key", "score")
                    .isEqualTo(search(single, query));
            assertThat(several.searcher().search(query, 10)).hasSize(3);
        }
    }

    @Test
    void addsTheScoresOfAllAndAny() throws IOException {
        try (var index = index(false)) {
            var fox = score(index, Query.term("title", "fox"), "b");
            var dog = score(index, Query.term("title", "dog"), "b");

            assertThat(score(index, Query.all(Query.term("title", "fox"), Query.term("title", "dog")), "b"))
                    .isCloseTo(fox + dog, within(1e-6f));
            assertThat(score(index, Query.any(Query.term("title", "fox"), Query.term("title", "dog")), "b"))
                    .isCloseTo(fox + dog, within(1e-6f));
        }
    }

    @Test
    void scoresAPhraseWithItsFrequencyAndTheSumOfItsIdfs() throws IOException {
        try (var index = index(false)) {
            var hits = search(index, Query.phrase("title", "fox dog"));

            assertThat(hits).extracting(Hit::key).containsExactly("b");
            // Once in b ("fox fox dog"), length 3.
            assertThat(hits.getFirst().score()).isCloseTo((float) (2 * IDF * 2.2 / 2.65), within(1e-6f));
        }
    }

    @Test
    void turnsATermOfSeveralWordsIntoAPhraseWhereThereArePositions() throws IOException {
        try (var index = index(false)) {
            assertThat(search(index, Query.term("title", "fox dog")))
                    .extracting(Hit::key)
                    .containsExactly("b");
            assertThat(search(index, Query.term("title", "dog fox"))).isEmpty();
        }
    }

    @Test
    void turnsATermOfSeveralWordsIntoAnAllWhereThereAreNoPositions() throws IOException {
        try (var index = index(false)) {
            assertThat(search(index, Query.term("body", "fox dog")))
                    .extracting(Hit::key)
                    .containsExactly("a");
        }
    }

    @Test
    void neverMatchesAPhraseAcrossTwoValues() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("x")
                        .text("title", "quick fox")
                        .text("title", "dog cat")
                        .build());
            }

            assertThat(search(index, Query.phrase("title", "fox dog"))).isEmpty();
            assertThat(search(index, Query.phrase("title", "quick fox"))).hasSize(1);
        }
    }

    @Test
    void dropsATermWithoutWords() throws IOException {
        try (var index = index(false)) {
            assertThat(search(index, Query.all(Query.term("title", "fox"), Query.term("title", "!!!"))))
                    .extracting(Hit::key)
                    .containsExactly("a", "b");
            assertThat(search(index, Query.any(Query.term("title", "!!!"), Query.term("title", "cat"))))
                    .extracting(Hit::key)
                    .containsExactly("c");
            assertThat(search(index, Query.term("title", "!!!"))).isEmpty();
            assertThat(search(index, Query.phrase("title", "..."))).isEmpty();
        }
    }

    @Test
    void matchesNothingWithAnAllOrAnyWhoseChildrenAreAllDropped() throws IOException {
        try (var index = index(false)) {
            assertThat(search(index, Query.all(Query.term("title", "!!!")))).isEmpty();
            assertThat(search(index, Query.any(Query.term("title", "!!!"), Query.term("tags", "  "))))
                    .isEmpty();
            assertThat(search(index, Query.all(Query.term("title", "fox"), Query.any(Query.term("title", "?")))))
                    .isEmpty();
        }
    }

    @Test
    void excludesOnlyInsideAnAll() throws IOException {
        try (var index = index(false)) {
            assertThat(search(index, Query.all(Query.term("title", "fox"), Query.not(Query.term("title", "dog")))))
                    .extracting(Hit::key)
                    .containsExactly("a");
            assertThat(search(index, Query.not(Query.term("title", "dog")))).isEmpty();
            assertThat(search(index, Query.all(Query.not(Query.term("title", "dog")))))
                    .isEmpty();
            assertThat(search(index, Query.any(Query.term("title", "cat"), Query.not(Query.term("title", "fox")))))
                    .extracting(Hit::key)
                    .containsExactly("c");
            assertThat(search(index, Query.all(Query.term("title", "fox"), Query.not(Query.term("title", "!!!")))))
                    .extracting(Hit::key)
                    .containsExactly("a", "b");
        }
    }

    @Test
    void readsANotOfANotAsTheQueryItself() throws IOException {
        try (var index = index(false)) {
            var twice = new Query.Not(new Query.Not(Query.term("title", "dog")));

            assertThat(search(index, Query.all(Query.term("title", "fox"), twice)))
                    .extracting(Hit::key)
                    .containsExactly("b");
            assertThat(search(index, twice)).extracting(Hit::key).containsExactlyInAnyOrder("b", "c");
        }
    }

    @Test
    void matchesKeywordsWholeAndNormalized() throws IOException {
        try (var index = index(false)) {
            assertThat(search(index, Query.term("tags", "FORÊT")))
                    .extracting(Hit::key)
                    .containsExactly("a");
            assertThat(search(index, Query.phrase("tags", "big cat")))
                    .extracting(Hit::key)
                    .containsExactly("c");
            assertThat(search(index, Query.term("tags", "big"))).isEmpty();
        }
    }

    @Test
    void removesAccentsWhereTheFieldAsksForIt() throws IOException {
        try (var index = index(false)) {
            assertThat(search(index, Query.term("body", "élève")))
                    .extracting(Hit::key)
                    .containsExactly("c");
        }
    }

    @Test
    void keepsDocumentOrderAmongEqualScores() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                for (var key : List.of("k3", "k1", "k2", "k0")) {
                    writer.add(Document.builder(key).text("title", "same words").build());
                    writer.refresh();
                }
            }

            assertThat(search(index, Query.term("title", "same")))
                    .extracting(Hit::key)
                    .containsExactly("k3", "k1", "k2", "k0");
            assertThat(index.searcher().search(Query.term("title", "same"), 2))
                    .extracting(Hit::key)
                    .containsExactly("k3", "k1");
        }
    }

    @Test
    void advancesPastTheOnlyBlockOfATerm() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("k0").text("title", "bravo").build());
                writer.add(Document.builder("k1").text("title", "bravo").build());
                writer.add(Document.builder("k2").text("title", "alpha charlie").build());
            }

            assertThat(search(index, Query.all(Query.term("title", "alpha"), Query.term("title", "bravo"))))
                    .isEmpty();
        }
    }

    @Test
    void forgetsThePreviousSegmentWhenItReusesAnIterator() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                for (var i = 0; i < 10; i++) {
                    writer.add(Document.builder("s1-" + i)
                            .text("title", i == 9 ? "alpha bravo" : "bravo")
                            .build());
                }
                writer.refresh();
                writer.add(Document.builder("s2-0").text("title", "bravo").build());
                for (var i = 1; i < 6; i++) {
                    writer.add(Document.builder("s2-" + i).text("title", "xray").build());
                }
                writer.add(Document.builder("s2-6").text("title", "alpha").build());
            }

            assertThat(search(index, Query.all(Query.term("title", "alpha"), Query.term("title", "bravo"))))
                    .extracting(Hit::key)
                    .containsExactly("s1-9");
        }
    }

    @Test
    void readsAnyNumberOfNestedNots() throws IOException {
        try (var index = index(false)) {
            Query dog = Query.term("title", "dog");
            for (var nots = 1; nots <= 4; nots++) {
                dog = new Query.Not(dog);
                var expected = nots % 2 == 1 ? List.of("a") : List.of("b");
                assertThat(search(index, Query.all(Query.term("title", "fox"), dog)))
                        .as("%s nots", nots)
                        .extracting(Hit::key)
                        .containsExactlyElementsOf(expected);
                assertThat(search(index, Query.any(Query.term("title", "cat"), dog)))
                        .as("%s nots", nots)
                        .extracting(Hit::key)
                        .containsExactlyInAnyOrderElementsOf(nots % 2 == 1 ? List.of("c") : List.of("b", "c"));
            }
        }
    }

    @Test
    void rejectsInvalidQueries() throws IOException {
        try (var index = index(false);
                var searcher = index.searcher()) {
            assertThatThrownBy(() -> searcher.search(Query.term("summary", "fox"), 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("summary");
            assertThatThrownBy(() -> searcher.search(Query.any(Query.phrase("body", "!!!")), 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positions");
            assertThatThrownBy(() -> searcher.search(Query.term("title", "fox"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void searchesAnEmptyIndex() throws IOException {
        try (var index = storage.open(SCHEMA, directory);
                var searcher = index.searcher()) {
            assertThat(searcher.search(Query.term("title", "fox"), 10)).isEmpty();
        }
    }

    // a: "fox", b: "fox fox dog", c: "dog cat", each in its own segment if asked.
    private Index index(boolean segmentPerDocument) throws IOException {
        var index = storage.open(SCHEMA, directory);
        try (var writer = index.writer()) {
            writer.add(Document.builder("a")
                    .text("title", "fox")
                    .text("body", "the dog runs from the fox")
                    .keyword("tags", "forêt")
                    .build());
            if (segmentPerDocument) {
                writer.refresh();
            }
            writer.add(Document.builder("b").text("title", "fox fox dog").build());
            if (segmentPerDocument) {
                writer.refresh();
            }
            writer.add(Document.builder("c")
                    .text("title", "dog cat")
                    .text("body", "un élève")
                    .keyword("tags", "Big Cat")
                    .build());
        }
        return index;
    }

    private static List<Hit> search(Index index, Query query) {
        try (var searcher = index.searcher()) {
            return searcher.search(query, 10);
        }
    }

    private static float score(Index index, Query query, String key) {
        return search(index, query).stream()
                .filter(hit -> hit.key().equals(key))
                .findFirst()
                .orElseThrow()
                .score();
    }
}
