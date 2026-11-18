package be.imgn.search.internal.index;

import static be.imgn.search.internal.index.Indexes.SCHEMA;
import static be.imgn.search.internal.index.Indexes.document;
import static be.imgn.search.internal.index.Indexes.expected;
import static be.imgn.search.internal.index.Indexes.live;
import static be.imgn.search.internal.index.Indexes.results;
import static be.imgn.search.internal.index.Indexes.versions;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.internal.index.Indexes.Operation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/// Several threads add, replace and delete the same keys while refreshes, merges and searches run. Each operation
/// records its sequence number, which orders it: every snapshot must hold exactly the operations up to its cut,
/// the last one of each key.
class ConcurrencyTest {

    private static final int WRITERS = 6;
    private static final int OPERATIONS = 1500;
    private static final int KEYS = 80;
    private static final int CHECKED_REFRESHES = 100;
    private static final long PAUSE_NANOS = 200_000;
    // Merges three segments as soon as four exist, so that merges run all along.
    private static final TieredMergePolicy EAGER = new TieredMergePolicy(1L << 30, 2, 3, 1);

    private final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

    @Test
    void endsEverySnapshotWithTheLastOperationOfEachKeyUpToItsCut() throws InterruptedException, IOException {
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, 32 << 10, EAGER);
        var latest = new AtomicReference<>(Snapshot.EMPTY);
        var operations = new ConcurrentLinkedQueue<Operation>();
        var refreshes = new ConcurrentLinkedQueue<IndexWriter.Refreshed>();
        var writers = new ArrayList<Thread>();
        for (var t = 0; t < WRITERS; t++) {
            var random = new Random(t);
            var name = "t" + t;
            writers.add(start(() -> {
                for (var i = 0; i < OPERATIONS; i++) {
                    var key = "k" + random.nextInt(KEYS);
                    if (random.nextInt(4) == 0) {
                        operations.add(new Operation(writer.delete(key), key, null));
                    } else {
                        var version = name + "-" + i;
                        operations.add(new Operation(writer.add(document(random, key, version)), key, version));
                    }
                }
            }));
        }
        var others = new ArrayList<Thread>();
        others.add(start(() -> {
            while (writers.stream().anyMatch(Thread::isAlive)) {
                var refreshed = writer.refreshWithCut();
                latest.set(refreshed.snapshot());
                if (refreshes.size() < CHECKED_REFRESHES) {
                    refreshes.add(refreshed);
                }
                LockSupport.parkNanos(PAUSE_NANOS);
            }
        }));
        for (var s = 0; s < 2; s++) {
            others.add(start(() -> {
                while (writers.stream().anyMatch(Thread::isAlive)) {
                    var snapshot = requireNonNull(latest.get());
                    // Each key is live at most once in every snapshot.
                    live(snapshot);
                    results(snapshot);
                }
            }));
        }
        join(writers);
        join(others);
        refreshes.add(writer.refreshWithCut());
        writer.awaitMerges();
        var last = writer.refreshWithCut();
        writer.forceMerge();
        var merged = writer.refresh();
        writer.close();

        assertThat(failures).isEmpty();
        assertThat(writer.failed()).isFalse();
        assertThat(operations.stream().map(Operation::seq).distinct()).hasSize(WRITERS * OPERATIONS);
        assertThat(refreshes).hasSizeGreaterThan(2);
        for (var refreshed : refreshes) {
            assertThat(versions(refreshed.snapshot()))
                    .as("cut %s", refreshed.cut())
                    .isEqualTo(expected(operations, refreshed.cut()));
        }
        var all = expected(operations, Long.MAX_VALUE);
        assertThat(versions(last.snapshot())).isEqualTo(all);
        assertThat(merged.segments()).hasSize(1);
        assertThat(versions(merged)).isEqualTo(all);
    }

    private Thread start(Indexes.IoTask task) {
        return Thread.ofPlatform()
                .uncaughtExceptionHandler((thread, failure) -> failures.add(failure))
                .start(Indexes.unchecked(task));
    }

    private static void join(List<Thread> threads) throws InterruptedException {
        for (var thread : threads) {
            thread.join();
        }
    }
}
