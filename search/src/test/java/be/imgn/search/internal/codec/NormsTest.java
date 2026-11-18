package be.imgn.search.internal.codec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormsTest {

    @Test
    void encodesShortLengthsExactly() {
        for (var length = 0; length < Norms.EXACT + 8; length++) {
            assertThat(Norms.encode(length)).isEqualTo(length);
            assertThat(Norms.decode(length)).isEqualTo(length);
        }
    }

    @Test
    void growsWithTheLength() {
        var previous = 0;
        for (long length = 0; length <= Integer.MAX_VALUE; length += 1 + length / 64) {
            var code = Norms.encode((int) length);
            assertThat(code).isGreaterThanOrEqualTo(previous).isLessThanOrEqualTo(Norms.MAX);
            previous = code;
        }
        assertThat(Norms.encode(Integer.MAX_VALUE)).isEqualTo(Norms.MAX);
    }

    @Test
    void decodesToTheShortestLengthOfEachCode() {
        var previous = -1;
        for (var code = 0; code <= Norms.MAX; code++) {
            var length = Norms.decode(code);
            assertThat(length).isGreaterThan(previous);
            assertThat(Norms.encode(length)).isEqualTo(code);
            if (length > 0) {
                assertThat(Norms.encode(length - 1)).isEqualTo(code - 1);
            }
            previous = length;
        }
    }
}
