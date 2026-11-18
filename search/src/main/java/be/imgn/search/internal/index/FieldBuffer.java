package be.imgn.search.internal.index;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// The terms of one internal field in a buffer, with their postings.
///
/// Terms are identified by a number, found through an open-addressing hash table, and their bytes are kept in the
/// buffer's shared [ByteBlockPool]. Each term has a stream of documents and frequencies and, in a field with
/// positions, a stream of positions, both appended to slices of the buffer's shared [IntBlockPool]: a slice ends
/// with the address of the next one, each bigger than the last, so a frequent term takes few slices and a rare
/// one little room. The state of every term is [#STRIDE] `int`s of one array.
final class FieldBuffer {

    // The sizes of the successive slices of a stream; the last int of each holds the address of the next.
    private static final int[] SLICE_SIZES = {4, 8, 16, 32, 64, 128, 256, 512, 1024};
    private static final int LAST_LEVEL = SLICE_SIZES.length - 1;

    private static final int BLOCK = 0;
    private static final int OFFSET = 1;
    private static final int LENGTH = 2;
    private static final int HASH = 3;
    private static final int LAST_DOC = 4;
    private static final int FREQ = 5;
    private static final int DOCS = 6;
    private static final int POSITIONS = 10;
    // A stream's start, write address, end of its current slice (the address of the next one's) and level.
    private static final int START = 0;
    private static final int WRITE = 1;
    private static final int END = 2;
    private static final int LEVEL = 3;
    private static final int STRIDE = 14;

    private static final int INITIAL_TABLE = 64;
    private static final int INITIAL_TERMS = 32;
    private static final int HASH_MULTIPLIER = 31;
    private static final int GOLDEN = 0x9E37_79B9;
    private static final int HALF = 16;

    private final String name;
    private final boolean positions;
    private final boolean norms;
    private final @Nullable FieldBuffer neutral;
    private final ByteBlockPool bytes;
    private final IntBlockPool ints;
    private final Map<Locale, FieldBuffer> languages = new HashMap<>();
    // Term numbers plus 1; 0 is an empty slot.
    private int[] table = new int[INITIAL_TABLE];
    private int[] state = new int[INITIAL_TERMS * STRIDE];
    private int count;
    private int[] lengths = new int[0];
    private final StreamReader docReader = new StreamReader();
    private final StreamReader positionReader = new StreamReader();

    FieldBuffer(
            String name,
            boolean positions,
            boolean norms,
            @Nullable FieldBuffer neutral,
            ByteBlockPool bytes,
            IntBlockPool ints) {
        this.name = name;
        this.positions = positions;
        this.norms = norms;
        this.neutral = neutral;
        this.bytes = bytes;
        this.ints = ints;
    }

    String name() {
        return name;
    }

    boolean positions() {
        return positions;
    }

    /// Whether the field has its own norms: whether it is a neutral text field.
    boolean norms() {
        return norms;
    }

    /// The neutral field of a language field.
    @Nullable
    FieldBuffer neutral() {
        return neutral;
    }

    Map<Locale, FieldBuffer> languages() {
        return languages;
    }

    int termCount() {
        return count;
    }

    /// The lengths of the field in each document, for a field with norms; 0 past the end.
    int length(int doc) {
        return doc < lengths.length ? lengths[doc] : 0;
    }

    void setLength(int doc, int length) {
        if (doc >= lengths.length) {
            lengths = Arrays.copyOf(lengths, Math.max(doc + 1, lengths.length * 2));
        }
        lengths[doc] = length;
    }

    long bytesUsed() {
        return (long) (table.length + state.length + lengths.length) * Integer.BYTES;
    }

    /// Records an occurrence of a term.
    ///
    /// @param term an array holding the term's UTF-8 bytes
    /// @param offset the index of the term's first byte
    /// @param length the number of bytes
    /// @param doc the document, at least the last one given
    /// @param position the term's position, ignored in a field without positions
    void add(byte[] term, int offset, int length, int doc, int position) {
        var id = find(term, offset, length);
        var base = id * STRIDE;
        if (state[base + LAST_DOC] != doc) {
            if (state[base + LAST_DOC] >= 0) {
                append(base + DOCS, state[base + LAST_DOC]);
                append(base + DOCS, state[base + FREQ]);
            }
            state[base + LAST_DOC] = doc;
            state[base + FREQ] = 1;
        } else {
            state[base + FREQ]++;
        }
        if (positions) {
            append(base + POSITIONS, position);
        }
    }

    /// Compares two terms by their unsigned bytes.
    int compare(int a, int b) {
        var baseA = a * STRIDE;
        var baseB = b * STRIDE;
        return Arrays.compareUnsigned(
                bytes.block(state[baseA + BLOCK]),
                state[baseA + OFFSET],
                state[baseA + OFFSET] + state[baseA + LENGTH],
                bytes.block(state[baseB + BLOCK]),
                state[baseB + OFFSET],
                state[baseB + OFFSET] + state[baseB + LENGTH]);
    }

