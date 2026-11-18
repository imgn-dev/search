package be.imgn.search;

import java.util.Locale;

/// The analysis of one language: what turns the words of a text value in that language into its language terms,
/// such as elision and stemming.
///
/// A [Schema] registers the analyzers it uses. An index records the [id][#id()] of each analyzer it used, and
/// refuses to open with a schema that changes one: the terms already indexed would no longer match the queries.
///
/// @since 0.1.0
public interface LanguageAnalyzer {

    /// The language this analyzer handles, as its primary language subtag alone, such as `fr`.
    ///
    /// @return the language, never with a country or a variant
    Locale language();

    /// The name of this analyzer's algorithm and version, such as `snowball-3.2.0/french`. Two analyzers with the
    /// same identifier produce the same terms.
    ///
    /// @return the identifier, not empty
    String id();

    /// A new analyzer of words in this language, for the calling thread only.
    ///
    /// @return a new word analyzer
    WordAnalyzer newWordAnalyzer();
}
