package be.imgn.search.internal.analysis;

import be.imgn.search.TermBuffer;
import java.text.Normalizer;

/// Normalizes words in place: NFKC, lowercase, case folding and accent removal.
public final class Normalization {

    private static final char FIRST_NON_ASCII = 0x80;

    private Normalization() {}

    /// Normalizes `word` with NFKC, which also folds full-width and half-width forms, and lowercases it
    /// character by character ([Character#toLowerCase(int)]), independently of the default locale. ASCII words
    /// skip NFKC.
    ///
    /// @param word the word, rewritten
    public static void normalizeAndLowercase(TermBuffer word) {
        var chars = word.chars();
        var length = word.length();
        if (isAscii(chars, length)) {
            for (var i = 0; i < length; i++) {
                var c = chars[i];
                if (c >= 'A' && c <= 'Z') {
                    chars[i] = (char) (c + ('a' - 'A'));
                }
            }
            return;
        }
        var text = new String(chars, 0, length);
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFKC)) {
            text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        }
        var size = 0;
        // A lowercase letter may take two chars where its capital took one.
        chars = word.ensureCapacity(text.length() * 2);
        for (var i = 0; i < text.length(); ) {
            var codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            size += Character.toChars(Character.toLowerCase(codePoint), chars, size);
        }
        word.setLength(size);
    }

    /// Folds the letters lowercasing leaves apart from their other forms: `ß` becomes `ss`, so that `STRASSE`
    /// matches `straße`, and the final sigma `ς` becomes `σ`.
    ///
    /// @param word a lowercased word, rewritten
    public static void fold(TermBuffer word) {
        var chars = word.chars();
        var length = word.length();
        var extra = 0;
        for (var i = 0; i < length; i++) {
            if (chars[i] == 'ß') {
                extra++;
            } else if (chars[i] == 'ς') {
                chars[i] = 'σ';
            }
        }
        if (extra == 0) {
            return;
        }
        chars = word.ensureCapacity(length + extra);
        var to = length + extra;
        for (var from = length - 1; from >= 0; from--) {
            if (chars[from] == 'ß') {
                chars[--to] = 's';
                chars[--to] = 's';
            } else {
                chars[--to] = chars[from];
            }
        }
        word.setLength(length + extra);
    }

    /// Removes the accents of `word`: decomposes it (NFD), drops the combining marks, and replaces the letters
    /// that do not decompose (`ø`, `ł`, `đ`, `æ`, `œ`, `ð`, `þ`). ASCII words are left as they are.
    ///
    /// @param word a lowercased word, rewritten
    public static void removeAccents(TermBuffer word) {
        var chars = word.chars();
        var length = word.length();
        if (isAscii(chars, length)) {
            return;
        }
        var text = Normalizer.normalize(new String(chars, 0, length), Normalizer.Form.NFD);
        chars = word.ensureCapacity(text.length() * 2);
        var size = 0;
        for (var i = 0; i < text.length(); ) {
            var codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Tokenizer.isMark(codePoint)) {
                continue;
            }
            switch (codePoint) {
                case 'ø' -> chars[size++] = 'o';
                case 'ł' -> chars[size++] = 'l';
                case 'đ', 'ð' -> chars[size++] = 'd';
                case 'æ' -> {
                    chars[size++] = 'a';
                    chars[size++] = 'e';
                }
                case 'œ' -> {
                    chars[size++] = 'o';
                    chars[size++] = 'e';
                }
                case 'þ' -> {
                    chars[size++] = 't';
                    chars[size++] = 'h';
                }
                default -> size += Character.toChars(codePoint, chars, size);
            }
        }
        word.setLength(size);
    }

    private static boolean isAscii(char[] chars, int length) {
        for (var i = 0; i < length; i++) {
            if (chars[i] >= FIRST_NON_ASCII) {
                return false;
            }
        }
        return true;
    }
}
