package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Output;
import org.jspecify.annotations.Nullable;

/// A sink that keeps the sections and the segment on the heap. Not thread-safe.
public final class HeapSink implements SegmentSink {

    private @Nullable ByteOutput segment;

    @Override
    public Output newSection() {
        return new ByteOutput();
    }

    @Override
    public Output newSegment(long sizeHint) {
        var created = new ByteOutput((int) Math.min(sizeHint, ByteOutput.MAX_SIZE));
        segment = created;
        return created;
    }

    /// The bytes of the segment written.
    ///
    /// @return a new array
    /// @throws IllegalStateException if no segment was written
    public byte[] bytes() {
        var written = segment;
        if (written == null) {
            throw new IllegalStateException("No segment was written");
        }
        return written.toByteArray();
    }
}
