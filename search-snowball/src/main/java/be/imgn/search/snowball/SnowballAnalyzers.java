package be.imgn.search.snowball;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;

import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.snowball.internal.SnowballStemmer;
import be.imgn.search.snowball.internal.ext.basqueStemmer;
import be.imgn.search.snowball.internal.ext.catalanStemmer;
import be.imgn.search.snowball.internal.ext.czechStemmer;
import be.imgn.search.snowball.internal.ext.danishStemmer;
import be.imgn.search.snowball.internal.ext.dutchStemmer;
import be.imgn.search.snowball.internal.ext.englishStemmer;
import be.imgn.search.snowball.internal.ext.esperantoStemmer;
import be.imgn.search.snowball.internal.ext.estonianStemmer;
import be.imgn.search.snowball.internal.ext.finnishStemmer;
import be.imgn.search.snowball.internal.ext.frenchStemmer;
import be.imgn.search.snowball.internal.ext.germanStemmer;
import be.imgn.search.snowball.internal.ext.greekStemmer;
import be.imgn.search.snowball.internal.ext.hungarianStemmer;
import be.imgn.search.snowball.internal.ext.irishStemmer;
import be.imgn.search.snowball.internal.ext.italianStemmer;
import be.imgn.search.snowball.internal.ext.lithuanianStemmer;
import be.imgn.search.snowball.internal.ext.norwegianStemmer;
import be.imgn.search.snowball.internal.ext.polishStemmer;
import be.imgn.search.snowball.internal.ext.portugueseStemmer;
import be.imgn.search.snowball.internal.ext.romanianStemmer;
import be.imgn.search.snowball.internal.ext.russianStemmer;
import be.imgn.search.snowball.internal.ext.serbianStemmer;
import be.imgn.search.snowball.internal.ext.spanishStemmer;
import be.imgn.search.snowball.internal.ext.swedishStemmer;
import be.imgn.search.snowball.internal.ext.ukrainianStemmer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/// The language analyzers of the European languages, each the Snowball stemmer of its language, preceded by
/// elision in French, Italian, Catalan and Irish: `l'arbre` is analysed as `arbre`.
///
/// The stemmers are generated from one Snowball release, which an analyzer's [id][LanguageAnalyzer#id()] names:
/// `snowball-<release>/<algorithm>`, such as `snowball-3.2.0/french`. An index written with one release refuses
/// to open with the analyzers of another, whose terms may differ.
///
/// The analyzers are immutable and thread-safe; each [word analyzer][LanguageAnalyzer#newWordAnalyzer()] they
/// make holds its own stemmer, for one thread.
///
/// ```java
/// var schema = Schema.builder()
///         .text("body", Positions.KEEP, Accents.KEEP, Stored.NO)
///         .languages(SnowballAnalyzers.all())
///         .build();
/// ```
///
/// @since 0.1.0
public final class SnowballAnalyzers {

    private static final String RELEASE = release();

