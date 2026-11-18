package be.imgn.search;

import static java.util.Objects.checkFromIndexSize;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;

/// A word being analysed: a growable array of characters and a length.
///
/// Analysis reuses one buffer per word rather than allocating a string, so a [WordAnalyzer] reads and rewrites
/// the word in place. Not thread-safe.
///
/// @since 0.1.0
public final class TermBuffer {

    private static final int INITIAL_CAPACITY = 32;

    private char[] chars;
    private int length;

    /// An empty buffer.
    public TermBuffer() {
        this.chars = new char[INITIAL_CAPACITY];
    }

    /// The characters; only the first [#length()] are the word's.
    ///
    /// @return the backing array, which a later [#ensureCapacity] may replace
    public char[] chars() {
        return chars;
    }

    /// The length of the word.
    ///
    /// @return the number of characters
    public int length() {
        return length;
    }

    /// Sets the length of the word; setting 0 empties it.
    ///
    /// @param length the new length, at most the capacity of [#chars()]
    /// @throws IndexOutOfBoundsException if `length` is negative or larger than the capacity
    public void setLength(int length) {
        checkFromIndexSize(0, length, chars.length);
        this.length = length;
    }

    /// Makes room for at least `capacity` characters, keeping the word.
    ///
    /// @param capacity the capacity needed
    /// @return the backing array, possibly a new one
    /// @throws IllegalArgumentException if `capacity` is negative
    public char[] ensureCapacity(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity " + capacity + " is negative");
        }
        if (capacity > chars.length) {
            chars = Arrays.copyOf(chars, Math.max(capacity, chars.length * 2));
        }
        return chars;
    }

    /// Replaces the word with characters of an array.
    ///
    /// @param source the array
    /// @param offset the index of the first character
    /// @param count the number of characters
    /// @throws NullPointerException if `source` is `null`
    /// @throws IndexOutOfBoundsException if the range is not within `source`
    public void set(char[] source, int offset, int count) {
        requireNonNull(source, "source");
        checkFromIndexSize(offset, count, source.length);
        ensureCapacity(count);
        System.arraycopy(source, offset, chars, 0, count);
        length = count;
    }

    /// Replaces the word with a character sequence.
    ///
    /// @param text the characters
    /// @throws NullPointerException if `text` is `null`
    public void set(CharSequence text) {
        requireNonNull(text, "text");
        var count = text.length();
        ensureCapacity(count);
        for (var i = 0; i < count; i++) {
            chars[i] = text.charAt(i);
        }
        length = count;
    }

    /// The word.
    ///
    /// @return the word as a string
    @Override
    public String toString() {
        return new String(chars, 0, length);
    }
}
