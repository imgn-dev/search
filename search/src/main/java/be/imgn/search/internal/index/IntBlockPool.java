package be.imgn.search.internal.index;

import java.util.Arrays;

/// `int`s in large shared blocks, handed out as slices addressed by one `int`: the block in the high bits, the
/// offset in the low [#SHIFT] bits. A slice never spans two blocks.
final class IntBlockPool {

    static final int SHIFT = 13;
    static final int BLOCK_SIZE = 1 << SHIFT;

    private static final int MASK = BLOCK_SIZE - 1;
    private static final int INITIAL_BLOCKS = 16;

    private int[][] blocks = new int[INITIAL_BLOCKS][];
    private int count;
    private int upto = BLOCK_SIZE;

    /// A new slice.
    ///
    /// @param size the number of ints, at most [#BLOCK_SIZE]
    /// @return the address of its first int
    int allocate(int size) {
        if (upto + size > BLOCK_SIZE) {
            if (count == blocks.length) {
                blocks = Arrays.copyOf(blocks, count * 2);
            }
            blocks[count++] = new int[BLOCK_SIZE];
            upto = 0;
        }
        var address = ((count - 1) << SHIFT) | upto;
        upto += size;
        return address;
    }

    int get(int address) {
        return blocks[address >>> SHIFT][address & MASK];
    }

    void set(int address, int value) {
        blocks[address >>> SHIFT][address & MASK] = value;
    }

    long bytesUsed() {
        return (long) count * BLOCK_SIZE * Integer.BYTES;
    }
}
