package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.Output;

/// Where a [SegmentWriter] puts its sections while it writes them, then the segment: arrays on the heap, or files.
public interface SegmentSink {

    /// A new, empty section, written while the terms and documents are added.
    ///
    /// @return the section
    Output newSection();

    /// The output of the segment, which [SegmentWriter#finish()] asks for once.
    ///
    /// @param sizeHint about the number of bytes the segment takes
    /// @return the output, empty
    Output newSegment(long sizeHint);
}
