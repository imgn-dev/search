package be.imgn.search.internal.index;

import be.imgn.search.internal.segment.Segment;
import be.imgn.search.internal.segment.SegmentSink;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/// Where a writer's segments live, and how its commits become durable: on the heap, where nothing is durable, or
/// in files. A writer holds a reference to each segment it makes or starts from, until it
/// [releases][#release] it or [closes][#close()] these files. Thread-safe.
public interface SegmentFiles {

    /// The largest segment a merge may make.
    ///
    /// @return the size, in bytes
    long maxSegmentBytes();

    /// Writes a segment and opens it.
    ///
    /// @param large whether the segment may be large, as a merge's: its sections then go to temporary files
    ///     rather than the heap, in a file index
    /// @param job what writes the segment into a sink, and tells whether it did
    /// @return the segment, or `null` if the job wrote none
    /// @throws IOException if the segment cannot be written
    @Nullable
    Segment write(boolean large, Job job) throws IOException;

    /// Takes a reference to the segments of a snapshot, for the snapshot's owner to release.
    ///
    /// @param snapshot the snapshot
    void retain(Snapshot snapshot);

    /// Releases a reference to the segments of a snapshot, which [#retain] took.
    ///
    /// @param snapshot the snapshot
    void release(Snapshot snapshot);

    /// Releases the writer's reference to a segment it dropped.
    ///
    /// @param segment the segment
    void release(Segment segment);

    /// Makes a snapshot durable, as the index's last commit.
    ///
    /// @param snapshot the snapshot, which the caller holds a reference to
    /// @throws IOException if the commit cannot be written
    void commit(Snapshot snapshot) throws IOException;

    /// Releases the references of the writer. Later segments are refused.
    void close();

    /// Writes a segment into a sink.
    @FunctionalInterface
    interface Job {

        /// Writes the segment.
        ///
        /// @param sink where the segment goes
        /// @return whether a segment was written
        boolean write(SegmentSink sink);
    }
}
