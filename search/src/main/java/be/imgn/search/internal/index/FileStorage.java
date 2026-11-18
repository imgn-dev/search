package be.imgn.search.internal.index;

import static be.imgn.gaia.core.base.Preconditions.checkState;

import be.imgn.search.CorruptIndexException;
import be.imgn.search.Schema;
import be.imgn.search.internal.codec.CorruptDataException;
import be.imgn.search.internal.codec.Format;
import be.imgn.search.internal.codec.Layouts;
import be.imgn.search.internal.segment.Deletions;
import be.imgn.search.internal.segment.Segment;
import be.imgn.search.internal.store.Directory;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// The storage of a file index: a directory of segment files, deletion files, one manifest and a lock.
///
/// - A segment file, `segment-<id>`, is written once, without `force`, and mapped into an `Arena.ofShared()`;
///   its deletions go to `segment-<id>.del-<generation>`, a new generation whenever a commit finds new ones.
/// - A commit forces the files it lists and the directory, writes `manifest-<n+1>` to a temporary file, forces
///   it, renames it atomically and forces the directory; then it releases the previous manifest's files and
///   deletes it. A writer that ended, by closing or failing, never commits.
/// - One counter holds the references to every file: from the last committed manifest, from each snapshot a
///   searcher or the index holds, and from the writer's segments. A file whose count drops to zero is unmapped
///   then deleted, unless the storage is closed: then it is only unmapped.
/// - A writer, when it opens, deletes the files that nothing references and the manifest does not list: what a
///   crash or a failed writer left. When the last commit failed after renaming its manifest, which may thus be
///   the newest on disk, the writer first reads the newest manifest again. It waits for a commit in progress,
///   such as one of a writer that failed meanwhile on another thread.
///
/// The index holds the lock on `write.lock` from [#open] to [#close()]. Thread-safe.
public final class FileStorage implements Storage {

    /// The largest segment a merge makes in a file index.
    static final long MAX_SEGMENT_BYTES = 5L << 30;

    /// The suffix of temporary files, which a writer deletes when it opens.
    static final String TEMPORARY = ".tmp";

    private static final String LOCK = "write.lock";
    private static final String SEGMENT = "segment-";
    private static final String DELETIONS = ".del-";
    // Every file the storage writes but the manifests and the lock, temporary ones included. Numbers are written
    // without leading zeros and fit in a long: a file named otherwise is not the storage's, and is left alone.
    private static final Pattern SEGMENT_FILE = Pattern.compile("segment-(" + Manifest.NUMBER + ")(\\..*)?");
    private static final Pattern SEGMENT_OR_DELETIONS =
            Pattern.compile("segment-" + Manifest.NUMBER + "(\\.del-" + Manifest.NUMBER + ")?");

    private final Directory directory;
    private final Closeable lock;
    private final List<Schema.FieldDefinition> fields;
    private final Map<String, String> analyzers;
    // Serializes commits with the opening of writers, which clean the directory up, and the closing of the
    // storage: a writer that replaces a failed one waits for that one's commit in flight. Taken before this.
    private final ReentrantLock commitLock = new ReentrantLock();

    // Guarded by this.
    private final Map<String, Integer> references = new HashMap<>();
    private final Map<String, SegmentFile> byName = new HashMap<>();
    private final IdentityHashMap<Segment, SegmentFile> bySegment = new IdentityHashMap<>();
    // The references of a commit that failed after renaming its manifest, which may be the newest on disk.
    private final List<String> uncertain = new ArrayList<>();
    private Commit committed;
    private long nextId;
    private boolean closed;

