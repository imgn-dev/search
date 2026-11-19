package be.imgn.search.internal.index;

import static be.imgn.gaia.core.base.Preconditions.checkState;

import be.imgn.search.Document;
import be.imgn.search.Schema;
import be.imgn.search.internal.segment.Deletions;
import be.imgn.search.internal.segment.Segment;
import be.imgn.search.internal.segment.SegmentMerger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/// Adds and deletes documents from several threads, turns them into segments and snapshots, and merges segments
/// in the background. Thread-safe.
///
/// Each add takes a free buffer from a pool, or creates one, fills it alone and puts it back: the analysis runs
/// without a lock, and there are as many buffers as concurrent adds at their peak. When the buffers and the
/// buffered deletes together exceed the memory budget, the largest buffer becomes a segment, written by the thread
/// that puts it back; or, when the deletes outweigh every buffer, that thread refreshes, which resolves them.
///
/// Every add and delete takes a sequence number, and every document keeps its add's: a delete, or the
/// replacement an add does, applies to the documents of its key with a lower number. Operations belong to an
/// epoch, which a refresh ends: its last number is the refresh's cut. An epoch buffers its deletes, and its adds
/// as replacements, by key with the highest number; a buffer that becomes a segment drops the documents an
/// operation of its epoch replaced or deleted, and the refresh resolves them against every segment in one sorted
/// pass over its keys. A segment keeps its documents' numbers until a refresh has resolved every operation older
/// than its newest document: every later one is newer than all its documents, and deletes their keys outright.
///
/// Adds hold a short lock to take and put back their buffer, and run concurrently otherwise; a refresh cuts the
/// epoch under that lock, reserving one number per add in progress so that they stay before the cut, waits for
/// them, and turns the buffers into segments in parallel. The segments have their own lock, which refreshes and
/// the end of merges take, never adds, deletes or searches.
///
/// Segments live in [SegmentFiles]: on the heap, or in files that the writer references from when it makes them,
/// or starts from them, until it drops them. Every snapshot it returns holds a reference, for the caller to
/// release.
///
/// Fail-stop: an exception escaping a flush, a refresh, a commit or a merge closes the writer, an `IOException`
/// among them. The snapshots it made stay as they are, and every later call throws an `IllegalStateException`
/// caused by that exception.
public final class IndexWriter {

    /// The memory budget of the buffers by default.
    public static final long DEFAULT_BUDGET = 64L << 20;

    static final int MERGE_THREADS = 2;
    static final long LARGE_MERGE_BYTES = 256L << 20;

    // The heap a buffered delete takes besides its key's characters: the map's entry, the string and the number.
    private static final long DELETE_OVERHEAD = 96;

    private final Schema schema;
    private final long budget;
    private final TieredMergePolicy policy;
    private final SegmentWriting writing;
    private final SegmentFiles files;
    private final List<String> storedFields;
    // The documents of the segments and buffers, deleted ones included until a merge or a flush drops them.
    // Only adds increase it, under lock.
    private final AtomicLong documents = new AtomicLong();

    // Guards the workers, the epochs, closed and failure.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition returned = lock.newCondition();
    private final List<Worker> workers = new ArrayList<>();
    private final ArrayDeque<Worker> free = new ArrayDeque<>();
    private Epoch epoch = new Epoch(1);
    private boolean closed;
    private @Nullable Throwable failure;

    // Serializes refreshes.
    private final ReentrantLock refreshLock = new ReentrantLock();
    // The flushers of refreshes, which flush buffers in parallel; guarded by itself.
    private final ArrayDeque<SegmentFlusher> flushers = new ArrayDeque<>();

    // Guards the segments and the merges.
    private final ReentrantLock segmentsLock = new ReentrantLock();
    private final Condition mergeEnded = segmentsLock.newCondition();
    private final List<LiveSegment> segments = new ArrayList<>();
    private final ExecutorService merges;
    private int runningMerges;
    private volatile boolean stopMerges;

    /// A writer that continues from a snapshot.
    ///
    /// @param schema the schema documents are analysed with
    /// @param snapshot the current snapshot, whose segments `files` holds references to for the writer
    /// @param budget the memory budget of the buffers and the buffered deletes, in bytes
    /// @param files where the segments live
    public IndexWriter(Schema schema, Snapshot snapshot, long budget, SegmentFiles files) {
        this(
                schema,
                snapshot,
                budget,
                TieredMergePolicy.DEFAULT.withMaxSegmentBytes(files.maxSegmentBytes()),
                SegmentWriting.DEFAULT,
                files);
    }

