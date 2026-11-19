package be.imgn.search.internal.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
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

    @Test
    void writesAStringInPiecesAsAWholeString() {
        var random = new Random(42);
        char[] pieces = {'a', 'é', 'Ж', '中', '\uD800', '\uDC00', '\uD835', '\uDC9C', '\n'};
        for (var length : new int[] {0, 1, 42, 127, 128, 129, 5000, 16_383, 16_384, 70_000}) {
            var chars = new char[length];
            for (var i = 0; i < length; i++) {
                chars[i] = pieces[random.nextInt(pieces.length)];
            }
            var text = new String(chars);
            var whole = new ByteOutput(1);
            whole.writeByte(7);
            whole.writeString(text);
            whole.writeByte(9);

            var pieced = new ByteOutput(1);
            pieced.writeByte(7);
            var start = pieced.startString();
            var at = 0;
            while (at < length) {
                var end = Math.min(length, at + 1 + random.nextInt(300));
                // Pieces end between code points, as the tokenizer gives them.
                if (end < length && Character.isHighSurrogate(chars[end - 1]) && Character.isLowSurrogate(chars[end])) {
                    end++;
                }
                pieced.writeUtf8(chars, at, end);
                at = end;
            }
            pieced.endString(start);
            pieced.writeByte(9);

            assertThat(pieced.toByteArray()).as("%d chars", length).isEqualTo(whole.toByteArray());
        }
    }

    @Test
    void countsTheBytesItEncodes() {
        for (var text :
                new String[] {"", "fox", "élève", "Привет", "𝄞", "lone \uD800 surrogate", "end \uDC00", "\uD800"}) {
            var chars = text.toCharArray();

            assertThat(Utf8.length(chars, 0, chars.length))
                    .as(text)
                    .isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
        }
    }
}
