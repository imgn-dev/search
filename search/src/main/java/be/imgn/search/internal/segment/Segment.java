package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.CorruptDataException;
import be.imgn.search.internal.codec.DataInput;
import be.imgn.search.internal.codec.Format;
import be.imgn.search.internal.codec.Layouts;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/// A segment written by [SegmentWriter], read through a `MemorySegment`. Opening it checks its header and
/// loads its directory and the block indexes of its dictionaries on the heap; [#checkIntegrity()] checks its
/// checksum. Immutable and thread-safe.
public final class Segment {

    private static final int BYTE_MASK = 0xFF;

    private final MemorySegment data;
    private final int maxDoc;
    private final long postingsBase;
    private final long positionsBase;
    private final List<FieldInfo> fields;
    private final Map<String, FieldInfo> fieldsByName;
    private final long keyOffsets;
    private final long keyBytes;
    private final TermDictionary keyDictionary;
    private final StoredFieldsReader stored;

    private Segment(MemorySegment data) {
        this.data = data;
        Format.checkHeader(data, Format.SEGMENT_MAGIC, SegmentWriter.FOOTER_SIZE);
        var directoryOffset = data.get(Layouts.LONG, data.byteSize() - SegmentWriter.FOOTER_SIZE);
        if (directoryOffset < Format.HEADER_SIZE || directoryOffset > data.byteSize() - SegmentWriter.FOOTER_SIZE) {
            throw new CorruptDataException("Directory offset " + directoryOffset + " out of the segment");
        }
        var in = new DataInput(data, directoryOffset);
        this.maxDoc = in.readVInt();
        this.postingsBase = in.readVLong();
        this.positionsBase = in.readVLong();
        var dictionariesBase = in.readVLong();
        var normsBase = in.readVLong();
        this.fields = readFields(in, dictionariesBase, normsBase);
        var byName = new HashMap<String, FieldInfo>();
        fields.forEach(field -> byName.put(field.name(), field));
        this.fieldsByName = Map.copyOf(byName);
        this.keyOffsets = in.readVLong();
        this.keyBytes = in.readVLong();
        var keyStart = in.readVLong();
        var keyIndex = in.readVLong();
        var keyCount = in.readVLong();
        this.keyDictionary = new TermDictionary(data, keyStart, keyIndex, keyCount, 1, 0);
        var storedCount = in.readVInt();
        var storedFields = new ArrayList<String>(storedCount);
        for (var i = 0; i < storedCount; i++) {
            storedFields.add(in.readString());
        }
        var storedStart = in.readVLong();
        var storedIndex = in.readVLong();
        var storedBlocks = in.readVInt();
        this.stored = new StoredFieldsReader(data, storedStart, storedIndex, storedBlocks, storedFields);
    }

    /// Opens a segment.
    ///
    /// @param data the segment's bytes
    /// @return the segment
    /// @throws CorruptDataException if the header or the directory is wrong
    public static Segment open(MemorySegment data) {
        return new Segment(data);
    }

    /// The number of documents, deleted ones included.
    ///
    /// @return the number of documents
    public int maxDoc() {
        return maxDoc;
    }

    /// The internal fields.
    ///
    /// @return the fields
    public List<FieldInfo> fields() {
        return fields;
    }

    /// An internal field.
    ///
    /// @param name the field's name, such as `body` or `body.fr`
    /// @return the field, or `null` if no document of the segment has it
    public @Nullable FieldInfo field(String name) {
        return fieldsByName.get(name);
    }

    /// Looks a term up in a field.
    ///
    /// @param field the field
    /// @param term the term's bytes, from index 0
    /// @param length the number of bytes
    /// @return the term, or `null` if the field does not have it
    public @Nullable TermInfo term(FieldInfo field, byte[] term, int length) {
        var values = new long[TermInfo.COLUMNS];
        return field.terms().lookup(term, length, values) ? TermInfo.of(values) : null;
    }

