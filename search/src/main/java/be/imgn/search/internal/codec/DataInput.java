package be.imgn.search.internal.codec;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/// A cursor that reads what a [ByteOutput] wrote, from a `MemorySegment`. Not thread-safe.
public final class DataInput {

    private static final int SEVEN_BITS = 0x7F;
    private static final int CONTINUATION = 0x80;
    private static final int PAYLOAD_BITS = 7;
    private static final int BYTE_MASK = 0xFF;

    private final MemorySegment data;
    private long position;

    /// A cursor at `position` of `data`.
    ///
    /// @param data the bytes
    /// @param position the offset of the first byte to read
    public DataInput(MemorySegment data, long position) {
        this.data = data;
        this.position = position;
    }

    /// The offset of the next byte to read.
    ///
    /// @return the position
    public long position() {
        return position;
    }

    /// Moves the cursor.
    ///
    /// @param position the offset of the next byte to read
    public void seek(long position) {
        this.position = position;
    }

    /// Reads a byte, as unsigned.
    ///
    /// @return the byte, from 0 to 255
    public int readUnsignedByte() {
        return data.get(Layouts.BYTE, position++) & BYTE_MASK;
    }

    /// Reads an `int` written in 4 bytes.
    ///
    /// @return the value
    public int readInt() {
        var value = data.get(Layouts.INT, position);
        position += Integer.BYTES;
        return value;
    }

    /// Reads a `long` written in 8 bytes.
    ///
    /// @return the value
    public long readLong() {
        var value = data.get(Layouts.LONG, position);
        position += Long.BYTES;
        return value;
    }

    /// Reads a variable-length `int`.
    ///
    /// @return the value
    public int readVInt() {
        var b = data.get(Layouts.BYTE, position++);
        var value = b & SEVEN_BITS;
        var shift = PAYLOAD_BITS;
        while ((b & CONTINUATION) != 0) {
            b = data.get(Layouts.BYTE, position++);
            value |= (b & SEVEN_BITS) << shift;
            shift += PAYLOAD_BITS;
        }
        return value;
    }

    /// Reads a variable-length `long`.
    ///
    /// @return the value
    public long readVLong() {
        var b = data.get(Layouts.BYTE, position++);
        long value = b & SEVEN_BITS;
        var shift = PAYLOAD_BITS;
        while ((b & CONTINUATION) != 0) {
            b = data.get(Layouts.BYTE, position++);
            value |= (long) (b & SEVEN_BITS) << shift;
            shift += PAYLOAD_BITS;
        }
        return value;
    }

    /// Reads bytes into an array.
    ///
    /// @param target the array
    /// @param offset the index of the first byte to set
    /// @param length the number of bytes
    public void readBytes(byte[] target, int offset, int length) {
        MemorySegment.copy(data, Layouts.BYTE, position, target, offset, length);
        position += length;
    }

    /// Reads a string written by [ByteOutput#writeString].
    ///
    /// @return the string
    public String readString() {
        var length = readVInt();
        var bytes = new byte[length];
        readBytes(bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
