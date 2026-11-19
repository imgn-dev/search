package be.imgn.search.internal.index;

import be.imgn.search.internal.codec.ByteOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Analysed documents not yet written into a segment: their keys, the terms and postings of each internal field,
/// the field lengths, the stored records and the sequence number of each document's add. A document that a later
/// operation replaced or deleted is marked before the buffer becomes a segment, and dropped. Not thread-safe.
final class DocumentBuffer {

    private static final int INITIAL_DOCUMENTS = 64;
    private static final long OBJECT_OVERHEAD = 64;
    private static final int INITIAL_STORED_BYTES = 256;

    private final ByteBlockPool bytes = new ByteBlockPool();
    private final IntBlockPool ints = new IntBlockPool();
    private final Map<String, FieldBuffer> fields = new HashMap<>();
    private final BitSet deleted = new BitSet();
    private final ByteOutput stored;
    private String[] keys = new String[INITIAL_DOCUMENTS];
    private long[] seqs = new long[INITIAL_DOCUMENTS];
    private int[] storedEnds = new int[INITIAL_DOCUMENTS];
    private int docCount;
    private long keyBytes;

    /// An empty buffer.
    DocumentBuffer() {
        this(ByteOutput.MAX_SIZE);
    }

    /// An empty buffer whose stored records take at most `storedLimit` bytes, for tests that reach the limit
    /// without allocating [ByteOutput#MAX_SIZE] bytes.
    DocumentBuffer(int storedLimit) {
        stored = new ByteOutput(INITIAL_STORED_BYTES, storedLimit);
    }

    int docCount() {
        return docCount;
    }

    String key(int doc) {
        return keys[doc];
    }

    long seq(int doc) {
        return seqs[doc];
    }

    boolean isDeleted(int doc) {
        return deleted.get(doc);
    }

    int liveCount() {
        return docCount - deleted.cardinality();
    }

    List<FieldBuffer> fields() {
        return new ArrayList<>(fields.values());
    }

    /// Starts a document.
    ///
    /// @param key the document's key
    /// @param seq the sequence number of its add
    /// @return the document's number in the buffer
    int newDocument(String key, long seq) {
        var doc = docCount++;
        if (doc == keys.length) {
            keys = Arrays.copyOf(keys, doc * 2);
            storedEnds = Arrays.copyOf(storedEnds, doc * 2);
            seqs = Arrays.copyOf(seqs, doc * 2);
        }
        keys[doc] = key;
        seqs[doc] = seq;
        keyBytes += key.length() * 2L + OBJECT_OVERHEAD;
        return doc;
    }

    /// Marks a document replaced or deleted, to drop it.
    void drop(int doc) {
        deleted.set(doc);
    }

    /// The buffer of a neutral text field or a keyword field, created on first use.
    FieldBuffer field(String name, boolean positions, boolean norms) {
        var field = fields.get(name);
        if (field == null) {
            field = new FieldBuffer(name, positions, norms, null, bytes, ints);
            fields.put(name, field);
        }
        return field;
    }

    /// The buffer of the language field of a neutral text field, created on first use.
    FieldBuffer languageField(FieldBuffer neutral, Locale language) {
        var field = neutral.languages().get(language);
        if (field == null) {
            var name = neutral.name() + "." + language.getLanguage();
            field = new FieldBuffer(name, false, false, neutral, bytes, ints);
            neutral.languages().put(language, field);
            fields.put(name, field);
        }
        return field;
    }

    /// Makes room for a stored record, so that a document can be checked against the limit of the stored records
    /// before it changes the buffer.
    ///
    /// @param bytes the size of the record
    /// @throws IllegalStateException if the stored records would exceed their limit
    void reserveStored(int bytes) {
        stored.reserve(bytes);
    }

    /// Sets the stored record of the last document started.
    void stored(ByteOutput record) {
        stored.writeBytes(record);
        storedEnds[docCount - 1] = stored.size();
    }

    byte[] storedBytes() {
        return stored.array();
    }

    int storedStart(int doc) {
        return doc == 0 ? 0 : storedEnds[doc - 1];
    }

    int storedEnd(int doc) {
        return storedEnds[doc];
    }

    /// An estimate of the heap the buffer takes.
    long bytesUsed() {
        var used = bytes.bytesUsed() + ints.bytesUsed() + stored.array().length + keyBytes;
        used += (long) (keys.length + storedEnds.length) * Integer.BYTES + (long) seqs.length * Long.BYTES;
        for (var field : fields.values()) {
            used += field.bytesUsed();
        }
        return used;
    }
}