    /// Moves an iterator to the start of a term's postings.
    ///
    /// @param iterator the iterator
    /// @param field the term's field
    /// @param term the term
    /// @param withPositions whether the positions will be read; the field must have them
    public void postings(PostingsIterator iterator, FieldInfo field, TermInfo term, boolean withPositions) {
        iterator.reset(data, term, postingsBase, withPositions && field.positions() ? positionsBase : -1);
    }

    /// The norm of a document in a field.
    ///
    /// @param field a field with norms
    /// @param doc the document
    /// @return the norm, from 0 to 255
    public int norm(FieldInfo field, int doc) {
        return data.get(Layouts.BYTE, field.normsOffset() + doc) & BYTE_MASK;
    }

    /// The key of a document.
    ///
    /// @param doc the document
    /// @return the key
    public String key(int doc) {
        var start = data.get(Layouts.LONG, keyOffsets + (long) doc * Long.BYTES);
        var end = data.get(Layouts.LONG, keyOffsets + (long) (doc + 1) * Long.BYTES);
        var bytes = new byte[(int) (end - start)];
        MemorySegment.copy(data, Layouts.BYTE, keyBytes + start, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /// The document of a key.
    ///
    /// @param key the key's UTF-8 bytes, from index 0
    /// @param length the number of bytes
    /// @return the document, or -1 if no document of the segment has that key
    public int document(byte[] key, int length) {
        var values = new long[1];
        return keyDictionary.lookup(key, length, values) ? (int) values[0] : -1;
    }

    /// The keys in increasing order of their bytes, each with its document as its one value.
    ///
    /// @return a cursor before the first key
    public TermDictionary.Cursor keys() {
        return keyDictionary.cursor();
    }

    /// The stored fields, to copy them in a merge.
    ///
    /// @return the reader
    public StoredFieldsReader storedFields() {
        return stored;
    }

    /// The size of the segment.
    ///
    /// @return the number of bytes
    public long byteSize() {
        return data.byteSize();
    }

    /// The stored record of a document.
    ///
    /// @param doc the document
    /// @param inflater the decompressor to use
    /// @return the record
    public StoredRecord stored(int doc, Inflater inflater) {
        return stored.document(doc, inflater);
    }

    /// Checks the whole segment against its checksum, which reads every byte.
    ///
    /// @throws CorruptDataException if the checksum does not match
    public void checkIntegrity() {
        Format.checkChecksum(data);
    }

    private List<FieldInfo> readFields(DataInput in, long dictionariesBase, long normsBase) {
        var count = in.readVInt();
        var names = new ArrayList<String>(count);
        var flags = new int[count];
        var termCounts = new long[count];
        var dictionaries = new long[count];
        var indexes = new long[count];
        var norms = new long[count];
        var docCounts = new long[count];
        var totalLengths = new long[count];
        var normsByName = new HashMap<String, Long>();
        for (var i = 0; i < count; i++) {
            names.add(in.readString());
            flags[i] = in.readUnsignedByte();
            termCounts[i] = in.readVLong();
            dictionaries[i] = dictionariesBase + in.readVLong();
            indexes[i] = dictionariesBase + in.readVLong();
            norms[i] = normsBase + in.readVLong();
            docCounts[i] = in.readVLong();
            totalLengths[i] = in.readVLong();
            if ((flags[i] & SegmentWriter.NORMS_FLAG) != 0) {
                normsByName.put(names.get(i), norms[i]);
            }
        }
        var result = new ArrayList<FieldInfo>(count);
        for (var i = 0; i < count; i++) {
            var name = names.get(i);
            var dot = name.indexOf('.');
            var normsOffset = normsByName.getOrDefault(dot < 0 ? name : name.substring(0, dot), -1L);
            var terms = new TermDictionary(
                    data, dictionaries[i], indexes[i], termCounts[i], TermInfo.COLUMNS, TermInfo.DELTA_MASK);
            result.add(new FieldInfo(
                    name,
                    (flags[i] & SegmentWriter.POSITIONS_FLAG) != 0,
                    normsOffset,
                    docCounts[i],
                    totalLengths[i],
                    terms));
        }
        return List.copyOf(result);
    }
}