    /// Copies a term's bytes.
    ///
    /// @return the number of bytes, which `target` must have room for: see [#termLength]
    int term(int id, byte[] target) {
        var base = id * STRIDE;
        var length = state[base + LENGTH];
        System.arraycopy(bytes.block(state[base + BLOCK]), state[base + OFFSET], target, 0, length);
        return length;
    }

    int termLength(int id) {
        return state[id * STRIDE + LENGTH];
    }

    /// Reads a term's postings, renumbering its documents.
    ///
    /// @param docMap the new number of each document, -1 for a dropped one
    void postings(int id, int[] docMap, TermPostings out) {
        out.clear();
        var base = id * STRIDE;
        docReader.reset(state[base + DOCS + START], state[base + DOCS + WRITE]);
        if (positions) {
            positionReader.reset(state[base + POSITIONS + START], state[base + POSITIONS + WRITE]);
        }
        while (docReader.hasNext()) {
            var doc = docReader.next();
            addDoc(doc, docReader.next(), docMap, out);
        }
        addDoc(state[base + LAST_DOC], state[base + FREQ], docMap, out);
    }

    private void addDoc(int doc, int freq, int[] docMap, TermPostings out) {
        var mapped = docMap[doc];
        if (mapped >= 0) {
            out.addDoc(mapped, freq);
        }
        if (positions) {
            for (var i = 0; i < freq; i++) {
                var position = positionReader.next();
                if (mapped >= 0) {
                    out.addPosition(position);
                }
            }
        }
    }

    private int find(byte[] term, int offset, int length) {
        var hash = hash(term, offset, length);
        var mask = table.length - 1;
        var slot = hash & mask;
        while (true) {
            var entry = table[slot];
            if (entry == 0) {
                var id = insert(term, offset, length, hash);
                table[slot] = id + 1;
                if (count * 2 > table.length) {
                    rehash();
                }
                return id;
            }
            var base = (entry - 1) * STRIDE;
            if (state[base + HASH] == hash
                    && state[base + LENGTH] == length
                    && Arrays.equals(
                            bytes.block(state[base + BLOCK]),
                            state[base + OFFSET],
                            state[base + OFFSET] + length,
                            term,
                            offset,
                            offset + length)) {
                return entry - 1;
            }
            slot = (slot + 1) & mask;
        }
    }

    private int insert(byte[] term, int offset, int length, int hash) {
        var id = count++;
        if (count * STRIDE > state.length) {
            state = Arrays.copyOf(state, state.length * 2);
        }
        var base = id * STRIDE;
        var address = bytes.append(term, offset, length);
        state[base + BLOCK] = (int) (address >>> Integer.SIZE);
        state[base + OFFSET] = (int) address;
        state[base + LENGTH] = length;
        state[base + HASH] = hash;
        state[base + LAST_DOC] = -1;
        newStream(base + DOCS);
        if (positions) {
            newStream(base + POSITIONS);
        }
        return id;
    }

    private void newStream(int stream) {
        var start = ints.allocate(SLICE_SIZES[0]);
        state[stream + START] = start;
        state[stream + WRITE] = start;
        state[stream + END] = start + SLICE_SIZES[0] - 1;
        state[stream + LEVEL] = 0;
    }

    private void append(int stream, int value) {
        var write = state[stream + WRITE];
        if (write == state[stream + END]) {
            var level = Math.min(state[stream + LEVEL] + 1, LAST_LEVEL);
            var slice = ints.allocate(SLICE_SIZES[level]);
            ints.set(write, slice);
            write = slice;
            state[stream + END] = slice + SLICE_SIZES[level] - 1;
            state[stream + LEVEL] = level;
        }
        ints.set(write, value);
        state[stream + WRITE] = write + 1;
    }

    private void rehash() {
        var newTable = new int[table.length * 2];
        var mask = newTable.length - 1;
        for (var id = 0; id < count; id++) {
            var slot = state[id * STRIDE + HASH] & mask;
            while (newTable[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            newTable[slot] = id + 1;
        }
        table = newTable;
    }

    private static int hash(byte[] term, int offset, int length) {
        var hash = 0;
        for (var i = 0; i < length; i++) {
            hash = hash * HASH_MULTIPLIER + term[offset + i];
        }
        hash *= GOLDEN;
        return hash ^ (hash >>> HALF);
    }

    /// Reads a stream back, following its slices.
    private final class StreamReader {

        private int address;
        private int end;
        private int level;
        private int limit;

        void reset(int start, int limit) {
            this.address = start;
            this.end = start + SLICE_SIZES[0] - 1;
            this.level = 0;
            this.limit = limit;
        }

        boolean hasNext() {
            return address != limit;
        }

        int next() {
            if (address == end) {
                address = ints.get(end);
                level = Math.min(level + 1, LAST_LEVEL);
                end = address + SLICE_SIZES[level] - 1;
            }
            return ints.get(address++);
        }
    }
}
