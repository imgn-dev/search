package be.imgn.search.internal.segment;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.DataInput;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A document's stored fields: its language and its stored values in input order.
///
/// Encoded as the document's language, then the number of values, then per value the index of its field in the
/// segment's list of stored fields, a byte telling where its language comes from (the document's, its own that
/// follows, or none), and its text. Strings are UTF-8 after their length; a language is its primary subtag, the
/// empty string for none.
///
/// @param language the document's language, or `null`
/// @param values the stored values
public record StoredRecord(@Nullable String language, List<Value> values) {

    /// The value has the document's language.
    public static final int INHERITED = 0;

    /// The value has its own language, which follows.
    public static final int OWN = 1;

    /// The value has no language, as a keyword.
    public static final int NONE = 2;

    /// Copies the values.
    public StoredRecord {
        values = List.copyOf(values);
    }

    /// Writes the start of a record.
    ///
    /// @param out the receiver
    /// @param language the document's language, or `null`
    /// @param valueCount the number of values that will follow
    public static void writeHeader(ByteOutput out, @Nullable String language, int valueCount) {
        out.writeString(language == null ? "" : language);
        out.writeVInt(valueCount);
    }

    /// Writes a value of a record.
    ///
    /// @param out the receiver
    /// @param field the index of the value's field in the segment's stored fields
    /// @param value the value
    /// @param languageKind [#INHERITED], [#OWN] or [#NONE]
    /// @param language the value's own language when `languageKind` is [#OWN]
    public static void writeValue(
            ByteOutput out, int field, String value, int languageKind, @Nullable String language) {
        writeValueStart(out, field, languageKind, language);
        out.writeString(value);
    }

    /// Writes a value of a record but its text, which the caller writes next, as a string.
    ///
    /// @param out the receiver
    /// @param field the index of the value's field in the segment's stored fields
    /// @param languageKind [#INHERITED], [#OWN] or [#NONE]
    /// @param language the value's own language when `languageKind` is [#OWN]
    public static void writeValueStart(ByteOutput out, int field, int languageKind, @Nullable String language) {
        out.writeVInt(field);
        out.writeByte(languageKind);
        if (languageKind == OWN && language != null) {
            out.writeString(language);
        }
    }

    static StoredRecord read(DataInput in, List<String> fields) {
        var language = language(in.readString());
        var count = in.readVInt();
        var values = new ArrayList<Value>(count);
        for (var i = 0; i < count; i++) {
            var field = fields.get(in.readVInt());
            var kind = in.readUnsignedByte();
            var valueLanguage = switch (kind) {
                case INHERITED -> language;
                case OWN -> language(in.readString());
                default -> null;
            };
            values.add(new Value(field, in.readString(), valueLanguage));
        }
        return new StoredRecord(language, values);
    }

    private static @Nullable String language(String language) {
        return language.isEmpty() ? null : language;
    }

    /// A stored value.
    ///
    /// @param field the field's name
    /// @param value the value
    /// @param language the value's language, or `null`
    public record Value(
            String field, String value, @Nullable String language) {}
}
