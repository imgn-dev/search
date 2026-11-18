package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Format;
import be.imgn.search.internal.codec.IntSorter;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import org.jspecify.annotations.Nullable;

/// Writes a segment into one array.
///
/// The segment is a [header][Format], then its sections: postings, positions, term dictionaries, norms, keys
/// (offsets, bytes, then a dictionary from key to document) and stored fields; then the directory that locates
/// them, the offset of the directory as a `long`, and the checksum.
///
/// The directory holds the number of documents, the offsets of the postings, positions, dictionaries and norms,
/// the internal fields (name, flags, number of terms, offsets of the dictionary and its block index from the
/// dictionaries, offset of the norms from the norms, number of documents and total length), the offsets of the
/// keys, the names of the stored fields, and the offset of the stored fields, of their index and the number of
/// their blocks.
///
/// Fields are written one at a time, each term with its postings, then the keys and stored fields in document
/// order. Not thread-safe.
public final class SegmentWriter {

    static final int POSITIONS_FLAG = 1;
    static final int NORMS_FLAG = 2;
    static final int FOOTER_SIZE = Long.BYTES + Format.CHECKSUM_SIZE;

    private final int maxDoc;
    private final List<String> storedFields;
    private final ByteOutput postings = new ByteOutput();
    private final ByteOutput positions = new ByteOutput();
    private final ByteOutput dictionaries = new ByteOutput();
    private final ByteOutput norms = new ByteOutput();
    private final ByteOutput fields = new ByteOutput();
    private final PostingsWriter postingsWriter = new PostingsWriter();
    private final StoredFieldsWriter stored;
    private final List<byte[]> keys = new ArrayList<>();
    private final long[] values = new long[TermInfo.COLUMNS];
    private int fieldCount;
    private @Nullable TermDictionaryWriter dictionary;
    private String fieldName = "";
    private boolean fieldPositions;
    private byte @Nullable [] fieldNorms;
    private boolean ownNorms;

    /// A writer of a segment of `maxDoc` documents.
    ///
    /// @param maxDoc the number of documents
    /// @param storedFields the names of the fields the stored records refer to by index
    /// @param deflater the compressor of the stored fields
    public SegmentWriter(int maxDoc, List<String> storedFields, Deflater deflater) {
        this.maxDoc = maxDoc;
        this.storedFields = List.copyOf(storedFields);
        this.stored = new StoredFieldsWriter(deflater);
    }

    /// Starts a field; its terms follow in increasing order of their bytes.
    ///
    /// @param name the field's name
    /// @param positions whether its terms have positions
    /// @param norms the norm of each document that scores the field, or `null` for a keyword field
    /// @param own whether `norms` are the field's own, as in a neutral text field, rather than those of its
    ///     neutral field
    public void startField(String name, boolean positions, byte @Nullable [] norms, boolean own) {
        this.dictionary = new TermDictionaryWriter(dictionaries, TermInfo.COLUMNS, TermInfo.DELTA_MASK);
        this.fieldName = name;
        this.fieldPositions = positions;
        this.fieldNorms = norms;
        this.ownNorms = own && norms != null;
    }

    /// Adds a term of the current field with its postings.
    ///
    /// @param term the term's bytes, from index 0
    /// @param length the number of bytes
    /// @param docs the documents, increasing
    /// @param freqs the frequency in each document
    /// @param docFreq the number of documents, at least 1
    /// @param termPositions the positions in each document one after the other, if the field has positions
    public void addTerm(byte[] term, int length, int[] docs, int[] freqs, int docFreq, int @Nullable [] termPositions) {
        var info = postingsWriter.write(
                docs, freqs, docFreq, fieldPositions ? termPositions : null, fieldNorms, postings, positions);
        info.toValues(values);
        currentDictionary().add(term, length, values);
    }

