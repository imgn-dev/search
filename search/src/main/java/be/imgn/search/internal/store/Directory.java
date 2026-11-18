package be.imgn.search.internal.store;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/// The files of a directory, by name: every file operation of a file index goes through it, so that tests can
/// replace it with one that fails or simulates a power cut. Implementations are thread-safe.
public interface Directory {

    /// The names of the files.
    ///
    /// @return the names, in no particular order
    /// @throws IOException if the directory cannot be listed
    List<String> list() throws IOException;

    /// Creates a file to write, which must not exist.
    ///
    /// @param name the file's name
    /// @return the file, to close
    /// @throws IOException if the file exists or cannot be created
    WritableFile create(String name) throws IOException;

    /// Reads a whole file.
    ///
    /// @param name the file's name
    /// @return its bytes
    /// @throws IOException if the file cannot be read
    byte[] read(String name) throws IOException;

    /// Maps a whole file, read only, until `arena` closes.
    ///
    /// @param name the file's name
    /// @param arena the arena of the mapping
    /// @return the mapped bytes
    /// @throws IOException if the file cannot be mapped
    MemorySegment map(String name, Arena arena) throws IOException;

    /// Forces a closed file's content to the storage device.
    ///
    /// @param name the file's name
    /// @throws IOException if the file cannot be forced
    void force(String name) throws IOException;

    /// Renames a file atomically, replacing the target if it exists.
    ///
    /// @param source the file's name
    /// @param target its new name
    /// @throws IOException if the file cannot be renamed
    void rename(String source, String target) throws IOException;

    /// Deletes a file, if it exists.
    ///
    /// @param name the file's name
    /// @throws IOException if the file exists and cannot be deleted
    void delete(String name) throws IOException;

    /// Forces the directory's entries to the storage device, so that the files created, renamed and deleted so far
    /// survive a power cut. Does nothing where a directory cannot be opened.
    ///
    /// @throws IOException if the directory cannot be forced
    void forceDirectory() throws IOException;

    /// Locks a file, which is created if missing, for writing, until the returned lock closes.
    ///
    /// @param name the file's name
    /// @return the lock
    /// @throws be.imgn.search.IndexLockedException if another process or this one holds the lock
    /// @throws IOException if the file cannot be created or locked
    Closeable lock(String name) throws IOException;

    /// A file being written.
    interface WritableFile extends Closeable {

        /// Writes all the remaining bytes of a buffer at the end of the file.
        ///
        /// @param buffer the bytes
        /// @throws IOException if the bytes cannot be written
        void write(ByteBuffer buffer) throws IOException;

        /// Forces what was written to the storage device.
        ///
        /// @throws IOException if the file cannot be forced
        void force() throws IOException;
    }
}
