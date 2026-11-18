package be.imgn.search.internal.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.MemorySegment;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BitPackingTest {

    static IntStream widths() {
        return IntStream.rangeClosed(0, Integer.SIZE);
    }

    @ParameterizedTest
    @MethodSource("widths")
    void roundTripsEveryWidth(int width) {
        var random = new Random(width);
        var values = new int[BitPacking.BLOCK];
        for (var i = 0; i < values.length; i++) {
            values[i] = width == 0 ? 0 : random.nextInt() >>> (Integer.SIZE - width);
        }
        // The largest value of the width, so that the block needs exactly that width.
        if (width > 0) {
            values[BitPacking.BLOCK / 2] = (int) ((1L << width) - 1);
        }
        var out = new ByteOutput();
        out.writeByte(0xAB);

        BitPacking.pack(values, width, out);

        assertThat(BitPacking.width(values, values.length)).isEqualTo(width);
        assertThat(out.size()).isEqualTo(1 + BitPacking.bytes(width));
        var decoded = new int[BitPacking.BLOCK];
        BitPacking.unpack(
                MemorySegment.ofArray(out.toByteArray()), 1, width, decoded, new long[BitPacking.SCRATCH_LONGS]);
        assertThat(decoded).containsExactly(values);
    }
}
