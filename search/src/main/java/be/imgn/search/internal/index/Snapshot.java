package be.imgn.search.internal.index;

import be.imgn.search.internal.segment.Deletions;
import be.imgn.search.internal.segment.Segment;
import java.util.List;

/// The segments a searcher reads, each with its deletions and the number of its first document in the snapshot.
///
/// @param segments the segments, in order
/// @param maxDoc the number of documents, deleted ones included
public record Snapshot(List<Entry> segments, int maxDoc) {

    /// A snapshot without documents.
    public static final Snapshot EMPTY = new Snapshot(List.of(), 0);

    /// Copies the segments.
    public Snapshot {
        segments = List.copyOf(segments);
    }

    /// The index of the segment that holds a document.
    ///
    /// @param doc a document of the snapshot
    /// @return the index in [#segments()]
    public int segmentOf(int doc) {
        var low = 0;
        var high = segments.size() - 1;
        while (low < high) {
            var middle = (low + high + 1) >>> 1;
            if (segments.get(middle).base() <= doc) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /// A segment of a snapshot.
    ///
    /// @param segment the segment
    /// @param deletions its deleted documents
    /// @param base the number, in the snapshot, of its first document
    public record Entry(Segment segment, Deletions deletions, int base) {}
}
