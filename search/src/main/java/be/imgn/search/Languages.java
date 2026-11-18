package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/// Turns locales into the form the index routes on: the primary language subtag alone.
final class Languages {

    private Languages() {}

    /// The language of `locale` as the index routes it: `fr-BE` and `fr-CA` become `fr`, and the Norwegian
    /// written standards `nb` and `nn` become `no`, the language Snowball stems.
    ///
    /// @throws IllegalArgumentException if `locale` has no language, such as [Locale#ROOT]
    static Locale normalize(Locale locale) {
        var language = locale.getLanguage();
        checkArgument(!language.isEmpty(), "Locale %s has no language", locale);
        return switch (language) {
            case "nb", "nn" -> Locale.of("no");
            default -> Locale.of(language);
        };
    }

    static @Nullable Locale normalizeNullable(@Nullable Locale locale) {
        return locale == null ? null : normalize(locale);
    }
}
