package be.imgn.search.internal.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TieredMergePolicyTest {

    private static final long KIB = 1 << 10;
    private static final long MIB = 1 << 20;
    private static final TieredMergePolicy POLICY = TieredMergePolicy.DEFAULT;

    @Test
    void leavesAFewSegmentsAlone() {
        var sizes = new long[10];
        Arrays.fill(sizes, 10 * KIB);

        assertThat(POLICY.findMerge(sizes, sizes, POLICY.maxSegmentBytes())).isEmpty();
        assertThat(POLICY.findMerge(new long[0], new long[0], POLICY.maxSegmentBytes()))
                .isEmpty();
    }

    @Test
    void mergesTenSmallSegmentsBeyondATier() {
        var sizes = new long[11];
        for (var i = 0; i < sizes.length; i++) {
            sizes[i] = (i + 1) * KIB;
        }

        var merge = POLICY.findMerge(sizes, sizes, POLICY.maxSegmentBytes());

        assertThat(merge).hasSize(10).isSorted();
    }

    @Test
    void mergesSegmentsOfSimilarSizes() {
        // Three large segments, and many small ones: the small ones merge together.
        var sizes = new long[28];
        Arrays.fill(sizes, 0, 3, 300 * MIB);
        Arrays.fill(sizes, 3, 28, 3 * MIB);

        var merge = POLICY.findMerge(sizes, sizes, POLICY.maxSegmentBytes());

        assertThat(merge).hasSize(10);
        assertThat(Arrays.stream(merge).min().orElseThrow()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void keepsMergesUnderTheirLimit() {
        var sizes = new long[12];
        Arrays.fill(sizes, 90 * MIB);

        var large = POLICY.findMerge(sizes, sizes, POLICY.maxSegmentBytes());
        var small = POLICY.findMerge(sizes, sizes, IndexWriter.LARGE_MERGE_BYTES);

        assertThat(large).hasSize(10);
        assertThat(small).hasSize(2);
    }

    @Test
    void keepsAMarginBelowTheSegmentCap() {
        // Ten segments of 100 MiB total less than 1 GiB, but more than the margin allows.
        var sizes = new long[12];
        Arrays.fill(sizes, 100 * MIB);

        var merge = POLICY.findMerge(sizes, sizes, POLICY.maxSegmentBytes());

        assertThat(merge).hasSize(9);
        assertThat(9 * 100 * MIB)
                .isLessThanOrEqualTo((long) (POLICY.maxSegmentBytes() * TieredMergePolicy.OUTPUT_MARGIN));
        assertThat(10 * 100 * MIB).isLessThan(POLICY.maxSegmentBytes()).isGreaterThan((long)
                (POLICY.maxSegmentBytes() * TieredMergePolicy.OUTPUT_MARGIN));
    }

    @Test
    void boundsMergesByTheWholeSegmentsDeletedDocumentsIncluded() {
        // Twelve segments of 200 MiB, mostly deleted: their live documents would fit in one merge, but not the
        // segments themselves, which bound what a merge writes.
        var sizes = new long[12];
        Arrays.fill(sizes, 20 * MIB);
        var bytes = new long[12];
        Arrays.fill(bytes, 200 * MIB);

        var merge = POLICY.findMerge(sizes, bytes, POLICY.maxSegmentBytes());

        long total = 0;
        for (var i : merge) {
            total += bytes[i];
        }
        assertThat(merge).hasSizeGreaterThan(1);
        assertThat(total).isLessThanOrEqualTo(POLICY.maxSegmentBytes());
        Arrays.fill(bytes, 600 * MIB);
        assertThat(POLICY.findMerge(sizes, bytes, POLICY.maxSegmentBytes())).isEmpty();
    }

    @Test
    void leavesSegmentsOverHalfTheMaximumAlone() {
        var sizes = new long[12];
        Arrays.fill(sizes, 600 * MIB);

        assertThat(POLICY.findMerge(sizes, sizes, POLICY.maxSegmentBytes())).isEmpty();
    }
}
