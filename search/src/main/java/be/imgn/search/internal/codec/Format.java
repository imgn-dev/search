package be.imgn.search.internal.codec;

import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.zip.CRC32C;

/// The header and footer of every encoded file: a magic number and a format version first, a CRC32C checksum of
/// everything before it last.
public final class Format {

    /// The magic number of a segment.
    public static final int SEGMENT_MAGIC = 0x5345_4753;

    /// The magic number of a deletion bitset.
    public static final int DELETIONS_MAGIC = 0x4C45_4453;

    /// The magic number of a manifest.
    public static final int MANIFEST_MAGIC = 0x4E41_4D53;

    /// The current version of the format.
    public static final int VERSION = 1;

    /// The size of the header: magic number and version.
    public static final int HEADER_SIZE = 2 * Integer.BYTES;

    /// The size of the checksum that ends a file.
    public static final int CHECKSUM_SIZE = Integer.BYTES;

    private Format() {}

    /// Writes a header.
    ///
    /// @param out the receiver
    /// @param magic the magic number of the file's kind
    public static void writeHeader(Output out, int magic) {
        out.writeInt(magic);
        out.writeInt(VERSION);
    }

    /// Writes the checksum of everything written so far.
    ///
    /// @param out the receiver
    public static void writeChecksum(Output out) {
        out.writeInt(out.checksum());
    }

    /// Checks the header of a file and that it is long enough to hold a footer of `footerSize` bytes.
    ///
    /// @param data the file
    /// @param magic the magic number of the file's kind
    /// @param footerSize the size of the footer, checksum included
    /// @throws CorruptDataException if the header is wrong or the file too short
    public static void checkHeader(MemorySegment data, int magic, int footerSize) {
        if (data.byteSize() < HEADER_SIZE + footerSize) {
            throw new CorruptDataException("Truncated file of " + data.byteSize() + " bytes");
        }
        var actual = data.get(Layouts.INT, 0);
        if (actual != magic) {
            throw new CorruptDataException(
                    String.format(Locale.ROOT, "Magic number %08x instead of %08x", actual, magic));
        }
        var version = data.get(Layouts.INT, Integer.BYTES);
        if (version != VERSION) {
            throw new CorruptDataException("Unsupported format version " + version);
        }
    }

    /// Checks the checksum that ends a file against its content.
    ///
    /// @param data the file
    /// @throws CorruptDataException if the checksum does not match
    public static void checkChecksum(MemorySegment data) {
        var end = data.byteSize() - CHECKSUM_SIZE;
        var crc = new CRC32C();
        crc.update(data.asSlice(0, end).asByteBuffer());
        var expected = data.get(Layouts.INT, end);
        if ((int) crc.getValue() != expected) {
            throw new CorruptDataException("Checksum mismatch");
        }
    }
}