    /// A writer on the heap that continues from a snapshot.
    ///
    /// @param schema the schema documents are analysed with
    /// @param snapshot the current snapshot, of segments on the heap
    /// @param budget the memory budget of the buffers and the buffered deletes, in bytes
    public IndexWriter(Schema schema, Snapshot snapshot, long budget) {
        this(schema, snapshot, budget, TieredMergePolicy.DEFAULT, SegmentWriting.DEFAULT);
    }

    IndexWriter(Schema schema, Snapshot snapshot, long budget, TieredMergePolicy policy) {
        this(schema, snapshot, budget, policy, SegmentWriting.DEFAULT);
    }

    IndexWriter(Schema schema, Snapshot snapshot, long budget, TieredMergePolicy policy, SegmentWriting writing) {
        this(schema, snapshot, budget, policy, writing, HeapSegments.INSTANCE);
    }

    IndexWriter(
            Schema schema,
            Snapshot snapshot,
            long budget,
            TieredMergePolicy policy,
            SegmentWriting writing,
            SegmentFiles files) {
        this.schema = schema;
        this.budget = budget;
        this.policy = policy;
        this.writing = writing;
        this.files = files;
        this.storedFields = new DocumentIndexer(schema).storedFieldNames();
        this.merges = Executors.newFixedThreadPool(
                MERGE_THREADS,
                Thread.ofPlatform().daemon().name("search-merge-", 0).factory());
        for (var entry : snapshot.segments()) {
            segments.add(new LiveSegment(entry.segment(), entry.deletions(), null, 0));
            documents.addAndGet(entry.segment().maxDoc());
        }
    }

    /// Adds a document that the schema has checked, replacing any with the same key.
    ///
    /// Putting the buffer back may flush one, even after the document failed: when that flush fails too, the
    /// document's exception is thrown, with the flush's suppressed.
    ///
    /// @param document the document
    /// @return the add's sequence number
    /// @throws IllegalStateException if the writer is closed or failed, or the index already holds
    ///     `Integer.MAX_VALUE` documents; an exception from the analysis, such as a language analyzer's or a
    ///     source's, leaves the writer as it was
    /// @throws IOException if a source of the document cannot be read, which leaves the writer as it was, or a
    ///     segment cannot be written
    public long add(Document document) throws IOException {
        Worker worker;
        Epoch current;
        lock.lock();
        try {
            checkOpen();
            checkState(
                    documents.get() < Integer.MAX_VALUE,
                    "An index holds at most %s documents, deleted ones included",
                    Integer.MAX_VALUE);
            worker = take();
            documents.incrementAndGet();
            current = epoch;
            current.active++;
        } finally {
            lock.unlock();
        }
        long seq;
        try (var end = new AddEnd(worker, current)) {
            worker.indexer.analyze(document);
            seq = current.next.getAndIncrement();
            worker.indexer.write(document.key(), seq, worker.buffer);
            current.register(document.key(), seq);
            end.added = true;
        }
        return seq;
    }

    /// Deletes the documents with a key.
    ///
    /// @param key the key
    /// @return the delete's sequence number
    /// @throws IllegalStateException if the writer is closed or failed
    /// @throws IOException if a segment cannot be written
    public long delete(String key) throws IOException {
        long seq;
        boolean refresh;
        lock.lock();
        try {
            checkOpen();
            seq = epoch.next.getAndIncrement();
            epoch.register(key, seq);
            relieve(false);
            refresh = epoch.refreshWanted;
        } finally {
            lock.unlock();
        }
        if (refresh) {
            refreshForMemory();
        }
        return seq;
    }

    /// Makes a snapshot of every operation so far: ends the epoch, waits for the adds in progress, turns every
    /// buffer into a segment and resolves the epoch's deletes. Operations that start meanwhile wait for the next
    /// refresh. Merges that ended since the last refresh become visible.
    ///
    /// @return the snapshot, which the caller holds a reference to
    /// @throws IllegalStateException if the writer is closed or failed
    /// @throws IOException if a segment cannot be written
    public Snapshot refresh() throws IOException {
        return refreshWithCut().snapshot();
    }

