package be.imgn.search.internal.index;

import be.imgn.search.internal.segment.SegmentMerger;
import be.imgn.search.internal.segment.SegmentSink;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/// How the writer makes segments: from buffers and by merging. The writer goes through it, so that tests can
/// replace it with one that fails.
interface SegmentWriting {

    /// The real one.
    SegmentWriting DEFAULT = new SegmentWriting() {};

    /// A new flusher, which one thread at a time uses.
    ///
    /// @param storedFields the names the stored records refer to by index
    /// @return the flusher, to [end][SegmentFlusher#end()]
    default SegmentFlusher newFlusher(List<String> storedFields) {
        return new SegmentFlusher(storedFields);
    }

    /// Writes a buffer's live documents into a segment.
    ///
    /// @param flusher the flusher
    /// @param buffer the buffer
    /// @param sink where the segment goes
    /// @return whether a segment was written, rather than none because no document is live
    default boolean flush(SegmentFlusher flusher, DocumentBuffer buffer, SegmentSink sink) {
        return flusher.flush(buffer, sink);
    }

    /// Merges segments: see [SegmentMerger#merge].
    ///
    /// @param storedFields the names the stored records refer to by index
    /// @param inputs the segments, each with the deletions to drop
    /// @param docMaps their [doc maps][SegmentMerger#docMaps]
    /// @param maxDoc the number of documents the maps keep, at least 1
    /// @param stop whether to give up
    /// @param sink where the merged segment goes
    /// @return whether the segment was written, rather than given up because `stop` said so
    default boolean merge(
            List<String> storedFields,
            List<SegmentMerger.Input> inputs,
            List<int[]> docMaps,
            int maxDoc,
            BooleanSupplier stop,
            SegmentSink sink) {
        var deflater = new Deflater(Deflater.BEST_SPEED);
        var inflater = new Inflater();
        try {
            return new SegmentMerger(storedFields, deflater, inflater).merge(inputs, docMaps, maxDoc, stop, sink);
        } finally {
            deflater.end();
            inflater.end();
        }
    }
}
