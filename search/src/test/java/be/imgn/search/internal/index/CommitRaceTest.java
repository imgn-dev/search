package be.imgn.search.internal.index;

import static be.imgn.search.internal.index.Indexes.ANALYZERS;
import static be.imgn.search.internal.index.Indexes.SCHEMA;
import static be.imgn.search.internal.index.Indexes.committed;
import static be.imgn.search.internal.index.Indexes.document;
import static be.imgn.search.internal.index.Indexes.unlisted;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A writer that fails while its commit is in flight, on another thread: the commit either stops before its
/// manifest is renamed, or ends; and a new writer waits for it before it cleans the directory up.
class CommitRaceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration WHILE_BLOCKED = Duration.ofMillis(300);
    private static final int ADDS = 300;

    @TempDir
    private Path path;

    private final Random random = new Random(1);

    @Test
    void aNewWriterWaitsForTheCommitInFlightOfTheWriterItReplaces() throws Exception {
        // Holds the commit once its manifest is renamed, while it forces the directory.
        var directory = new Holding(path, "forceDirectory", "", "manifest-2");
        var storage = FileStorage.open(directory, SCHEMA.fields(), ANALYZERS);
        // A budget of one byte: every add writes a segment.
        var writer = storage.newWriter(SCHEMA, Snapshot.EMPTY, 1).writer();
        writer.add(document(random, "a", "v1"));
        writer.commit();
        writer.add(document(random, "b", "v1"));
        var commit = new FutureTask<>(writer::commit);
        directory.hold(Thread.ofPlatform().unstarted(commit)).start();
        directory.awaitHeld();

        failWriter(directory, writer);
        var opening = new FutureTask<>(() -> storage.newWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET));
        var opener = Thread.ofPlatform().start(opening);

        assertThat(opener.join(WHILE_BLOCKED))
                .as("the new writer waits for the commit")
                .isFalse();
        directory.release();
        // The manifest was renamed before the writer failed: the commit ends.
        commit.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        var next = opening.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).writer();
        next.add(document(random, "d", "v1"));
        next.close();
        storage.close();

        assertThat(committed(path)).containsOnlyKeys("a", "b", "d");
    }

    @Test
    void aWriterThatFailedDuringItsCommitRenamesNoManifest() throws Exception {
        // Holds the commit as it creates its manifest.
        var directory = new Holding(path, "create", "manifest-2.tmp", "");
        var storage = FileStorage.open(directory, SCHEMA.fields(), ANALYZERS);
        var writer = storage.newWriter(SCHEMA, Snapshot.EMPTY, 1).writer();
        writer.add(document(random, "a", "v1"));
        writer.commit();
        writer.add(document(random, "b", "v1"));
        var commit = new FutureTask<>(writer::commit);
        directory.hold(Thread.ofPlatform().unstarted(commit)).start();
        directory.awaitHeld();

        failWriter(directory, writer);
        directory.release();

        try {
            commit.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            throw new AssertionError("The commit of a failed writer ended");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        }
        assertThat(Files.exists(path.resolve("manifest-2"))).isFalse();
        storage.newWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET)
                .writer()
                .close();
        storage.close();
        assertThat(committed(path)).containsOnlyKeys("a");
    }

    @Test
    void keepsEveryCommitThatReturnedWhenTheWriterFailsWhileAnotherThreadCommits() throws Exception {
        for (var failAt = 30; failAt <= 600; failAt += 29) {
            var directory = Files.createDirectory(path.resolve("run-" + failAt));
            var faulty = new FaultyDirectory(
                    directory, FaultyDirectory.Mode.THROW, failAt, FaultyDirectory.Persistence.FORCED_ONLY, "");
            var storage = FileStorage.open(faulty, SCHEMA.fields(), ANALYZERS);
            var opened = storage.newWriter(SCHEMA, Snapshot.EMPTY, 4 << 10);
            var published = new AtomicReference<>(opened.snapshot());
            var writer = opened.writer();
            var added = new AtomicInteger();
            var durable = new AtomicInteger();
            var adder = Thread.ofPlatform().start(() -> {
                var adding = new Random(2);
                try {
                    for (var i = 0; i < ADDS; i++) {
                        writer.add(document(adding, "k" + i, "v1"));
                        added.set(i + 1);
                    }
                } catch (IOException | UncheckedIOException | IllegalStateException ignored) {
                    // The writer failed: no more adds.
                }
            });
            var committer = Thread.ofPlatform().start(() -> {
                try {
                    while (adder.isAlive()) {
                        var before = added.get();
                        storage.release(published.getAndSet(writer.commit()));
                        durable.set(before);
                    }
                } catch (IOException | UncheckedIOException | IllegalStateException ignored) {
                    // The writer failed: no more commits.
                }
            });
            adder.join();
            committer.join();
            writer.awaitStopped();

            var next = storage.newWriter(SCHEMA, requireNonNull(published.get()), IndexWriter.DEFAULT_BUDGET);
            storage.release(published.getAndSet(next.snapshot()));
            storage.release(published.getAndSet(next.writer().close()));
            storage.release(requireNonNull(published.get()));
            storage.close();

            var keys = committed(directory).keySet();
            for (var i = 0; i < durable.get(); i++) {
                assertThat(keys).as("operation %s", failAt).contains("k" + i);
            }
            assertThat(keys.size()).as("operation %s", failAt).isLessThanOrEqualTo(added.get() + 1);
            assertThat(unlisted(directory)).as("operation %s", failAt).isEmpty();
        }
    }

    // Fails the writer from another thread, whose add cannot write its segment.
    private void failWriter(Holding directory, IndexWriter writer) throws InterruptedException, TimeoutException {
        var add = new FutureTask<>(() -> writer.add(document(random, "c", "v1")));
        directory.fail(Thread.ofPlatform().unstarted(add)).start();
        try {
            add.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            throw new AssertionError("The add did not fail");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(IOException.class);
        }
        assertThat(writer.failed()).isTrue();
    }

    // Holds one thread at an operation, after a file is renamed if asked, and fails the segments of another.
    private static final class Holding extends HookedDirectory {

        private final String operation;
        private final String name;
        private final String renamedFirst;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile @Nullable Thread holding;
        private volatile @Nullable Thread failing;
        private volatile boolean renamed;

        Holding(Path path, String operation, String name, String renamedFirst) {
            super(path);
            this.operation = operation;
            this.name = name;
            this.renamedFirst = renamedFirst;
        }

        Thread hold(Thread thread) {
            holding = thread;
            return thread;
        }

        Thread fail(Thread thread) {
            failing = thread;
            return thread;
        }

        void awaitHeld() throws InterruptedException {
            assertThat(held.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            released.countDown();
        }

        @Override
        void before(String operation, String name) throws IOException {
            var current = Thread.currentThread();
            if (current == failing && "create".equals(operation) && name.startsWith("segment-")) {
                throw new IOException("Disk full");
            }
            if (current == holding
                    && operation.equals(this.operation)
                    && name.equals(this.name)
                    && (renamedFirst.isEmpty() || renamed)
                    && held.getCount() > 0) {
                held.countDown();
                try {
                    released.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }

        @Override
        void after(String operation, String name) {
            if ("rename".equals(operation) && name.equals(renamedFirst)) {
                renamed = true;
            }
        }
    }
}
