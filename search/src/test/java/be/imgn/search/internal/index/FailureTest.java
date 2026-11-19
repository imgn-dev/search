package be.imgn.search.internal.index;

import static be.imgn.search.internal.index.Indexes.SCHEMA;
import static be.imgn.search.internal.index.Indexes.document;
import static be.imgn.search.internal.index.Indexes.expected;
import static be.imgn.search.internal.index.Indexes.live;
import static be.imgn.search.internal.index.Indexes.unchecked;
import static be.imgn.search.internal.index.Indexes.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import be.imgn.gaia.core.io.CharSource;
import be.imgn.search.Document;
import be.imgn.search.internal.segment.SegmentMerger;
import be.imgn.search.internal.segment.SegmentSink;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/// Fail-stop: an exception that escapes a flush, a refresh or a merge closes the writer, and every later call
/// throws it as the cause; and failures elsewhere leave the writer working.
class FailureTest {

    // Merges up to three segments of similar sizes beyond about four.
    private static final TieredMergePolicy EAGER = new TieredMergePolicy(1L << 30, 2, 3, 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final Random random = new Random(1);

    @Test
    void closesTheWriterWhenABudgetFlushFails() throws IOException {
        var flushes = new AtomicInteger();
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, 1, EAGER, new SegmentWriting() {
            @Override
            public boolean flush(SegmentFlusher flusher, DocumentBuffer buffer, SegmentSink sink) {
                if (flushes.incrementAndGet() == 3) {
                    throw new IllegalStateException("Flush failure");
                }
                return flusher.flush(buffer, sink);
            }
        });
        writer.add(document(random, "a", "v0"));
        writer.add(document(random, "b", "v0"));
        var before = writer.refresh();

        assertThatThrownBy(() -> writer.add(document(random, "c", "v0"))).hasMessage("Flush failure");

        assertFailed(writer, "Flush failure");
        assertThat(live(before).keySet()).containsExactly("a", "b");
    }

    @Test
    void closesTheWriterWhenARefreshFlushFails() throws IOException {
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET, EAGER, new SegmentWriting() {
            @Override
            public boolean flush(SegmentFlusher flusher, DocumentBuffer buffer, SegmentSink sink) {
                throw new IllegalStateException("Flush failure");
            }
        });
        writer.add(document(random, "a", "v0"));

        assertThatThrownBy(writer::refresh).hasMessage("Flush failure");

