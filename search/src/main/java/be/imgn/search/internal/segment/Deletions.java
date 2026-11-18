package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.DataInput;
import be.imgn.search.internal.codec.Format;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// The deleted documents of a segment, as a bitset. Immutable: deleting more documents makes a new generation,
/// so a searcher keeps the one it started with.
///
/// The bitset is split into pages of 4 KB that generations share: a new generation copies only the pages it
/// changes, so deleting a few documents of a large segment costs little. A page without deletions is a shared
/// page of zeros.
///
/// Encoded as a header, the number of documents of the segment, the number of deleted ones, the bitset as
/// little-endian `long`s, and a checksum.
public final class Deletions {

    /// No document deleted.
    public static final Deletions NONE = new Deletions(new long[0][], 0);

    private static final int FOOTER_SIZE = Format.CHECKSUM_SIZE;
    private static final int LOG2_LONG_BITS = 6;
    private static final int LOG2_PAGE_LONGS = 9;
    private static final int PAGE_LONGS = 1 << LOG2_PAGE_LONGS;
    private static final int LOG2_PAGE_BITS = LOG2_PAGE_LONGS + LOG2_LONG_BITS;
    private static final int WORD_MASK = PAGE_LONGS - 1;
    private static final long[] ZEROS = new long[PAGE_LONGS];

    private final long[][] pages;
    private final int count;

    private Deletions(long[][] pages, int count) {
        this.pages = pages;
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
        var words = words(maxDoc);
        var pages = new long[pageCount(maxDoc)][];
        for (var p = 0; p < pages.length; p++) {
            var page = new long[PAGE_LONGS];
            var nonZero = false;
            for (var i = 0; i < PAGE_LONGS && (p << LOG2_PAGE_LONGS) + i < words; i++) {
                page[i] = in.readLong();
                nonZero |= page[i] != 0;
            }
            pages[p] = nonZero ? page : ZEROS;
        }
        return new Deletions(pages, count);
    }

    /// Whether a document is deleted.
    ///
    /// @param doc the document
    /// @return whether it is deleted
    public boolean isDeleted(int doc) {
        var page = doc >>> LOG2_PAGE_BITS;
        return page < pages.length && (pages[page][(doc >>> LOG2_LONG_BITS) & WORD_MASK] & (1L << doc)) != 0;
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
        long[][] copy = null;
        boolean[] copied = null;
        var added = 0;
        for (var i = 0; i < length; i++) {
            var doc = docs[i];
            var page = doc >>> LOG2_PAGE_BITS;
            var word = (doc >>> LOG2_LONG_BITS) & WORD_MASK;
            if (copy == null || copied == null) {
                if (isDeleted(doc)) {
                    continue;
                }
                copy = Arrays.copyOf(pages, pageCount(maxDoc));
                for (var p = pages.length; p < copy.length; p++) {
                    copy[p] = ZEROS;
                }
                copied = new boolean[copy.length];
            }
            if ((copy[page][word] & (1L << doc)) != 0) {
                continue;
            }
            if (!copied[page]) {
                copy[page] = copy[page].clone();
                copied[page] = true;
            }
            copy[page][word] |= 1L << doc;
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
            var page = i >>> LOG2_PAGE_LONGS;
            out.writeLong(page < pages.length ? pages[page][i & WORD_MASK] : 0);
        }
        Format.writeChecksum(out);
        return out.toByteArray();
    }

    private static int words(int maxDoc) {
        return (maxDoc + Long.SIZE - 1) / Long.SIZE;
    }

    private static int pageCount(int maxDoc) {
        return (words(maxDoc) + PAGE_LONGS - 1) >>> LOG2_PAGE_LONGS;
    }
}
