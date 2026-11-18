package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Locale;

/// Guesses the language of a document whose language the caller did not give.
///
/// The index runs it once per document, over the text values that have no language, and keeps the best guess
/// when its probability reaches the schema's threshold.
///
/// @since 0.1.0
@FunctionalInterface
public interface LanguageDetector {

    /// The likely languages of `text`, most likely first.
    ///
    /// @param text the text to read; a detector may read only a sample of it
    /// @return the guesses, most likely first; empty when the text is in none of the languages this detector
    ///     knows
    List<Guess> detect(CharSequence text);

    /// A language and the probability that a text is in it.
    ///
    /// @param language the language, as its primary language subtag alone
    /// @param probability the probability, from 0 to 1
    /// @since 0.1.0
    record Guess(Locale language, double probability) {

        /// Checks and normalizes the guess.
        ///
        /// @throws NullPointerException if `language` is `null`
        /// @throws IllegalArgumentException if `language` has no language subtag, or if `probability` is not
        ///     between 0 and 1
        public Guess {
            language = Languages.normalize(requireNonNull(language, "language"));
            checkArgument(probability >= 0 && probability <= 1, "Probability %s is not between 0 and 1", probability);
        }
    }
}
