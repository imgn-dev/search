package be.imgn.search.internal.index;

import static be.imgn.search.internal.index.Indexes.ANALYZERS;
import static be.imgn.search.internal.index.Indexes.SCHEMA;
import static be.imgn.search.internal.index.Indexes.committed;
import static be.imgn.search.internal.index.Indexes.document;
import static be.imgn.search.internal.index.Indexes.unlisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.search.internal.store.FsDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Fails a file index at every file operation of a scenario, then checks what reopening gives.
///
/// The scenario creates an index; adds, replaces and deletes documents, refreshes, commits and merges; closes it;
/// opens it again and commits; then a commit fails once its manifest is renamed into place, and a new writer
/// reads that manifest again and goes on. Reopening must give exactly the documents of one commit: the last one
/// that returned, or a later one that was in progress, never an older one.
class CrashTest {

    // The manifest of the commit that fails once renamed, in every run.
    private static final String UNCERTAIN = Manifest.fileName(8);

    @TempDir
    private Path root;

    @Test
    void reopensAtACommitAfterAFailureAtAnyOperationAndRecovers() throws IOException {
        var total = countOperations(FaultyDirectory.Mode.THROW);
        for (var failAt = 1; failAt <= total; failAt++) {
            var path = Files.createDirectory(root.resolve("throw-" + failAt));
            var directory = new FaultyDirectory(
                    path, FaultyDirectory.Mode.THROW, failAt, FaultyDirectory.Persistence.FORCED_ONLY, UNCERTAIN);
            var run = new Run(directory, path);
            run.scenario();
            Map<String, String> expected;
            if (run.finished) {
                expected = new HashMap<>(run.model);
            } else {
                var base = newest(new FsDirectory(path).list());
                assertThat(base).as("operation %s", failAt).isBetween(run.lastReturned, run.attempted);
                expected = new HashMap<>(run.states.getOrDefault(base, Map.of()));
            }

            // The same process goes on: a new writer starts from that commit, cleans up and commits. When that
            // fails too, a new process does the same.
            if (!run.recover()) {
                run.close();
                assertThat(run.recover()).as("operation %s", failAt).isTrue();
            }
            assertThat(directory.failed()).as("operation %s", failAt).isTrue();
            expected.put("z", "v9");
            assertThat(committed(path)).as("operation %s", failAt).isEqualTo(expected);
            assertThat(unlisted(path)).as("operation %s", failAt).isEmpty();
        }
    }

    @Test
    void reopensAtACommitAfterAPowerCutAtAnyChange() throws IOException {
        powerCuts(FaultyDirectory.Persistence.FORCED_ONLY);
    }

    @Test
    void reopensAtACommitAfterAPowerCutThatKeepsTheUnforcedRenames() throws IOException {
        powerCuts(FaultyDirectory.Persistence.RENAMES_KEPT);
    }

    private void powerCuts(FaultyDirectory.Persistence persistence) throws IOException {
        var total = countOperations(FaultyDirectory.Mode.POWER_CUT);
        for (var failAt = 1; failAt <= total; failAt++) {
            var path = Files.createDirectory(root.resolve(persistence + "-" + failAt));
            var directory = new FaultyDirectory(path, FaultyDirectory.Mode.POWER_CUT, failAt, persistence, UNCERTAIN);
            var run = new Run(directory, path);
            run.scenario();
            run.close();
            assertThat(directory.failed()).as("operation %s", failAt).isTrue();
            var durable = newest(directory.durableNames());
            directory.restore();

            assertThat(durable).as("operation %s", failAt).isBetween(run.lastReturned, run.attempted);
            assertThat(committed(path))
                    .as("operation %s", failAt)
                    .isEqualTo(run.states.getOrDefault(durable, Map.of()));
        }
    }

    private long countOperations(FaultyDirectory.Mode mode) throws IOException {
        var path = Files.createDirectory(root.resolve("count-" + mode));
        var directory =
                new FaultyDirectory(path, mode, Long.MAX_VALUE, FaultyDirectory.Persistence.FORCED_ONLY, UNCERTAIN);
        var run = new Run(directory, path);
        run.scenario();
        assertThat(run.finished).isTrue();
        assertThat(run.lastReturned).isEqualTo(run.attempted);
        assertThat(committed(path)).isEqualTo(run.model).containsOnlyKeys("f", "g", "h", "i", "j");
        assertThat(unlisted(path)).isEmpty();
        var total = directory.operations();
        assertThat(total).isGreaterThan(100);
        return total;
    }

    // The generation of the newest manifest among files, -1 if none.
    private static long newest(Collection<String> names) {
        return names.stream().mapToLong(Manifest::generationOf).max().orElse(-1);
    }

    // One run of the scenario, which stops at the first unexpected failure.
    private static final class Run {

