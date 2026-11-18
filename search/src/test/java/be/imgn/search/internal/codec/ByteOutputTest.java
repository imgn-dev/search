package be.imgn.search.internal.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ByteOutputTest {

    private static final int[] INTS = {0, 1, 127, 128, 255, 16_383, 16_384, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
    private static final long[] LONGS = {0, 1, 127, 128, 1L << 35, Long.MAX_VALUE, -1, Long.MIN_VALUE};

    @Test
    void roundTripsVariableLengthIntegers() {
        var out = new ByteOutput(1);
        for (var value : INTS) {
            out.writeVInt(value);
        }
        for (var value : LONGS) {
            out.writeVLong(value);
        }

        var in = new DataInput(MemorySegment.ofArray(out.toByteArray()), 0);
        for (var value : INTS) {
            assertThat(in.readVInt()).isEqualTo(value);
        }
        for (var value : LONGS) {
            assertThat(in.readVLong()).isEqualTo(value);
        }
        assertThat(in.position()).isEqualTo(out.size());
    }

    @Test
    void writesSmallValuesInOneByte() {
        var out = new ByteOutput();
        out.writeVInt(127);
        out.writeVInt(128);

        assertThat(out.size()).isEqualTo(3);
    }

    @Test
    void writesFixedNumbersInLittleEndianOrder() {
        var out = new ByteOutput();
        out.writeInt(0x0403_0201);
        out.writeLong(0x0C0B_0A09_0807_0605L);

        assertThat(out.toByteArray()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        var in = new DataInput(MemorySegment.ofArray(out.toByteArray()), 0);
        assertThat(in.readInt()).isEqualTo(0x0403_0201);
        assertThat(in.readLong()).isEqualTo(0x0C0B_0A09_0807_0605L);
    }

    @Test
    void roundTripsStrings() {
        var out = new ByteOutput();
        out.writeString("");
        out.writeString("forêt Straße 𝄞");

        var in = new DataInput(MemorySegment.ofArray(out.toByteArray()), 0);
        assertThat(in.readString()).isEmpty();
        assertThat(in.readString()).isEqualTo("forêt Straße 𝄞");
    }

    @Test
    void encodesUtf8AsTheJdkDoes() {
        for (var text : new String[] {"fox", "élève", "Привет", "𝄞 music", "lone \uD800 surrogate", "end \uDC00"}) {
            var chars = text.toCharArray();
            var bytes = new byte[chars.length * Utf8.MAX_BYTES_PER_CHAR];

            var length = Utf8.encode(chars, chars.length, bytes);

            assertThat(Arrays.copyOf(bytes, length)).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
