package be.imgn.search.internal.codec;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// Blocks of [#BLOCK] unsigned `int`s packed with the width of their largest value.
///
/// The values follow each other from the lowest bit of the first little-endian `long`, so a block of width `w`
/// takes exactly `16 w` bytes, a whole number of `long`s, and width 0 takes none.
public final class BitPacking {

    /// The number of values in a block.
    public static final int BLOCK = 128;

    /// The size of the `long[]` that [#unpack] needs: the longs of a block of width 32.
    public static final int SCRATCH_LONGS = BLOCK * Integer.SIZE / Long.SIZE;

    private static final long INT_MASK = 0xFFFF_FFFFL;
    private static final int LOG2_LONG_BITS = 6;

    private BitPacking() {}

    /// The number of bits of the largest of `count` values, read as unsigned.
    ///
    /// @param values the values
    /// @param count the number of values
    /// @return the width, from 0 to 32
    public static int width(int[] values, int count) {
        var or = 0;
        for (var i = 0; i < count; i++) {
            or |= values[i];
        }
        return Integer.SIZE - Integer.numberOfLeadingZeros(or);
    }

    /// The number of bytes of a block of a width.
    ///
    /// @param width the width, from 0 to 32
    /// @return the size of the block
    public static int bytes(int width) {
        return width * (BLOCK / Byte.SIZE);
    }

    /// Writes the first [#BLOCK] values of `values` with `width` bits each.
    ///
    /// @param values the values, each fitting in `width` bits
    /// @param width the width, from 0 to 32
    /// @param out the receiver of the [#bytes] bytes
    public static void pack(int[] values, int width, Output out) {
        if (width == 0) {
            return;
        }
        long word = 0;
        var bits = 0;
        for (var i = 0; i < BLOCK; i++) {
            var value = values[i] & INT_MASK;
            word |= value << bits;
            bits += width;
            if (bits >= Long.SIZE) {
                out.writeLong(word);
                bits -= Long.SIZE;
                word = bits == 0 ? 0 : value >>> (width - bits);
            }
        }
    }

    /// Reads a block of [#BLOCK] values of `width` bits each.
    ///
    /// @param in the bytes
    /// @param offset the offset of the block
    /// @param width the width, from 0 to 32
    /// @param values the receiver of the values
    /// @param scratch at least [#SCRATCH_LONGS] longs, overwritten
    public static void unpack(MemorySegment in, long offset, int width, int[] values, long[] scratch) {
        if (width == 0) {
            Arrays.fill(values, 0, BLOCK, 0);
            return;
        }
        var longs = 2 * width;
        MemorySegment.copy(in, Layouts.LONG, offset, scratch, 0, longs);
        var mask = width == Integer.SIZE ? INT_MASK : (1L << width) - 1;
        var bit = 0;
        for (var i = 0; i < BLOCK; i++) {
            var index = bit >>> LOG2_LONG_BITS;
            var shift = bit & (Long.SIZE - 1);
            var value = scratch[index] >>> shift;
            if (shift + width > Long.SIZE) {
                value |= scratch[index + 1] << (Long.SIZE - shift);
            }
            values[i] = (int) (value & mask);
            bit += width;
        }
    }
}