    /// Refreshes, as [#refresh()], and makes the snapshot durable as the index's last commit.
    ///
    /// @return the snapshot, which the caller holds a reference to
    /// @throws IllegalStateException if the writer is closed or failed
    /// @throws IOException if a segment or the commit cannot be written
    public Snapshot commit() throws IOException {
        refreshLock.lock();
        try {
            check(false);
            return failStop(this::doCommit);
        } finally {
            refreshLock.unlock();
        }
    }

    /// Refreshes, as [#refresh()], and tells the cut.
    Refreshed refreshWithCut() throws IOException {
        refreshLock.lock();
        try {
            check(false);
            return failStop(this::doRefresh);
        } finally {
            refreshLock.unlock();
        }
    }

    /// Stops the merges, waiting for those in progress to give up, commits, and releases the compressors and the
    /// writer's references to its segments. Adds and deletes fail from now on.
    ///
    /// @return the snapshot of every operation, which the caller holds a reference to
    /// @throws IllegalStateException if the writer failed
    /// @throws IOException if a segment or the commit cannot be written
    public Snapshot close() throws IOException {
        lock.lock();
        try {
            checkState(failure != null || !closed, "The writer is closed");
            closed = true;
        } finally {
            lock.unlock();
        }
        segmentsLock.lock();
        try {
            stopMerges = true;
        } finally {
            segmentsLock.unlock();
        }
        merges.close();
        try {
            return lastCommit();
        } finally {
            endFlushers();
            files.close();
        }
    }

    /// Whether an exception closed the writer.
    ///
    /// @return whether it failed
    public boolean failed() {
        lock.lock();
        try {
            return failure != null;
        } finally {
            lock.unlock();
        }
    }

    /// Waits for the merges in progress to give up, once the writer failed.
    public void awaitStopped() {
        merges.close();
    }

    /// Merges every segment into one on the calling thread, after waiting for the merges in progress. The merged
    /// segment becomes visible at the next refresh.
    void forceMerge() throws IOException {
        var merge = startMergeOfAll();
        if (merge != null) {
            runMerge(merge);
        }
    }

    /// Starts merging every segment, after waiting for the merges in progress: the merge reads the segments with
    /// their current deletions, and [#runMerge] ends it.
    ///
    /// @return the merge, or `null` if there is nothing to merge
    @Nullable
    Merge startMergeOfAll() {
        segmentsLock.lock();
        try {
            while (runningMerges > 0) {
                mergeEnded.awaitUninterruptibly();
            }
            var inputs = segments.stream().filter(LiveSegment::mergeable).toList();
            if (inputs.isEmpty()
                    || (inputs.size() == 1 && inputs.getFirst().deletions.count() == 0)) {
                return null;
            }
            return register(inputs);
        } finally {
            segmentsLock.unlock();
        }
    }

    /// Writes the merged segment, replays onto it the deletes that reached its inputs since the merge started, and
    /// swaps it for them.
    ///
    /// @param merge the merge
    /// @return whether the merge ended, rather than gave up because the writer is closing
    boolean runMerge(Merge merge) throws IOException {
        var swapped = false;
        try {
            var inputs = new ArrayList<SegmentMerger.Input>(merge.inputs.size());
            for (var i = 0; i < merge.inputs.size(); i++) {
                inputs.add(new SegmentMerger.Input(merge.inputs.get(i).segment, merge.deletions.get(i)));
            }
            var docMaps = SegmentMerger.docMaps(inputs);
            var maxDoc = 0;
            for (var map : docMaps) {
                for (var doc : map) {
                    maxDoc = Math.max(maxDoc, doc + 1);
                }
            }
            Segment merged = null;
            if (maxDoc > 0) {
                var size = maxDoc;
                merged = files.write(
                        true, sink -> writing.merge(storedFields, inputs, docMaps, size, () -> stopMerges, sink));
                if (merged == null) {
                    return false;
                }
            }
            swap(merge, merged, docMaps);
            swapped = true;
            return true;
        } finally {
            if (!swapped) {
                segmentsLock.lock();
                try {
                    merge.inputs.forEach(input -> input.merging = false);
                } finally {
                    segmentsLock.unlock();
                }
            }
        }
    }

