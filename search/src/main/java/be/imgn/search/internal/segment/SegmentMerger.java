package be.imgn.search.internal.segment;

import static be.imgn.gaia.core.base.Preconditions.checkState;

import be.imgn.search.internal.codec.DataInput;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/// Merges segments into one, dropping their deleted documents. The documents keep their order: those of the
/// first segment, then those of the second, and so on.
///
/// Terms are merged field by field, in order, and their postings renumbered. Norms are copied, since a document
/// keeps its lengths, and the statistics of a field are counted again over the remaining documents: those with a
/// norm, and the sum of their frequencies, which is their length. A block of stored fields without deleted
/// documents is copied still compressed; the others are decompressed and their remaining records written again.
/// Not thread-safe.
public final class SegmentMerger {

    // How many terms are merged between two checks of whether to stop.
    private static final int CHECK_INTERVAL = 1024;
    private static final int INITIAL_CAPACITY = 128;

    private final List<String> storedFields;
    private final Deflater deflater;
    private final Inflater inflater;
    private final PostingsIterator iterator = new PostingsIterator();
    private int[] docs = new int[INITIAL_CAPACITY];
    private int[] freqs = new int[INITIAL_CAPACITY];
    private int[] positions = new int[INITIAL_CAPACITY];
    private int[] docPositions = new int[INITIAL_CAPACITY];
    private byte[] term = new byte[INITIAL_CAPACITY];
    // The number of documents and of positions of the term being merged.
    private int termDocs;
    private int termPositions;

    /// A merger that writes stored fields with `deflater` and reads them with `inflater`.
    ///
    /// @param storedFields the names of the fields the stored records refer to by index, the same in every segment
    /// @param deflater the compressor
    /// @param inflater the decompressor
    public SegmentMerger(List<String> storedFields, Deflater deflater, Inflater inflater) {
        this.storedFields = List.copyOf(storedFields);
        this.deflater = deflater;
        this.inflater = inflater;
    }

    /// The new number of each document of some segments: in order, skipping the deleted ones.
    ///
    /// @param inputs the segments
    /// @return per segment, the new number of each document, -1 for a deleted one
    public static List<int[]> docMaps(List<Input> inputs) {
        var maps = new ArrayList<int[]>(inputs.size());
        var next = 0;
        for (var input : inputs) {
            var map = new int[input.segment().maxDoc()];
            for (var doc = 0; doc < map.length; doc++) {
                map[doc] = input.deletions().isDeleted(doc) ? -1 : next++;
            }
            maps.add(map);
        }
        return maps;
    }

    /// Merges segments.
    ///
    /// @param inputs the segments, each with the deletions to drop
    /// @param docMaps their [#docMaps]
    /// @param maxDoc the number of documents the maps keep, at least 1
    /// @param stop whether to give up, checked from time to time
    /// @return the merged segment's bytes, or `null` if `stop` said to give up
    public byte @Nullable [] merge(List<Input> inputs, List<int[]> docMaps, int maxDoc, BooleanSupplier stop) {
        var writer = new SegmentWriter(maxDoc, storedFields, deflater);
        var names = new TreeSet<String>();
        for (var input : inputs) {
            input.segment().fields().forEach(field -> names.add(field.name()));
        }
        var norms = new HashMap<String, byte[]>();
        for (var name : names) {
            if (stop.getAsBoolean() || !mergeField(name, inputs, docMaps, maxDoc, norms, writer, stop)) {
                return null;
            }
        }
        for (var i = 0; i < inputs.size(); i++) {
            var segment = inputs.get(i).segment();
            var map = docMaps.get(i);
            for (var doc = 0; doc < map.length; doc++) {
                if (map[doc] >= 0) {
                    writer.addKey(segment.key(doc));
                }
            }
        }
        for (var i = 0; i < inputs.size(); i++) {
            if (stop.getAsBoolean()) {
                return null;
            }
            mergeStored(inputs.get(i).segment(), docMaps.get(i), writer);
        }
        return writer.finish();
    }

    // Returns false if stop said to give up.
    private boolean mergeField(
            String name,
            List<Input> inputs,
            List<int[]> docMaps,
            int maxDoc,
            Map<String, byte[]> norms,
            SegmentWriter writer,
            BooleanSupplier stop) {
        var infos = new FieldInfo[inputs.size()];
        var cursors = new TermDictionary.Cursor[inputs.size()];
        var live = new boolean[inputs.size()];
        FieldInfo any = null;
        for (var i = 0; i < infos.length; i++) {
            var info = inputs.get(i).segment().field(name);
            if (info != null) {
                infos[i] = info;
                cursors[i] = info.terms().cursor();
                live[i] = cursors[i].next();
                any = info;
            }
        }
        if (any == null) {
            throw new IllegalStateException("No segment has field " + name);
        }
        // Field names have no dot: only a language field's name has one, after its neutral field's.
        var dot = name.indexOf('.');
        var own = any.hasNorms() && dot < 0;
        byte[] fieldNorms = null;
        long docCount = 0;
        if (own) {
            fieldNorms = new byte[maxDoc];
            docCount = copyNorms(infos, inputs, docMaps, fieldNorms);
            norms.put(name, fieldNorms);
        } else if (any.hasNorms()) {
            fieldNorms = norms.get(name.substring(0, dot));
        }
        writer.startField(name, any.positions(), fieldNorms, own);
        var totalLength = mergeTerms(infos, cursors, live, inputs, docMaps, writer, stop);
        if (totalLength < 0) {
            return false;
        }
        writer.finishField(own ? docCount : 0, own ? totalLength : 0);
        return true;
    }

