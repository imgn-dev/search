package be.imgn.search.internal.segment;

import static be.imgn.search.internal.codec.BitPacking.BLOCK;

import be.imgn.search.internal.codec.BitPacking;
import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Norms;
import be.imgn.search.internal.codec.Output;
import org.jspecify.annotations.Nullable;

/// Writes the postings and positions of terms.
///
/// A term's postings are its documents in blocks of [BitPacking#BLOCK]. A full block is the bit width of its
/// document gaps (a document number minus the previous one minus 1, the first from -1) as a byte, those gaps
/// bit-packed, then the same for its frequencies minus 1. The last, partial block is written as pairs of
/// variable-length gap and frequency minus 1. A term with more than one block starts with its skip table, one
/// [entry][#SKIP_ENTRY_SIZE] per block, the partial one included.
///
/// A term's positions are a stream of gaps, each document's first from 0, in blocks of [BitPacking#BLOCK]
/// written as a width byte then the bit-packed gaps, the last partial block as variable-length integers. The
/// blocks of positions do not follow the blocks of documents: a skip entry gives where its block's first
/// position is.
public final class PostingsWriter {

    /// The size of a skip entry: last document number (`int`), offset of the block from the end of the skip
    /// table (`long`), offset of the block of positions that holds the block's first position from the term's
    /// first positions (`long`), number of the term's positions before the block (`long`), highest frequency
    /// (`int`) and lowest norm (byte) of the block.
    public static final int SKIP_ENTRY_SIZE = 33;

    static final int SKIP_LAST_DOC = 0;
    static final int SKIP_DOC_OFFSET = 4;
    static final int SKIP_POSITIONS_OFFSET = 12;
    static final int SKIP_POSITIONS_UPTO = 20;
    static final int SKIP_MAX_FREQ = 28;
    static final int SKIP_MIN_NORM = 32;

    private static final int BYTE_MASK = 0xFF;

    private final int[] block = new int[BLOCK];
    private final int[] positionBlock = new int[BLOCK];
    private final ByteOutput blocks = new ByteOutput();
    private final ByteOutput skip = new ByteOutput();
    private int pendingPositions;

    /// Writes a term's postings, and its positions when `positions` is not `null`.
    ///
    /// @param docs the documents, increasing
    /// @param freqs the frequency in each document, at least 1
    /// @param docFreq the number of documents
    /// @param positions the positions in each document, increasing, one after the other, or `null`
    /// @param norms the norm of each document, used for the score bounds, or `null` in a field without norms
    /// @param postings the receiver of the postings
    /// @param positionsOut the receiver of the positions; unused when `positions` is `null`
    /// @return the term's entry in the dictionary
    public TermInfo write(
            int[] docs,
            int[] freqs,
            int docFreq,
            int @Nullable [] positions,
            byte @Nullable [] norms,
            Output postings,
            Output positionsOut) {
        var postingsOffset = postings.position();
        var positionsOffset = positions == null ? 0 : positionsOut.position();
        var blockCount = (docFreq + BLOCK - 1) / BLOCK;
        blocks.reset();
        skip.reset();
        pendingPositions = 0;
        var previous = -1;
        long positionsUpto = 0;
        var maxFreq = 0;
        var minNorm = norms == null ? 0 : Norms.MAX;
        for (var b = 0; b < blockCount; b++) {
            var from = b * BLOCK;
            var to = Math.min(docFreq, from + BLOCK);
            var blockOffset = blocks.size();
            var blockPositionsOffset = positions == null ? 0 : positionsOut.position() - positionsOffset;
            var blockMaxFreq = 0;
            var blockMinNorm = norms == null ? 0 : Norms.MAX;
            for (var i = from; i < to; i++) {
                blockMaxFreq = Math.max(blockMaxFreq, freqs[i]);
                if (norms != null) {
                    blockMinNorm = Math.min(blockMinNorm, norms[docs[i]] & BYTE_MASK);
                }
            }
            if (to - from == BLOCK) {
                writeFullBlock(docs, freqs, from, previous);
            } else {
                writeTail(docs, freqs, from, to, previous);
            }
            skip.writeInt(docs[to - 1]);
            skip.writeLong(blockOffset);
            skip.writeLong(blockPositionsOffset);
            skip.writeLong(positionsUpto);
            skip.writeInt(blockMaxFreq);
            skip.writeByte(blockMinNorm);
            positionsUpto = writePositions(freqs, from, to, positions, positionsUpto, positionsOut);
            previous = docs[to - 1];
            maxFreq = Math.max(maxFreq, blockMaxFreq);
            minNorm = Math.min(minNorm, blockMinNorm);
        }
        if (positions != null) {
            for (var i = 0; i < pendingPositions; i++) {
                positionsOut.writeVInt(positionBlock[i]);
            }
        }
        if (blockCount > 1) {
            postings.writeBytes(skip);
        }
        postings.writeBytes(blocks);
        return new TermInfo(docFreq, positionsUpto, maxFreq, minNorm, postingsOffset, positionsOffset);
    }

    private void writeFullBlock(int[] docs, int[] freqs, int from, int previous) {
        var last = previous;
        for (var i = 0; i < BLOCK; i++) {
            block[i] = docs[from + i] - last - 1;
            last = docs[from + i];
        }
        writePacked(block, blocks);
        for (var i = 0; i < BLOCK; i++) {
            block[i] = freqs[from + i] - 1;
        }
        writePacked(block, blocks);
    }

    private void writeTail(int[] docs, int[] freqs, int from, int to, int previous) {
        var last = previous;
        for (var i = from; i < to; i++) {
            blocks.writeVInt(docs[i] - last - 1);
            blocks.writeVInt(freqs[i] - 1);
            last = docs[i];
        }
    }

    // Returns the number of positions written so far, which is also the index of the next one.
    private long writePositions(int[] freqs, int from, int to, int @Nullable [] positions, long upto, Output out) {
        var next = upto;
        for (var i = from; i < to; i++) {
            if (positions != null) {
                var last = 0;
                for (var j = 0; j < freqs[i]; j++) {
                    var position = positions[(int) next + j];
                    positionBlock[pendingPositions++] = position - last;
                    last = position;
                    if (pendingPositions == BLOCK) {
                        writePacked(positionBlock, out);
                        pendingPositions = 0;
                    }
                }
            }
            next += freqs[i];
        }
        return next;
    }

    private static void writePacked(int[] values, Output out) {
        var width = BitPacking.width(values, BLOCK);
        out.writeByte(width);
        BitPacking.pack(values, width, out);
    }
}
