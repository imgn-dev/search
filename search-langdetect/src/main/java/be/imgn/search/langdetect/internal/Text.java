package be.imgn.search.langdetect.internal;

import java.nio.CharBuffer;
import java.text.Normalizer;
import org.jspecify.annotations.Nullable;

/// A text as the model reads it, the same for training and detection: a sample of a long text, normalized in
/// place to the letters of its dominant script, lowercased, each word followed by a single space.
///
/// Instances are mutable and confined to the thread that made them.
public final class Text {

    /// The number of chars sampled from a long text, not counting the ends of the words cut at the chunk ends.
    public static final int SAMPLE_LENGTH = 2048;

    // A long text is sampled in this many chunks spread evenly across it, so that a text starting with a quote or
    // a list of names is still detected from its body.
    private static final int CHUNKS = 8;
    private static final int CHUNK_LENGTH = SAMPLE_LENGTH / CHUNKS;
    // A chunk is moved past the word it starts in, and extended to the end of the word it ends in, by this many
    // chars at most.
    private static final int MAX_SHIFT = 32;

    private static final char FIRST_COMBINING_MARK = 0x0300;
    private static final char LAST_COMBINING_MARK = 0x036F;
    private static final char RIGHT_SINGLE_QUOTATION_MARK = 0x2019;
    private static final char MODIFIER_LETTER_APOSTROPHE = 0x02BC;

    private char[] chars;
    private int length;

    // The chars hold length chars, and room for the space that ends the last word once normalized.
    private Text(char[] chars, int length) {
        this.chars = chars;
        this.length = length;
    }

    /// A sample of `text`: the whole text if it has at most [#SAMPLE_LENGTH] chars, otherwise chunks spread
    /// evenly across it, cut at word boundaries and separated by spaces.
    ///
    /// The text is copied with [CharSequence#getChars(int, int, char[], int)], which a text made of several values
    /// may implement faster than char by char, and probed with [CharSequence#charAt(int)] only at the word
    /// boundaries of the chunks.
    public static Text sample(CharSequence text) {
        var length = text.length();
        if (length <= SAMPLE_LENGTH) {
            var chars = new char[length + 1];
            text.getChars(0, length, chars, 0);
            return new Text(chars, length);
        }
        var chars = new char[CHUNKS * (CHUNK_LENGTH + MAX_SHIFT + 1) + 1];
        var count = 0;
        for (var chunk = 0; chunk < CHUNKS; chunk++) {
            var start = (int) ((long) chunk * (length - CHUNK_LENGTH) / (CHUNKS - 1));
            var end = start + CHUNK_LENGTH;
            if (start > 0 && Character.isLetter(text.charAt(start - 1))) {
                var limit = Math.min(end, start + MAX_SHIFT);
                while (start < limit && Character.isLetter(text.charAt(start))) {
                    start++;
                }
            }
            var limit = Math.min(length, end + MAX_SHIFT);
            while (end < limit && Character.isLetter(text.charAt(end))) {
                end++;
            }
            text.getChars(start, end, chars, count);
            count += end - start;
            chars[count++] = ' ';
        }
        return new Text(chars, count);
    }

    /// A sentence of the training corpus, whole.
    public static Text of(String text) {
        var chars = new char[text.length() + 1];
        text.getChars(0, text.length(), chars, 0);
        return new Text(chars, text.length());
    }

    /// The chars of this text, in an array that may be longer: the caller must not change them.
    public char[] chars() {
        return chars;
    }

    /// The number of chars of this text.
    public int length() {
        return length;
    }

    /// Normalizes this text in place: composes the accents written as combining marks, finds the dominant script,
    /// the one with the most letters, and keeps its letters, lowercased, and the apostrophes between two of them,
    /// as `'`. Every run of other chars becomes a single space, and a space ends the last word.
    ///
    /// @return the dominant script, or `null` if the text has no letter, or more letters in other scripts than in
    ///     any [Script]: the text is then left composed but not normalized
    public @Nullable Script normalize() {
        for (var i = 0; i < length; i++) {
            if (isCombiningMark(chars[i])) {
                // Rare: most text is precomposed, so the allocation is too.
                var composed = Normalizer.normalize(CharBuffer.wrap(chars, 0, length), Normalizer.Form.NFC);
                chars = new char[composed.length() + 1];
                composed.getChars(0, composed.length(), chars, 0);
                length = composed.length();
                break;
            }
        }
        var script = dominantScript();
        if (script == null) {
            return null;
        }
        // In place: a char is written at an index no higher than the one it was read at, and the apostrophe test
        // reads ahead only.
        var index = script.index();
        var count = 0;
        for (var i = 0; i < length; i++) {
            var c = chars[i];
            if (Script.indexOf(c) == index) {
                chars[count++] = Character.toLowerCase(c);
            } else if (count > 0 && chars[count - 1] != ' ' && !isCombiningMark(c)) {
                // A mark NFC cannot compose, such as a stress mark on a Cyrillic vowel, stays out of the word without
                // splitting it.
                chars[count++] = isApostropheInWord(i, index) ? '\'' : ' ';
            }
        }
        if (chars[count - 1] != ' ') {
            chars[count++] = ' ';
        }
        length = count;
        return script;
    }

    @Override
    public String toString() {
        return new String(chars, 0, length);
    }

    private @Nullable Script dominantScript() {
        // Locals rather than an array: this runs once per text, and allocates nothing.
        var latin = 0;
        var greek = 0;
        var cyrillic = 0;
        var other = 0;
        for (var i = 0; i < length; i++) {
            var index = Script.indexOf(chars[i]);
            if (index == Script.LATIN.index()) {
                latin++;
            } else if (index == Script.GREEK.index()) {
                greek++;
            } else if (index == Script.CYRILLIC.index()) {
                cyrillic++;
            } else if (index == Script.OTHER) {
                other++;
            }
        }
        var max = Math.max(latin, Math.max(greek, cyrillic));
        if (max == 0 || other > max) {
            return null;
        }
        return max == latin ? Script.LATIN : max == greek ? Script.GREEK : Script.CYRILLIC;
    }

    private static boolean isCombiningMark(char c) {
        return c >= FIRST_COMBINING_MARK && c <= LAST_COMBINING_MARK;
    }

    // Whether chars[i] is an apostrophe followed by a letter of the script: the caller checks the letter before.
    private boolean isApostropheInWord(int i, int script) {
        var c = chars[i];
        return (c == '\'' || c == RIGHT_SINGLE_QUOTATION_MARK || c == MODIFIER_LETTER_APOSTROPHE)
                && i + 1 < length
                && Script.indexOf(chars[i + 1]) == script;
    }
}