    // Returns the sum of the frequencies, or -1 if stop said to give up.
    private long mergeTerms(
            FieldInfo[] infos,
            TermDictionary.Cursor[] cursors,
            boolean[] live,
            List<Input> inputs,
            List<int[]> docMaps,
            SegmentWriter writer,
            BooleanSupplier stop) {
        long totalLength = 0;
        var terms = 0;
        for (var smallest = smallest(cursors, live); smallest >= 0; smallest = smallest(cursors, live)) {
            var withPositions = infos[smallest].positions();
            var length = cursors[smallest].length();
            if (term.length < length) {
                term = new byte[Math.max(length, term.length * 2)];
            }
            System.arraycopy(cursors[smallest].term(), 0, term, 0, length);
            termDocs = 0;
            termPositions = 0;
            for (var i = smallest; i < cursors.length; i++) {
                if (live[i] && Arrays.equals(cursors[i].term(), 0, cursors[i].length(), term, 0, length)) {
                    inputs.get(i).segment().postings(iterator, infos[i], cursors[i].termInfo(), withPositions);
                    totalLength += collect(docMaps.get(i), withPositions);
                    live[i] = cursors[i].next();
                }
            }
            if (termDocs > 0) {
                writer.addTerm(term, length, docs, freqs, termDocs, withPositions ? positions : null);
            }
            if (++terms % CHECK_INTERVAL == 0 && stop.getAsBoolean()) {
                return -1;
            }
        }
        return totalLength;
    }

    // Appends the iterator's remaining documents to the term's postings; returns the sum of their frequencies.
    private long collect(int[] map, boolean withPositions) {
        long sum = 0;
        for (var doc = iterator.nextDoc(); doc != DocIterator.NO_MORE_DOCS; doc = iterator.nextDoc()) {
            if (map[doc] < 0) {
                continue;
            }
            var freq = iterator.freq();
            if (termDocs == docs.length) {
                docs = Arrays.copyOf(docs, termDocs * 2);
                freqs = Arrays.copyOf(freqs, termDocs * 2);
            }
            docs[termDocs] = map[doc];
            freqs[termDocs] = freq;
            termDocs++;
            sum += freq;
            if (withPositions) {
                addPositions(freq);
            }
        }
        return sum;
    }

    // Returns the number of documents with a norm, that is with a non-empty value.
    private static long copyNorms(FieldInfo[] infos, List<Input> inputs, List<int[]> docMaps, byte[] norms) {
        long count = 0;
        for (var i = 0; i < infos.length; i++) {
            var info = infos[i];
            if (info == null) {
                continue;
            }
            var segment = inputs.get(i).segment();
            var map = docMaps.get(i);
            for (var doc = 0; doc < map.length; doc++) {
                if (map[doc] >= 0) {
                    var norm = segment.norm(info, doc);
                    norms[map[doc]] = (byte) norm;
                    if (norm != 0) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // The cursor on the smallest term, the first of equal ones, or -1 when every cursor is done.
    private static int smallest(TermDictionary.Cursor[] cursors, boolean[] live) {
        var smallest = -1;
        for (var i = 0; i < cursors.length; i++) {
            if (live[i]
                    && (smallest < 0
                            || Arrays.compareUnsigned(
                                            cursors[i].term(),
                                            0,
                                            cursors[i].length(),
                                            cursors[smallest].term(),
                                            0,
                                            cursors[smallest].length())
                                    < 0)) {
                smallest = i;
            }
        }
        return smallest;
    }

    private void addPositions(int freq) {
        if (docPositions.length < freq) {
            docPositions = new int[Math.max(freq, docPositions.length * 2)];
        }
        iterator.readPositions(docPositions);
        if (positions.length < termPositions + freq) {
            positions = Arrays.copyOf(positions, Math.max(termPositions + freq, positions.length * 2));
        }
        System.arraycopy(docPositions, 0, positions, termPositions, freq);
        termPositions += freq;
    }

    private void mergeStored(Segment segment, int[] map, SegmentWriter writer) {
        var reader = segment.storedFields();
        checkState(
                reader.fields().equals(storedFields), "Stored fields %s differ from %s", reader.fields(), storedFields);
        for (var block = 0; block < reader.blockCount(); block++) {
            var first = reader.firstDoc(block);
            var end = block + 1 < reader.blockCount() ? reader.firstDoc(block + 1) : segment.maxDoc();
            var kept = 0;
            for (var doc = first; doc < end; doc++) {
                if (map[doc] >= 0) {
                    kept++;
                }
            }
            if (kept == end - first) {
                writer.addStoredBlock(reader.rawBlock(block), kept);
            } else if (kept > 0) {
                var bytes = reader.inflate(block, inflater);
                var in = new DataInput(MemorySegment.ofArray(bytes), 0);
                var count = in.readVInt();
                var sizes = new int[count];
                for (var i = 0; i < count; i++) {
                    sizes[i] = in.readVInt();
                }
                var offset = (int) in.position();
                for (var i = 0; i < count; i++) {
                    if (map[first + i] >= 0) {
                        writer.addStored(bytes, offset, sizes[i]);
                    }
                    offset += sizes[i];
                }
            }
        }
    }

    /// A segment to merge.
    ///
    /// @param segment the segment
    /// @param deletions its documents to drop
    public record Input(Segment segment, Deletions deletions) {}
}
