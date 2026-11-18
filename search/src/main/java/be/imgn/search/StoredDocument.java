package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// A document read back from an index: its key, its language and the values of its stored fields.
///
/// It is read only, and is not a [Document]: it lacks the fields that are not stored, so adding it back would
/// lose them.
///
/// @since 0.1.0
public final class StoredDocument {

    private final Schema schema;
    private final String key;
    private final @Nullable Locale language;
    // Per stored field present in the document; a keyword value has a null language.
    private final Map<String, List<StoredText>> values;

    StoredDocument(Schema schema, String key, @Nullable Locale language, Map<String, List<StoredText>> values) {
        this.schema = schema;
        this.key = key;
        this.language = language;
        this.values = Map.copyOf(values);
    }

    /// The document's key.
    ///
    /// @return the key
    public String key() {
        return key;
    }

    /// The document's language, given or detected, as its primary language subtag.
    ///
    /// @return the language, or empty if the document had none
    public Optional<Locale> language() {
        return Optional.ofNullable(language);
    }

    /// The stored values of a field, in the order they were added.
    ///
    /// @param name the field's name
    /// @return the values, empty if the document has none
    /// @throws NullPointerException if `name` is `null`
    /// @throws IllegalArgumentException if the schema does not declare `name` as a stored field
    public List<String> values(String name) {
        return stored(name).stream().map(StoredText::value).toList();
    }

    /// The first stored value of a field.
    ///
    /// @param name the field's name
    /// @return the first value, or empty if the document has none
    /// @throws NullPointerException if `name` is `null`
    /// @throws IllegalArgumentException if the schema does not declare `name` as a stored field
    public Optional<String> value(String name) {
        var stored = stored(name);
        return stored.isEmpty()
                ? Optional.empty()
                : Optional.of(stored.getFirst().value());
    }

    /// The stored values of a text field, each with the language it was indexed in.
    ///
    /// @param name the field's name
    /// @return the values, in the order they were added, empty if the document has none
    /// @throws NullPointerException if `name` is `null`
    /// @throws IllegalArgumentException if the schema does not declare `name` as a stored text field
    public List<StoredText> texts(String name) {
        var stored = stored(name);
        checkArgument(
                schema.field(name).orElseThrow() instanceof Schema.TextField,
                "Field %s is a keyword field, which has no languages",
                name);
        return stored;
    }

    private List<StoredText> stored(String name) {
        requireNonNull(name, "name");
        var field = schema.field(name);
        checkArgument(field.isPresent(), "The schema declares no field %s", name);
        checkArgument(field.get().stored() == Stored.YES, "Field %s is not stored", name);
        return values.getOrDefault(name, List.of());
    }

    @Override
    public String toString() {
        return "StoredDocument[key=" + key + ", language=" + language + ", values=" + values + "]";
    }
}
