package be.imgn.search.langdetect.internal;

/// The character 1- to 3-grams of a normalized text, each packed into an `int` key of symbols: `a` for a 1-gram,
/// `a << 8 | b` for a 2-gram and `a << 16 | b << 8 | c` for a 3-gram. Since no symbol is 0, the order of a key
/// follows from its magnitude.
///
/// The n-grams are those of the text after a word boundary: the letters, the 2-grams, which may start or end at a
/// word boundary, and the 3-grams that do not span two words. [ScriptModel] scores them in the same order as
/// [#keys(Text, Alphabet, int[])] gives them, without storing them.
public final class NGrams {

    /// The highest order.
    public static final int MAX_ORDER = 3;

    /// The bits of a symbol in a key.
    static final int SYMBOL_BITS = Byte.SIZE;

    private NGrams() {}

    /// The most keys a normalized text of `length` chars gives.
    public static int capacity(int length) {
        return MAX_ORDER * length;
    }

    /// Writes the keys of the n-grams of a normalized text into `keys`.
    ///
    /// @param keys an array of at least [#capacity(int)] keys
    /// @return the number of keys written
    public static int keys(Text text, Alphabet alphabet, int[] keys) {
        var chars = text.chars();
        var count = 0;
        // The symbols before the current one: none, then the boundary before the first word.
        var previous2 = 0;
        var previous1 = Alphabet.BOUNDARY;
        for (var i = 0; i < text.length(); i++) {
            var symbol = alphabet.symbol(chars[i]);
            if (symbol != Alphabet.BOUNDARY) {
                keys[count++] = symbol;
            }
            var bigram = (previous1 << SYMBOL_BITS) | symbol;
            keys[count++] = bigram;
            if (previous2 != 0 && previous1 != Alphabet.BOUNDARY) {
                keys[count++] = (previous2 << (2 * SYMBOL_BITS)) | bigram;
            }
            previous2 = previous1;
            previous1 = symbol;
        }
        return count;
    }

    /// The order of a key, from 1 to [#MAX_ORDER].
    public static int order(int key) {
        return key < (1 << SYMBOL_BITS) ? 1 : key < (1 << (2 * SYMBOL_BITS)) ? 2 : MAX_ORDER;
    }
}
