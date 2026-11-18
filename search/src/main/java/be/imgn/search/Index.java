package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import be.imgn.search.internal.codec.CorruptDataException;
import be.imgn.search.internal.index.FileStorage;
import be.imgn.search.internal.index.HeapStorage;
import be.imgn.search.internal.index.IndexWriter;
import be.imgn.search.internal.index.Snapshot;
import be.imgn.search.internal.index.Storage;
import be.imgn.search.internal.store.FsDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.jspecify.annotations.Nullable;

/// A full-text index: documents analysed by a [Schema], written by one [Writer] and searched by [Searcher]s over
/// snapshots.
///
/// [#inMemory] keeps the index on the heap: it lasts as long as the object. [#open] keeps it in a directory, where
/// it may be larger than the memory: its segments are mapped files, and its commits survive a crash or a power
/// cut. Thread-safe.
///
/// @since 0.1.0
public final class Index implements AutoCloseable {

    private final Schema schema;
    private final Storage storage;
    // Guards snapshot, which holds a reference to its files, so that a searcher takes its own before it changes.
    private final Object snapshotLock = new Object();
    private Snapshot snapshot;
    private @Nullable Writer writer;
    private volatile boolean closed;

    private Index(Schema schema, Storage storage) {
        this.schema = schema;
        this.storage = storage;
        this.snapshot = storage.snapshot();
    }

    /// A new, empty index held on the heap.
    ///
    /// @param schema the schema of the documents
    /// @return the index
    /// @throws NullPointerException if `schema` is `null`
    public static Index inMemory(Schema schema) {
        return new Index(requireNonNull(schema, "schema"), HeapStorage.INSTANCE);
    }

    /// Opens the index of a directory, or creates one there. One process opens an index at a time: the index
    /// holds a lock on the directory's `write.lock` file until it is closed.
    ///
    /// The index is created in a missing directory, or in one that holds nothing but a lock or temporary files;
    /// a directory that holds other files but no index is refused. An existing index records its fields and the
    /// language analyzers it used: the schema must declare the same fields, with the same options and in the same
    /// order, and an analyzer with the same identifier for each of those languages. An analyzer for another
    /// language is accepted, for the documents added from then on; the default fields and the language detector
    /// are free.
    ///
    /// Opening reads the last commit, and checks its manifest and deletion files in full and the header and
    /// footer of its segment files; [#check()] checks the segments in full. A manifest that fails its checks
    /// throws, rather than falling back to an older commit.
    ///
    /// @param directory the directory
    /// @param schema the schema of the documents
    /// @return the index
    /// @throws NullPointerException if an argument is `null`
    /// @throws IllegalArgumentException if the index records other fields, or an analyzer that the schema lacks
    ///     or has with another identifier
    /// @throws IndexLockedException if the index is open already, in this process or another
    /// @throws CorruptIndexException if the files of the last commit fail their checks
    /// @throws IOException if the directory holds files but no index, or cannot be created, read or written
    /// @since 0.1.0
    public static Index open(Path directory, Schema schema) throws IOException {
        requireNonNull(directory, "directory");
        requireNonNull(schema, "schema");
        if (Files.notExists(directory)) {
            var parent = directory.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            if (parent != null) {
                FsDirectory.forceDirectory(parent);
            }
        }
        var analyzers = new HashMap<String, String>();
        schema.languages().forEach((language, analyzer) -> analyzers.put(language.getLanguage(), analyzer.id()));
        return new Index(schema, FileStorage.open(new FsDirectory(directory), schema.fields(), analyzers));
    }

    /// The schema of the documents.
    ///
    /// @return the schema
    public Schema schema() {
        return schema;
    }

    /// The index's writer, with a memory budget of 64 MiB for the documents it buffers. There is one writer at a
    /// time: closing it commits, and lets a new one be taken, as its failure does.
    ///
    /// @return the writer
    /// @throws IllegalStateException if the writer is already open, or the index is closed
    /// @throws IOException if the index cannot be written
    public Writer writer() throws IOException {
        return writer(IndexWriter.DEFAULT_BUDGET);
    }

