package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Output;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.zip.Deflater;

/// Writes the stored fields of a segment: the documents' [records][StoredRecord] in blocks of about
/// [#BLOCK_SIZE] bytes compressed with a `Deflater`, then the block index.
///
/// A block is its uncompressed and compressed sizes then the compressed bytes. Uncompressed, it is its number of
/// documents, the size of each document's record, then the records. The index has, per block, its first document
/// (`int`) and its offset from the start of the stored fields (`long`).
public final class StoredFieldsWriter {

    /// The uncompressed size at which a block is closed.
    public static final int BLOCK_SIZE = 16 * 1024;

    static final int INDEX_ENTRY_SIZE = Integer.BYTES + Long.BYTES;

    private final Output out;
    private final ByteOutput index = new ByteOutput();
    private final ByteOutput sizes = new ByteOutput();
    private final ByteOutput records = new ByteOutput();
    private final ByteOutput block = new ByteOutput();
    private final Deflater deflater;
    private byte[] compressed = new byte[BLOCK_SIZE];
    private int documents;
    private int blockDocuments;
    private int blockCount;
    private long indexOffset;

    /// A writer that compresses with `deflater` into `out`.
    ///
    /// @param deflater the compressor, reset before each block
    /// @param out the receiver of the stored fields, empty
    public StoredFieldsWriter(Deflater deflater, Output out) {
        this.deflater = deflater;
        this.out = out;
    }

    /// Adds the next document's record.
    ///
    /// @param record the bytes
    /// @param offset the index of the first byte
    /// @param length the number of bytes
    public void add(byte[] record, int offset, int length) {
        sizes.writeVInt(length);
        records.writeBytes(record, offset, length);
        blockDocuments++;
        documents++;
        if (records.size() >= BLOCK_SIZE) {
            flush();
        }
    }

    /// Adds a block of another segment as it is, still compressed, after closing the current block.
    ///
    /// @param raw the block, as [StoredFieldsReader#rawBlock] gives it
    /// @param count the number of documents of the block
    public void addBlock(MemorySegment raw, int count) {
        if (blockDocuments > 0) {
            flush();
        }
        index.writeInt(documents);
        index.writeLong(out.position());
        var bytes = raw.toArray(ValueLayout.JAVA_BYTE);
        out.writeBytes(bytes, 0, bytes.length);
        documents += count;
        blockCount++;
    }

    /// Writes the last block and the index.
    ///
    /// @return the stored fields, whose index starts at [#indexOffset()]
    public Output finish() {
        if (blockDocuments > 0) {
            flush();
        }
        indexOffset = out.position();
        out.writeBytes(index);
        return out;
    }

    /// The offset of the index in the stored fields, once [#finish()]ed.
    ///
    /// @return the offset
    public long indexOffset() {
        return indexOffset;
    }

    /// The number of blocks.
    ///
    /// @return the number of blocks
    public int blockCount() {
        return blockCount;
    }

    private void flush() {
        index.writeInt(documents - blockDocuments);
        index.writeLong(out.position());
        block.reset();
        block.writeVInt(blockDocuments);
        block.writeBytes(sizes);
        block.writeBytes(records);
        deflater.reset();
        deflater.setInput(block.array(), 0, block.size());
        deflater.finish();
        var length = 0;
        while (!deflater.finished()) {
            if (length == compressed.length) {
                compressed = Arrays.copyOf(compressed, compressed.length * 2);
            }
            length += deflater.deflate(compressed, length, compressed.length - length);
        }
        out.writeVInt(block.size());
        out.writeVInt(length);
        out.writeBytes(compressed, 0, length);
        sizes.reset();
        records.reset();
        blockDocuments = 0;
        blockCount++;
    }
}
