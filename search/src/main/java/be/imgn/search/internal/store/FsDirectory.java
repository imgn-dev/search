package be.imgn.search.internal.store;

import be.imgn.search.IndexLockedException;
import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// A directory of the file system.
///
/// @param path the directory, which exists
public record FsDirectory(Path path) implements Directory {

    // The lock files this process holds a lock on.
    private static final Set<Path> LOCKED = ConcurrentHashMap.newKeySet();

    @Override
    public List<String> list() throws IOException {
        var names = new ArrayList<String>();
        try (var stream = Files.newDirectoryStream(path)) {
            for (var file : stream) {
                names.add(file.getFileName().toString());
            }
        }
        return names;
    }

    @Override
    public WritableFile create(String name) throws IOException {
        var channel = FileChannel.open(path.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new WritableFile() {
            @Override
            public void write(ByteBuffer buffer) throws IOException {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }

            @Override
            public void force() throws IOException {
                channel.force(true);
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    @Override
    public byte[] read(String name) throws IOException {
        return Files.readAllBytes(path.resolve(name));
    }

    @Override
    public MemorySegment map(String name, Arena arena) throws IOException {
        try (var channel = FileChannel.open(path.resolve(name), StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
    }

    @Override
    public void force(String name) throws IOException {
        // Writing access: some systems refuse to force a file opened for reading only.
        try (var channel = FileChannel.open(path.resolve(name), StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    @Override
    public void rename(String source, String target) throws IOException {
        Files.move(path.resolve(source), path.resolve(target), StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void delete(String name) throws IOException {
        Files.deleteIfExists(path.resolve(name));
    }

    @Override
    public void forceDirectory() throws IOException {
        forceDirectory(path);
    }

    /// Forces a directory's entries to the storage device, where a directory can be opened: not on Windows.
    ///
    /// @param directory the directory
    /// @throws IOException if the directory cannot be forced
    public static void forceDirectory(Path directory) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(directory, StandardOpenOption.READ);
        } catch (IOException e) {
            return;
        }
        try (channel) {
            channel.force(true);
        }
    }

    /// Locks the file with `FileChannel.tryLock`. The locks this process holds are also tracked by real path,
    /// checked before a channel is opened: on POSIX systems, closing any channel on a file releases every lock
    /// the process holds on it, so a second attempt must not even open one.
    @Override
    public Closeable lock(String name) throws IOException {
        var file = path.toRealPath().resolve(name);
        if (!LOCKED.add(file)) {
            throw new IndexLockedException(path + " is already open in this process");
        }
        var locked = false;
        try {
            var lock = lockChannel(file);
            locked = true;
            return () -> {
                try {
                    lock.close();
                } finally {
                    LOCKED.remove(file);
                }
            };
        } finally {
            if (!locked) {
                LOCKED.remove(file);
            }
        }
    }

    // Takes the lock of the file system on a file, held until the returned lock closes, with its channel.
    private Closeable lockChannel(Path file) throws IOException {
        var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var locked = false;
        try {
            var lock = channel.tryLock();
            if (lock == null) {
                throw new IndexLockedException(path + " is locked by another process");
            }
            locked = true;
            return () -> {
                try (channel) {
                    lock.release();
                }
            };
        } catch (OverlappingFileLockException e) {
            throw new IndexLockedException(path + " is already locked in this process", e);
        } finally {
            if (!locked) {
                channel.close();
            }
        }
    }
}
