package be.imgn.search.internal.codec;

/// Encodes characters into UTF-8 without allocating, as `String.getBytes(UTF_8)` does: an unpaired surrogate
/// becomes `?`.
public final class Utf8 {

    /// The most bytes one `char` takes.
    public static final int MAX_BYTES_PER_CHAR = 3;

    // The bytes of a surrogate pair, a code point outside the BMP.
    private static final int PAIR_BYTES = 4;
    private static final int ONE_BYTE = 0x80;
    private static final int TWO_BYTES = 0x800;
    private static final int LEAD_2 = 0xC0;
    private static final int LEAD_3 = 0xE0;
    private static final int LEAD_4 = 0xF0;
    private static final int CONTINUATION = 0x80;
    private static final int SIX_BITS = 0x3F;
    private static final int SHIFT_2 = 6;
    private static final int SHIFT_3 = 12;
    private static final int SHIFT_4 = 18;

    private Utf8() {}

    /// Encodes characters.
    ///
    /// @param chars the characters, from index 0
    /// @param length the number of characters
    /// @param bytes the receiver, with room for `length * 3` bytes
    /// @return the number of bytes written
    public static int encode(char[] chars, int length, byte[] bytes) {
        return encode(chars, 0, length, bytes, 0);
    }

    /// Encodes a range of characters; a high surrogate at its end is unpaired.
    ///
    /// @param chars the characters
    /// @param from the index of the first
    /// @param to the index past the last
    /// @param bytes the receiver, with room for `(to - from) * 3` bytes from `offset`
    /// @param offset the index of the first byte to write
    /// @return the number of bytes written
    public static int encode(char[] chars, int from, int to, byte[] bytes, int offset) {
        var size = offset;
        var i = from;
        while (i < to) {
            int c = chars[i++];
            if (c < ONE_BYTE) {
                bytes[size++] = (byte) c;
            } else if (c < TWO_BYTES) {
                bytes[size++] = (byte) (LEAD_2 | (c >> SHIFT_2));
                bytes[size++] = (byte) (CONTINUATION | (c & SIX_BITS));
            } else if (Character.isSurrogate((char) c)) {
                if (Character.isHighSurrogate((char) c) && i < to && Character.isLowSurrogate(chars[i])) {
                    var codePoint = Character.toCodePoint((char) c, chars[i++]);
                    bytes[size++] = (byte) (LEAD_4 | (codePoint >> SHIFT_4));
                    bytes[size++] = (byte) (CONTINUATION | ((codePoint >> SHIFT_3) & SIX_BITS));
                    bytes[size++] = (byte) (CONTINUATION | ((codePoint >> SHIFT_2) & SIX_BITS));
                    bytes[size++] = (byte) (CONTINUATION | (codePoint & SIX_BITS));
                } else {
                    bytes[size++] = '?';
                }
            } else {
                bytes[size++] = (byte) (LEAD_3 | (c >> SHIFT_3));
                bytes[size++] = (byte) (CONTINUATION | ((c >> SHIFT_2) & SIX_BITS));
                bytes[size++] = (byte) (CONTINUATION | (c & SIX_BITS));
            }
        }
        return size - offset;
    }

    /// The number of bytes [#encode(char[], int, int, byte[], int)] writes for a range of characters.
    ///
    /// @param chars the characters
    /// @param from the index of the first
    /// @param to the index past the last
    /// @return the number of bytes
    public static int length(char[] chars, int from, int to) {
        var length = 0;
        var i = from;
        while (i < to) {
            int c = chars[i++];
            if (c < ONE_BYTE) {
                length++;
            } else if (c < TWO_BYTES) {
                length += 2;
            } else if (Character.isHighSurrogate((char) c) && i < to && Character.isLowSurrogate(chars[i])) {
                i++;
                length += PAIR_BYTES;
            } else if (Character.isSurrogate((char) c)) {
                length++;
            } else {
                length += MAX_BYTES_PER_CHAR;
            }
        }
        return length;
    }
}
