package be.imgn.search.internal.codec;

import static be.imgn.gaia.core.base.Preconditions.checkState;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32C;

/// A growable array of bytes that numbers are written into in little-endian order. Not thread-safe.
public final class ByteOutput {

    /// The largest size: a segment held on the heap is limited to 1 GiB, so that merges never approach the 2 GiB
    /// limit of an array.
    public static final int MAX_SIZE = 1 << 30;

    private static final int INITIAL_CAPACITY = 256;
    private static final int SEVEN_BITS = 0x7F;
    private static final int CONTINUATION = 0x80;
    private static final int PAYLOAD_BITS = 7;
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] bytes;
    private int size;

    /// An empty output.
    public ByteOutput() {
        this(INITIAL_CAPACITY);
    }

    /// An empty output with room for `capacity` bytes.
    ///
    /// @param capacity the initial capacity
    public ByteOutput(int capacity) {
        this.bytes = new byte[capacity];
    }

    /// The number of bytes written.
    ///
    /// @return the size
    public int size() {
        return size;
    }

    /// Forgets the bytes written, keeping the memory.
    public void reset() {
        size = 0;
    }

    /// The backing array; only the first [#size()] bytes are written.
    ///
    /// @return the array, which a later write may replace
    public byte[] array() {
        return bytes;
    }

    /// Writes a byte.
    ///
    /// @param value the byte, in its low 8 bits
    public void writeByte(int value) {
        ensure(1);
        bytes[size++] = (byte) value;
    }

    /// Writes an `int` in 4 bytes.
    ///
    /// @param value the value
    public void writeInt(int value) {
        ensure(Integer.BYTES);
        INT.set(bytes, size, value);
        size += Integer.BYTES;
    }

    /// Writes a `long` in 8 bytes.
    ///
    /// @param value the value
    public void writeLong(long value) {
        ensure(Long.BYTES);
        LONG.set(bytes, size, value);
        size += Long.BYTES;
    }

    /// Writes an `int`, read as unsigned, in 1 to 5 bytes: 7 bits per byte, low bits first, the high bit set on
    /// every byte but the last.
    ///
    /// @param value the value
    public void writeVInt(int value) {
        ensure(Integer.BYTES + 1);
        var v = value;
        while ((v & ~SEVEN_BITS) != 0) {
            bytes[size++] = (byte) ((v & SEVEN_BITS) | CONTINUATION);
            v >>>= PAYLOAD_BITS;
        }
        bytes[size++] = (byte) v;
    }

    /// Writes a `long`, read as unsigned, in 1 to 10 bytes, as [#writeVInt] does.
    ///
    /// @param value the value
    public void writeVLong(long value) {
        ensure(Long.BYTES + 2);
        var v = value;
        while ((v & ~SEVEN_BITS) != 0) {
            bytes[size++] = (byte) ((v & SEVEN_BITS) | CONTINUATION);
            v >>>= PAYLOAD_BITS;
        }
        bytes[size++] = (byte) v;
    }

    /// Writes bytes of an array.
    ///
    /// @param source the array
    /// @param offset the index of the first byte
    /// @param length the number of bytes
    public void writeBytes(byte[] source, int offset, int length) {
        ensure(length);
        System.arraycopy(source, offset, bytes, size, length);
        size += length;
    }

    /// Writes the bytes written into another output.
    ///
    /// @param other the other output
    public void writeBytes(ByteOutput other) {
        writeBytes(other.bytes, 0, other.size);
    }

    /// Writes a string as its length in UTF-8 bytes, [variable-length][#writeVInt], then those bytes.
    ///
    /// @param value the string
    public void writeString(String value) {
        var utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVInt(utf8.length);
        writeBytes(utf8, 0, utf8.length);
    }

    /// The CRC32C checksum of the bytes written.
    ///
    /// @return the checksum
    public int checksum() {
        var crc = new CRC32C();
        crc.update(bytes, 0, size);
        return (int) crc.getValue();
    }

    /// A copy of the bytes written.
    ///
    /// @return a new array of [#size()] bytes
    public byte[] toByteArray() {
        return Arrays.copyOf(bytes, size);
    }

    private void ensure(int more) {
        var needed = (long) size + more;
        if (needed <= bytes.length) {
            return;
        }
        checkState(needed <= MAX_SIZE, "%s bytes exceed the limit of %s", needed, MAX_SIZE);
        var capacity = Math.max(needed, Math.min((long) bytes.length * 2, MAX_SIZE));
        bytes = Arrays.copyOf(bytes, (int) capacity);
    }
}
