package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import be.imgn.search.internal.index.IndexWriter;
import be.imgn.search.internal.index.Snapshot;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/// A full-text index: documents analysed by a [Schema], written by one [Writer] and searched by [Searcher]s over
/// snapshots.
///
/// [#inMemory] keeps the index on the heap: it lasts as long as the object. Thread-safe.
///
/// @since 0.1.0
public final class Index implements AutoCloseable {

    private final Schema schema;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private @Nullable Writer writer;
    private boolean closed;

    private Index(Schema schema) {
        this.schema = schema;
    }

    /// A new, empty index held on the heap.
    ///
    /// @param schema the schema of the documents
    /// @return the index
    /// @throws NullPointerException if `schema` is `null`
    public static Index inMemory(Schema schema) {
        return new Index(requireNonNull(schema, "schema"));
    }

    /// The schema of the documents.
    ///
    /// @return the schema
    public Schema schema() {
        return schema;
    }

    /// The index's writer, with a memory budget of 64 MiB for the documents it buffers. There is one writer at a
    /// time: closing it commits, and lets a new one be taken.
    ///
    /// @return the writer
    /// @throws IllegalStateException if the writer is already open, or the index is closed
    /// @throws IOException if the index cannot be written
    public synchronized Writer writer() throws IOException {
        checkState(!closed, "The index is closed");
        checkState(writer == null, "The writer is already open");
        var opened = new Writer(this, new IndexWriter(schema, snapshot, IndexWriter.DEFAULT_BUDGET));
        writer = opened;
        return opened;
    }

    /// A searcher over the current snapshot: the documents as of the writer's last refresh. Later changes are not
    /// visible to it.
    ///
    /// @return the searcher, to close after use
    /// @throws IllegalStateException if the index is closed
    public Searcher searcher() {
        checkState(!isClosed(), "The index is closed");
        return new Searcher(schema, snapshot);
    }

    /// Closes the index, and its writer if it is open, which commits.
    ///
    /// @throws IOException if the writer fails to commit
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
        if (open != null) {
            open.close();
        }
    }

    void publish(Snapshot newSnapshot) {
        snapshot = newSnapshot;
    }

    synchronized void writerClosed(Writer closedWriter) {
        if (writer == closedWriter) {
            writer = null;
        }
    }

    private synchronized boolean isClosed() {
        return closed;
    }
}
