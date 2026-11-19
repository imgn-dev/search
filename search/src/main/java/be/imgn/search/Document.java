package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import be.imgn.gaia.core.io.CharSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/// A document to index: a key, an optional language and an ordered list of fields.
///
/// It is not a map: a field may appear several times, such as several tags, and the order of its values is kept.
/// The document's language is the default of its text values; a text value's own language overrides it.
/// Languages are kept as their primary language subtag: `fr-BE` becomes `fr`.
///
/// A document read back from an index is a [StoredDocument], a different type: it holds only the stored fields.
///
/// Keys and keyword values must be well-formed UTF-16, without a lone surrogate: they are stored and compared as
/// UTF-8, where a lone surrogate has no encoding, so two keys differing only by one would collide. Text values may
/// have them: analysis treats them as separators.
///
/// @param key the key, unique in the index: adding a document whose key exists replaces it
/// @param language the language of the text values that give none, or `null` to detect it
/// @param fields the fields, in order
/// @since 0.1.0
public record Document(String key, @Nullable Locale language, List<Field> fields) {

    /// Checks the document, copies the fields and normalizes the language.
    ///
    /// @throws NullPointerException if `key`, `fields` or one of them is `null`
    /// @throws IllegalArgumentException if `key` is empty or not well-formed UTF-16, or `language` has no language
    ///     subtag
    public Document {
        requireNonNull(key, "key");
        checkArgument(!key.isEmpty(), "A document key cannot be empty");
        checkWellFormed(key, "A document key");
        language = Languages.normalizeNullable(language);
        fields = List.copyOf(fields);
    }

    /// A builder of a document, with no language and no field.
    ///
    /// @param key the document's key, unique in the index
    /// @return a new builder
    /// @throws NullPointerException if `key` is `null`
    /// @throws IllegalArgumentException if `key` is empty or not well-formed UTF-16
    public static Builder builder(String key) {
        requireNonNull(key, "key");
        checkArgument(!key.isEmpty(), "A document key cannot be empty");
        checkWellFormed(key, "A document key");
        return new Builder(key);
    }

    // Throws if a string has a lone surrogate, which a code point stream gives as is.
    static void checkWellFormed(String value, String what) {
        checkArgument(
                value.codePoints().noneMatch(c -> c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE),
                "%s has a lone surrogate: %s",
                what,
                value);
    }

    /// A value of a field.
    ///
    /// @since 0.1.0
    public sealed interface Field {

        /// The field's name.
        ///
        /// @return the name
        String name();
    }

    /// A value of a text field, split into words and analysed in its language.
    ///
    /// @param name the field's name
    /// @param value the text
    /// @param language the language of this value, or `null` for the document's
    /// @since 0.1.0
    public record Text(String name, String value, @Nullable Locale language) implements Field {

        /// Checks the value and normalizes its language.
        ///
        /// @throws NullPointerException if `name` or `value` is `null`
        /// @throws IllegalArgumentException if `language` has no language subtag
        public Text {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            language = Languages.normalizeNullable(language);
        }
    }

    /// A value of a text field read from a source of characters, such as a file: it gives the same terms as a
    /// [Text] value of the same content, and the same stored value when the field is stored. It is analysed as it
    /// is read, a block at a time, so the writer never holds the whole text, except as the UTF-8 of the stored
    /// value when the field is stored.
    ///
    /// The source is read only while [Writer#add] runs, and not kept: it is read once to its end, or opened twice
    /// when the document's language is detected and the source reaches into the text the detector reads: the first
    /// 16,384 chars of the document's values without a language, read before any value is indexed, as for [Text]
    /// values. It must give the same characters each time it is opened: when it does not, the language is detected
    /// from the first opening and the value indexed from the second.
    ///
    /// @param name the field's name
    /// @param content the text
    /// @param language the language of this value, or `null` for the document's
    /// @since 0.1.0
    public record TextSource(
            String name, CharSource content, @Nullable Locale language) implements Field {

        /// Checks the value and normalizes its language.
        ///
        /// @throws NullPointerException if `name` or `content` is `null`
        /// @throws IllegalArgumentException if `language` has no language subtag
        public TextSource {
            requireNonNull(name, "name");
            requireNonNull(content, "content");
            language = Languages.normalizeNullable(language);
        }
    }

    /// A value of a keyword field: one term, such as a tag.
    ///
    /// @param name the field's name
    /// @param value the keyword
    /// @since 0.1.0
    public record Keyword(String name, String value) implements Field {

        /// Checks the value.
        ///
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `value` is not well-formed UTF-16
        public Keyword {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            checkWellFormed(value, "A keyword");
        }
    }

    /// A builder of a [Document]. Not thread-safe.
    ///
    /// @since 0.1.0
    public static final class Builder {

        private final String key;
        private @Nullable Locale language;
        private final List<Field> fields = new ArrayList<>();

        private Builder(String key) {
            this.key = key;
        }

        /// Sets the document's language, the default of its text values.
        ///
        /// @param language the language; only its language subtag is kept
        /// @return this builder
        /// @throws NullPointerException if `language` is `null`
        /// @throws IllegalArgumentException if `language` has no language subtag, such as [Locale#ROOT]
        public Builder language(Locale language) {
            this.language = Languages.normalize(requireNonNull(language, "language"));
            return this;
        }

        /// Adds a value to a text field, in the document's language.
        ///
        /// @param name the field's name
        /// @param value the text
        /// @return this builder
        /// @throws NullPointerException if an argument is `null`
        public Builder text(String name, String value) {
            fields.add(new Text(name, value, null));
            return this;
        }

        /// Adds a value to a text field, in its own language.
        ///
        /// @param name the field's name
        /// @param value the text
        /// @param language the value's language; only its language subtag is kept
        /// @return this builder
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `language` has no language subtag
        public Builder text(String name, String value, Locale language) {
            requireNonNull(language, "language");
            fields.add(new Text(name, value, language));
            return this;
        }

        /// Adds a value read from a source to a text field, in the document's language. See [TextSource] for when
        /// the source is read.
        ///
        /// @param name the field's name
        /// @param content the text
        /// @return this builder
        /// @throws NullPointerException if an argument is `null`
        public Builder text(String name, CharSource content) {
            fields.add(new TextSource(name, content, null));
            return this;
        }

        /// Adds a value read from a source to a text field, in its own language. See [TextSource] for when the
        /// source is read.
        ///
        /// @param name the field's name
        /// @param content the text
        /// @param language the value's language; only its language subtag is kept
        /// @return this builder
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `language` has no language subtag
        public Builder text(String name, CharSource content, Locale language) {
            requireNonNull(language, "language");
            fields.add(new TextSource(name, content, language));
            return this;
        }

        /// Adds a value to a keyword field.
        ///
        /// @param name the field's name
        /// @param value the keyword
        /// @return this builder
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `value` is not well-formed UTF-16
        public Builder keyword(String name, String value) {
            fields.add(new Keyword(name, value));
            return this;
        }

        /// Builds the document.
        ///
        /// @return the document
        public Document build() {
            return new Document(key, language, fields);
        }
    }
}
