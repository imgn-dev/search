package be.imgn.search.internal.index;

import be.imgn.search.internal.codec.IntSorter;
import be.imgn.search.internal.codec.Norms;
import be.imgn.search.internal.segment.SegmentWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import org.jspecify.annotations.Nullable;

/// Turns a [DocumentBuffer] into a segment, dropping the documents replaced or deleted while buffered. Not
/// thread-safe: it reuses its arrays from one buffer to the next.
final class SegmentFlusher {

    private static final int INITIAL_TERM_BYTES = 256;

    private final Deflater deflater;
    private final List<String> storedFields;
    private final TermPostings postings = new TermPostings();
    private byte[] term = new byte[INITIAL_TERM_BYTES];

    SegmentFlusher(Deflater deflater, List<String> storedFields) {
        this.deflater = deflater;
        this.storedFields = storedFields;
    }

    /// Writes a segment of the buffer's live documents.
    ///
    /// @return the segment's bytes, or `null` if no document is live
    byte @Nullable [] flush(DocumentBuffer buffer) {
        var maxDoc = buffer.liveCount();
        if (maxDoc == 0) {
            return null;
        }
        var docMap = new int[buffer.docCount()];
        var next = 0;
        for (var doc = 0; doc < docMap.length; doc++) {
            docMap[doc] = buffer.isDeleted(doc) ? -1 : next++;
        }
        var writer = new SegmentWriter(maxDoc, storedFields, deflater);
        var fields = buffer.fields();
        fields.sort(Comparator.comparing(FieldBuffer::name));
        var norms = new HashMap<String, byte[]>();
        for (var field : fields) {
            writeField(field, docMap, maxDoc, norms, writer);
        }
        for (var doc = 0; doc < docMap.length; doc++) {
            if (docMap[doc] >= 0) {
                writer.addKey(buffer.key(doc));
                var start = buffer.storedStart(doc);
                writer.addStored(buffer.storedBytes(), start, buffer.storedEnd(doc) - start);
            }
        }
        return writer.finish();
    }

    // A neutral field sorts before its language fields, whose names it prefixes, so its norms are ready for them.
    private void writeField(
            FieldBuffer field, int[] docMap, int maxDoc, Map<String, byte[]> norms, SegmentWriter writer) {
        byte[] fieldNorms = null;
        long docCount = 0;
        long totalLength = 0;
        if (field.norms()) {
            fieldNorms = new byte[maxDoc];
            for (var doc = 0; doc < docMap.length; doc++) {
                var length = field.length(doc);
                if (docMap[doc] >= 0 && length > 0) {
                    fieldNorms[docMap[doc]] = (byte) Norms.encode(length);
                    docCount++;
                    totalLength += length;
                }
            }
            norms.put(field.name(), fieldNorms);
        } else {
            var neutral = field.neutral();
            if (neutral != null) {
                fieldNorms = norms.get(neutral.name());
            }
        }
        writer.startField(field.name(), field.positions(), fieldNorms, field.norms());
        var ids = new int[field.termCount()];
        for (var i = 0; i < ids.length; i++) {
            ids[i] = i;
        }
        IntSorter.sort(ids, ids.length, field::compare);
        for (var id : ids) {
            field.postings(id, docMap, postings);
            if (postings.docCount() == 0) {
                continue;
            }
            if (term.length < field.termLength(id)) {
                term = new byte[Math.max(field.termLength(id), term.length * 2)];
            }
            var length = field.term(id, term);
            writer.addTerm(
                    term,
                    length,
                    postings.docs(),
                    postings.freqs(),
                    postings.docCount(),
                    field.positions() ? postings.positions() : null);
        }
        writer.finishField(docCount, totalLength);
    }
}
