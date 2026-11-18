package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.ByteOutput;
import java.util.Arrays;

/// Writes a term dictionary: sorted terms, each with a fixed number of `long` values, in blocks of [#BLOCK_SIZE]
/// terms.
///
/// A block is its number of terms then, per term, the length of the prefix it shares with the previous term of
/// the block, the length and bytes of the rest, and its values as variable-length integers. A value marked as a
/// delta is written as the difference from the previous term's in the block, the first term's in full. The block
/// index follows the blocks: their number then, per block, its first term and its offset from the start of the
/// dictionary.
///
/// Terms must be added in increasing order of their unsigned bytes.
public final class TermDictionaryWriter {

    /// The number of terms per block.
    public static final int BLOCK_SIZE = 32;

    private static final int INITIAL_TERM_CAPACITY = 64;

    private final ByteOutput out;
    private final long deltaMask;
    private final long start;
    private final ByteOutput block = new ByteOutput();
    private final ByteOutput index = new ByteOutput();
    private final long[] previousValues;
    private byte[] previousTerm = new byte[INITIAL_TERM_CAPACITY];
    private int previousLength;
    private int blockTerms;
    private int blockCount;
    private long termCount;

    /// A writer that appends a dictionary to `out`.
    ///
    /// @param out the receiver
    /// @param columns the number of values per term
    /// @param deltaMask the values written as deltas, one bit per column
    public TermDictionaryWriter(ByteOutput out, int columns, long deltaMask) {
        this.out = out;
        this.deltaMask = deltaMask;
        this.start = out.size();
        this.previousValues = new long[columns];
    }

    /// The offset of the dictionary in its output.
    ///
    /// @return the offset of the first block
    public long start() {
        return start;
    }

    /// The number of terms added.
    ///
    /// @return the number of terms
    public long termCount() {
        return termCount;
    }

    /// Adds a term.
    ///
    /// @param term the term's bytes, from index 0
    /// @param length the number of bytes
    /// @param values the term's values, one per column
    public void add(byte[] term, int length, long[] values) {
        if (blockTerms == BLOCK_SIZE) {
            flushBlock();
        }
        var prefix = 0;
        if (blockTerms == 0) {
            index.writeVInt(length);
            index.writeBytes(term, 0, length);
            index.writeVLong(out.size() - start);
        } else {
            var mismatch = Arrays.mismatch(previousTerm, 0, previousLength, term, 0, length);
            prefix = mismatch < 0 ? length : mismatch;
        }
        block.writeVInt(prefix);
        block.writeVInt(length - prefix);
        block.writeBytes(term, prefix, length - prefix);
        for (var c = 0; c < previousValues.length; c++) {
            var delta = blockTerms > 0 && (deltaMask & (1L << c)) != 0;
            block.writeVLong(delta ? values[c] - previousValues[c] : values[c]);
            previousValues[c] = values[c];
        }
        if (previousTerm.length < length) {
            previousTerm = new byte[Math.max(length, previousTerm.length * 2)];
        }
        System.arraycopy(term, 0, previousTerm, 0, length);
        previousLength = length;
        blockTerms++;
        termCount++;
    }

    /// Writes the last block and the block index.
    ///
    /// @return the offset of the block index in the output
    public long finish() {
        if (blockTerms > 0) {
            flushBlock();
        }
        var indexOffset = out.size();
        out.writeVInt(blockCount);
        out.writeBytes(index);
        return indexOffset;
    }

    private void flushBlock() {
        out.writeVInt(blockTerms);
        out.writeBytes(block);
        block.reset();
        blockTerms = 0;
        blockCount++;
    }
}
