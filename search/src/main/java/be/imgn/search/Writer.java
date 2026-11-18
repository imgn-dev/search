package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import be.imgn.search.internal.index.IndexWriter;
import java.io.IOException;

/// Adds and deletes the documents of an [Index]. Changes become visible to new searchers at the next
/// [#refresh()], and durable at the next [#commit()]; [#close()] commits.
///
/// Thread-safe. Methods that may write files throw `IOException` on every storage, so one type serves them all;
/// after an `IOException` the writer is closed and the index stays at its last commit.
///
/// @since 0.1.0
public final class Writer implements AutoCloseable {

    private final Index index;
    private final IndexWriter writer;
    private boolean closed;

    Writer(Index index, IndexWriter writer) {
        this.index = index;
        this.writer = writer;
    }

    /// Adds a document, replacing the document with the same key if there is one.
    ///
    /// An exception from the analysis, such as one a language analyzer or detector throws, leaves the writer as
    /// it was.
    ///
    /// @param document the document
    /// @throws NullPointerException if `document` is `null`
    /// @throws IllegalArgumentException if a field of the document is not declared by the schema, or is of
    ///     another kind
    /// @throws IllegalStateException if the writer is closed, or the index already holds `Integer.MAX_VALUE`
    ///     documents, deleted ones included
    /// @throws IOException if the index cannot be written
    public synchronized void add(Document document) throws IOException {
        requireNonNull(document, "document");
        checkOpen();
        index.schema().check(document);
        writer.add(document);
    }

    /// Deletes the document with a key, if there is one.
    ///
    /// @param key the document's key
    /// @throws NullPointerException if `key` is `null`
    /// @throws IllegalArgumentException if `key` is empty
    /// @throws IllegalStateException if the writer is closed
    /// @throws IOException if the index cannot be written
    public synchronized void delete(String key) throws IOException {
        requireNonNull(key, "key");
        checkArgument(!key.isEmpty(), "A document key cannot be empty");
        checkOpen();
        writer.delete(key);
    }

    /// Makes every change so far visible to the searchers opened from now on.
    ///
    /// @throws IllegalStateException if the writer is closed
    /// @throws IOException if the index cannot be written
    public synchronized void refresh() throws IOException {
        checkOpen();
        index.publish(writer.refresh());
    }

    /// Refreshes and makes every change so far durable. An index in memory has nothing to make durable, so
    /// committing it is refreshing it.
    ///
    /// @throws IllegalStateException if the writer is closed
    /// @throws IOException if the index cannot be written
    public synchronized void commit() throws IOException {
        refresh();
    }

    /// Commits and releases the writer, so that the index can give a new one. Closing a closed writer does
    /// nothing.
    ///
    /// @throws IOException if the index cannot be written
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            commit();
        } finally {
            closed = true;
            writer.close();
            index.writerClosed(this);
        }
    }

    private void checkOpen() {
        checkState(!closed, "The writer is closed");
    }
}
