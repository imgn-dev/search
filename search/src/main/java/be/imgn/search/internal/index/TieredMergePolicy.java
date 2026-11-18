package be.imgn.search.internal.index;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/// Chooses which segments to merge: a simpler form of Lucene's tiered policy.
///
/// The index may hold [#segmentsPerTier] segments per tier of size, each tier [#maxMergeAtOnce] times larger than
/// the one below, the lowest being the smallest segment's size, at least [#floorBytes]. Beyond that count, the
/// policy merges at most [#maxMergeAtOnce] segments whose sizes are close, never into more than the largest total
/// allowed; among the possible merges it prefers the least skewed, then the smallest. Sizes are those of the live
/// documents, so that deletions make a segment smaller and merges reclaim them. The limits, though, apply to the
/// whole segments, deleted documents included, which nearly bound what the merge writes: segments over half of
/// [#maxSegmentBytes] are left as they are, and the segments of a merge never total more than its limit, nor more
/// than [#OUTPUT_MARGIN] of [#maxSegmentBytes].
///
/// @param maxSegmentBytes the largest segment a merge makes
/// @param segmentsPerTier the number of segments allowed per tier
/// @param maxMergeAtOnce the most segments merged at once
/// @param floorBytes the size under which segments count as of that size, so that tiny ones merge together
record TieredMergePolicy(long maxSegmentBytes, int segmentsPerTier, int maxMergeAtOnce, long floorBytes) {

    /// The policy of the in-memory index: segments of at most 1 GiB, 10 per tier, merged 10 at a time, sizes
    /// floored at 2 MiB. A file index allows larger segments: see [#withMaxSegmentBytes].
    static final TieredMergePolicy DEFAULT = new TieredMergePolicy(1L << 30, 10, 10, 2L << 20);

    /// The share of [#maxSegmentBytes] the segments of a merge may total. A merged segment is usually smaller
    /// than its segments together, but not always: a block of postings that spans two merged segments is
    /// bit-packed at the width of its largest gap, which the gap across the boundary may widen beyond what either
    /// block had, and the last, partial blocks of the segments become full, packed blocks. The margin keeps that
    /// growth below the cap, beyond which writing the merged segment fails, and with it the writer.
    static final double OUTPUT_MARGIN = 0.9;

    // A slight preference for smaller merges, as Lucene's.
    private static final double SIZE_EXPONENT = 0.05;

    /// This policy with another largest segment, as the storage allows.
    ///
    /// @param bytes the largest segment a merge makes
    /// @return the policy
    TieredMergePolicy withMaxSegmentBytes(long bytes) {
        return new TieredMergePolicy(bytes, segmentsPerTier, maxMergeAtOnce, floorBytes);
    }

    /// The best merge of some segments, if they need one.
    ///
    /// @param sizes the size of each segment's live documents
    /// @param bytes the size of each segment, deleted documents included
    /// @param maxMergeBytes the largest total of `bytes` a merge may have, lowered to [#OUTPUT_MARGIN] of
    ///     [#maxSegmentBytes] if it is above
    /// @return the indexes of the segments to merge, increasing; empty when no merge is needed
    int[] findMerge(long[] sizes, long[] bytes, long maxMergeBytes) {
        var limit = Math.min(maxMergeBytes, (long) ((double) maxSegmentBytes * OUTPUT_MARGIN));
        var eligible = IntStream.range(0, sizes.length)
                .filter(i -> bytes[i] <= maxSegmentBytes / 2)
                .boxed()
                .sorted(Comparator.comparingLong((Integer i) -> sizes[i]).reversed())
                .mapToInt(Integer::intValue)
                .toArray();
        if (eligible.length <= allowedSegments(sizes, eligible)) {
            return new int[0];
        }
        int[] best = null;
        var bestScore = Double.MAX_VALUE;
        for (var start = 0; start < eligible.length; start++) {
            var candidate = new int[maxMergeAtOnce];
            var count = 0;
            long total = 0;
            long totalBytes = 0;
            var tooLarge = false;
            for (var i = start; i < eligible.length && count < maxMergeAtOnce; i++) {
                if (totalBytes + bytes[eligible[i]] > limit) {
                    tooLarge = true;
                    continue;
                }
                candidate[count++] = eligible[i];
                total += sizes[eligible[i]];
                totalBytes += bytes[eligible[i]];
            }
            if (count < 2) {
                continue;
            }
            // Past here, the candidates only get smaller: the best one is found.
            if (best != null && !tooLarge && count < maxMergeAtOnce) {
                break;
            }
            var score = score(sizes, candidate, count, total);
            if (score < bestScore) {
                bestScore = score;
                best = Arrays.copyOf(candidate, count);
            }
        }
        if (best == null) {
            return new int[0];
        }
        Arrays.sort(best);
        return best;
    }

    private long allowedSegments(long[] sizes, int[] eligible) {
        if (eligible.length == 0) {
            return 0;
        }
        long total = 0;
        for (var i : eligible) {
            total += sizes[i];
        }
        var levelSize = Math.max(sizes[eligible[eligible.length - 1]], floorBytes);
        var bytesLeft = total;
        long allowed = 0;
        while (true) {
            var levelCount = (double) bytesLeft / levelSize;
            if (levelCount < segmentsPerTier || levelSize >= maxSegmentBytes) {
                allowed += (long) Math.ceil(levelCount);
                break;
            }
            allowed += segmentsPerTier;
            bytesLeft -= segmentsPerTier * levelSize;
            levelSize = Math.min(maxSegmentBytes, levelSize * maxMergeAtOnce);
        }
        return Math.max(allowed, segmentsPerTier);
    }

    // Lower is better: the share of the largest segment, so that merges join similar sizes, slightly weighed by
    // the total.
    private double score(long[] sizes, int[] candidate, int count, long total) {
        long flooredTotal = 0;
        long largest = 0;
        for (var i = 0; i < count; i++) {
            var floored = Math.max(sizes[candidate[i]], floorBytes);
            flooredTotal += floored;
            largest = Math.max(largest, floored);
        }
        return (double) largest / flooredTotal * Math.pow((double) total, SIZE_EXPONENT);
    }
}