    /// Finishes the current field.
    ///
    /// @param docCount the number of documents with a non-empty value, for a neutral text field
    /// @param totalLength the sum of their lengths, for a neutral text field
    public void finishField(long docCount, long totalLength) {
        var current = currentDictionary();
        var indexOffset = current.finish();
        var normsOffset = 0L;
        var fieldNormsNow = fieldNorms;
        if (ownNorms && fieldNormsNow != null) {
            normsOffset = norms.size();
            norms.writeBytes(fieldNormsNow, 0, maxDoc);
        }
        fields.writeString(fieldName);
        fields.writeByte((fieldPositions ? POSITIONS_FLAG : 0) | (ownNorms ? NORMS_FLAG : 0));
        fields.writeVLong(current.termCount());
        fields.writeVLong(current.start());
        fields.writeVLong(indexOffset);
        fields.writeVLong(normsOffset);
        fields.writeVLong(docCount);
        fields.writeVLong(totalLength);
        fieldCount++;
        dictionary = null;
    }

    /// Adds the key of the next document.
    ///
    /// @param key the key
    public void addKey(String key) {
        keys.add(key.getBytes(StandardCharsets.UTF_8));
    }

    /// Adds the stored record of the next document.
    ///
    /// @param record the bytes of a [StoredRecord]
    /// @param offset the index of the first byte
    /// @param length the number of bytes
    public void addStored(byte[] record, int offset, int length) {
        stored.add(record, offset, length);
    }

    /// Adds the stored records of the next documents as a block of another segment, still compressed.
    ///
    /// @param raw the block, as [StoredFieldsReader#rawBlock] gives it
    /// @param count the number of documents of the block
    public void addStoredBlock(MemorySegment raw, int count) {
        stored.addBlock(raw, count);
    }

    /// Writes the segment.
    ///
    /// @return the segment's bytes
    public byte[] finish() {
        var storedBytes = stored.finish();
        var out = new ByteOutput(Format.HEADER_SIZE
                + postings.size()
                + positions.size()
                + dictionaries.size()
                + norms.size()
                + storedBytes.size()
                + keys.size() * (Long.BYTES + Long.BYTES)
                + fields.size());
        Format.writeHeader(out, Format.SEGMENT_MAGIC);
        var directory = new ByteOutput();
        directory.writeVInt(maxDoc);
        for (var section : List.of(postings, positions, dictionaries, norms)) {
            directory.writeVLong(out.size());
            out.writeBytes(section);
        }
        directory.writeVInt(fieldCount);
        directory.writeBytes(fields);
        writeKeys(out, directory);
        directory.writeVInt(storedFields.size());
        storedFields.forEach(directory::writeString);
        directory.writeVLong(out.size());
        directory.writeVLong(out.size() + stored.indexOffset());
        directory.writeVInt(stored.blockCount());
        out.writeBytes(storedBytes);
        var directoryOffset = out.size();
        out.writeBytes(directory);
        out.writeLong(directoryOffset);
        Format.writeChecksum(out);
        return out.toByteArray();
    }

    private void writeKeys(ByteOutput out, ByteOutput directory) {
        directory.writeVLong(out.size());
        long offset = 0;
        out.writeLong(offset);
        for (var key : keys) {
            offset += key.length;
            out.writeLong(offset);
        }
        directory.writeVLong(out.size());
        for (var key : keys) {
            out.writeBytes(key, 0, key.length);
        }
        var order = new int[keys.size()];
        Arrays.setAll(order, i -> i);
        IntSorter.sort(order, order.length, (a, b) -> Arrays.compareUnsigned(keys.get(a), keys.get(b)));
        var keyDictionary = new TermDictionaryWriter(out, 1, 0);
        var docValue = new long[1];
        for (var doc : order) {
            docValue[0] = doc;
            var key = keys.get(doc);
            keyDictionary.add(key, key.length, docValue);
        }
        var indexOffset = keyDictionary.finish();
        directory.writeVLong(keyDictionary.start());
        directory.writeVLong(indexOffset);
        directory.writeVLong(keyDictionary.termCount());
    }

    private TermDictionaryWriter currentDictionary() {
        var current = dictionary;
        if (current == null) {
            throw new IllegalStateException("No field started");
        }
        return current;
    }
}
