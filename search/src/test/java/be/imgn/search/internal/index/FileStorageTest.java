package be.imgn.search.internal.index;

import static be.imgn.search.internal.index.Indexes.ANALYZERS;
import static be.imgn.search.internal.index.Indexes.SCHEMA;
import static be.imgn.search.internal.index.Indexes.committed;
import static be.imgn.search.internal.index.Indexes.document;
import static be.imgn.search.internal.index.Indexes.unlisted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.search.internal.segment.Deletions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The references a file index counts, through failures: what nothing uses any more is deleted.
class FileStorageTest {

    @TempDir
    private Path path;

    private final Random random = new Random(1);

    @Test
    void releasesTheSnapshotOfACommitThatFailed() throws IOException {
        var directory = new HookedDirectory(path) {
            private boolean failed;

            @Override
            void before(String operation, String name) throws IOException {
                if ("create".equals(operation) && "manifest-2.tmp".equals(name) && !failed) {
                    failed = true;
                    throw new IOException("Disk full");
                }
            }
        };
        var storage = FileStorage.open(directory, SCHEMA.fields(), ANALYZERS);
        var opened = storage.newWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        var published = opened.snapshot();
        var writer = opened.writer();
        writer.add(document(random, "a", "v1"));
        published = publish(storage, published, writer.commit());
        writer.add(document(random, "b", "v1"));

        assertThatThrownBy(writer::commit).isInstanceOf(IOException.class);
        var next = storage.newWriter(SCHEMA, published, IndexWriter.DEFAULT_BUDGET);
        published = publish(storage, published, next.snapshot());
        next.writer().add(document(random, "c", "v1"));
        published = publish(storage, published, next.writer().close());
        storage.release(published);
        storage.close();

        assertThat(unlisted(path)).isEmpty();
        assertThat(committed(path)).containsOnlyKeys("a", "c");
    }

    @Test
    void takesNoReferenceFromASnapshotItCannotRetainInFull() throws IOException {
        var storage = FileStorage.open(new HookedDirectory(path), SCHEMA.fields(), ANALYZERS);
        var opened = storage.newWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        var published = opened.snapshot();
        var writer = opened.writer();
        writer.add(document(random, "a", "v1"));
        published = publish(storage, published, writer.commit());
        var heap = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        heap.add(document(random, "x", "v1"));
        var foreign = heap.close().segments().getFirst().segment();
        var mixed = new Snapshot(
                List.of(published.segments().getFirst(), new Snapshot.Entry(foreign, Deletions.NONE, 1)), 2);

        assertThatThrownBy(() -> storage.retain(mixed)).isInstanceOf(IllegalStateException.class);
        writer.delete("a");
        writer.add(document(random, "b", "v1"));
        published = publish(storage, published, writer.commit());
        published = publish(storage, published, writer.close());
        storage.release(published);
        storage.close();

        assertThat(unlisted(path)).isEmpty();
    }

    @Test
    void releasesWhatAFailedReloadRead() throws IOException {
        // The commit of manifest-2 fails once renamed, and so does the directory force of the reload after it.
        var directory = new HookedDirectory(path) {
            private int failures;
            private boolean renamed;

            @Override
            void before(String operation, String name) throws IOException {
                if ("forceDirectory".equals(operation) && renamed && failures < 2) {
                    failures++;
                    throw new IOException("Disk gone");
                }
            }

            @Override
            void after(String operation, String name) {
                renamed |= "rename".equals(operation) && "manifest-2".equals(name);
            }
        };
        var storage = FileStorage.open(directory, SCHEMA.fields(), ANALYZERS);
        var opened = storage.newWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        var published = opened.snapshot();
        var writer = opened.writer();
        writer.add(document(random, "a", "v1"));
        published = publish(storage, published, writer.commit());
        writer.add(document(random, "b", "v1"));
        assertThatThrownBy(writer::commit).isInstanceOf(IOException.class);
        writer.awaitStopped();
        var failed = published;
        assertThatThrownBy(() -> storage.newWriter(SCHEMA, failed, IndexWriter.DEFAULT_BUDGET))
                .isInstanceOf(IOException.class);

        var next = storage.newWriter(SCHEMA, published, IndexWriter.DEFAULT_BUDGET);
        published = publish(storage, published, next.snapshot());
        next.writer().delete("a");
        next.writer().delete("b");
        next.writer().add(document(random, "c", "v1"));
        published = publish(storage, published, next.writer().close());
        storage.release(published);
        storage.close();

        assertThat(unlisted(path)).isEmpty();
        assertThat(committed(path)).containsOnlyKeys("c");
    }

    @Test
    void leavesFilesOfOtherNamesAlone() throws IOException {
        var foreign =
                List.of("manifest-01", "manifest-99999999999999999999", "segment-007", "segment-99999999999999999999");
        Files.createDirectories(path);
        var storage = FileStorage.open(new HookedDirectory(path), SCHEMA.fields(), ANALYZERS);
        for (var name : foreign) {
            Files.writeString(path.resolve(name), "not ours");
        }
        // A leftover of the index, which the next writer deletes.
        Files.writeString(path.resolve("segment-12.del-3"), "left over");
        storage.close();

        storage = FileStorage.open(new HookedDirectory(path), SCHEMA.fields(), ANALYZERS);
        var opened = storage.newWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        opened.writer().add(document(random, "a", "v1"));
        storage.release(opened.snapshot());
        storage.release(opened.writer().close());
        storage.close();

        assertThat(unlisted(path)).containsExactlyInAnyOrderElementsOf(foreign);
        assertThat(committed(path)).containsOnlyKeys("a");
    }

    @Test
    void releasesTheSnapshotOfARefreshForMemory() throws IOException {
        var storage = FileStorage.open(new HookedDirectory(path), SCHEMA.fields(), ANALYZERS);
        // A budget of one byte: every add writes a segment, and ten segments ask for a refresh.
        var opened = storage.newWriter(SCHEMA, Snapshot.EMPTY, 1);
        var published = opened.snapshot();
        var writer = opened.writer();
        for (var i = 0; i < 25; i++) {
            writer.add(document(random, "k" + i, "v1"));
        }
        for (var i = 0; i < 25; i++) {
            writer.delete("k" + i);
        }
        writer.add(document(random, "a", "v1"));
        published = publish(storage, published, writer.close());
        storage.release(published);
        storage.close();

        assertThat(unlisted(path)).isEmpty();
        assertThat(committed(path)).containsOnlyKeys("a");
    }

    // Publishes a snapshot as the index does: the previous one is released.
    private static Snapshot publish(FileStorage storage, Snapshot previous, Snapshot next) {
        storage.release(previous);
        return next;
    }
}
