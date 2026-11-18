package be.imgn.search.internal.index;

import be.imgn.search.Accents;
import be.imgn.search.CorruptIndexException;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.CorruptDataException;
import be.imgn.search.internal.codec.DataInput;
import be.imgn.search.internal.codec.Format;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/// A commit of a file index, in its file `manifest-<generation>`: the schema it records and its live segments,
/// each with its deletions.
///
/// Encoded as a header, the generation, the fields (name, kind, then the options of a text field and whether
/// the values are stored, options as their names), the language analyzers (language and identifier, sorted by
/// language), the segments (identifier, size, checksum and generation of the deletions, 0 for none), and a
/// checksum.
///
/// @param generation the generation, which names the file
/// @param fields the fields, in the order the schema declares them
/// @param analyzers the identifier of each language analyzer the index used, by language
/// @param segments the live segments, in order
record Manifest(
        long generation, List<Schema.FieldDefinition> fields, Map<String, String> analyzers, List<Entry> segments) {

    /// A number in a file name: no leading zero, and at most 18 digits, so that it fits in a `long`.
    static final String NUMBER = "(?:0|[1-9]\\d{0,17})";

    private static final Pattern NAME = Pattern.compile("manifest-(" + NUMBER + ")");
    private static final String PREFIX = "manifest-";
    private static final int TEXT = 0;
    private static final int KEYWORD = 1;

    /// Copies the lists and sorts the analyzers.
    Manifest {
        fields = List.copyOf(fields);
        analyzers = Map.copyOf(new TreeMap<>(analyzers));
        segments = List.copyOf(segments);
    }

    /// The name of a manifest's file.
    ///
    /// @param generation the manifest's generation
    /// @return the name
    static String fileName(long generation) {
        return PREFIX + generation;
    }

    /// The generation of a manifest's file.
    ///
    /// @param name a file's name
    /// @return the generation, or -1 if the file is not a manifest
    static long generationOf(String name) {
        var matcher = NAME.matcher(name);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : -1;
    }

    /// Encodes the manifest.
    ///
    /// @return the bytes of its file
    byte[] encode() {
        var out = new ByteOutput();
        Format.writeHeader(out, Format.MANIFEST_MAGIC);
        out.writeVLong(generation);
        out.writeVInt(fields.size());
        for (var field : fields) {
            out.writeString(field.name());
            switch (field) {
                case Schema.TextField text -> {
                    out.writeByte(TEXT);
                    out.writeString(text.positions().name());
                    out.writeString(text.accents().name());
                }
                case Schema.KeywordField _ -> out.writeByte(KEYWORD);
            }
            out.writeString(field.stored().name());
        }
        var sorted = new TreeMap<>(analyzers);
        out.writeVInt(sorted.size());
        sorted.forEach((language, id) -> {
            out.writeString(language);
            out.writeString(id);
        });
        out.writeVInt(segments.size());
        for (var segment : segments) {
            out.writeVLong(segment.id());
            out.writeVLong(segment.byteSize());
            out.writeInt(segment.checksum());
            out.writeVLong(segment.deletionGeneration());
        }
        Format.writeChecksum(out);
        return out.toByteArray();
    }

    /// Reads a manifest, checking it in full.
    ///
    /// @param bytes the bytes of its file
    /// @param generation the generation its file's name gives
    /// @return the manifest
    /// @throws CorruptIndexException if the manifest is not well-formed, its checksum does not match, or it has
    ///     another generation
    static Manifest decode(byte[] bytes, long generation) throws CorruptIndexException {
        var name = fileName(generation);
        try {
            var data = MemorySegment.ofArray(bytes);
            Format.checkHeader(data, Format.MANIFEST_MAGIC, Format.CHECKSUM_SIZE);
            Format.checkChecksum(data);
            var in = new DataInput(data, Format.HEADER_SIZE);
            var recorded = in.readVLong();
            if (recorded != generation) {
                throw new CorruptIndexException(name + " records generation " + recorded);
            }
            var end = bytes.length - Format.CHECKSUM_SIZE;
            var fieldCount = count(in, end);
            var fields = new ArrayList<Schema.FieldDefinition>(fieldCount);
            for (var i = 0; i < fieldCount; i++) {
                fields.add(readField(in, end));
            }
            var analyzerCount = count(in, end);
            var analyzers = new TreeMap<String, String>();
            for (var i = 0; i < analyzerCount; i++) {
                analyzers.put(string(in, end), string(in, end));
            }
            var segmentCount = count(in, end);
            var segments = new ArrayList<Entry>(segmentCount);
            for (var i = 0; i < segmentCount; i++) {
                segments.add(new Entry(in.readVLong(), in.readVLong(), in.readInt(), in.readVLong()));
            }
            if (in.position() != end) {
                throw new CorruptIndexException(name + " has trailing bytes");
            }
            return new Manifest(generation, fields, analyzers, segments);
        } catch (CorruptDataException | IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new CorruptIndexException(name + " is corrupt: " + e.getMessage(), e);
        }
    }

    /// Checks that a schema reads this index as it was written: the same fields, with the same options and in the
    /// same order, since stored records refer to their fields by position; and, for each analyzer the index used,
    /// one with the same identifier. Analyzers of other languages are accepted.
    ///
    /// @param schemaFields the schema's fields
    /// @param schemaAnalyzers the identifier of each of the schema's analyzers, by language
    /// @throws IllegalArgumentException if the schema does not fit
    void checkSchema(List<Schema.FieldDefinition> schemaFields, Map<String, String> schemaAnalyzers) {
        if (!fields.equals(schemaFields)) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT, "The index has fields %s, but the schema declares %s", fields, schemaFields));
        }
        analyzers.forEach((language, id) -> {
            var given = schemaAnalyzers.get(language);
            if (given == null) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT, "The index used analyzer %s for %s, which the schema lacks", id, language));
            }
            if (!given.equals(id)) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT, "The index used analyzer %s for %s, but the schema has %s", id, language, given));
            }
        });
    }

    // A count of elements, each of at least one byte, so at most the bytes left.
    private static int count(DataInput in, long end) {
        var count = in.readVInt();
        if (count < 0 || count > end - in.position()) {
            throw new CorruptDataException("Count " + Integer.toUnsignedString(count) + " beyond the manifest");
        }
        return count;
    }

    private static String string(DataInput in, long end) {
        var position = in.position();
        var length = in.readVInt();
        if (length < 0 || length > end - in.position()) {
            throw new CorruptDataException(
                    "String of " + Integer.toUnsignedString(length) + " bytes beyond the manifest");
        }
        in.seek(position);
        return in.readString();
    }

    private static Schema.FieldDefinition readField(DataInput in, long end) {
        var name = string(in, end);
        var kind = in.readUnsignedByte();
        if (kind == TEXT) {
            var positions = Positions.valueOf(string(in, end));
            var accents = Accents.valueOf(string(in, end));
            return new Schema.TextField(name, positions, accents, Stored.valueOf(string(in, end)));
        }
        if (kind == KEYWORD) {
            return new Schema.KeywordField(name, Stored.valueOf(string(in, end)));
        }
        throw new CorruptDataException("Unknown kind " + kind + " of field " + name);
    }

    /// A live segment of a commit.
    ///
    /// @param id the segment's identifier, which names its file
    /// @param byteSize the size of its file
    /// @param checksum the checksum that ends its file
    /// @param deletionGeneration the generation of its deletions' file, 0 if it has no deleted document
    record Entry(long id, long byteSize, int checksum, long deletionGeneration) {}
}
