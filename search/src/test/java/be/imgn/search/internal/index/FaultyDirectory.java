package be.imgn.search.internal.index;

import be.imgn.search.internal.store.Directory;
import be.imgn.search.internal.store.FsDirectory;
import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// A directory that fails at its Nth operation, in one of two ways:
///
/// - [Mode#THROW]: the operation throws an `IOException`, and the following ones work. Every operation counts.
/// - [Mode#POWER_CUT]: the operation does not happen, and neither does any later one: they all throw. Only the
///   operations that change the files count: create, write, force, rename, delete and forcing the directory.
///   [#restore()] then leaves the directory as a power cut would.
///
/// The model follows POSIX: a file's content survives once forced, and an entry (creation, rename or deletion)
/// once the directory is forced. The changes to entries since the last force may survive in part: with
/// [Persistence#RENAMES_KEPT], the renames survive and the creations and deletions do not, as when a journal
/// commits a rename while the entries of files created before it are still in memory. The files present when the
/// directory is created count as durable.
///
/// Besides, the first directory force after a file is renamed to a given name may throw, without counting: a
/// commit that fails once its manifest is in place.
final class FaultyDirectory implements Directory {

    private static final String LOCK = "write.lock";

    private final Path path;
    private final FsDirectory real;
    private final Mode mode;
    private final long failAt;
    private final Persistence persistence;
    private final String failForceAfter;
    // Guarded by this.
    private final Map<String, Inode> current = new HashMap<>();
    // The renames since the last directory force.
    private final List<Rename> renames = new ArrayList<>();
    private Map<String, Inode> durable;
    private long operations;
    private boolean crashed;
    private boolean failed;
    private boolean renamedToFailForce;

    /// A directory that fails at an operation.
    ///
    /// @param path the real directory
    /// @param mode how the operation fails
    /// @param failAt the number of the failing operation, from 1; `Long.MAX_VALUE` to count without failing
    /// @param persistence what a power cut keeps of the unforced changes to entries
    /// @param failForceAfter the name whose rename makes the next directory force throw, or empty
    FaultyDirectory(Path path, Mode mode, long failAt, Persistence persistence, String failForceAfter)
            throws IOException {
        this.path = path;
        this.real = new FsDirectory(path);
        this.mode = mode;
        this.failAt = failAt;
        this.persistence = persistence;
        this.failForceAfter = failForceAfter;
        for (var name : real.list()) {
            var inode = new Inode();
            inode.forced = Files.readAllBytes(path.resolve(name));
            current.put(name, inode);
        }
        this.durable = new HashMap<>(current);
    }

    /// Whether the failing operation happened.
    synchronized boolean failed() {
        return failed;
    }

    /// The number of operations counted so far.
    synchronized long operations() {
        return operations;
    }

    /// The names a power cut would leave now.
    synchronized Set<String> durableNames() {
        return Set.copyOf(afterPowerCut().keySet());
    }

    /// Rewrites the real directory as a power cut now would leave it. The index must be closed.
    synchronized void restore() throws IOException {
        var kept = afterPowerCut();
        for (var name : real.list()) {
            if (!LOCK.equals(name)) {
                Files.delete(path.resolve(name));
            }
        }
        for (var entry : kept.entrySet()) {
            if (!LOCK.equals(entry.getKey())) {
                var content = entry.getValue().forced;
                Files.write(path.resolve(entry.getKey()), content == null ? new byte[0] : content);
            }
        }
    }

    @Override
    public List<String> list() throws IOException {
        operation(false);
        return real.list();
    }

    @Override
    public WritableFile create(String name) throws IOException {
        operation(true);
        var file = real.create(name);
        var inode = new Inode();
        synchronized (this) {
            current.put(name, inode);
        }
        return new WritableFile() {
            @Override
            public void write(ByteBuffer buffer) throws IOException {
                operation(true);
                file.write(buffer);
            }

            @Override
            public void force() throws IOException {
                operation(true);
                file.force();
                var content = Files.readAllBytes(path.resolve(name));
                synchronized (FaultyDirectory.this) {
                    inode.forced = content;
                }
            }

            @Override
            public void close() throws IOException {
                file.close();
            }
        };
    }

    @Override
    public byte[] read(String name) throws IOException {
        operation(false);
        return real.read(name);
    }

    @Override
    public MemorySegment map(String name, Arena arena) throws IOException {
        operation(false);
        return real.map(name, arena);
    }

    @Override
    public void force(String name) throws IOException {
        operation(true);
        real.force(name);
        var content = Files.readAllBytes(path.resolve(name));
        synchronized (this) {
            var inode = current.get(name);
            if (inode != null) {
                inode.forced = content;
            }
        }
    }

    @Override
    public void rename(String source, String target) throws IOException {
        operation(true);
        real.rename(source, target);
        synchronized (this) {
            var inode = current.remove(source);
            if (inode != null) {
                current.put(target, inode);
                renames.add(new Rename(source, target, inode));
            }
            renamedToFailForce |= target.equals(failForceAfter);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        operation(true);
        real.delete(name);
        synchronized (this) {
            current.remove(name);
        }
    }

    @Override
    public void forceDirectory() throws IOException {
        synchronized (this) {
            if (renamedToFailForce && !crashed) {
                renamedToFailForce = false;
                throw new IOException("Injected failure after renaming " + failForceAfter);
            }
        }
        operation(true);
        real.forceDirectory();
        synchronized (this) {
            durable = new HashMap<>(current);
            renames.clear();
        }
    }

    @Override
    public Closeable lock(String name) throws IOException {
        return real.lock(name);
    }

    // Called under this: the entries a power cut would leave now, with their files.
    private Map<String, Inode> afterPowerCut() {
        var kept = new HashMap<>(durable);
        if (persistence == Persistence.RENAMES_KEPT) {
            for (var rename : renames) {
                if (kept.get(rename.source()) == rename.inode()) {
                    kept.remove(rename.source());
                }
                kept.put(rename.target(), rename.inode());
            }
        }
        return kept;
    }

    private synchronized void operation(boolean changes) throws IOException {
        if (crashed) {
            throw new IOException("Power cut");
        }
        if (mode == Mode.POWER_CUT && !changes) {
            return;
        }
        operations++;
        if (operations == failAt) {
            failed = true;
            if (mode == Mode.POWER_CUT) {
                crashed = true;
                throw new IOException("Power cut");
            }
            throw new IOException("Injected failure at operation " + operations);
        }
    }

    /// How the failing operation fails.
    enum Mode {
        THROW,
        POWER_CUT
    }

    /// What a power cut keeps of the changes to entries since the last directory force.
    enum Persistence {
        /// Nothing.
        FORCED_ONLY,
        /// The renames, not the creations and deletions.
        RENAMES_KEPT
    }

    /// A rename since the last directory force.
    ///
    /// @param source the old name
    /// @param target the new name
    /// @param inode the file
    private record Rename(String source, String target, Inode inode) {}

    // A file's content as last forced, null if never.
    private static final class Inode {

        private byte @Nullable [] forced;
    }
}
