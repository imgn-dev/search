package be.imgn.search.internal.index;

import static be.imgn.gaia.core.base.Preconditions.checkState;

import be.imgn.search.Document;
import be.imgn.search.Schema;
import be.imgn.search.internal.segment.Deletions;
import be.imgn.search.internal.segment.Segment;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/// Adds and deletes documents, and turns them into segments and snapshots. Single-threaded: the caller
/// serializes its calls.
///
/// Documents go into a [DocumentBuffer], which becomes a segment at a refresh or when it exceeds the memory budget.
/// A delete, or the replacement an add does, applies at once to the buffer, and is buffered by key for the
/// segments: the next refresh resolves it against every segment older than it, by generation, so it never touches
/// the segment its own buffer becomes.
public final class IndexWriter {

    /// The memory budget of the buffer.
    public static final long DEFAULT_BUDGET = 64L << 20;

    private final long budget;
    private final DocumentIndexer indexer;
    private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    private final SegmentFlusher flusher;
    private final List<LiveSegment> segments = new ArrayList<>();
    // Per key, the generation of the first segment the delete does not apply to.
    private final Map<String, Long> deletes = new HashMap<>();
    private DocumentBuffer buffer = new DocumentBuffer();
    private long nextGeneration;
    private long segmentDocs;

    /// A writer that continues from a snapshot.
    ///
    /// @param schema the schema documents are analysed with
    /// @param snapshot the current snapshot
    /// @param budget the memory budget of the buffer, in bytes
    public IndexWriter(Schema schema, Snapshot snapshot, long budget) {
        this.budget = budget;
        this.indexer = new DocumentIndexer(schema);
        this.flusher = new SegmentFlusher(deflater, indexer.storedFieldNames());
        for (var entry : snapshot.segments()) {
            segments.add(new LiveSegment(entry.segment(), nextGeneration++, entry.deletions()));
            segmentDocs += entry.segment().maxDoc();
        }
    }

    /// Adds a document that the schema has checked, replacing any with the same key.
    ///
    /// @param document the document
    /// @throws IllegalStateException if the index already holds `Integer.MAX_VALUE` documents; an exception from
    ///     the analysis, such as a language analyzer's, leaves the writer as it was
    public void add(Document document) {
        checkState(
                segmentDocs + buffer.docCount() < Integer.MAX_VALUE,
                "An index holds at most %s documents, deleted ones included",
                Integer.MAX_VALUE);
        // Indexing first: if it fails, the delete must not be buffered either.
        indexer.index(document, buffer);
        if (!segments.isEmpty()) {
            deletes.put(document.key(), nextGeneration);
        }
        if (buffer.bytesUsed() > budget) {
            flushBuffer();
        }
    }

    /// Deletes the documents with a key.
    ///
    /// @param key the key
    public void delete(String key) {
        buffer.delete(key);
        if (!segments.isEmpty()) {
            deletes.put(key, nextGeneration);
        }
    }

    /// Turns the buffer into a segment and resolves the buffered deletes.
    ///
    /// @return the snapshot of every change so far
    public Snapshot refresh() {
        flushBuffer();
        resolveDeletes();
        segments.removeIf(live -> live.deletions.count() == live.segment.maxDoc());
        var entries = new ArrayList<Snapshot.Entry>(segments.size());
        var base = 0;
        for (var live : segments) {
            entries.add(new Snapshot.Entry(live.segment, live.deletions, base));
            base += live.segment.maxDoc();
        }
        segmentDocs = base;
        return new Snapshot(entries, base);
    }

    /// Releases the compressor.
    public void close() {
        deflater.end();
    }

    private void flushBuffer() {
        if (buffer.docCount() == 0) {
            return;
        }
        var bytes = flusher.flush(buffer);
        buffer = new DocumentBuffer();
        if (bytes != null) {
            var segment = Segment.open(MemorySegment.ofArray(bytes));
            segments.add(new LiveSegment(segment, nextGeneration++, Deletions.NONE));
            segmentDocs += segment.maxDoc();
        }
    }

    private void resolveDeletes() {
        if (deletes.isEmpty()) {
            return;
        }
        var keys = new ArrayList<byte[]>(deletes.size());
        var limits = new long[deletes.size()];
        for (var delete : deletes.entrySet()) {
            limits[keys.size()] = delete.getValue();
            keys.add(delete.getKey().getBytes(StandardCharsets.UTF_8));
        }
        var docs = new int[keys.size()];
        for (var live : segments) {
            var count = 0;
            for (var i = 0; i < keys.size(); i++) {
                if (live.generation < limits[i]) {
                    var key = keys.get(i);
                    var doc = live.segment.document(key, key.length);
                    if (doc >= 0) {
                        docs[count++] = doc;
                    }
                }
            }
            live.deletions = live.deletions.with(docs, count, live.segment.maxDoc());
        }
        deletes.clear();
    }

    private static final class LiveSegment {

        private final Segment segment;
        private final long generation;
        private Deletions deletions;

        LiveSegment(Segment segment, long generation, Deletions deletions) {
            this.segment = segment;
            this.generation = generation;
            this.deletions = deletions;
        }
    }
}
