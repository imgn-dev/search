package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.DataInput;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// Reads a dictionary written by [TermDictionaryWriter]. Its block index is held on the heap, so a lookup is a
/// binary search there and the scan of one block in the segment. Thread-safe.
public final class TermDictionary {

    private static final int INITIAL_TERM_CAPACITY = 64;

    private final MemorySegment data;
    private final int columns;
    private final long deltaMask;
    private final long termCount;
    private final byte[] firstTerms;
    // Block i's first term is firstTerms[firstTermStarts[i] .. firstTermStarts[i + 1]].
    private final int[] firstTermStarts;
    private final long[] blockOffsets;

    /// Opens a dictionary and loads its block index.
    ///
    /// @param data the segment
    /// @param start the offset of the dictionary's first block
    /// @param indexOffset the offset of its block index
    /// @param termCount the number of terms
    /// @param columns the number of values per term
    /// @param deltaMask the values written as deltas, one bit per column
    public TermDictionary(
            MemorySegment data, long start, long indexOffset, long termCount, int columns, long deltaMask) {
        this.data = data;
        this.columns = columns;
        this.deltaMask = deltaMask;
        this.termCount = termCount;
        var in = new DataInput(data, indexOffset);
        var blocks = in.readVInt();
        this.firstTermStarts = new int[blocks + 1];
        this.blockOffsets = new long[blocks];
        var terms = new byte[INITIAL_TERM_CAPACITY];
        var size = 0;
        for (var i = 0; i < blocks; i++) {
            var length = in.readVInt();
            if (size + length > terms.length) {
                terms = Arrays.copyOf(terms, Math.max(size + length, terms.length * 2));
            }
            in.readBytes(terms, size, length);
            size += length;
            firstTermStarts[i + 1] = size;
            blockOffsets[i] = start + in.readVLong();
        }
        this.firstTerms = Arrays.copyOf(terms, size);
    }

    /// The number of terms.
    ///
    /// @return the number of terms
    public long size() {
        return termCount;
    }

    /// Looks a term up.
    ///
    /// @param term the term's bytes, from index 0
    /// @param length the number of bytes
    /// @param values the receiver of the term's values, one per column; changed even when the term is absent
    /// @return whether the dictionary has the term
    public boolean lookup(byte[] term, int length, long[] values) {
        var block = findBlock(term, length);
        if (block < 0) {
            return false;
        }
        var in = new DataInput(data, blockOffsets[block]);
        var count = in.readVInt();
        var current = new byte[Math.max(length, INITIAL_TERM_CAPACITY)];
        for (var i = 0; i < count; i++) {
            var prefix = in.readVInt();
            var suffix = in.readVInt();
            var currentLength = prefix + suffix;
            if (currentLength > current.length) {
                current = Arrays.copyOf(current, Math.max(currentLength, current.length * 2));
            }
            in.readBytes(current, prefix, suffix);
            for (var c = 0; c < columns; c++) {
                var value = in.readVLong();
                values[c] = i > 0 && (deltaMask & (1L << c)) != 0 ? values[c] + value : value;
            }
            var comparison = Arrays.compareUnsigned(current, 0, currentLength, term, 0, length);
            if (comparison >= 0) {
                return comparison == 0;
            }
        }
        return false;
    }

    /// A new cursor before the first term.
    ///
    /// @return the cursor
    public Cursor cursor() {
        return new Cursor();
    }

    // The last block whose first term is at most the term, or -1 if the term is before them all.
    private int findBlock(byte[] term, int length) {
        return findBlock(term, length, 0);
    }

    // The same from block `from` on, or from - 1 if the term is before that block.
    private int findBlock(byte[] term, int length, int from) {
        var low = from;
        var high = blockOffsets.length - 1;
        var found = from - 1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            var comparison = Arrays.compareUnsigned(
                    firstTerms, firstTermStarts[middle], firstTermStarts[middle + 1], term, 0, length);
            if (comparison <= 0) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found;
    }

    /// Reads the terms in order, and seeks forward to a term: one pass over a dictionary, such as the pass over a
    /// segment's keys that resolves sorted deletes, or over a field's terms in a merge. Not thread-safe.
    public final class Cursor {

        private final DataInput in = new DataInput(data, 0);
        private final long[] values = new long[columns];
        private byte[] term = new byte[INITIAL_TERM_CAPACITY];
        private int length;
        private int block = -1;
        private int index;
        private int remaining;

        private Cursor() {}

        /// Moves to the next term.
        ///
        /// @return whether there is one
        public boolean next() {
            if (remaining == 0) {
                if (block + 1 >= blockOffsets.length) {
                    return false;
                }
                load(block + 1);
            }
            read();
            return true;
        }

        /// Moves to the first term at or after `target`, never backwards: the targets of successive seeks must
        /// increase. A seek skips the blocks before the target's through the block index.
        ///
        /// @param target the term's bytes, from index 0
        /// @param targetLength the number of bytes
        /// @return whether the dictionary has the term, which is then the current one
        public boolean seek(byte[] target, int targetLength) {
            var found = findBlock(target, targetLength, Math.max(block, 0));
            if (found < 0) {
                return false;
            }
            if (found != block) {
                load(found);
                read();
            }
            while (true) {
                var comparison = Arrays.compareUnsigned(term, 0, length, target, 0, targetLength);
                if (comparison >= 0) {
                    return comparison == 0;
                }
                if (remaining == 0) {
                    return false;
                }
                read();
            }
        }

        /// The bytes of the current term, from index 0; the array is reused.
        ///
        /// @return the bytes
        public byte[] term() {
            return term;
        }

        /// The number of bytes of the current term.
        ///
        /// @return the length
        public int length() {
            return length;
        }

        /// The values of the current term, one per column; the array is reused.
        ///
        /// @return the values
        public long[] values() {
            return values;
        }

        /// The current term of a field's dictionary.
        ///
        /// @return the term's entry
        public TermInfo termInfo() {
            return TermInfo.of(values);
        }

        private void load(int newBlock) {
            block = newBlock;
            in.seek(blockOffsets[newBlock]);
            remaining = in.readVInt();
            index = 0;
        }

        private void read() {
            var prefix = in.readVInt();
            var suffix = in.readVInt();
            length = prefix + suffix;
            if (length > term.length) {
                term = Arrays.copyOf(term, Math.max(length, term.length * 2));
            }
            in.readBytes(term, prefix, suffix);
            for (var c = 0; c < columns; c++) {
                var value = in.readVLong();
                values[c] = index > 0 && (deltaMask & (1L << c)) != 0 ? values[c] + value : value;
            }
            index++;
            remaining--;
        }
    }
}
