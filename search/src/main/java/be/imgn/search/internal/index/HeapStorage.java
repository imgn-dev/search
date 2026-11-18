package be.imgn.search.internal.index;

import be.imgn.search.Schema;

/// The storage of an in-memory index: the heap, without files, so nothing to count or to make durable. A new
/// writer starts from the snapshot searchers see.
public final class HeapStorage implements Storage {

    /// The one instance: it has no state.
    public static final HeapStorage INSTANCE = new HeapStorage();

    private HeapStorage() {}

    @Override
    public Snapshot snapshot() {
        return Snapshot.EMPTY;
    }

    @Override
    public Opened newWriter(Schema schema, Snapshot published, long budget) {
        return new Opened(new IndexWriter(schema, published, budget, HeapSegments.INSTANCE), published);
    }

    @Override
    public void retain(Snapshot snapshot) {}

    @Override
    public void release(Snapshot snapshot) {}

    @Override
    public void close() {}
}