    /// The index's writer, with a memory budget for the documents and deletes it buffers: when they exceed it, the
    /// largest buffer is written into a segment, or the buffered deletes are resolved. There is one writer at a
    /// time: closing it commits, and lets a new one be taken, as its failure does.
    ///
    /// A writer taken after one failed starts from the last commit of a file index, which searchers opened from
    /// then on see, and from the last refresh of an index in memory. In a file index, it first deletes the files
    /// that neither the last commit nor an open searcher uses: what a crash or the failed writer left.
    ///
    /// @param memoryBudget the budget, in bytes
    /// @return the writer
    /// @throws IllegalArgumentException if `memoryBudget` is not positive
    /// @throws IllegalStateException if the writer is already open, or the index is closed
    /// @throws IOException if the index cannot be read or its leftover files deleted
    /// @since 0.1.0
    public synchronized Writer writer(long memoryBudget) throws IOException {
        checkArgument(memoryBudget > 0, "The memory budget is %s, but must be positive", memoryBudget);
        checkState(!closed, "The index is closed");
        var current = writer;
        checkState(current == null || current.failed(), "The writer is already open");
        if (current != null) {
            current.awaitStopped();
        }
        Snapshot published;
        synchronized (snapshotLock) {
            published = snapshot;
        }
        var start = storage.newWriter(schema, published, memoryBudget);
        publish(start.snapshot());
        var opened = new Writer(this, start.writer());
        writer = opened;
        return opened;
    }

    /// A searcher over the current snapshot: the documents as of the writer's last refresh. Later changes are not
    /// visible to it, and the files it reads stay until it is closed.
    ///
    /// @return the searcher, to close after use
    /// @throws IllegalStateException if the index is closed
    public Searcher searcher() {
        synchronized (snapshotLock) {
            checkState(!isClosed(), "The index is closed");
            storage.retain(snapshot);
            return new Searcher(this, snapshot);
        }
    }

    /// Checks every segment of the current snapshot against its checksum, which reads the whole index. Opening
    /// an index checks only the header and footer of its segment files: reading them in full would take long, and
    /// evict the page cache for an index larger than the memory.
    ///
    /// @throws IllegalStateException if the index is closed
    /// @throws CorruptIndexException if a segment's checksum does not match its content
    /// @throws IOException if the index cannot be read
    /// @since 0.1.0
    public void check() throws IOException {
        Snapshot checked;
        synchronized (snapshotLock) {
            checkState(!isClosed(), "The index is closed");
            checked = snapshot;
            storage.retain(checked);
        }
        try {
            for (var entry : checked.segments()) {
                try {
                    entry.segment().checkIntegrity();
                } catch (CorruptDataException e) {
                    throw new CorruptIndexException("A segment is corrupt: " + e.getMessage(), e);
                }
            }
        } finally {
            storage.release(checked);
        }
    }

    /// Closes the index, and its writer if it is open, which commits; then releases the lock of a file index. The
    /// searchers still open keep working until they are closed. Closing a closed index does nothing.
    ///
    /// @throws IllegalStateException if the writer failed, after closing the index
    /// @throws IOException if the writer fails to commit, or the lock cannot be released
    @Override
    public void close() throws IOException {
        Writer open;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            open = writer;
        }
        try {
            if (open != null) {
                open.close();
            }
        } finally {
            synchronized (snapshotLock) {
                storage.release(snapshot);
                snapshot = Snapshot.EMPTY;
            }
            storage.close();
        }
    }

    /// Makes a snapshot the current one, taking over the caller's reference to it.
    void publish(Snapshot newSnapshot) {
        Snapshot old;
        synchronized (snapshotLock) {
            old = snapshot;
            snapshot = newSnapshot;
        }
        storage.release(old);
    }

    /// Releases a searcher's reference to its snapshot.
    void release(Snapshot released) {
        storage.release(released);
    }

    synchronized void writerClosed(Writer closedWriter) {
        if (writer == closedWriter) {
            writer = null;
        }
    }

    private boolean isClosed() {
        return closed;
    }
}
