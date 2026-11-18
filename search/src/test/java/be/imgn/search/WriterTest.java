package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass
@EnumSource(StorageKind.class)
class WriterTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.KEEP, Accents.KEEP, Stored.NO)
            .keyword("tags", Stored.YES)
            .languages(List.of(ToyAnalyzer.FRENCH))
            .build();
    private final StorageKind storage;
    private final Path directory;

    WriterTest(StorageKind storage, @TempDir Path directory) {
        this.storage = storage;
        this.directory = directory;
    }

    @Test
    void showsChangesToSearchersOpenedAfterARefresh() throws IOException {
        try (var index = storage.open(SCHEMA, directory);
                var writer = index.writer()) {
            writer.add(document("a", "fox"));
            var before = index.searcher();
            writer.refresh();
            var after = index.searcher();
            writer.add(document("b", "fox"));

            assertThat(keys(before, "fox")).isEmpty();
            assertThat(keys(after, "fox")).containsExactly("a");
            writer.commit();
            assertThat(keys(after, "fox")).containsExactly("a");
            assertThat(keys(index, "fox")).containsExactly("a", "b");
            before.close();
            after.close();
        }
    }

    @Test
    void replacesADocumentWithTheSameKeyInTheBuffer() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(document("a", "fox"));
                writer.add(document("b", "fox"));
                writer.add(document("a", "dog"));
            }

            assertThat(keys(index, "fox")).containsExactly("b");
            assertThat(keys(index, "dog")).containsExactly("a");
        }
    }

    @Test
    void replacesADocumentWithTheSameKeyInAnotherSegment() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(document("a", "fox"));
                writer.refresh();
                writer.add(document("a", "dog"));
                writer.refresh();
                writer.add(document("a", "cat"));
                writer.add(document("a", "fox cat"));
            }

            assertThat(keys(index, "fox")).containsExactly("a");
            assertThat(keys(index, "cat")).containsExactly("a");
            assertThat(keys(index, "dog")).isEmpty();
        }
    }

    @Test
    void deletesInTheBufferAndInSegments() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(document("a", "fox"));
                writer.add(document("b", "fox"));
                writer.refresh();
                writer.add(document("c", "fox"));
                writer.add(document("d", "fox"));
                writer.delete("a");
                writer.delete("c");
                writer.delete("missing");
            }

            assertThat(keys(index, "fox")).containsExactly("b", "d");
        }
    }

    @Test
    void keepsADocumentAddedAfterTheDeleteOfItsKey() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(document("a", "fox"));
                writer.refresh();
                writer.delete("a");
                writer.add(document("a", "dog"));
                writer.refresh();
                writer.delete("a");
                writer.delete("a");
                writer.add(document("a", "cat"));
            }

            assertThat(keys(index, "fox")).isEmpty();
            assertThat(keys(index, "dog")).isEmpty();
            assertThat(keys(index, "cat")).containsExactly("a");
        }
    }

    @Test
    void searchesManyDocumentsOverSeveralSegmentsWithDeletes() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                for (var i = 0; i < 2000; i++) {
                    writer.add(document("k" + i, i % 3 == 0 ? "fox dog" : "dog"));
                    if (i % 450 == 449) {
                        writer.refresh();
                    }
                }
                for (var i = 0; i < 2000; i += 5) {
                    writer.delete("k" + i);
                }
            }

            try (var searcher = index.searcher()) {
                var hits = searcher.search(Query.phrase("title", "fox dog"), Integer.MAX_VALUE);
                assertThat(hits)
                        .extracting(Hit::key)
                        .containsExactlyInAnyOrderElementsOf(IntStream.range(0, 2000)
                                .filter(i -> i % 3 == 0 && i % 5 != 0)
                                .mapToObj(i -> "k" + i)
                                .toList());
                assertThat(searcher.search(Query.term("title", "dog"), Integer.MAX_VALUE))
                        .hasSize(1600);
            }
        }
    }

    @Test
    void readsTheStoredFieldsOfAHit() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("doc-42")
                        .language(Locale.FRENCH)
                        .text("title", "Le renard")
                        .text("title", "The fox", Locale.ENGLISH)
                        .text("body", "not stored")
                        .keyword("tags", "animal")
                        .keyword("tags", "forêt")
                        .build());
                writer.refresh();
                writer.add(Document.builder("doc-43").text("title", "fox").build());
            }

            try (var searcher = index.searcher()) {
                var hits = searcher.search(Query.term("title", "fox"), 10);
                assertThat(hits).extracting(Hit::key).containsExactly("doc-43", "doc-42");
                var first = searcher.document(hits.get(1));
                assertThat(first.key()).isEqualTo("doc-42");
                assertThat(first.language()).contains(Locale.FRENCH);
                assertThat(first.texts("title"))
                        .containsExactly(
                                new StoredText("Le renard", Locale.FRENCH), new StoredText("The fox", Locale.ENGLISH));
                assertThat(first.values("tags")).containsExactly("animal", "forêt");
                var second = searcher.document(hits.getFirst());
                assertThat(second.language()).isEmpty();
                assertThat(second.values("tags")).isEmpty();
                assertThat(second.texts("title")).containsExactly(new StoredText("fox", null));
            }
        }
    }

    @Test
    void rejectsAHitFromAnotherSearcher() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(document("a", "fox"));
            }
            var one = index.searcher();
            var other = index.searcher();
            var hit = one.search(Query.term("title", "fox"), 1).getFirst();

            assertThatThrownBy(() -> other.document(hit)).isInstanceOf(IllegalArgumentException.class);
            one.close();
            assertThatThrownBy(() -> one.document(hit)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> one.search(Query.term("title", "fox"), 1))
                    .isInstanceOf(IllegalStateException.class);
            other.close();
        }
    }

    @Test
    void rejectsADocumentThatDoesNotFitTheSchema() throws IOException {
        try (var index = storage.open(SCHEMA, directory);
                var writer = index.writer()) {
            assertThatThrownBy(() -> writer.add(
                            Document.builder("a").text("summary", "x").build()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> writer.add(
                            Document.builder("a").keyword("title", "x").build()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> writer.delete("")).isInstanceOf(IllegalArgumentException.class);
            writer.add(document("a", "fox"));
            writer.refresh();
            assertThat(keys(index, "fox")).containsExactly("a");
        }
    }

    @Test
    void leavesTheWriterAsItWasWhenAnAddFails() throws IOException {
        var failing = new LanguageAnalyzer() {
            @Override
            public Locale language() {
                return Locale.ENGLISH;
            }

            @Override
            public String id() {
                return "failing";
            }

            @Override
            public WordAnalyzer newWordAnalyzer() {
                return word -> {
                    if ("boom".equals(word.toString())) {
                        throw new IllegalStateException("Analyzer failure");
                    }
                };
            }
        };
        var schema = Schema.builder()
                .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
                .language(failing)
                .build();
        try (var index = storage.open(schema, directory)) {
            var writer = index.writer();
            writer.add(Document.builder("a")
                    .language(Locale.ENGLISH)
                    .text("title", "first")
                    .build());
            writer.refresh();
            writer.add(Document.builder("b")
                    .language(Locale.ENGLISH)
                    .text("title", "second")
                    .build());

            for (var key : List.of("a", "b", "c")) {
                assertThatThrownBy(() -> writer.add(Document.builder(key)
                                .language(Locale.ENGLISH)
                                .text("title", "words before")
                                .text("title", "boom")
                                .build()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Analyzer failure");
            }
            writer.add(Document.builder("c")
                    .language(Locale.ENGLISH)
                    .text("title", "third")
                    .build());
            writer.refresh();

            try (var searcher = index.searcher()) {
                assertThat(searcher.search(
                                Query.any(
                                        Query.term("title", "first"),
                                        Query.term("title", "second"),
                                        Query.term("title", "third"),
                                        Query.term("title", "words")),
                                10))
                        .extracting(Hit::key)
                        .containsExactlyInAnyOrder("a", "b", "c");
                for (var hit : searcher.search(Query.term("title", "second"), 10)) {
                    assertThat(searcher.document(hit).values("title")).containsExactly("second");
                }
            }
            writer.close();
        }
    }

    @Test
    void splitsTextAtALoneSurrogateAndRejectsKeysWithOne() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            try (var writer = index.writer()) {
                writer.add(document("a", "fox\uD800dog\uDC00"));
                assertThatThrownBy(() -> writer.delete("x\uD800")).isInstanceOf(IllegalArgumentException.class);
            }

            assertThat(keys(index, "fox")).containsExactly("a");
            assertThat(keys(index, "dog")).containsExactly("a");
        }
    }

    @Test
    void rejectsABudgetThatIsNotPositive() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            assertThatThrownBy(() -> index.writer(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.writer(-1)).isInstanceOf(IllegalArgumentException.class);
            try (var writer = index.writer(1)) {
                writer.add(document("a", "fox"));
                writer.add(document("b", "fox"));
            }
            assertThat(keys(index, "fox")).containsExactly("a", "b");
        }
    }

    @Test
    void givesOneWriterAtATime() throws IOException {
        try (var index = storage.open(SCHEMA, directory)) {
            var writer = index.writer();
            writer.add(document("a", "fox"));

            assertThatThrownBy(index::writer).isInstanceOf(IllegalStateException.class);
            writer.close();
            writer.close();
            assertThatThrownBy(() -> writer.add(document("b", "fox"))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(writer::refresh).isInstanceOf(IllegalStateException.class);
            try (var next = index.writer()) {
                next.add(document("a", "dog"));
            }
            assertThat(keys(index, "fox")).isEmpty();
            assertThat(keys(index, "dog")).containsExactly("a");
        }
    }

    @Test
    void commitsTheOpenWriterWhenTheIndexCloses() throws IOException {
        var index = storage.open(SCHEMA, directory);
        var writer = index.writer();
        writer.add(document("a", "fox"));
        var searcher = index.searcher();

        index.close();

        assertThatThrownBy(() -> writer.add(document("b", "fox"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(index::searcher).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(index::writer).isInstanceOf(IllegalStateException.class);
        assertThat(keys(searcher, "fox")).isEmpty();
        searcher.close();
    }

    private static Document document(String key, String title) {
        return Document.builder(key).text("title", title).build();
    }

    private static List<String> keys(Index index, String word) {
        try (var searcher = index.searcher()) {
            return keys(searcher, word);
        }
    }

    private static List<String> keys(Searcher searcher, String word) {
        return searcher.search(Query.term("title", word), 100).stream()
                .map(Hit::key)
                .toList();
    }
}