    /// The number of segments, including those flushed since the last refresh, for tests.
    int segmentCount() {
        segmentsLock.lock();
        lock.lock();
        try {
            return segments.size() + epoch.flushed.size();
        } finally {
            lock.unlock();
            segmentsLock.unlock();
        }
    }

    /// The number of segments flushed since the last refresh, which cannot merge yet, for tests.
    int pendingSegments() {
        lock.lock();
        try {
            return epoch.flushed.size();
        } finally {
            lock.unlock();
        }
    }

    /// The heap the buffered deletes of the current epoch take, for tests.
    long deleteBytes() {
        lock.lock();
        try {
            return epoch.deleteBytes.get();
        } finally {
            lock.unlock();
        }
    }

    /// Waits for the merges in progress, for tests.
    void awaitMerges() {
        segmentsLock.lock();
        try {
            while (runningMerges > 0) {
                mergeEnded.awaitUninterruptibly();
            }
        } finally {
            segmentsLock.unlock();
        }
    }

    private Snapshot lastCommit() throws IOException {
        refreshLock.lock();
        try {
            check(true);
            return failStop(this::doCommit);
        } finally {
            refreshLock.unlock();
        }
    }

    private void check(boolean closing) {
        lock.lock();
        try {
            if (closing) {
                checkNotFailed();
            } else {
                checkOpen();
            }
        } finally {
            lock.unlock();
        }
    }

    // Called under lock.
    private void checkOpen() {
        checkNotFailed();
        checkState(!closed, "The writer is closed");
    }

    // Called under lock.
    private void checkNotFailed() {
        var cause = failure;
        if (cause != null) {
            throw new IllegalStateException("The writer failed", cause);
        }
    }

    // Runs a step whose exception closes the writer, and is thrown again.
    private <T> T failStop(Callable<T> step) throws IOException {
        var task = new FutureTask<>(step);
        task.run();
        if (task.state() == Future.State.FAILED) {
            var cause = task.exceptionNow();
            fail(cause);
            throw rethrow(cause);
        }
        return task.resultNow();
    }

    private void fail(Throwable cause) {
        lock.lock();
        try {
            if (failure == null) {
                failure = cause;
            }
            closed = true;
            returned.signalAll();
        } finally {
            lock.unlock();
        }
        segmentsLock.lock();
        try {
            stopMerges = true;
        } finally {
            segmentsLock.unlock();
        }
        merges.shutdown();
        files.close();
    }

    // Called under lock: a free worker, or a new one.
    private Worker take() {
        var worker = free.pollFirst();
        if (worker == null) {
            worker = new Worker(new DocumentIndexer(schema), writing.newFlusher(storedFields));
            workers.add(worker);
        }
        worker.inUse = true;
        worker.epoch = epoch;
        return worker;
    }

    private void putBack(Worker worker, Epoch current) throws IOException {
        var bytes = worker.buffer.bytesUsed();
        var docs = worker.buffer.docCount();
        @Nullable Worker toFlush;
        boolean refresh;
        lock.lock();
        try {
            current.active--;
            worker.inUse = false;
            if (current != epoch || failure != null) {
                // A refresh ended the epoch during the add: it flushes the buffer. A failed writer flushes nothing.
                if (current != epoch) {
                    if (docs > 0) {
                        current.buffers.add(worker.buffer);
                    }
                    worker.reset();
                }
                free.push(worker);
                returned.signalAll();
                return;
            }
            worker.bytes = bytes;
            worker.docs = docs;
            toFlush = worker.flushPending ? worker : relieve(true);
            if (toFlush != worker) {
                free.push(worker);
            }
            if (toFlush != null) {
                free.remove(toFlush);
                toFlush.inUse = true;
                current.flushing++;
            }
            refresh = current.refreshWanted && !closed;
        } finally {
            lock.unlock();
        }
        if (toFlush != null) {
            refresh |= flushWorker(toFlush, current);
        }
        if (refresh) {
            refreshForMemory();
        }
    }