    private FileStorage(
            Directory directory,
            Closeable lock,
            List<Schema.FieldDefinition> fields,
            Map<String, String> analyzers,
            List<String> names)
            throws IOException {
        this.directory = directory;
        this.lock = lock;
        this.fields = List.copyOf(fields);
        this.analyzers = Map.copyOf(analyzers);
        var generation = newest(names);
        Manifest manifest;
        if (generation < 0) {
            for (var name : names) {
                if (!name.equals(LOCK) && !name.endsWith(TEMPORARY)) {
                    throw new IOException("The directory holds " + name + ", but no index manifest");
                }
            }
            // Leftovers of a creation that failed.
            for (var name : names) {
                if (name.endsWith(TEMPORARY)) {
                    directory.delete(name);
                }
            }
            manifest = new Manifest(0, fields, analyzers, List.of());
            var name = Manifest.fileName(0);
            writeFile(name + TEMPORARY, manifest.encode());
            directory.rename(name + TEMPORARY, name);
            directory.forceDirectory();
        } else {
            manifest = readManifest(generation);
            manifest.checkSchema(fields, analyzers);
        }
        this.committed = load(manifest);
        this.nextId = nextId(names, 0);
    }

    /// Opens the index of a directory, or creates one in a directory that holds nothing but a lock or temporary
    /// files, and locks it.
    ///
    /// @param directory the directory
    /// @param fields the schema's fields
    /// @param analyzers the identifier of each of the schema's language analyzers, by language
    /// @return the storage
    /// @throws be.imgn.search.IndexLockedException if the index is locked
    /// @throws CorruptIndexException if the newest manifest, a deletion file, or the header or footer of a
    ///     segment file is corrupt
    /// @throws IllegalArgumentException if the schema does not fit the index: see [Manifest#checkSchema]
    /// @throws IOException if the directory holds other files but no manifest, or cannot be read or written
    public static FileStorage open(
            Directory directory, List<Schema.FieldDefinition> fields, Map<String, String> analyzers)
            throws IOException {
        var held = directory.lock(LOCK);
        var opened = false;
        try {
            var storage = new FileStorage(directory, held, fields, analyzers, directory.list());
            opened = true;
            return storage;
        } finally {
            if (!opened) {
                held.close();
            }
        }
    }

    @Override
    public synchronized Snapshot snapshot() {
        var snapshot = committed.snapshot();
        retain(snapshot);
        return snapshot;
    }

    @Override
    public Opened newWriter(Schema schema, Snapshot published, long budget) throws IOException {
        commitLock.lock();
        try {
            boolean reload;
            synchronized (this) {
                checkState(!closed, "The index is closed");
                reload = !uncertain.isEmpty();
            }
            if (reload) {
                reload();
            }
            directory.forceDirectory();
            cleanUp();
            Snapshot start;
            synchronized (this) {
                start = committed.snapshot();
            }
            var session = new Session(start);
            retain(start);
            return new Opened(new IndexWriter(schema, start, budget, session), start);
        } finally {
            commitLock.unlock();
        }
    }

    @Override
    public synchronized void retain(Snapshot snapshot) {
        // Every segment is looked up first, so that a failure takes no reference.
        var names = new ArrayList<String>(snapshot.segments().size());
        for (var entry : snapshot.segments()) {
            names.add(fileOf(entry.segment()).name);
        }
        names.forEach(this::reference);
    }

    @Override
    public void release(Snapshot snapshot) {
        var names = new ArrayList<String>(snapshot.segments().size());
        synchronized (this) {
            for (var entry : snapshot.segments()) {
                names.add(fileOf(entry.segment()).name);
            }
        }
        releaseFiles(names);
    }

