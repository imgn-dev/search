package be.imgn.search.internal.segment;

import static be.imgn.search.internal.codec.BitPacking.BLOCK;

import be.imgn.search.internal.codec.BitPacking;
import be.imgn.search.internal.codec.Layouts;
import be.imgn.search.internal.codec.Norms;
import java.lang.foreign.MemorySegment;

/// Iterates over the postings of a term, and over its positions in each document, as [PostingsWriter] wrote them.
///
/// It decodes a block of documents at a time into reusable arrays, finds blocks through the skip table, and
/// reads positions only when asked for them. It also gives the score bounds of its blocks, highest frequency and
/// lowest norm, straight from the skip table, without decoding them or moving. One instance serves term after term
/// through [#reset], so a search reuses it across segments. Not thread-safe.
public final class PostingsIterator implements DocIterator {

    private static final int SEVEN_BITS = 0x7F;
    private static final int CONTINUATION = 0x80;
    private static final int BYTE_MASK = 0xFF;
    private static final int PAYLOAD_BITS = 7;

    private final int[] docs = new int[BLOCK];
    private final int[] freqs = new int[BLOCK];
    private final int[] positionBuffer = new int[BLOCK];
    private final long[] scratch = new long[BitPacking.SCRATCH_LONGS];

    private MemorySegment data = MemorySegment.NULL;
    private int docFreq;
    private int blockCount;
    private int fullBlocks;
    private long skipStart;
    private long blocksStart;
    private int blockIndex;
    private int bufferUpto;
    private int bufferLength;
    private long nextBlockOffset;
    private int doc;
    private long cursor;
    // The block bounds: the term's, and the block that holds the last target given to advanceShallow.
    private int termMaxFreq;
    private int termMinNorm;
    private int shallowBlock;

    private long positionsStart;
    private long totalTermFreq;
    private long fullPositionsEnd;
    // The index, among the term's positions, of the current document's first position.
    private long docPositionStart;
    private int positionsRead;
    private int lastPosition;
    private long positionBufferStart;
    private int positionBufferLength;
    private long nextPositionBlockStart;
    private long nextPositionBlockOffset;

    /// Moves to the start of a term's postings.
    ///
    /// @param data the segment
    /// @param term the term
    /// @param postingsBase the offset of the segment's postings
    /// @param positionsBase the offset of the segment's positions, or -1 if the positions will not be read
    public void reset(MemorySegment data, TermInfo term, long postingsBase, long positionsBase) {
        this.data = data;
        this.docFreq = term.docFreq();
        this.blockCount = (docFreq + BLOCK - 1) / BLOCK;
        this.fullBlocks = docFreq / BLOCK;
        this.skipStart = postingsBase + term.postingsOffset();
        this.blocksStart = skipStart + (blockCount > 1 ? (long) blockCount * PostingsWriter.SKIP_ENTRY_SIZE : 0);
        this.blockIndex = -1;
        this.bufferUpto = -1;
        this.bufferLength = 0;
        this.nextBlockOffset = blocksStart;
        this.doc = -1;
        this.termMaxFreq = term.maxFreq();
        this.termMinNorm = term.minNorm();
        this.shallowBlock = 0;
        this.totalTermFreq = term.totalTermFreq();
        this.fullPositionsEnd = totalTermFreq / BLOCK * BLOCK;
        this.positionsStart = positionsBase < 0 ? -1 : positionsBase + term.positionsOffset();
        this.docPositionStart = 0;
        this.positionsRead = 0;
        resetPositions(positionsStart, 0);
    }

    @Override
    public int docID() {
        return doc;
    }

    @Override
    public long cost() {
        return docFreq;
    }

    @Override
    public int nextDoc() {
        if (doc == NO_MORE_DOCS) {
            return doc;
        }
        if (bufferUpto >= 0) {
            docPositionStart += freqs[bufferUpto];
        }
        bufferUpto++;
        if (bufferUpto == bufferLength) {
            if (blockIndex + 1 == blockCount) {
                doc = NO_MORE_DOCS;
                return doc;
            }
            var base = blockIndex < 0 ? -1 : docs[bufferLength - 1];
            decodeBlock(blockIndex + 1, nextBlockOffset, base);
        }
        positionsRead = 0;
        doc = docs[bufferUpto];
        return doc;
    }