    // Called under lock: keeps the buffers and deletes of the epoch within the budget. Marks the largest buffer for
    // a flush, and returns it if it is free, for the calling thread to flush; when the deletes outweigh every
    // buffer, the epoch wants a refresh instead.
    private @Nullable Worker relieve(boolean mayFlush) {
        long buffered = 0;
        Worker largest = null;
        for (var worker : workers) {
            if (counts(worker)) {
                buffered += worker.bytes;
                largest = largest == null || worker.bytes > largest.bytes ? worker : largest;
            }
        }
        var deleteBytes = epoch.deleteBytes.get();
        var over = buffered + deleteBytes > budget;
        if (over && (largest == null || largest.bytes <= deleteBytes)) {
            epoch.refreshWanted = true;
            return null;
        }
        if (!over || largest == null || !mayFlush) {
            return null;
        }
        largest.flushPending = true;
        return largest.inUse ? null : largest;
    }

    // Called under lock: whether a worker holds a buffer of the epoch that counts against the budget.
    private boolean counts(Worker worker) {
        return worker.epoch == epoch && worker.docs > 0 && !worker.flushPending;
    }

    // A refresh for the budget, unless one is running, which does the same.
    private void refreshForMemory() throws IOException {
        if (!refreshLock.tryLock()) {
            return;
        }
        try {
            lock.lock();
            try {
                if (closed) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            // Nobody publishes this snapshot.
            files.release(failStop(this::doRefresh).snapshot());
        } finally {
            refreshLock.unlock();
        }
    }

    // Returns whether the epoch wants a refresh.
    private boolean flushWorker(Worker worker, Epoch current) throws IOException {
        var task = new FutureTask<>(() -> listOf(flush(worker.flusher, worker.buffer, current)));
        task.run();
        var failed = task.state() == Future.State.FAILED;
        boolean refresh;
        lock.lock();
        try {
            if (!failed) {
                current.flushed.addAll(task.resultNow());
                // Flushed segments merge only once a refresh has resolved their deletes.
                current.refreshWanted |= current.flushed.size() >= policy.segmentsPerTier();
                worker.reset();
            }
            current.flushing--;
            worker.inUse = false;
            free.push(worker);
            returned.signalAll();
            refresh = current == epoch && current.refreshWanted && !closed;
        } finally {
            lock.unlock();
        }
        if (failed) {
            var cause = task.exceptionNow();
            fail(cause);
            throw rethrow(cause);
        }
        return refresh;
    }

    // A snapshot that fails to commit is released: its caller never gets it.
    private Snapshot doCommit() throws IOException {
        var snapshot = doRefresh().snapshot();
        var committed = false;
        try {
            files.commit(snapshot);
            committed = true;
        } finally {
            if (!committed) {
                files.release(snapshot);
            }
        }
        return snapshot;
    }

    private Refreshed doRefresh() throws IOException {
        Epoch ended;
        var buffers = new ArrayList<DocumentBuffer>();
        var created = new ArrayList<LiveSegment>();
        lock.lock();
        try {
            ended = epoch;
            // Each add in progress takes at most one more number, which must stay before the cut.
            ended.cut = ended.next.get() + ended.active - 1;
            epoch = new Epoch(ended.cut + 1);
            for (var worker : free) {
                if (worker.buffer.docCount() > 0) {
                    buffers.add(worker.buffer);
                    worker.reset();
                }
            }
            while ((ended.active > 0 || ended.flushing > 0) && failure == null) {
                returned.awaitUninterruptibly();
            }
            checkNotFailed();
            buffers.addAll(ended.buffers);
            created.addAll(ended.flushed);
        } finally {
            lock.unlock();
        }
        created.addAll(flushAll(buffers, ended));
        Snapshot snapshot;
        segmentsLock.lock();
        try {
            segments.addAll(created);
            resolveDeletes(ended.deletes, ended.cut);
            snapshot = snapshot();
        } finally {
            segmentsLock.unlock();
        }
        var merging = false;
        try {
            maybeMerge();
            merging = true;
        } finally {
            if (!merging) {
                files.release(snapshot);
            }
        }
        return new Refreshed(snapshot, ended.cut);
    }

    // Flushes the buffers in parallel: this thread and a virtual thread per other buffer.
    private List<LiveSegment> flushAll(List<DocumentBuffer> buffers, Epoch owner) throws IOException {
        var tasks = new ArrayList<FutureTask<List<LiveSegment>>>(buffers.size());
        for (var buffer : buffers) {
            tasks.add(new FutureTask<>(() -> flushPooled(buffer, owner)));
        }
        var helpers = new ArrayList<Thread>();
        for (var i = 1; i < tasks.size(); i++) {
            helpers.add(Thread.ofVirtual().start(tasks.get(i)));
        }
        if (!tasks.isEmpty()) {
            tasks.getFirst().run();
        }
        helpers.forEach(IndexWriter::joinUninterruptibly);
        var created = new ArrayList<LiveSegment>(tasks.size());
        for (var task : tasks) {
            if (task.state() == Future.State.FAILED) {
                throw rethrow(task.exceptionNow());
            }
            created.addAll(task.resultNow());
        }
        return created;
    }

    private List<LiveSegment> flushPooled(DocumentBuffer buffer, Epoch owner) throws IOException {
        SegmentFlusher flusher;
        synchronized (flushers) {
            flusher = flushers.poll();
        }
        if (flusher == null) {
            flusher = writing.newFlusher(storedFields);
        }
        try {
            return listOf(flush(flusher, buffer, owner));
        } finally {
            synchronized (flushers) {
                flushers.push(flusher);
            }
        }
    }

    // Drops the documents that an operation of the epoch replaced or deleted, and writes the others.
    private @Nullable LiveSegment flush(SegmentFlusher flusher, DocumentBuffer buffer, Epoch owner) throws IOException {
        for (var doc = 0; doc < buffer.docCount(); doc++) {
            var latest = owner.deletes.get(buffer.key(doc));
            if (latest != null && latest > buffer.seq(doc)) {
                buffer.drop(doc);
            }
        }
        var segment = files.write(false, sink -> writing.flush(flusher, buffer, sink));
        documents.addAndGet(buffer.liveCount() - buffer.docCount());
        if (segment == null) {
            return null;
        }
        var seqs = new long[buffer.liveCount()];
        var next = 0;
        long maxSeq = 0;
        for (var doc = 0; doc < buffer.docCount(); doc++) {
            if (!buffer.isDeleted(doc)) {
                seqs[next++] = buffer.seq(doc);
                maxSeq = Math.max(maxSeq, buffer.seq(doc));
            }
        }
        return new LiveSegment(segment, Deletions.NONE, seqs, maxSeq);
    }

    // Called under segmentsLock.
    private void resolveDeletes(Map<String, Long> deletes, long cut) {
        if (!deletes.isEmpty()) {
            var keys = new ArrayList<Delete>(deletes.size());
            deletes.forEach((key, seq) -> keys.add(new Delete(key.getBytes(StandardCharsets.UTF_8), seq)));
            keys.sort((a, b) -> Arrays.compareUnsigned(a.key, b.key));
            var docs = new int[keys.size()];
            for (var live : segments) {
                var cursor = live.segment.keys();
                var seqs = live.seqs;
                var count = 0;
                for (var delete : keys) {
                    if (cursor.seek(delete.key, delete.key.length)) {
                        var doc = (int) cursor.values()[0];
                        if (seqs == null || seqs[doc] < delete.seq) {
                            docs[count++] = doc;
                        }
                    }
                }
                live.deletions = live.deletions.with(docs, count, live.segment.maxDoc());
            }
        }
        for (var live : segments) {
            if (live.maxSeq <= cut) {
                live.seqs = null;
            }
        }
    }

    // Called under segmentsLock: drops the segments without live documents, and numbers the others.
    private Snapshot snapshot() {
        var iterator = segments.iterator();
        while (iterator.hasNext()) {
            var live = iterator.next();
            if (!live.merging && live.deletions.count() == live.segment.maxDoc()) {
                iterator.remove();
                documents.addAndGet(-live.segment.maxDoc());
                files.release(live.segment);
            }
        }
        var entries = new ArrayList<Snapshot.Entry>(segments.size());
        var base = 0;
        for (var live : segments) {
            entries.add(new Snapshot.Entry(live.segment, live.deletions, base));
            base += live.segment.maxDoc();
        }
        var snapshot = new Snapshot(entries, base);
        files.retain(snapshot);
        return snapshot;
    }

    // Starts the merges the policy chooses, as long as threads are free: a large merge never takes the last one.
    private void maybeMerge() {
        segmentsLock.lock();
        try {
            while (!stopMerges && runningMerges < MERGE_THREADS) {
                var candidates =
                        segments.stream().filter(LiveSegment::mergeable).toList();
                var sizes = new long[candidates.size()];
                var bytes = new long[candidates.size()];
                for (var i = 0; i < sizes.length; i++) {
                    sizes[i] = candidates.get(i).liveBytes();
                    bytes[i] = candidates.get(i).segment.byteSize();
                }
                var maxBytes = runningMerges + 1 < MERGE_THREADS ? policy.maxSegmentBytes() : LARGE_MERGE_BYTES;
                var chosen = policy.findMerge(sizes, bytes, maxBytes);
                if (chosen.length == 0) {
                    return;
                }
                var inputs = new ArrayList<LiveSegment>(chosen.length);
                for (var i : chosen) {
                    inputs.add(candidates.get(i));
                }
                start(register(inputs));
            }
        } finally {
            segmentsLock.unlock();
        }
    }

    // Called under segmentsLock.
    private void start(Merge merge) {
        runningMerges++;
        var started = false;
        try {
            merges.execute(() -> runInBackground(merge));
            started = true;
        } finally {
            if (!started) {
                runningMerges--;
                merge.inputs.forEach(input -> input.merging = false);
            }
        }
    }

    // A merge that throws closes the writer. The next merges start, or the writer fails, before the end is
    // signalled, so that waiting for the merges waits for them too.
    private void runInBackground(Merge merge) {
        var task = new FutureTask<>(() -> runMerge(merge));
        task.run();
        var cause = task.state() == Future.State.FAILED ? task.exceptionNow() : null;
        segmentsLock.lock();
        try {
            runningMerges--;
            if (cause == null) {
                var next = new FutureTask<>(() -> {
                    maybeMerge();
                    return true;
                });
                next.run();
                cause = next.state() == Future.State.FAILED ? next.exceptionNow() : null;
            }
            if (cause != null) {
                fail(cause);
            }
            mergeEnded.signalAll();
        } finally {
            segmentsLock.unlock();
        }
    }

    // Called under segmentsLock.
    private Merge register(List<LiveSegment> inputs) {
        var deletions = new ArrayList<Deletions>(inputs.size());
        for (var input : inputs) {
            input.merging = true;
            deletions.add(input.deletions);
        }
        return new Merge(List.copyOf(inputs), List.copyOf(deletions));
    }

    private void swap(Merge merge, @Nullable Segment merged, List<int[]> docMaps) {
        segmentsLock.lock();
        try {
            var replayed = new int[0];
            var count = 0;
            long dropped = 0;
            for (var i = 0; i < merge.inputs.size(); i++) {
                var input = merge.inputs.get(i);
                dropped += input.segment.maxDoc();
                var before = merge.deletions.get(i);
                if (input.deletions == before) {
                    continue;
                }
                var map = docMaps.get(i);
                for (var doc = 0; doc < map.length; doc++) {
                    if (input.deletions.isDeleted(doc) && !before.isDeleted(doc)) {
                        if (count == replayed.length) {
                            replayed = Arrays.copyOf(replayed, count * 2 + 1);
                        }
                        replayed[count++] = map[doc];
                    }
                }
            }
            var position = segments.indexOf(merge.inputs.getFirst());
            segments.removeAll(merge.inputs);
            merge.inputs.forEach(input -> files.release(input.segment));
            if (merged != null) {
                var deletions = Deletions.NONE.with(replayed, count, merged.maxDoc());
                segments.add(position, new LiveSegment(merged, deletions, null, 0));
                dropped -= merged.maxDoc();
            }
            documents.addAndGet(-dropped);
        } finally {
            segmentsLock.unlock();
        }
    }

    // Waits for the adds in progress, which may still use their flushers after a failure, then ends the flushers.
    private void endFlushers() {
        lock.lock();
        try {
            while (workers.stream().anyMatch(worker -> worker.inUse)) {
                returned.awaitUninterruptibly();
            }
            workers.forEach(worker -> worker.flusher.end());
        } finally {
            lock.unlock();
        }
        synchronized (flushers) {
            flushers.forEach(SegmentFlusher::end);
            flushers.clear();
        }
    }

    private static List<LiveSegment> listOf(@Nullable LiveSegment segment) {
        return segment == null ? List.of() : List.of(segment);
    }

    // The exception, to throw: unchecked ones and I/O exceptions as they are, those an output wraps unwrapped.
    private static RuntimeException rethrow(Throwable cause) throws IOException {
        if (cause instanceof IOException e) {
            throw e;
        }
        if (cause instanceof UncheckedIOException e) {
            throw e.getCause();
        }
        if (cause instanceof RuntimeException e) {
            return e;
        }
        if (cause instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(cause);
    }

    private static void joinUninterruptibly(Thread thread) {
        var interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// A refresh's snapshot and cut.
    ///
    /// @param snapshot the snapshot: every operation up to the cut
    /// @param cut the number of the last operation of the snapshot
    record Refreshed(Snapshot snapshot, long cut) {}

    /// A merge in progress: its segments, and their deletions when it started.
    ///
    /// @param inputs the segments, in their order in the index
    /// @param deletions their deletions when the merge started
    record Merge(List<LiveSegment> inputs, List<Deletions> deletions) {}

    // A delete or replacement of a key, as a refresh resolves it.
    private static final class Delete {

        private final byte[] key;
        private final long seq;

        Delete(byte[] key, long seq) {
            this.key = key;
            this.seq = seq;
        }
    }

    /// A segment of the writer.
    static final class LiveSegment {

        private final Segment segment;
        private final long maxSeq;
        private Deletions deletions;
        // The sequence number of each document, until no unresolved operation can be older than one of them.
        private long @Nullable [] seqs;
        private boolean merging;

        LiveSegment(Segment segment, Deletions deletions, long @Nullable [] seqs, long maxSeq) {
            this.segment = segment;
            this.deletions = deletions;
            this.seqs = seqs;
            this.maxSeq = maxSeq;
        }

        private boolean mergeable() {
            return !merging && seqs == null;
        }

        // The bytes of the segment, less the share of its deleted documents.
        private long liveBytes() {
            var maxDoc = segment.maxDoc();
            return maxDoc == 0 ? 0 : segment.byteSize() * (maxDoc - deletions.count()) / maxDoc;
        }
    }

    // The operations between two refreshes. Guarded by lock, except next, deletes and deleteBytes, which adds
    // update without it.
    private static final class Epoch {

        private final AtomicLong next;
        // Per key, the highest number of a delete or add.
        private final Map<String, Long> deletes = new ConcurrentHashMap<>();
        private final AtomicLong deleteBytes = new AtomicLong();
        private final List<DocumentBuffer> buffers = new ArrayList<>();
        private final List<LiveSegment> flushed = new ArrayList<>();
        private long cut = Long.MAX_VALUE;
        private int active;
        private int flushing;
        private boolean refreshWanted;

        Epoch(long first) {
            this.next = new AtomicLong(first);
        }

        void register(String key, long seq) {
            if (deletes.putIfAbsent(key, seq) == null) {
                deleteBytes.addAndGet(key.length() * 2L + DELETE_OVERHEAD);
            } else {
                deletes.merge(key, seq, Math::max);
            }
        }
    }

    // The end of an add, which puts its worker back. As a resource, it keeps the failure of an add as the exception
    // thrown, with a failure to put the worker back, from a flush that fails the writer, suppressed.
    private final class AddEnd implements AutoCloseable {

        private final Worker worker;
        private final Epoch current;
        private boolean added;

        AddEnd(Worker worker, Epoch current) {
            this.worker = worker;
            this.current = current;
        }

        @Override
        public void close() throws IOException {
            if (!added) {
                documents.decrementAndGet();
            }
            putBack(worker, current);
        }
    }

    // A buffer with what fills it and writes it; a worker serves one thread at a time.
    private static final class Worker {

        private final DocumentIndexer indexer;
        private final SegmentFlusher flusher;
        private DocumentBuffer buffer = new DocumentBuffer();
        private @Nullable Epoch epoch;
        private long bytes;
        private int docs;
        private boolean inUse;
        private boolean flushPending;

        Worker(DocumentIndexer indexer, SegmentFlusher flusher) {
            this.indexer = indexer;
            this.flusher = flusher;
        }

        void reset() {
            buffer = new DocumentBuffer();
            bytes = 0;
            docs = 0;
            flushPending = false;
        }
    }
}