        private final FaultyDirectory directory;
        private final Path path;
        private final Random random = new Random(1);
        // The documents as of now, and as of each commit attempted, by generation.
        private final Map<String, String> model = new HashMap<>();
        private final Map<Long, Map<String, String>> states = new HashMap<>();
        private long generation;
        private long attempted = -1;
        private long lastReturned = -1;
        private boolean finished;
        private @Nullable FileStorage storage;
        private @Nullable IndexWriter writer;
        private Snapshot published = Snapshot.EMPTY;

        Run(FaultyDirectory directory, Path path) {
            this.directory = directory;
            this.path = path;
        }

        void scenario() {
            try {
                firstProcess();
                secondProcess();
                finished = true;
            } catch (IOException | UncheckedIOException e) {
                stopped();
            }
        }

        // Creates the index, writes in it and closes it.
        private void firstProcess() throws IOException {
            attempt();
            open();
            returned();
            var current = startWriter();
            add(current, "a", "v1");
            add(current, "b", "v1");
            add(current, "c", "v1");
            commit(current);
            add(current, "d", "v1");
            add(current, "b", "v2");
            delete(current, "a");
            publish(current.refresh());
            add(current, "e", "v1");
            commit(current);
            delete(current, "c");
            commit(current);
            // Commits a segment whose deletions an earlier commit wrote.
            add(current, "g", "v1");
            commit(current);
            current.forceMerge();
            add(current, "f", "v1");
            commit(current);
            delete(current, "d");
            attempt();
            publish(current.close());
            returned();
            closeStorage();
        }

        // Opens the index again and writes in it; one commit fails once its manifest is renamed.
        private void secondProcess() throws IOException {
            open();
            var current = startWriter();
            add(current, "h", "v1");
            delete(current, "b");
            commit(current);
            add(current, "i", "v1");
            attempt();
            assertThat(attempted).isEqualTo(Manifest.generationOf(UNCERTAIN));
            assertThatThrownBy(current::commit).isInstanceOf(IOException.class);
            assertThat(current.failed()).isTrue();
            current.awaitStopped();

            // The new writer reads the newest manifest again: the one that failed, if it was renamed.
            current = startWriter();
            lastReturned = newest(new FsDirectory(path).list());
            generation = lastReturned + 1;
            model.clear();
            model.putAll(states.getOrDefault(lastReturned, Map.of()));
            add(current, "j", "v1");
            delete(current, "e");
            commit(current);
            attempt();
            publish(current.close());
            returned();
            closeStorage();
        }

        // After a failure: a new writer starts from the last commit, adds a document and closes. Returns whether
        // it did, rather than failed.
        boolean recover() {
            try {
                if (storage == null) {
                    open();
                }
                var current = startWriter();
                current.add(document(random, "z", "v9"));
                publish(current.close());
                closeStorage();
                return true;
            } catch (IOException | UncheckedIOException e) {
                stopped();
                return false;
            }
        }

        // Closes the storage, as the process ending would, so that another can open the index.
        void close() throws IOException {
            stopped();
            closeStorage();
        }

        private void open() throws IOException {
            var opened = FileStorage.open(directory, SCHEMA.fields(), ANALYZERS);
            storage = opened;
            published = opened.snapshot();
        }

        private IndexWriter startWriter() throws IOException {
            var opened = storage;
            if (opened == null) {
                throw new IllegalStateException("No storage");
            }
            var start = opened.newWriter(SCHEMA, published, IndexWriter.DEFAULT_BUDGET);
            publish(start.snapshot());
            writer = start.writer();
            return start.writer();
        }

        private void closeStorage() throws IOException {
            var opened = storage;
            if (opened != null) {
                opened.release(published);
                published = Snapshot.EMPTY;
                storage = null;
                opened.close();
            }
        }

        // After a failure: the writer, if failed, refuses everything, and its merges stop.
        private void stopped() {
            var current = writer;
            if (current != null && current.failed()) {
                assertThatThrownBy(() -> current.add(document(random, "y", "v0")))
                        .isInstanceOf(IllegalStateException.class);
            }
            if (current != null) {
                current.awaitStopped();
            }
        }

        private void add(IndexWriter current, String key, String version) throws IOException {
            current.add(document(random, key, version));
            model.put(key, version);
        }

        private void delete(IndexWriter current, String key) throws IOException {
            current.delete(key);
            model.remove(key);
        }

        private void commit(IndexWriter current) throws IOException {
            attempt();
            publish(current.commit());
            returned();
        }

        private void attempt() {
            states.put(generation, Map.copyOf(model));
            attempted = generation++;
        }

        private void returned() {
            lastReturned = attempted;
        }

        private void publish(Snapshot snapshot) {
            var opened = storage;
            if (opened != null) {
                opened.release(published);
            }
            published = snapshot;
        }
    }
}
