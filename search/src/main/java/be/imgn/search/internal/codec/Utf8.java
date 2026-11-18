package be.imgn.search.internal.codec;

/// Encodes characters into UTF-8 without allocating, as `String.getBytes(UTF_8)` does: an unpaired surrogate
/// becomes `?`.
public final class Utf8 {

    /// The most bytes one `char` takes.
    public static final int MAX_BYTES_PER_CHAR = 3;

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
        var size = 0;
        var i = 0;
        while (i < length) {
            int c = chars[i++];
            if (c < ONE_BYTE) {
                bytes[size++] = (byte) c;
            } else if (c < TWO_BYTES) {
                bytes[size++] = (byte) (LEAD_2 | (c >> SHIFT_2));
                bytes[size++] = (byte) (CONTINUATION | (c & SIX_BITS));
            } else if (Character.isSurrogate((char) c)) {
                if (Character.isHighSurrogate((char) c) && i < length && Character.isLowSurrogate(chars[i])) {
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
        return size;
    }
}
