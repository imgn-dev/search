package be.imgn.search.internal.index;

import be.imgn.search.internal.segment.SegmentMerger;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

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
    /// @return the segment's bytes, or `null` if no document is live
    default byte @Nullable [] flush(SegmentFlusher flusher, DocumentBuffer buffer) {
        return flusher.flush(buffer);
    }

    /// Merges segments: see [SegmentMerger#merge].
    ///
    /// @param storedFields the names the stored records refer to by index
    /// @param inputs the segments, each with the deletions to drop
    /// @param docMaps their [doc maps][SegmentMerger#docMaps]
    /// @param maxDoc the number of documents the maps keep, at least 1
    /// @param stop whether to give up
    /// @return the merged segment's bytes, or `null` if `stop` said to give up
    default byte @Nullable [] merge(
            List<String> storedFields,
            List<SegmentMerger.Input> inputs,
            List<int[]> docMaps,
            int maxDoc,
            BooleanSupplier stop) {
        var deflater = new Deflater(Deflater.BEST_SPEED);
        var inflater = new Inflater();
        try {
            return new SegmentMerger(storedFields, deflater, inflater).merge(inputs, docMaps, maxDoc, stop);
        } finally {
            deflater.end();
            inflater.end();
        }
    }
}
