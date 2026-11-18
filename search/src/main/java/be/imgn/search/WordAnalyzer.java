package be.imgn.search;

/// The language analysis of one word, such as elision then stemming. Not thread-safe: a
/// [LanguageAnalyzer] makes one per thread that analyses.
///
/// @since 0.1.0
@FunctionalInterface
public interface WordAnalyzer {

    /// Rewrites `word`, a lowercased word with its accents, into its language term, in place. Emptying the
    /// buffer drops the word.
    ///
    /// @param word the word, replaced by its term
    void analyze(TermBuffer word);
}
