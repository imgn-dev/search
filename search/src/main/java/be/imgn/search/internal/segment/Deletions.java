package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.DataInput;
import be.imgn.search.internal.codec.Format;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// The deleted documents of a segment, as a bitset. Immutable: deleting more documents makes a new generation,
/// so a searcher keeps the one it started with.
///
/// Encoded as a header, the number of documents of the segment, the number of deleted ones, the bitset as
/// little-endian `long`s, and a checksum.
public final class Deletions {

    /// No document deleted.
    public static final Deletions NONE = new Deletions(new long[0], 0);

    private static final int FOOTER_SIZE = Format.CHECKSUM_SIZE;
    private static final int LOG2_LONG_BITS = 6;

    private final long[] bits;
    private final int count;

    private Deletions(long[] bits, int count) {
        this.bits = bits;
        this.count = count;
    }

    /// Reads deletions written by [#encode].
    ///
    /// @param data the bytes
    /// @return the deletions
    /// Throws a `CorruptDataException` if the header or the checksum is wrong.
    public static Deletions decode(MemorySegment data) {
        Format.checkHeader(data, Format.DELETIONS_MAGIC, FOOTER_SIZE);
        Format.checkChecksum(data);
        var in = new DataInput(data, Format.HEADER_SIZE);
        var maxDoc = in.readVInt();
        var count = in.readVInt();
        var bits = new long[words(maxDoc)];
        for (var i = 0; i < bits.length; i++) {
            bits[i] = in.readLong();
        }
        return new Deletions(bits, count);
    }

    /// Whether a document is deleted.
    ///
    /// @param doc the document
    /// @return whether it is deleted
    public boolean isDeleted(int doc) {
        var word = doc >>> LOG2_LONG_BITS;
        return word < bits.length && (bits[word] & (1L << doc)) != 0;
    }

    /// The number of deleted documents.
    ///
    /// @return the number
    public int count() {
        return count;
    }

    /// These deletions plus some documents.
    ///
    /// @param docs the documents to delete, possibly deleted already
    /// @param length the number of documents in `docs`
    /// @param maxDoc the number of documents of the segment
    /// @return the new deletions, or this if nothing changes
    public Deletions with(int[] docs, int length, int maxDoc) {
        long[] copy = null;
        var added = 0;
        for (var i = 0; i < length; i++) {
            var doc = docs[i];
            if (isDeleted(doc) || (copy != null && (copy[doc >>> LOG2_LONG_BITS] & (1L << doc)) != 0)) {
                continue;
            }
            if (copy == null) {
                copy = Arrays.copyOf(bits, words(maxDoc));
            }
            copy[doc >>> LOG2_LONG_BITS] |= 1L << doc;
            added++;
        }
        return copy == null ? this : new Deletions(copy, count + added);
    }

    /// Encodes these deletions.
    ///
    /// @param maxDoc the number of documents of the segment
    /// @return the bytes
    public byte[] encode(int maxDoc) {
        var out = new ByteOutput();
        Format.writeHeader(out, Format.DELETIONS_MAGIC);
        out.writeVInt(maxDoc);
        out.writeVInt(count);
        for (var i = 0; i < words(maxDoc); i++) {
            out.writeLong(i < bits.length ? bits[i] : 0);
        }
        Format.writeChecksum(out);
        return out.toByteArray();
    }

    private static int words(int maxDoc) {
        return (maxDoc + Long.SIZE - 1) / Long.SIZE;
    }
}
