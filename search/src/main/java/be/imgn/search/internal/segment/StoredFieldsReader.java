package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.CorruptDataException;
import be.imgn.search.internal.codec.DataInput;
import be.imgn.search.internal.codec.Layouts;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/// Reads the stored fields [StoredFieldsWriter] wrote. Its block index is held on the heap. Thread-safe, given an
/// `Inflater` per thread.
public final class StoredFieldsReader {

    private final MemorySegment data;
    private final List<String> fields;
    private final int[] firstDocs;
    private final long[] offsets;
    private final long indexOffset;

    StoredFieldsReader(MemorySegment data, long start, long indexOffset, int blockCount, List<String> fields) {
        this.data = data;
        this.fields = List.copyOf(fields);
        this.firstDocs = new int[blockCount];
        this.offsets = new long[blockCount];
        this.indexOffset = indexOffset;
        for (var i = 0; i < blockCount; i++) {
            var entry = indexOffset + (long) i * StoredFieldsWriter.INDEX_ENTRY_SIZE;
            firstDocs[i] = data.get(Layouts.INT, entry);
            offsets[i] = start + data.get(Layouts.LONG, entry + Integer.BYTES);
        }
    }

    /// The names of the fields the records refer to by index.
    ///
    /// @return the names
    public List<String> fields() {
        return fields;
    }

    /// Reads a document's record.
    ///
    /// @param doc the document
    /// @param inflater the decompressor, reset before use
    /// @return the record
    /// @throws CorruptDataException if the block does not inflate
    public StoredRecord document(int doc, Inflater inflater) {
        var search = Arrays.binarySearch(firstDocs, doc);
        var block = search >= 0 ? search : -search - 2;
        var content = new DataInput(MemorySegment.ofArray(inflate(block, inflater)), 0);
        var count = content.readVInt();
        var skip = 0L;
        var before = doc - firstDocs[block];
        for (var i = 0; i < count; i++) {
            var size = content.readVInt();
            if (i < before) {
                skip += size;
            }
        }
        content.seek(content.position() + skip);
        return StoredRecord.read(content, fields);
    }

    /// The number of blocks.
    ///
    /// @return the number of blocks
    public int blockCount() {
        return offsets.length;
    }

    /// The first document of a block.
    ///
    /// @param block the block
    /// @return the document
    public int firstDoc(int block) {
        return firstDocs[block];
    }

    /// A block as written, still compressed, to copy it whole into another segment.
    ///
    /// @param block the block
    /// @return its bytes
    public MemorySegment rawBlock(int block) {
        var end = block + 1 < offsets.length ? offsets[block + 1] : indexOffset;
        return data.asSlice(offsets[block], end - offsets[block]);
    }

    /// Decompresses a block: its number of documents, the size of each document's record, then the records.
    ///
    /// @param block the block
    /// @param inflater the decompressor, reset before use
    /// @return the uncompressed block
    /// @throws CorruptDataException if the block does not inflate
    public byte[] inflate(int block, Inflater inflater) {
        var in = new DataInput(data, offsets[block]);
        var uncompressed = new byte[in.readVInt()];
        var compressed = in.readVInt();
        inflater.reset();
        inflater.setInput(data.asSlice(in.position(), compressed).asByteBuffer());
        try {
            var length = 0;
            while (length < uncompressed.length) {
                var inflated = inflater.inflate(uncompressed, length, uncompressed.length - length);
                if (inflated == 0 && (inflater.finished() || inflater.needsInput())) {
                    throw new CorruptDataException("Truncated block of stored fields");
                }
                length += inflated;
            }
        } catch (DataFormatException e) {
            throw new CorruptDataException("Corrupt block of stored fields", e);
        }
        return uncompressed;
    }
}
