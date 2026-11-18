package be.imgn.search.internal.codec;

/// The length of a field in one byte, quantized logarithmically: exact up to [#EXACT], then with 3 bits of
/// mantissa, which still reaches `Integer.MAX_VALUE` within 255. The code grows with the length, so the lowest
/// code of a set of documents is the shortest length, the one that scores highest.
public final class Norms {

    /// The lengths below this one are encoded exactly.
    public static final int EXACT = 24;

    /// The largest code.
    public static final int MAX = 255;

    private static final int MANTISSA_BITS = 3;
    private static final int MANTISSA = 1 << MANTISSA_BITS;
    private static final int MANTISSA_WIDTH = MANTISSA_BITS + 1;

    private Norms() {}

    /// The code of a length.
    ///
    /// @param length the length, not negative
    /// @return the code, from 0 to 255
    public static int encode(int length) {
        if (length < EXACT) {
            return length;
        }
        var value = length - EXACT;
        if (value < MANTISSA) {
            return EXACT + value;
        }
        var shift = Integer.SIZE - Integer.numberOfLeadingZeros(value) - MANTISSA_WIDTH;
        return EXACT + shift * MANTISSA + (value >>> shift);
    }

    /// The length a code stands for: the shortest length that has that code.
    ///
    /// @param code the code, from 0 to 255
    /// @return the length
    public static int decode(int code) {
        if (code < EXACT + MANTISSA) {
            return code;
        }
        var value = code - EXACT;
        var shift = value / MANTISSA - 1;
        var mantissa = value % MANTISSA + MANTISSA;
        return EXACT + (mantissa << shift);
    }
}
