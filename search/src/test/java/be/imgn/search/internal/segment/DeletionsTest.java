package be.imgn.search.internal.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.search.internal.codec.CorruptDataException;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class DeletionsTest {

    @Test
    void addsDeletionsWithoutChangingTheOriginal() {
        var first = Deletions.NONE.with(new int[] {3, 64, 3}, 3, 100);
        var second = first.with(new int[] {99, 64}, 2, 100);

        assertThat(Deletions.NONE.count()).isZero();
        assertThat(first.count()).isEqualTo(2);
        assertThat(first.isDeleted(3)).isTrue();
        assertThat(first.isDeleted(99)).isFalse();
        assertThat(second.count()).isEqualTo(3);
        assertThat(second.isDeleted(99)).isTrue();
        assertThat(second.with(new int[] {3}, 1, 100)).isSameAs(second);
    }

    @Test
    void roundTripsThroughBytes() {
        var deletions = Deletions.NONE.with(new int[] {0, 63, 64, 129}, 4, 130);

        var decoded = Deletions.decode(MemorySegment.ofArray(deletions.encode(130)));

        assertThat(decoded.count()).isEqualTo(4);
        for (var doc = 0; doc < 130; doc++) {
            assertThat(decoded.isDeleted(doc)).isEqualTo(deletions.isDeleted(doc));
        }
    }

    @Test
    void rejectsCorruptBytes() {
        var bytes = Deletions.NONE.with(new int[] {5}, 1, 10).encode(10);
        bytes[bytes.length - 6] ^= 1;

        assertThatThrownBy(() -> Deletions.decode(MemorySegment.ofArray(bytes)))
                .isInstanceOf(CorruptDataException.class);
    }
}
