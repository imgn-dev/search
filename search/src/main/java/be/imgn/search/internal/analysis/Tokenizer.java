package be.imgn.search.internal.analysis;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;

import be.imgn.search.TermBuffer;
import java.io.IOException;
import java.io.Reader;

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
/// A text read from a [Reader] is read [#BLOCK_SIZE] chars at a time into a reused buffer, and split into the
/// same words as the whole text at once: the current word carries over from block to block, and so do the last
/// chars of a block, which splitting may need to look past: a high surrogate, or an apostrophe followed by a code
/// point.
///
/// One instance per thread: it keeps the state of the text being split, and the buffer of a reader's blocks.
public final class Tokenizer {

    /// The length beyond which a word is skipped.
    public static final int MAX_LENGTH = 255;

    /// The number of chars read from a [Reader] at a time.
    public static final int BLOCK_SIZE = 8192;

    // How far past a char splitting may look: an apostrophe needs the code point after it, up to two chars. A
    // block that is not the text's last is split only that far from its end, and its last chars carry over.
    private static final int LOOKAHEAD = 2;
    private static final char SOFT_HYPHEN = '­';
    private static final char RIGHT_SINGLE_QUOTATION_MARK = '’';

    private final TermBuffer word;
    private final int blockSize;
    private final Block block = new Block();
    private CharSequence text = "";
    private WordConsumer consumer = w -> {};
    private int size;
    private boolean tooLong;

    /// A tokenizer that writes each word into `word`.
    ///
    /// @param word the buffer each word is written into
    public Tokenizer(TermBuffer word) {
        this(word, BLOCK_SIZE);
    }

    /// A tokenizer that reads `blockSize` chars at a time from a [Reader], so that tests can cut texts into many
    /// blocks.
    Tokenizer(TermBuffer word, int blockSize) {
        checkArgument(blockSize > LOOKAHEAD, "A block of %s chars leaves no room past the lookahead", blockSize);
        this.word = word;
        this.blockSize = blockSize;
    }

    /// Splits `text` into words.
    ///
    /// @param text the text
    /// @param consumer the receiver of each word, written into this tokenizer's buffer; the consumer may
    ///     rewrite the buffer
    public void tokenize(CharSequence text, WordConsumer consumer) {
        start(text, consumer);
        // The same loop as split's, written out: calling split makes the analysis of an ASCII string about 6%
        // slower in AnalysisBenchmark.
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
        stop();
    }

    /// Splits the text a reader gives into words, reading it to its end: the same words as
    /// [#tokenize(CharSequence, WordConsumer)] gives for the whole text.
    ///
    /// @param text the reader of the text, which the caller closes
    /// @param consumer the receiver of each word, written into this tokenizer's buffer; the consumer may
    ///     rewrite the buffer
    /// @param chars the receiver of the text's chars, in order, each once, as they are split
    /// @throws IOException if the reader throws
    public void tokenize(Reader text, WordConsumer consumer, CharsConsumer chars) throws IOException {
        if (block.chars.length == 0) {
            block.chars = new char[blockSize];
        }
        var buffer = block.chars;
        start(block, consumer);
        try {
            var carried = 0;
            int read;
            while ((read = text.read(buffer, carried, buffer.length - carried)) >= 0) {
                var length = carried + read;
                block.length = length;
                var split = split(length - LOOKAHEAD);
                chars.chars(buffer, 0, split);
                carried = length - split;
                System.arraycopy(buffer, split, buffer, 0, carried);
            }
            block.length = carried;
            chars.chars(buffer, 0, split(carried));
            emit();
        } finally {
            block.length = 0;
            stop();
        }
    }

    // The memory of the buffer of a reader's blocks; the word's buffer belongs to the caller.
    long scratchBytes() {
        return (long) block.chars.length * Character.BYTES;
    }

    private void start(CharSequence text, WordConsumer consumer) {
        this.text = text;
        this.consumer = consumer;
        this.size = 0;
        this.tooLong = false;
    }

    private void stop() {
        this.text = "";
        this.consumer = w -> {};
    }

    // Splits the code points of the text that start before `end`, and returns the index of the next one: 0 if
    // `end` is not positive, else `end`, or one past it after a surrogate pair.
    private int split(int end) {
        var i = 0;
        while (i < end) {
            var codePoint = Character.codePointAt(text, i);
            var width = Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) || (size > 0 && isMark(codePoint))) {
                append(i, width);
            } else if (!continuesWord(i)) {
                emit();
            }
            i += width;
        }
        return i;
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

    /// Receives the chars of a text read from a [Reader].
    @FunctionalInterface
    public interface CharsConsumer {

        /// Receives chars, valid only during the call.
        ///
        /// @param chars the array holding them
        /// @param from the index of the first
        /// @param to the index past the last
        void chars(char[] chars, int from, int to);
    }

    // A block of a text read from a reader: a view over the reused buffer, since the tokenizer reads a text as a
    // `CharSequence`.
    private static final class Block implements CharSequence {

        private char[] chars = new char[0];
        private int length;

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            return chars[index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new String(chars, start, end - start);
        }

        @Override
        public String toString() {
            return new String(chars, 0, length);
        }
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