    @Override
    public void close() throws IOException {
        var names = new ArrayList<String>();
        commitLock.lock();
        try {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                names.addAll(committed.files());
                names.addAll(uncertain);
                uncertain.clear();
            }
        } finally {
            commitLock.unlock();
        }
        try (lock) {
            releaseFiles(names);
        }
    }

    // Reads the newest manifest on disk, after a commit that failed once its manifest was renamed.
    private void reload() throws IOException {
        var names = directory.list();
        var loaded = load(readManifest(newest(names)));
        var forced = false;
        try {
            // The manifest just read must survive a power cut before the files of the others may go.
            directory.forceDirectory();
            forced = true;
        } finally {
            if (!forced) {
                releaseFiles(loaded.files());
            }
        }
        var dropped = new ArrayList<String>();
        synchronized (this) {
            dropped.addAll(committed.files());
            dropped.addAll(uncertain);
            uncertain.clear();
            committed = loaded;
        }
        releaseFiles(dropped);
    }

    // Deletes what nothing references and the manifest does not list.
    private void cleanUp() throws IOException {
        var names = directory.list();
        var obsolete = new ArrayList<String>();
        synchronized (this) {
            nextId = nextId(names, nextId);
            var generation = committed.manifest().generation();
            for (var name : names) {
                var manifest = Manifest.generationOf(name);
                if (name.endsWith(TEMPORARY)
                        || (manifest >= 0 && manifest != generation)
                        || (SEGMENT_OR_DELETIONS.matcher(name).matches() && !references.containsKey(name))) {
                    obsolete.add(name);
                }
            }
        }
        for (var name : obsolete) {
            directory.delete(name);
        }
    }

    // Commits for a writer, unless it ended: a writer that failed must not rename a manifest once a new one may
    // have started.
    private void commit(Snapshot snapshot, Session session) throws IOException {
        commitLock.lock();
        try {
            commitLocked(snapshot, session);
        } finally {
            commitLock.unlock();
        }
    }

    private void commitLocked(Snapshot snapshot, Session session) throws IOException {
        var pending = new ArrayList<String>();
        var written = new ArrayList<Written>();
        Manifest previous;
        synchronized (this) {
            checkState(!closed, "The index is closed");
            checkState(!session.ended, "The writer is closed");
            previous = committed.manifest();
            for (var entry : snapshot.segments()) {
                var name = fileOf(entry.segment()).name;
                reference(name);
                pending.add(name);
            }
        }
        var name = Manifest.fileName(previous.generation() + 1);
        var renaming = false;
        var done = false;
        try {
            var entries = writeFiles(snapshot, pending, written);
            // The entries of the new files must be durable before the manifest that lists them: a rename may
            // persist before the creations that precede it.
            directory.forceDirectory();
            var used = new TreeMap<>(previous.analyzers());
            used.putAll(analyzers);
            var manifest = new Manifest(previous.generation() + 1, fields, used, entries);
            writeFile(name + TEMPORARY, manifest.encode());
            synchronized (this) {
                checkState(!session.ended, "The writer is closed");
            }
            renaming = true;
            directory.rename(name + TEMPORARY, name);
            directory.forceDirectory();
            done = true;
            Commit old;
            synchronized (this) {
                old = committed;
                committed = new Commit(manifest, snapshot, pending);
                for (var deletions : written) {
                    deletions.file.written = deletions.deletions;
                    deletions.file.writtenGeneration = deletions.generation;
                }
            }
            releaseFiles(old.files());
            deleteQuietly(Manifest.fileName(old.manifest().generation()));
        } finally {
            if (!done) {
                if (renaming) {
                    synchronized (this) {
                        uncertain.addAll(pending);
                    }
                } else {
                    releaseFiles(pending);
                    deleteQuietly(name + TEMPORARY);
                }
            }
        }
    }

    // Writes the deletions not yet written and forces them, with the segments not yet forced; returns the entries
    // of the manifest.
    private List<Manifest.Entry> writeFiles(Snapshot snapshot, List<String> pending, List<Written> written)
            throws IOException {
        var entries = new ArrayList<Manifest.Entry>(snapshot.segments().size());
        for (var entry : snapshot.segments()) {
            SegmentFile file;
            synchronized (this) {
                file = fileOf(entry.segment());
            }
            var generation = writeDeletions(file, entry.deletions(), pending, written);
            force(file);
            entries.add(new Manifest.Entry(file.id, file.byteSize, file.checksum, generation));
        }
        return entries;
    }

    // Writes and forces the deletions of a segment if they are new; returns their generation, 0 for none.
    private long writeDeletions(SegmentFile file, Deletions deletions, List<String> pending, List<Written> written)
            throws IOException {
        if (deletions.count() == 0) {
            return 0;
        }
        long generation;
        String name;
        synchronized (this) {
            if (deletions == file.written) {
                name = deletionsName(file.id, file.writtenGeneration);
                reference(name);
                pending.add(name);
                return file.writtenGeneration;
            }
            generation = ++file.lastGeneration;
            name = deletionsName(file.id, generation);
            reference(name);
            pending.add(name);
        }
        writeFile(name, deletions.encode(file.segment.maxDoc()));
        written.add(new Written(file, deletions, generation));
        return generation;
    }

    private void force(SegmentFile file) throws IOException {
        synchronized (this) {
            if (file.forced) {
                return;
            }
        }
        directory.force(file.name);
        synchronized (this) {
            file.forced = true;
        }
    }

    // Maps the segments of a manifest and reads their deletions, then references its files.
    private Commit load(Manifest manifest) throws IOException {
        var entries = new ArrayList<Snapshot.Entry>(manifest.segments().size());
        var files = new ArrayList<String>();
        var mapped = new ArrayList<SegmentFile>();
        var loadedDeletions = new ArrayList<Written>();
        var reused = new ArrayList<String>();
        var loaded = false;
        try {
            var base = 0;
            for (var entry : manifest.segments()) {
                var file = segmentFile(manifest, entry, mapped, reused);
                var deletions = Deletions.NONE;
                if (entry.deletionGeneration() > 0) {
                    var deletionsName = deletionsName(entry.id(), entry.deletionGeneration());
                    deletions = readDeletions(deletionsName);
                    files.add(deletionsName);
                }
                loadedDeletions.add(new Written(file, deletions, entry.deletionGeneration()));
                files.add(file.name);
                entries.add(new Snapshot.Entry(file.segment, deletions, base));
                base += file.segment.maxDoc();
            }
            var snapshot = new Snapshot(entries, base);
            synchronized (this) {
                for (var file : mapped) {
                    byName.put(file.name, file);
                    bySegment.put(file.segment, file);
                }
                files.forEach(this::reference);
                for (var deletions : loadedDeletions) {
                    deletions.file.written = deletions.deletions;
                    deletions.file.writtenGeneration = deletions.generation;
                    deletions.file.lastGeneration = Math.max(deletions.file.lastGeneration, deletions.generation);
                }
            }
            loaded = true;
            return new Commit(manifest, snapshot, files);
        } finally {
            if (!loaded) {
                mapped.forEach(SegmentFile::close);
            }
            releaseFiles(reused);
        }
    }

    // The file of a segment of a manifest, already open or mapped now, checked against what the manifest records.
    private SegmentFile segmentFile(
            Manifest manifest, Manifest.Entry entry, List<SegmentFile> mapped, List<String> reused) throws IOException {
        var name = SEGMENT + entry.id();
        SegmentFile file;
        synchronized (this) {
            file = byName.get(name);
            if (file != null) {
                reference(name);
                reused.add(name);
            }
        }
        if (file == null) {
            var arena = Arena.ofShared();
            try {
                file = new SegmentFile(directory, name, entry.id(), true, arena);
                mapped.add(file);
            } finally {
                if (file == null) {
                    arena.close();
                }
            }
        }
        if (file.byteSize != entry.byteSize() || file.checksum != entry.checksum()) {
            throw new CorruptIndexException(String.format(
                    Locale.ROOT,
                    "%s has %d bytes and checksum %08x, but %s records %d bytes and checksum %08x",
                    name,
                    file.byteSize,
                    file.checksum,
                    Manifest.fileName(manifest.generation()),
                    entry.byteSize(),
                    entry.checksum()));
        }
        return file;
    }

    private Deletions readDeletions(String name) throws IOException {
        var bytes = directory.read(name);
        try {
            return Deletions.decode(MemorySegment.ofArray(bytes));
        } catch (CorruptDataException | IndexOutOfBoundsException e) {
            throw new CorruptIndexException(name + " is corrupt: " + e.getMessage(), e);
        }
    }

    private Manifest readManifest(long generation) throws IOException {
        return Manifest.decode(directory.read(Manifest.fileName(generation)), generation);
    }

    // Writes a new file and forces it.
    private void writeFile(String name, byte[] bytes) throws IOException {
        try (var file = directory.create(name)) {
            file.write(ByteBuffer.wrap(bytes));
            file.force();
        }
    }

    // Called under this.
    private void reference(String name) {
        references.merge(name, 1, Integer::sum);
    }

    // Unmaps then deletes the files no longer referenced; only unmaps them once the storage is closed.
    private void releaseFiles(Collection<String> names) {
        var unmapped = new ArrayList<SegmentFile>();
        var obsolete = new ArrayList<String>();
        synchronized (this) {
            for (var name : names) {
                var count = references.get(name);
                if (count == null) {
                    throw new IllegalStateException("File " + name + " is not referenced");
                }
                if (count > 1) {
                    references.put(name, count - 1);
                    continue;
                }
                references.remove(name);
                var file = byName.remove(name);
                if (file != null) {
                    bySegment.remove(file.segment);
                    unmapped.add(file);
                }
                if (!closed) {
                    obsolete.add(name);
                }
            }
        }
        unmapped.forEach(SegmentFile::close);
        obsolete.forEach(this::deleteQuietly);
    }

    // A file that cannot be deleted now is deleted when the next writer opens.
    private void deleteQuietly(String name) {
        try {
            directory.delete(name);
        } catch (IOException ignored) {
            // Left for the next writer to delete.
        }
    }

    // Called under this.
    private SegmentFile fileOf(Segment segment) {
        var file = bySegment.get(segment);
        if (file == null) {
            throw new IllegalStateException("The segment has no file");
        }
        return file;
    }

    private static long newest(List<String> names) {
        long newest = -1;
        for (var name : names) {
            newest = Math.max(newest, Manifest.generationOf(name));
        }
        return newest;
    }

    // The identifier after those of the files of the directory, and at least `atLeast`.
    private static long nextId(List<String> names, long atLeast) {
        var next = atLeast;
        for (var name : names) {
            var matcher = SEGMENT_FILE.matcher(name);
            if (matcher.matches()) {
                next = Math.max(next, Long.parseLong(matcher.group(1)) + 1);
            }
        }
        return next;
    }

    private static String deletionsName(long id, long generation) {
        return SEGMENT + id + DELETIONS + generation;
    }

    /// A commit: its manifest, its snapshot and the files it references.
    ///
    /// @param manifest the manifest
    /// @param snapshot the snapshot
    /// @param files the segment and deletion files the manifest lists
    private record Commit(Manifest manifest, Snapshot snapshot, List<String> files) {}

    /// Deletions of a segment file, under a generation.
    ///
    /// @param file the segment file
    /// @param deletions the deletions
    /// @param generation their generation
    private record Written(SegmentFile file, Deletions deletions, long generation) {}

    // A mapped segment file, opened. Its mutable fields are guarded by the storage.
    private static final class SegmentFile implements AutoCloseable {

        private final String name;
        private final long id;
        private final Segment segment;
        private final Arena arena;
        private final long byteSize;
        private final int checksum;
        private boolean forced;
        // The deletions last written, in a file of that generation, and the last generation used.
        private Deletions written = Deletions.NONE;
        private long writtenGeneration;
        private long lastGeneration;

        // Maps the file into an arena of its own, which it takes over once constructed, and checks the segment's
        // header and footer.
        SegmentFile(Directory directory, String name, long id, boolean forced, Arena arena) throws IOException {
            this.name = name;
            this.id = id;
            this.forced = forced;
            this.arena = arena;
            var data = directory.map(name, arena);
            if (data.byteSize() < Format.HEADER_SIZE + Format.CHECKSUM_SIZE) {
                throw new CorruptIndexException(name + " is truncated to " + data.byteSize() + " bytes");
            }
            this.byteSize = data.byteSize();
            this.checksum = data.get(Layouts.INT, data.byteSize() - Format.CHECKSUM_SIZE);
            try {
                this.segment = Segment.open(data);
            } catch (CorruptDataException | IndexOutOfBoundsException e) {
                throw new CorruptIndexException(name + " is corrupt: " + e.getMessage(), e);
            }
        }

        // Unmaps the file: reading the segment throws from now on.
        @Override
        public void close() {
            arena.close();
        }
    }

    // The files of a writer: the segments it holds a reference to, until it drops them or closes.
    private final class Session implements SegmentFiles {

        // Guarded by the storage.
        private final Set<String> held = new HashSet<>();
        private boolean ended;

        Session(Snapshot start) {
            synchronized (FileStorage.this) {
                for (var entry : start.segments()) {
                    var name = fileOf(entry.segment()).name;
                    reference(name);
                    held.add(name);
                }
            }
        }

        @Override
        public long maxSegmentBytes() {
            return MAX_SEGMENT_BYTES;
        }

        @Override
        public @Nullable Segment write(boolean large, Job job) throws IOException {
            long id;
            synchronized (FileStorage.this) {
                checkState(!ended, "The writer is closed");
                id = nextId++;
            }
            var name = SEGMENT + id;
            var sink = new FileSink(directory, name, large);
            var kept = false;
            try {
                if (!job.write(sink)) {
                    return null;
                }
                sink.close(true);
                kept = true;
            } catch (UncheckedIOException e) {
                throw e.getCause();
            } finally {
                if (!kept) {
                    closeQuietly(sink);
                }
            }
            var registered = false;
            try {
                var segment = register(name, id);
                registered = true;
                return segment;
            } finally {
                if (!registered) {
                    deleteQuietly(name);
                }
            }
        }

        // Maps a segment file the writer wrote, and references it for the writer.
        private Segment register(String name, long id) throws IOException {
            var registered = false;
            SegmentFile file;
            var arena = Arena.ofShared();
            try {
                file = new SegmentFile(directory, name, id, false, arena);
                synchronized (FileStorage.this) {
                    checkState(!ended, "The writer is closed");
                    byName.put(name, file);
                    bySegment.put(file.segment, file);
                    reference(name);
                    held.add(name);
                }
                registered = true;
            } finally {
                if (!registered) {
                    arena.close();
                }
            }
            return file.segment;
        }

        @Override
        public void retain(Snapshot snapshot) {
            FileStorage.this.retain(snapshot);
        }

        @Override
        public void release(Snapshot snapshot) {
            FileStorage.this.release(snapshot);
        }

        @Override
        public void release(Segment segment) {
            String name;
            synchronized (FileStorage.this) {
                var file = bySegment.get(segment);
                if (file == null || !held.remove(file.name)) {
                    return;
                }
                name = file.name;
            }
            releaseFiles(List.of(name));
        }

        @Override
        public void commit(Snapshot snapshot) throws IOException {
            FileStorage.this.commit(snapshot, this);
        }

        @Override
        public void close() {
            List<String> names;
            synchronized (FileStorage.this) {
                if (ended) {
                    return;
                }
                ended = true;
                names = new ArrayList<>(held);
                held.clear();
            }
            FileStorage.this.releaseFiles(names);
        }

        private void closeQuietly(FileSink sink) {
            try {
                sink.close(false);
            } catch (IOException ignored) {
                // Left for the next writer to delete.
            }
        }
    }
}
