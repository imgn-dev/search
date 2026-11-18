package be.imgn.search.internal.codec;

import java.nio.charset.StandardCharsets;

/// A receiver of bytes that numbers are written into in little-endian order: an array on the heap, or a file.
/// Not thread-safe.
///
/// A file output reports a failure to write as an `UncheckedIOException`, so that the encoders need not declare
/// it.
public abstract class Output {

    /// Writes a byte.
    ///
    /// @param value the byte, in its low 8 bits
    public abstract void writeByte(int value);

    /// Writes an `int` in 4 bytes.
    ///
    /// @param value the value
    public abstract void writeInt(int value);

    /// Writes a `long` in 8 bytes.
    ///
    /// @param value the value
    public abstract void writeLong(long value);

    /// Writes an `int`, read as unsigned, in 1 to 5 bytes: 7 bits per byte, low bits first, the high bit set on
    /// every byte but the last.
    ///
    /// @param value the value
    public abstract void writeVInt(int value);

    /// Writes a `long`, read as unsigned, in 1 to 10 bytes, as [#writeVInt] does.
    ///
    /// @param value the value
    public abstract void writeVLong(long value);

    /// Writes bytes of an array.
    ///
    /// @param source the array
    /// @param offset the index of the first byte
    /// @param length the number of bytes
    public abstract void writeBytes(byte[] source, int offset, int length);

    /// Writes the bytes written into a heap output.
    ///
    /// @param other the other output
    public final void writeBytes(ByteOutput other) {
        writeBytes(other.array(), 0, other.size());
    }

    /// The number of bytes written.
    ///
    /// @return the offset of the next byte
    public abstract long position();

    /// The CRC32C checksum of the bytes written.
    ///
    /// @return the checksum
    public abstract int checksum();

    /// Writes every byte written to this output into another.
    ///
    /// @param target the receiver
    public abstract void writeTo(Output target);

    /// Writes a string as its length in UTF-8 bytes, [variable-length][#writeVInt], then those bytes.
    ///
    /// @param value the string
    public final void writeString(String value) {
        var utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVInt(utf8.length);
        writeBytes(utf8, 0, utf8.length);
    }
}
