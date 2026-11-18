package be.imgn.search.internal.index;

import java.util.Arrays;

/// Bytes appended to large shared blocks, so that a buffer holds its terms in few objects. A term never spans two
/// blocks: one longer than a block gets a block of its own.
final class ByteBlockPool {

    static final int BLOCK_SIZE = 1 << 15;

    private static final int INITIAL_BLOCKS = 16;

    private byte[][] blocks = new byte[INITIAL_BLOCKS][];
    private int count;
    private int upto = BLOCK_SIZE;
    private long bytesUsed;

    /// Appends bytes.
    ///
    /// @return the block index in the high 32 bits, the offset in the block in the low 32 bits
    long append(byte[] source, int from, int length) {
        if (count == 0 || upto + length > blocks[count - 1].length) {
            newBlock(Math.max(BLOCK_SIZE, length));
        }
        var block = count - 1;
        var offset = upto;
        System.arraycopy(source, from, blocks[block], offset, length);
        upto += length;
        return ((long) block << Integer.SIZE) | offset;
    }

    byte[] block(int index) {
        return blocks[index];
    }

    long bytesUsed() {
        return bytesUsed;
    }

    private void newBlock(int size) {
        if (count == blocks.length) {
            blocks = Arrays.copyOf(blocks, count * 2);
        }
        blocks[count++] = new byte[size];
        upto = 0;
        bytesUsed += size;
    }
}