    /// Moves to the first document at or after `target`. A target at or before the current document leaves the
    /// iterator where it is, positions included: the next [#nextPosition()] continues where the last one stopped.
    ///
    /// @param target a document number
    /// @return the new current document, or [#NO_MORE_DOCS]
    @Override
    public int advance(int target) {
        if (target <= doc) {
            return doc;
        }
        if (bufferUpto < 0 || docs[bufferLength - 1] < target) {
            var block = blockCount > 1 ? findBlock(target) : blockIndex + 1;
            if (block >= blockCount) {
                doc = NO_MORE_DOCS;
                return doc;
            }
            jumpTo(block);
            // Without a skip table, the only block may still end before the target.
            if (docs[bufferLength - 1] < target) {
                doc = NO_MORE_DOCS;
                return doc;
            }
        }
        while (docs[bufferUpto] < target) {
            docPositionStart += freqs[bufferUpto];
            bufferUpto++;
        }
        positionsRead = 0;
        doc = docs[bufferUpto];
        return doc;
    }

    /// The frequency of the term in the current document.
    ///
    /// @return the frequency, at least 1
    public int freq() {
        return freqs[bufferUpto];
    }

    /// The next position of the term in the current document; call it at most [#freq()] times per document.
    /// Only valid when the iterator was reset with the positions.
    ///
    /// @return the position
    public int nextPosition() {
        var gap = position(docPositionStart + positionsRead);
        lastPosition = positionsRead == 0 ? gap : lastPosition + gap;
        positionsRead++;
        return lastPosition;
    }

    /// Reads every position of the term in the current document at once, as [#nextPosition()] would one at a
    /// time; then no position is left to read in that document. Only valid when the iterator was reset with the
    /// positions, and before any [#nextPosition()] in the document.
    ///
    /// @param positions the receiver, with room for [#freq()] positions
    public void readPositions(int[] positions) {
        var freq = freqs[bufferUpto];
        var index = docPositionStart;
        var position = 0;
        var read = 0;
        while (read < freq) {
            fillPositions(index);
            var from = (int) (index - positionBufferStart);
            var count = Math.min(freq - read, positionBufferLength - from);
            for (var i = 0; i < count; i++) {
                position += positionBuffer[from + i];
                positions[read + i] = position;
            }
            read += count;
            index += count;
        }
        positionsRead = freq;
        lastPosition = position;
    }

    /// The highest frequency of the term in any document.
    ///
    /// @return the frequency
    public int termMaxFreq() {
        return termMaxFreq;
    }

    /// The lowest norm of a document that has the term.
    ///
    /// @return the norm
    public int termMinNorm() {
        return termMinNorm;
    }

