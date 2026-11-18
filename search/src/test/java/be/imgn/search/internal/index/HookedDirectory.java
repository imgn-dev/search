package be.imgn.search.internal.index;

import be.imgn.search.internal.store.Directory;
import be.imgn.search.internal.store.FsDirectory;
import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

/// A directory of the file system that calls a hook before and after each operation, so that a test can fail an
/// operation or hold a thread at a given point.
class HookedDirectory implements Directory {

    private final FsDirectory real;

    HookedDirectory(Path path) {
        this.real = new FsDirectory(path);
    }

    /// Called before an operation; may throw to fail it.
    ///
    /// @param operation the operation: `list`, `create`, `write`, `read`, `map`, `force`, `rename`, `delete` or
    ///     `forceDirectory`
    /// @param name the file's name, the target of a rename, or empty
    void before(String operation, String name) throws IOException {}

    /// Called after an operation that succeeded; may throw.
    ///
    /// @param operation the operation
    /// @param name the file's name, the target of a rename, or empty
    void after(String operation, String name) throws IOException {}

    @Override
    public List<String> list() throws IOException {
        before("list", "");
        var names = real.list();
        after("list", "");
        return names;
    }

    @Override
    public WritableFile create(String name) throws IOException {
        before("create", name);
        var file = real.create(name);
        after("create", name);
        return new WritableFile() {
            @Override
            public void write(ByteBuffer buffer) throws IOException {
                before("write", name);
                file.write(buffer);
                after("write", name);
            }

            @Override
            public void force() throws IOException {
                before("force", name);
                file.force();
                after("force", name);
            }

            @Override
            public void close() throws IOException {
                file.close();
            }
        };
    }

    @Override
    public byte[] read(String name) throws IOException {
        before("read", name);
        var bytes = real.read(name);
        after("read", name);
        return bytes;
    }

    @Override
    public MemorySegment map(String name, Arena arena) throws IOException {
        before("map", name);
        var data = real.map(name, arena);
        after("map", name);
        return data;
    }

    @Override
    public void force(String name) throws IOException {
        before("force", name);
        real.force(name);
        after("force", name);
    }

    @Override
    public void rename(String source, String target) throws IOException {
        before("rename", target);
        real.rename(source, target);
        after("rename", target);
    }

    @Override
    public void delete(String name) throws IOException {
        before("delete", name);
        real.delete(name);
        after("delete", name);
    }

    @Override
    public void forceDirectory() throws IOException {
        before("forceDirectory", "");
        real.forceDirectory();
        after("forceDirectory", "");
    }

    @Override
    public Closeable lock(String name) throws IOException {
        return real.lock(name);
    }
}
