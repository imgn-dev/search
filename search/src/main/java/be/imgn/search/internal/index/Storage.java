package be.imgn.search.internal.index;

import be.imgn.search.Schema;
import java.io.IOException;

/// Where an index lives: on the heap, or in a directory. It gives the index its writers, and counts the
/// references that snapshots hold to its files. Thread-safe.
public sealed interface Storage permits HeapStorage, FileStorage {

    /// The snapshot the index starts with, which the caller holds a reference to.
    ///
    /// @return the snapshot: empty, or the last commit
    Snapshot snapshot();

    /// A new writer, the only one until it is closed or fails.
    ///
    /// @param schema the schema
    /// @param published the snapshot searchers see now
    /// @param budget the memory budget of the writer's buffers
    /// @return the writer, and the snapshot it starts from, which the caller holds a reference to
    /// @throws IOException if the index cannot be read or cleaned
    Opened newWriter(Schema schema, Snapshot published, long budget) throws IOException;

    /// Takes a reference to the files of a snapshot.
    ///
    /// @param snapshot the snapshot
    void retain(Snapshot snapshot);

    /// Releases a reference to the files of a snapshot: the files no longer referenced are closed, and deleted
    /// unless the storage is closed.
    ///
    /// @param snapshot the snapshot
    void release(Snapshot snapshot);

    /// Closes the storage, once the writer is closed: releases the last commit's files, without deleting any
    /// file from now on, and the lock. The snapshots still referenced keep their files open until released.
    ///
    /// @throws IOException if the lock cannot be released
    void close() throws IOException;

    /// A new writer, and the snapshot it starts from.
    ///
    /// @param writer the writer
    /// @param snapshot the snapshot, which searchers should see from now on
    record Opened(IndexWriter writer, Snapshot snapshot) {}
}
