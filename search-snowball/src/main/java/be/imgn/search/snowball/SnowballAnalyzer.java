package be.imgn.search.snowball;

import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.WordAnalyzer;
import be.imgn.search.snowball.internal.SnowballStemmer;
import java.util.Locale;
import java.util.function.Supplier;

/// The analyzer of one language: its elision and its Snowball stemmer. Immutable.
final class SnowballAnalyzer implements LanguageAnalyzer {

    private final Locale language;
    private final String id;
    private final Supplier<SnowballStemmer> stemmers;
    private final Elision elision;

    /// @param language the primary language subtag
    /// @param id the identifier
    /// @param stemmers makes a new stemmer
    /// @param elision the elision of the language
    SnowballAnalyzer(String language, String id, Supplier<SnowballStemmer> stemmers, Elision elision) {
        this.language = Locale.of(language);
        this.id = id;
        this.stemmers = stemmers;
        this.elision = elision;
    }

    @Override
    public Locale language() {
        return language;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WordAnalyzer newWordAnalyzer() {
        return new SnowballWordAnalyzer(stemmers.get(), elision);
    }

    @Override
    public String toString() {
        return id;
    }
}
