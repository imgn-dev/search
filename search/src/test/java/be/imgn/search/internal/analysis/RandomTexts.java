package be.imgn.search.internal.analysis;

import java.io.Reader;
import java.util.Random;

/// Random texts that exercise the tokenizer's edge cases, and a reader that gives a text a few chars at a time.
final class RandomTexts {

    // Pieces a text is made of: letters of several scripts, digits, separators, apostrophes, a soft hyphen, a
    // combining mark, a letter and a symbol outside the BMP, lone surrogates and a full-width letter.
    private static final String[] PIECES = {
        "a", "B", "z", "é", "ß", "Σ", "ς", "λ", "Ж", "я", "0", "7", " ", " ", "\n", ",", "-", ".", "'", "’", "­", "́",
        "𝒜", "😀", "\uD800", "\uDC00", "Ａ", "中", "ǅ", "İ"
    };

    private RandomTexts() {}

    /// A random text of about `length` chars, with some words of about [Tokenizer#MAX_LENGTH] chars.
    static String text(Random random, int length) {
        var text = new StringBuilder(length + Tokenizer.MAX_LENGTH + 2);
        while (text.length() < length) {
            if (random.nextInt(50) == 0) {
                var word = Tokenizer.MAX_LENGTH - 2 + random.nextInt(5);
                for (var i = 0; i < word; i++) {
                    text.append(random.nextBoolean() ? 'x' : 'é');
                }
            } else if (random.nextInt(4) == 0) {
                text.append(PIECES[random.nextInt(PIECES.length)]);
            } else {
                text.append((char) ('a' + random.nextInt(26)));
            }
        }
        return text.toString();
    }

    /// A reader of `text` that gives at most `most` chars per read.
    static Reader choppy(String text, int most) {
        return new Reader() {
            private int at;

            @Override
            public int read(char[] buffer, int offset, int length) {
                if (at == text.length()) {
                    return -1;
                }
                var count = Math.min(Math.min(length, most), text.length() - at);
                text.getChars(at, at + count, buffer, offset);
                at += count;
                return count;
            }

            @Override
            public void close() {}
        };
    }
}
