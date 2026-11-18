package be.imgn.search.internal.analysis;

import be.imgn.search.TermBuffer;

/// Splits text into words, for the Latin, Greek and Cyrillic scripts.
///
/// - A word is a run of letters and digits, with the combining marks that follow them, so a decomposed accent
///   stays on its letter. A soft hyphen (U+00AD) inside a word is dropped.
/// - An apostrophe between two letters belongs to the word, and `’` (U+2019) is read as `'`: `l’arbre` is one
///   word, `l'arbre`.
/// - Anything else separates words, hyphens included.
/// - A word longer than [#MAX_LENGTH] characters, such as an encoded blob, is skipped.
///
/// Text in other scripts is split the same way, which is crude but harmless.
///
/// One instance per thread: it keeps the state of the text being split.
public final class Tokenizer {

    /// The length beyond which a word is skipped.
    public static final int MAX_LENGTH = 255;

    private static final char SOFT_HYPHEN = '­';
    private static final char RIGHT_SINGLE_QUOTATION_MARK = '’';

    private final TermBuffer word;
    private CharSequence text = "";
    private WordConsumer consumer = w -> {};
    private int size;
    private boolean tooLong;

    /// A tokenizer that writes each word into `word`.
    ///
    /// @param word the buffer each word is written into
    public Tokenizer(TermBuffer word) {
        this.word = word;
    }

    /// Splits `text` into words.
    ///
    /// @param text the text
    /// @param consumer the receiver of each word, written into this tokenizer's buffer; the consumer may
    ///     rewrite the buffer
    public void tokenize(CharSequence text, WordConsumer consumer) {
        this.text = text;
        this.consumer = consumer;
        this.size = 0;
        this.tooLong = false;
        var length = text.length();
        var i = 0;
        while (i < length) {
            var codePoint = Character.codePointAt(text, i);
            var width = Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) || (size > 0 && isMark(codePoint))) {
                append(i, width);
            } else if (!continuesWord(i)) {
                emit();
            }
            i += width;
        }
        emit();
        this.text = "";
        this.consumer = w -> {};
    }

    /// Whether the character at `index`, neither a letter, a digit nor a mark, belongs to the current word: a
    /// soft hyphen, dropped, or an apostrophe between two letters, appended as `'`.
    private boolean continuesWord(int index) {
        if (size == 0) {
            return false;
        }
        var c = text.charAt(index);
        if (c == SOFT_HYPHEN) {
            return true;
        }
        if ((c == '\'' || c == RIGHT_SINGLE_QUOTATION_MARK)
                && !tooLong
                && Character.isLetter(Character.codePointBefore(word.chars(), size))
                && index + 1 < text.length()
                && Character.isLetter(Character.codePointAt(text, index + 1))) {
            appendChar('\'');
            return true;
        }
        return false;
    }

    private void append(int index, int width) {
        appendChar(text.charAt(index));
        if (width == 2) {
            appendChar(text.charAt(index + 1));
        }
    }

    private void appendChar(char c) {
        if (size == MAX_LENGTH) {
            tooLong = true;
        }
        if (!tooLong) {
            word.ensureCapacity(size + 1)[size++] = c;
        }
    }

    private void emit() {
        if (size > 0 && !tooLong) {
            word.setLength(size);
            consumer.word(word);
        }
        size = 0;
        tooLong = false;
    }

    static boolean isMark(int codePoint) {
        var type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /// Receives the words of a text.
    @FunctionalInterface
    public interface WordConsumer {

        /// Receives a word, valid only during the call.
        ///
        /// @param word the word, not empty
        void word(TermBuffer word);
    }
}
