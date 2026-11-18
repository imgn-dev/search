package be.imgn.search;

import java.util.Locale;

/// A language analyzer for tests: drops a suffix, as a stemmer would.
///
/// @param language the language
/// @param id the identifier
/// @param suffix the suffix dropped from the words that are longer than it
record ToyAnalyzer(Locale language, String id, String suffix) implements LanguageAnalyzer {

    static final ToyAnalyzer FRENCH = new ToyAnalyzer(Locale.FRENCH, "toy/french", "s");
    static final ToyAnalyzer ENGLISH = new ToyAnalyzer(Locale.ENGLISH, "toy/english", "es");

    @Override
    public WordAnalyzer newWordAnalyzer() {
        return word -> {
            var text = word.toString();
            if (text.length() > suffix.length() && text.endsWith(suffix)) {
                word.set(text.substring(0, text.length() - suffix.length()));
            }
        };
    }
}
