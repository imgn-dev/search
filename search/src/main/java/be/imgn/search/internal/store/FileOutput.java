package be.imgn.search.internal.store;

import be.imgn.search.internal.codec.Output;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/// An output into a new file of a [Directory], through a buffer. A failure to write throws an
/// `UncheckedIOException`. Not thread-safe.
public final class FileOutput extends Output implements Closeable {

    private static final int BUFFER_SIZE = 64 << 10;
    private static final int SEVEN_BITS = 0x7F;
    private static final int CONTINUATION = 0x80;
    private static final int PAYLOAD_BITS = 7;
    private static final int MAX_VLONG_BYTES = 10;
    private static final long INT_MASK = 0xFFFF_FFFFL;
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final Directory directory;
    private final String name;
    private final Directory.WritableFile file;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final CRC32C crc = new CRC32C();
    private int used;
    private long flushed;
    private boolean closed;

    /// Creates the file.
    ///
    /// @param directory the directory
    /// @param name the file's name, which must not exist
    /// @throws IOException if the file cannot be created
    public FileOutput(Directory directory, String name) throws IOException {
        this.directory = directory;
        this.name = name;
        this.file = directory.create(name);
    }

    /// The file's name.
    ///
    /// @return the name
    public String name() {
        return name;
    }

    @Override
    public void writeByte(int value) {
        ensure(1);
        buffer[used++] = (byte) value;
    }

    @Override
    public void writeInt(int value) {
        ensure(Integer.BYTES);
        INT.set(buffer, used, value);
        used += Integer.BYTES;
    }

    @Override
    public void writeLong(long value) {
        ensure(Long.BYTES);
        LONG.set(buffer, used, value);
        used += Long.BYTES;
    }

    @Override
    public void writeVInt(int value) {
        writeVLong(value & INT_MASK);
    }

    @Override
    public void writeVLong(long value) {
        ensure(MAX_VLONG_BYTES);
        var v = value;
        while ((v & ~SEVEN_BITS) != 0) {
            buffer[used++] = (byte) ((v & SEVEN_BITS) | CONTINUATION);
            v >>>= PAYLOAD_BITS;
        }
        buffer[used++] = (byte) v;
    }

    @Override
    public void writeBytes(byte[] source, int offset, int length) {
        var done = 0;
        while (done < length) {
            if (used == buffer.length) {
                flushBuffer();
            }
            var count = Math.min(length - done, buffer.length - used);
            System.arraycopy(source, offset + done, buffer, used, count);
            used += count;
            done += count;
        }
    }

    @Override
    public long position() {
        return flushed + used;
    }

    @Override
    public int checksum() {
        flushBuffer();
        return (int) crc.getValue();
    }

    /// Writes what this output wrote into another, reading the file back.
    @Override
    public void writeTo(Output target) {
        flushBuffer();
        if (flushed == 0) {
            return;
        }
        try (var arena = Arena.ofConfined()) {
            var data = directory.map(name, arena);
            var chunk = new byte[BUFFER_SIZE];
            for (long offset = 0; offset < data.byteSize(); offset += chunk.length) {
                var count = (int) Math.min(chunk.length, data.byteSize() - offset);
                MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, chunk, 0, count);
                target.writeBytes(chunk, 0, count);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Writes what is buffered and closes the file. Closing a closed output does nothing.
    ///
    /// @throws IOException if the file cannot be written or closed
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (file) {
            flushBuffer();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void ensure(int more) {
        if (used + more > buffer.length) {
            flushBuffer();
        }
    }

    private void flushBuffer() {
        if (used == 0) {
            return;
        }
        try {
            file.write(ByteBuffer.wrap(buffer, 0, used));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        crc.update(buffer, 0, used);
        flushed += used;
        used = 0;
    }
}
