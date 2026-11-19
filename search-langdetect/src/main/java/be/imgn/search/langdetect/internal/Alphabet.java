package be.imgn.search.langdetect.internal;

import java.util.Arrays;

/// The chars a model knows, each turned into a symbol, so that a 3-gram fits in an `int`.
///
/// Symbol 0 is never used, so that no n-gram key is 0; [#BOUNDARY] stands for the space between words and
/// [#RARE] for any letter outside the alphabet.
public final class Alphabet {

    /// The symbol of the space between words.
    public static final int BOUNDARY = 1;

    /// The symbol of the letters outside the alphabet.
    public static final int RARE = 2;

    /// The most chars an alphabet holds: the symbols are bytes.
    public static final int MAX_SIZE = 256 - 3;

    private static final int SYMBOL_MASK = 0xFF;

    private final char[] chars;
    private final byte[] symbols = new byte[Character.MAX_VALUE + 1];

    /// Creates the alphabet of `chars`, which must be distinct, in ascending order, at most [#MAX_SIZE] and
    /// without the space.
    public Alphabet(char[] chars) {
        if (chars.length > MAX_SIZE) {
            throw new IllegalArgumentException("An alphabet holds at most " + MAX_SIZE + " chars");
        }
        this.chars = chars.clone();
        Arrays.fill(symbols, (byte) RARE);
        symbols[' '] = BOUNDARY;
        for (var i = 0; i < chars.length; i++) {
            if (chars[i] == ' ' || (i > 0 && chars[i] <= chars[i - 1])) {
                throw new IllegalArgumentException("The chars of an alphabet are distinct, ascending and not spaces");
            }
            symbols[chars[i]] = (byte) (RARE + 1 + i);
        }
    }

    /// The chars of this alphabet, in ascending order.
    public char[] chars() {
        return chars.clone();
    }

    /// The symbol of a normalized char.
    public int symbol(char c) {
        return symbols[c] & SYMBOL_MASK;
    }
}