        assertFailed(writer, "Flush failure");
    }

    @Test
    void closesTheWriterWhenAMergeFails() throws IOException {
        var uncaught = new ConcurrentLinkedQueue<Throwable>();
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaught.add(failure));
        try {
            var segments = segments(8);
            var writer = new IndexWriter(SCHEMA, segments, IndexWriter.DEFAULT_BUDGET, EAGER, new SegmentWriting() {
                @Override
                public boolean merge(
                        List<String> storedFields,
                        List<SegmentMerger.Input> inputs,
                        List<int[]> docMaps,
                        int maxDoc,
                        BooleanSupplier stop,
                        SegmentSink sink) {
                    throw new IllegalStateException("Merge failure");
                }
            });
            writer.refresh();
            writer.awaitMerges();

            assertFailed(writer, "Merge failure");
            assertThat(uncaught).isEmpty();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void keepsWorkingWhenABufferCannotBeCreated() throws IOException {
        var flushers = new AtomicInteger();
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET, EAGER, new SegmentWriting() {
            @Override
            public SegmentFlusher newFlusher(List<String> storedFields) {
                if (flushers.incrementAndGet() == 1) {
                    throw new IllegalStateException("No buffer");
                }
                return new SegmentFlusher(storedFields);
            }
        });

        assertThatThrownBy(() -> writer.add(document(random, "a", "v0"))).hasMessage("No buffer");
        writer.add(document(random, "b", "v0"));
        var snapshot = assertTimeoutPreemptively(TIMEOUT, writer::refresh);
        writer.close();

        assertThat(live(snapshot).keySet()).containsExactly("b");
    }

    @Test
    void closesWhileAMergeGivesUp() throws IOException {
        var started = new CountDownLatch(1);
        var gaveUp = new AtomicInteger();
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET, EAGER, new SegmentWriting() {
            @Override
            public boolean merge(
                    List<String> storedFields,
                    List<SegmentMerger.Input> inputs,
                    List<int[]> docMaps,
                    int maxDoc,
                    BooleanSupplier stop,
                    SegmentSink sink) {
                started.countDown();
                while (!stop.getAsBoolean()) {
                    Thread.onSpinWait();
                }
                gaveUp.incrementAndGet();
                return false;
            }
        });
        for (var i = 0; i < 8; i++) {
            writer.add(document(random, "k" + i, "v0"));
            writer.refresh();
        }
        assertTimeoutPreemptively(TIMEOUT, () -> started.await());

        var last = assertTimeoutPreemptively(TIMEOUT, writer::close);

        assertThat(gaveUp).hasPositiveValue();
        assertThat(writer.failed()).isFalse();
        assertThat(last.segments()).hasSize(8);
        assertThat(live(last)).hasSize(8);
    }

    @Test
    void closesWithEveryOperationThatReturned() throws InterruptedException, IOException {
        for (var round = 0; round < 5; round++) {
            var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, 16 << 10, EAGER);
            var operations = new ConcurrentLinkedQueue<Indexes.Operation>();
            var failures = new ConcurrentLinkedQueue<Throwable>();
            var threads = new ArrayList<Thread>();
            for (var t = 0; t < 4; t++) {
                var threadRandom = new Random(round * 10L + t);
                var name = "t" + t;
                threads.add(Thread.ofPlatform()
                        .uncaughtExceptionHandler((thread, failure) -> failures.add(failure))
                        .start(unchecked(() -> operate(writer, threadRandom, name, operations))));
            }
            while (operations.size() < 300) {
                Thread.onSpinWait();
            }

            var last = writer.close();
            for (var thread : threads) {
                thread.join();
            }

            assertThat(failures).isEmpty();
            assertThat(versions(last)).isEqualTo(expected(operations, Long.MAX_VALUE));
        }
    }

    @Test
    void keepsTheBufferedDeletesWithinTheBudget() throws IOException {
        var budget = 64 << 10;
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, budget, EAGER);
        var maxDeleteBytes = 0L;
        var maxPending = 0;
        for (var i = 0; i < 6000; i++) {
            var key = "a-rather-long-key-to-weigh-on-the-deletes-" + i;
            if (i % 3 == 0) {
                writer.delete(key);
            } else {
                writer.add(document(random, key, "v0"));
            }
            maxDeleteBytes = Math.max(maxDeleteBytes, writer.deleteBytes());
            maxPending = Math.max(maxPending, writer.pendingSegments());
        }
        var snapshot = writer.close();

        assertThat(maxDeleteBytes).isLessThanOrEqualTo(budget);
        assertThat(maxPending).isLessThanOrEqualTo(EAGER.segmentsPerTier());
        assertThat(live(snapshot)).hasSize(4000);
    }

    // A snapshot of segments of a document each, which no merge touched.
    private Snapshot segments(int count) throws IOException {
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        for (var i = 0; i < count; i++) {
            writer.add(document(random, "k" + i, "v0"));
            writer.refresh();
        }
        return writer.close();
    }

    private static void operate(IndexWriter writer, Random random, String name, Queue<Indexes.Operation> operations)
            throws IOException {
        try {
            for (var i = 0; ; i++) {
                var key = "k" + random.nextInt(100);
                if (random.nextInt(4) == 0) {
                    operations.add(new Indexes.Operation(writer.delete(key), key, null));
                } else {
                    var version = name + "-" + i;
                    operations.add(new Indexes.Operation(writer.add(document(random, key, version)), key, version));
                }
                if (i % 50 == 0) {
                    writer.refresh();
                }
            }
        } catch (IllegalStateException closed) {
            assertThat(closed).hasMessage("The writer is closed");
        }
    }

    private void assertFailed(IndexWriter writer, String cause) throws IOException {
        assertThat(writer.failed()).isTrue();
        assertThatThrownBy(() -> writer.add(document(random, "z", "v0")))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(cause);
        assertThatThrownBy(() -> writer.delete("z")).hasRootCauseMessage(cause);
        assertThatThrownBy(writer::refresh).hasRootCauseMessage(cause);
        assertThatThrownBy(writer::close).hasRootCauseMessage(cause);
    }

    @Test
    void keepsTheFailureOfAnAddWhenPuttingItsBufferBackFailsTheWriter() throws IOException {
        var first = Document.builder("a").text("title", "le renard").build();
        // A budget the first document fits, which a delete then passes: putting back the buffer of the next add
        // flushes it.
        var indexer = new DocumentIndexer(SCHEMA);
        var buffer = new DocumentBuffer();
        indexer.analyze(first);
        indexer.write("a", 0, buffer);
        var probe = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        probe.add(first);
        var budget = buffer.bytesUsed() + probe.deleteBytes() + 1;
        probe.close();
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, budget, EAGER, new SegmentWriting() {
            @Override
            public boolean flush(SegmentFlusher flusher, DocumentBuffer flushed, SegmentSink sink) {
                throw new IllegalStateException("Flush failure");
            }
        });
        writer.add(first);
        writer.delete("z");
        var broken = new IOException("Broken source");
        CharSource source = () -> {
            throw broken;
        };

        assertThatThrownBy(() ->
                        writer.add(Document.builder("b").text("title", source).build()))
                .isSameAs(broken);

        assertThat(broken.getSuppressed())
                .singleElement()
                .satisfies(suppressed -> assertThat(suppressed).hasMessage("Flush failure"));
        assertFailed(writer, "Flush failure");
    }
}