    private static final List<LanguageAnalyzer> ALL = List.of(
            analyzer("eu", "basque", basqueStemmer::new, Elision.NONE),
            analyzer("ca", "catalan", catalanStemmer::new, new Elision("d", "l", "m", "n", "s", "t")),
            analyzer("cs", "czech", czechStemmer::new, Elision.NONE),
            analyzer("da", "danish", danishStemmer::new, Elision.NONE),
            analyzer("nl", "dutch", dutchStemmer::new, Elision.NONE),
            analyzer("en", "english", englishStemmer::new, Elision.NONE),
            analyzer("eo", "esperanto", esperantoStemmer::new, Elision.NONE),
            analyzer("et", "estonian", estonianStemmer::new, Elision.NONE),
            analyzer("fi", "finnish", finnishStemmer::new, Elision.NONE),
            analyzer(
                    "fr",
                    "french",
                    frenchStemmer::new,
                    new Elision("l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu", "quoiqu", "lorsqu", "puisqu")),
            analyzer("de", "german", germanStemmer::new, Elision.NONE),
            analyzer("el", "greek", greekStemmer::new, Elision.NONE),
            analyzer("hu", "hungarian", hungarianStemmer::new, Elision.NONE),
            analyzer("ga", "irish", irishStemmer::new, new Elision("d", "m", "b")),
            analyzer(
                    "it",
                    "italian",
                    italianStemmer::new,
                    new Elision(
                            "c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell", "gl", "agl", "dagl",
                            "degl", "negl", "sugl", "un", "m", "t", "s", "v", "d")),
            analyzer("lt", "lithuanian", lithuanianStemmer::new, Elision.NONE),
            analyzer("no", "norwegian", norwegianStemmer::new, Elision.NONE),
            analyzer("pl", "polish", polishStemmer::new, Elision.NONE),
            analyzer("pt", "portuguese", portugueseStemmer::new, Elision.NONE),
            analyzer("ro", "romanian", romanianStemmer::new, Elision.NONE),
            analyzer("ru", "russian", russianStemmer::new, Elision.NONE),
            analyzer("sr", "serbian", serbianStemmer::new, Elision.NONE),
            analyzer("es", "spanish", spanishStemmer::new, Elision.NONE),
            analyzer("sv", "swedish", swedishStemmer::new, Elision.NONE),
            analyzer("uk", "ukrainian", ukrainianStemmer::new, Elision.NONE));

    private static final Map<String, LanguageAnalyzer> BY_LANGUAGE = ALL.stream()
            .collect(toUnmodifiableMap(analyzer -> analyzer.language().getLanguage(), identity()));

    private SnowballAnalyzers() {}

    /// The analyzers of all 25 languages: Basque, Catalan, Czech, Danish, Dutch, English, Esperanto, Estonian,
    /// Finnish, French, German, Greek, Hungarian, Irish, Italian, Lithuanian, Norwegian, Polish, Portuguese,
    /// Romanian, Russian, Serbian, Spanish, Swedish and Ukrainian.
    ///
    /// @return the analyzers, in that order; the list is immutable
    public static List<LanguageAnalyzer> all() {
        return ALL;
    }

    /// The analyzer of a language, identified as the index does: by its primary language subtag alone, so `fr-BE`
    /// is `fr`, and with the Norwegian written standards `nb` and `nn` as `no`.
    ///
    /// @param language the language
    /// @return the analyzer, or empty if Snowball has no stemmer for the language here
    /// @throws NullPointerException if `language` is `null`
    /// @throws IllegalArgumentException if `language` has no language subtag, such as [Locale#ROOT]
    public static Optional<LanguageAnalyzer> forLanguage(Locale language) {
        requireNonNull(language, "language");
        var subtag = language.getLanguage();
        if (subtag.isEmpty()) {
            throw new IllegalArgumentException("Locale " + language + " has no language");
        }
        return Optional.ofNullable(BY_LANGUAGE.get(
                switch (subtag) {
                    case "nb", "nn" -> "no";
                    default -> subtag;
                }));
    }

    private static LanguageAnalyzer analyzer(
            String language, String algorithm, Supplier<SnowballStemmer> stemmers, Elision elision) {
        return new SnowballAnalyzer(language, "snowball-" + RELEASE + "/" + algorithm, stemmers, elision);
    }

    /// The Snowball release, which the build writes into `snowball.properties`.
    private static String release() {
        var properties = new Properties();
        try (var in = SnowballAnalyzers.class.getResourceAsStream("snowball.properties")) {
            if (in == null) {
                throw new IllegalStateException("snowball.properties is missing");
            }
            properties.load(new InputStreamReader(in, UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read snowball.properties", e);
        }
        var release = properties.getProperty("version");
        if (release == null || release.isEmpty()) {
            throw new IllegalStateException("snowball.properties has no version");
        }
        return release;
    }
}
