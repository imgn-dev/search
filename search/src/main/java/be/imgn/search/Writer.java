package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import be.imgn.search.internal.index.IndexWriter;
import java.io.IOException;

/// Adds and deletes the documents of an [Index]. Changes become visible to new searchers at the next
/// [#refresh()], and durable at the next [#commit()]; [#close()] commits.
///
/// Thread-safe, and adds from several threads run in parallel. Concurrent operations on one key end as they
/// were ordered: each add and delete is ordered when it takes effect, and applies to the documents of its key
/// added before it. Segments are merged in the background, on at most two threads of the writer.
///
/// Methods that may write files throw `IOException` on every storage, so one type serves them all. An exception
/// that escapes writing a segment, a refresh, a commit or a merge, an `IOException` among them, closes the writer:
/// the index stays at its last commit, or in memory at its last refresh, the index can give a new writer, and
/// every later call to this one throws an `IllegalStateException` caused by that exception.
///
/// @since 0.1.0
public final class Writer implements AutoCloseable {

    private final Index index;
    private final IndexWriter writer;
    private volatile boolean closed;

    Writer(Index index, IndexWriter writer) {
        this.index = index;
        this.writer = writer;
    }

    /// Adds a document, replacing the document with the same key if there is one.
    ///
    /// An exception from the analysis, such as one a language analyzer or detector throws, or an `IOException` a
    /// [Document.TextSource] throws when it is opened or read, leaves the writer as it was: the document is not
    /// added, and the writer stays open. The sources of the document are read during this call only, and closed
    /// before it returns. An add may also write a segment to keep the writer within its memory budget, whether the
    /// document was added or not; when that fails after the document failed, the writer is closed, and the
    /// document's exception is thrown with that failure suppressed.
    ///
    /// @param document the document
    /// @throws NullPointerException if `document` is `null`
    /// @throws IllegalArgumentException if a field of the document is not declared by the schema, or is of
    ///     another kind
    /// @throws IllegalStateException if the writer is closed or failed, or the index already holds
    ///     `Integer.MAX_VALUE` documents, deleted ones included
    /// @throws IOException if a source of the document cannot be read, which leaves the writer as it was, or the
    ///     index cannot be written, which closes it
    public void add(Document document) throws IOException {
        requireNonNull(document, "document");
        checkOpen();
        index.schema().check(document);
        writer.add(document);
    }

    /// Deletes the document with a key, if there is one.
    ///
    /// @param key the document's key
    /// @throws NullPointerException if `key` is `null`
    /// @throws IllegalArgumentException if `key` is empty, or not well-formed UTF-16: see [Document]
    /// @throws IllegalStateException if the writer is closed or failed
    /// @throws IOException if the index cannot be written
    public void delete(String key) throws IOException {
        requireNonNull(key, "key");
        checkArgument(!key.isEmpty(), "A document key cannot be empty");
        Document.checkWellFormed(key, "A document key");
        checkOpen();
        writer.delete(key);
    }

    /// Makes every change so far visible to the searchers opened from now on: it waits for the adds in progress,
    /// and the operations that start meanwhile wait for the next refresh.
    ///
    /// @throws IllegalStateException if the writer is closed or failed
    /// @throws IOException if the index cannot be written
    public synchronized void refresh() throws IOException {
        checkOpen();
        index.publish(writer.refresh());
    }

    /// Refreshes and makes every change so far durable. An index in memory has nothing to make durable, so
    /// committing it is refreshing it.
    ///
    /// In a file index, the commit forces the new segment and deletion files to the storage device, then writes a
    /// new manifest that lists them, atomically: a crash or a power cut leaves the index at this commit or at the
    /// previous one. When the commit throws once its manifest is renamed into place, the index on disk may be at
    /// either.
    ///
    /// @throws IllegalStateException if the writer is closed or failed
    /// @throws IOException if the index cannot be written
    public synchronized void commit() throws IOException {
        checkOpen();
        index.publish(writer.commit());
    }

    /// Stops the merges, commits and releases the writer, so that the index can give a new one. Closing a closed
    /// writer does nothing.
    ///
    /// @throws IllegalStateException if the writer failed, after releasing it
    /// @throws IOException if the index cannot be written
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            index.publish(writer.close());
        } finally {
            index.writerClosed(this);
        }
    }

    boolean failed() {
        return writer.failed();
    }

    void awaitStopped() {
        writer.awaitStopped();
    }

    private void checkOpen() {
        checkState(!closed, "The writer is closed");
    }
}
