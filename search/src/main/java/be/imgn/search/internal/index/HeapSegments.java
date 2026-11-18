package be.imgn.search.internal.index;

import be.imgn.search.internal.segment.HeapSink;
import be.imgn.search.internal.segment.Segment;
import java.lang.foreign.MemorySegment;
import org.jspecify.annotations.Nullable;

/// The segments of an in-memory index: arrays on the heap, which the garbage collector frees. Nothing is
/// durable, and nothing is counted.
final class HeapSegments implements SegmentFiles {

    /// The one instance: it has no state.
    static final HeapSegments INSTANCE = new HeapSegments();

    private HeapSegments() {}

    @Override
    public long maxSegmentBytes() {
        return TieredMergePolicy.DEFAULT.maxSegmentBytes();
    }

    @Override
    public @Nullable Segment write(boolean large, Job job) {
        var sink = new HeapSink();
        return job.write(sink) ? Segment.open(MemorySegment.ofArray(sink.bytes())) : null;
    }

    @Override
    public void retain(Snapshot snapshot) {}

    @Override
    public void release(Snapshot snapshot) {}

    @Override
    public void release(Segment segment) {}

    @Override
    public void commit(Snapshot snapshot) {}

    @Override
    public void close() {}
}
