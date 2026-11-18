package be.imgn.search;

import static java.util.Objects.requireNonNull;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/// A stored value of a text field, with the language it was indexed in.
///
/// @param value the text
/// @param language the value's language as its primary language subtag, given or detected, or `null` if it had
///     none
/// @since 0.1.0
public record StoredText(String value, @Nullable Locale language) {

    /// Checks the value and normalizes its language.
    ///
    /// @throws NullPointerException if `value` is `null`
    /// @throws IllegalArgumentException if `language` has no language subtag
    public StoredText {
        requireNonNull(value, "value");
        language = Languages.normalizeNullable(language);
    }
}
