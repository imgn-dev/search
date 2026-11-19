package be.imgn.search.internal.index;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.gaia.core.io.CharSource;
import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.LanguageDetector;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.internal.segment.HeapSink;
import be.imgn.search.internal.segment.Segment;
import java.io.IOException;
import java.io.StringReader;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

class DocumentIndexerTest {

    // Repeats of a short sentence that grow every buffer of the scratch beyond what it keeps: 4 MiB.
    private static final int LARGE = 500_000;

    private final List<Integer> detected = new ArrayList<>();

    private final Schema schema = Schema.builder()
            .text("body", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("notes", Positions.KEEP, Accents.KEEP, Stored.NO)
            .keyword("tags", Stored.YES)
            .languageDetector(
                    text -> {
                        detected.add(text.length());
                        return List.of(new LanguageDetector.Guess(Locale.FRENCH, 0.9));
                    },
                    0.5)
            .build();

    @Test
    void givesBackTheScratchOfALargeDocument() throws IOException {
        var indexer = new DocumentIndexer(schema);
        var small = warmedUp(indexer);

        index(
                indexer,
                Document.builder("x")
                        .language(Locale.FRENCH)
                        .text("body", "le renard brun saute ".repeat(LARGE))
                        .keyword("tags", "k".repeat(3_000_000))
                        .build());
        index(
                indexer,
                Document.builder("y")
                        .language(Locale.FRENCH)
                        .text("body", CharSource.wrap("le renard brun saute ".repeat(LARGE)))
                        .build());

        assertThat(indexer.scratchBytes()).isEqualTo(small);
    }

    @Test
    void givesBackTheScratchOfADocumentThatFails() throws IOException {
        var indexer = new DocumentIndexer(schema);
        var small = warmedUp(indexer);
        var text = "le renard brun saute ".repeat(LARGE);
        CharSource failing = () -> new StringReader(text) {
            private int read;

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                if (read > text.length() / 10 * 9) {
                    throw new IOException("Broken");
                }
                var count = super.read(buffer, offset, length);
                read += Math.max(count, 0);
                return count;
            }
        };

        assertThatThrownBy(() -> indexer.analyze(Document.builder("x")
                        .language(Locale.FRENCH)
                        .text("body", failing)
                        .build()))
                .hasMessage("Broken");

        assertThat(indexer.scratchBytes()).isEqualTo(small);
    }

    @Test
    void detectsFromTheFirstCharsOfTheDocumentOnly() throws IOException {
        var indexer = new DocumentIndexer(schema);
        var small = warmedUp(indexer);
        var opened = new int[100];
        var builder = Document.builder("x");
        for (var i = 0; i < opened.length; i++) {
            var source = i;
            builder.text("notes", () -> {
                opened[source]++;
                // Few words, not stored: the scratch the document needs is small, and the detection's is bounded.
                return new StringReader("le renard" + " ".repeat(9991));
            });
        }

        index(indexer, builder.build());

        assertThat(detected).containsExactly(DocumentIndexer.DETECTED_LENGTH);
        // 10,001 chars per source with its line feed: the second one ends the detected text.
        assertThat(opened[0]).isEqualTo(2);
        assertThat(opened[1]).isEqualTo(2);
        for (var i = 2; i < opened.length; i++) {
            assertThat(opened[i]).as("source %d", i).isEqualTo(1);
        }
        // The detection keeps its 16 K chars in the buffer it had: only the terms of the document grow the scratch.
        assertThat(indexer.scratchBytes()).isLessThan(small + 4096);
    }

    @Test
    void leavesTheBufferAsItWasWhenItsStoredRecordsAreFull() throws IOException {
        var indexer = new DocumentIndexer(schema);
        var buffer = new DocumentBuffer(1000);
        indexer.analyze(Document.builder("a")
                .language(Locale.FRENCH)
                .text("body", "le renard")
                .build());
        indexer.write("a", 0, buffer);
        var used = buffer.bytesUsed();

        indexer.analyze(Document.builder("b")
                .language(Locale.FRENCH)
                .text("body", "le chat ".repeat(200))
                .build());
        assertThatThrownBy(() -> indexer.write("b", 1, buffer)).isInstanceOf(IllegalStateException.class);

        assertThat(buffer.docCount()).isEqualTo(1);
        assertThat(buffer.bytesUsed()).isEqualTo(used);
        indexer.analyze(Document.builder("c")
                .language(Locale.FRENCH)
                .text("body", "le loup")
                .build());
        indexer.write("c", 2, buffer);
        var segment = flush(indexer, buffer);
        assertThat(segment.maxDoc()).isEqualTo(2);
        var inflater = new Inflater();
        try {
            assertThat(segment.stored(0, inflater).values().getFirst().value()).isEqualTo("le renard");
            assertThat(segment.stored(1, inflater).values().getFirst().value()).isEqualTo("le loup");
        } finally {
            inflater.end();
        }
        var chat = "chat".getBytes(StandardCharsets.UTF_8);
        assertThat(segment.term(requireNonNull(segment.field("body")), chat, chat.length))
                .isNull();
    }

    // The scratch of an indexer once it has indexed a small document that uses every buffer.
    private long warmedUp(DocumentIndexer indexer) throws IOException {
        index(
                indexer,
                Document.builder("w")
                        .text("body", "le renard")
                        .text("body", CharSource.wrap("le chat"))
                        .keyword("tags", "k")
                        .build());
        detected.clear();
        return indexer.scratchBytes();
    }

    private static void index(DocumentIndexer indexer, Document document) throws IOException {
        indexer.analyze(document);
        indexer.write(document.key(), 0, new DocumentBuffer());
    }

    private static Segment flush(DocumentIndexer indexer, DocumentBuffer buffer) {
        var sink = new HeapSink();
        var flusher = new SegmentFlusher(indexer.storedFieldNames());
        try {
            assertThat(flusher.flush(buffer, sink)).isTrue();
        } finally {
            flusher.end();
        }
        return Segment.open(MemorySegment.ofArray(sink.bytes()));
    }
}