    /// Finds the block that holds `target` in the skip table, for [#maxFreq] and [#minNorm], without decoding
    /// anything or moving the iterator.
    ///
    /// @param target a document number
    /// @return the last document of that block, at least `target`; [#NO_MORE_DOCS] if the term has no skip table,
    ///     whose one block then spans every document, or no document at or after `target`
    public int advanceShallow(int target) {
        if (blockCount <= 1) {
            shallowBlock = 0;
            return NO_MORE_DOCS;
        }
        var low = 0;
        var high = blockCount - 1;
        var found = blockCount;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            if (lastDoc(middle) >= target) {
                found = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        shallowBlock = found;
        return found == blockCount ? NO_MORE_DOCS : lastDoc(found);
    }

    /// The highest frequency in the blocks from the one [#advanceShallow] found to the one that holds `upTo`.
    ///
    /// @param upTo the last document of the range
    /// @return the frequency, 0 if the range has no block
    public int maxFreq(int upTo) {
        if (blockCount <= 1) {
            return termMaxFreq;
        }
        var max = 0;
        for (var block = shallowBlock; block < blockCount; block++) {
            var entry = skipStart + (long) block * PostingsWriter.SKIP_ENTRY_SIZE;
            max = Math.max(max, data.get(Layouts.INT, entry + PostingsWriter.SKIP_MAX_FREQ));
            if (lastDoc(block) >= upTo) {
                break;
            }
        }
        return max;
    }

    /// The lowest norm in the blocks from the one [#advanceShallow] found to the one that holds `upTo`.
    ///
    /// @param upTo the last document of the range
    /// @return the norm, [Norms#MAX] if the range has no block
    public int minNorm(int upTo) {
        if (blockCount <= 1) {
            return termMinNorm;
        }
        var min = Norms.MAX;
        for (var block = shallowBlock; block < blockCount; block++) {
            var entry = skipStart + (long) block * PostingsWriter.SKIP_ENTRY_SIZE;
            min = Math.min(min, data.get(Layouts.BYTE, entry + PostingsWriter.SKIP_MIN_NORM) & BYTE_MASK);
            if (lastDoc(block) >= upTo) {
                break;
            }
        }
        return min;
    }
    // The first block, after the current one, whose last document is at least target, or blockCount.
    private int findBlock(int target) {
        var low = blockIndex + 1;
        var high = blockCount - 1;
        var found = blockCount;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            if (lastDoc(middle) >= target) {
                found = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return found;
    }

    private int lastDoc(int block) {
        return data.get(Layouts.INT, skipStart + (long) block * PostingsWriter.SKIP_ENTRY_SIZE);
    }

    private void jumpTo(int block) {
        if (blockCount == 1) {
            decodeBlock(0, blocksStart, -1);
            return;
        }
        var entry = skipStart + (long) block * PostingsWriter.SKIP_ENTRY_SIZE;
        var base = block == 0 ? -1 : lastDoc(block - 1);
        decodeBlock(block, blocksStart + data.get(Layouts.LONG, entry + PostingsWriter.SKIP_DOC_OFFSET), base);
        docPositionStart = data.get(Layouts.LONG, entry + PostingsWriter.SKIP_POSITIONS_UPTO);
        if (positionsStart >= 0) {
            var positionsOffset = data.get(Layouts.LONG, entry + PostingsWriter.SKIP_POSITIONS_OFFSET);
            resetPositions(positionsStart + positionsOffset, docPositionStart);
        }
    }

    private void decodeBlock(int block, long offset, int base) {
        blockIndex = block;
        bufferUpto = 0;
        if (block < fullBlocks) {
            var position = unpack(offset, docs);
            var last = base;
            for (var i = 0; i < BLOCK; i++) {
                last += docs[i] + 1;
                docs[i] = last;
            }
            nextBlockOffset = unpack(position, freqs);
            for (var i = 0; i < BLOCK; i++) {
                freqs[i]++;
            }
            bufferLength = BLOCK;
        } else {
            cursor = offset;
            bufferLength = docFreq - fullBlocks * BLOCK;
            var last = base;
            for (var i = 0; i < bufferLength; i++) {
                last += readVInt() + 1;
                docs[i] = last;
                freqs[i] = readVInt() + 1;
            }
            nextBlockOffset = cursor;
        }
    }

    // Returns the offset after the block.
    private long unpack(long offset, int[] values) {
        var width = data.get(Layouts.BYTE, offset);
        BitPacking.unpack(data, offset + 1, width, values, scratch);
        return offset + 1 + BitPacking.bytes(width);
    }

    private void resetPositions(long blockOffset, long upto) {
        nextPositionBlockStart = upto - upto % BLOCK;
        nextPositionBlockOffset = blockOffset;
        positionBufferStart = 0;
        positionBufferLength = 0;
    }

    private int position(long index) {
        fillPositions(index);
        return positionBuffer[(int) (index - positionBufferStart)];
    }

    // Makes the position buffer hold the position of that index among the term's.
    private void fillPositions(long index) {
        if (index < positionBufferStart || index >= positionBufferStart + positionBufferLength) {
            while (nextPositionBlockStart + BLOCK <= index && nextPositionBlockStart < fullPositionsEnd) {
                var width = data.get(Layouts.BYTE, nextPositionBlockOffset);
                nextPositionBlockOffset += 1 + BitPacking.bytes(width);
                nextPositionBlockStart += BLOCK;
            }
            positionBufferStart = nextPositionBlockStart;
            if (nextPositionBlockStart < fullPositionsEnd) {
                nextPositionBlockOffset = unpack(nextPositionBlockOffset, positionBuffer);
                positionBufferLength = BLOCK;
            } else {
                cursor = nextPositionBlockOffset;
                positionBufferLength = (int) (totalTermFreq - nextPositionBlockStart);
                for (var i = 0; i < positionBufferLength; i++) {
                    positionBuffer[i] = readVInt();
                }
                nextPositionBlockOffset = cursor;
            }
            nextPositionBlockStart += positionBufferLength;
        }
    }

    private int readVInt() {
        var b = data.get(Layouts.BYTE, cursor++);
        var value = b & SEVEN_BITS;
        var shift = PAYLOAD_BITS;
        while ((b & CONTINUATION) != 0) {
            b = data.get(Layouts.BYTE, cursor++);
            value |= (b & SEVEN_BITS) << shift;
            shift += PAYLOAD_BITS;
        }
        return value;
    }
}
