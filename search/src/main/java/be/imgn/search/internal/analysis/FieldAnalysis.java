package be.imgn.search.internal.analysis;

import be.imgn.search.TermBuffer;
import be.imgn.search.WordAnalyzer;
import org.jspecify.annotations.Nullable;

/// Analyses field values into terms: a text value into its neutral terms and, in a language with an analyzer,
/// its language terms; a keyword value into one term.
///
/// One instance per thread: it reuses its buffers, so it allocates nothing per word for ASCII text.
public final class FieldAnalysis {

    /// How far the first position of a value is from the last position of the previous value of the same field,
    /// so that a phrase never matches across two values.
    public static final int VALUE_GAP = 100;

    private final TermBuffer word = new TermBuffer();
    private final TermBuffer languageWord = new TermBuffer();
    private final Tokenizer tokenizer = new Tokenizer(word);

    /// Analyses a value of a text field.
    ///
    /// Each word is normalized and lowercased. Its neutral terms are then case-folded, stripped of their accents
    /// if `removeAccents`, and split at their apostrophes: `l’arbre` gives `l` and `arbre`, at consecutive
    /// positions. Its language term, when `language` is not `null`, is the lowercased word rewritten by
    /// `language`, then case-folded and stripped of its accents if `removeAccents`; it takes the position of the
    /// word's first neutral term.
    ///
    /// @param value the text
    /// @param position the position of the first neutral term
    /// @param removeAccents whether accents are removed
    /// @param language the analyzer of the value's language, or `null` for neutral terms only
    /// @param neutral the receiver of the neutral terms
    /// @param languageTerms the receiver of the language terms
    /// @return the position of the next value's first neutral term: [#VALUE_GAP] after the last one of this
    ///     value, or `position` if this value has none
    public int text(
            CharSequence value,
            int position,
            boolean removeAccents,
            @Nullable WordAnalyzer language,
            TermSink neutral,
            TermSink languageTerms) {
        var next = new int[] {position};
        tokenizer.tokenize(value, w -> {
            Normalization.normalizeAndLowercase(w);
            if (language != null) {
                languageWord.set(w.chars(), 0, w.length());
                language.analyze(languageWord);
                if (languageWord.length() > 0) {
                    finish(languageWord, removeAccents);
                    if (languageWord.length() > 0) {
                        languageTerms.term(languageWord.chars(), languageWord.length(), next[0]);
                    }
                }
            }
            finish(w, removeAccents);
            next[0] = splitAtApostrophes(w, next[0], neutral);
        });
        return next[0] == position ? position : next[0] - 1 + VALUE_GAP;
    }

    /// Analyses a value of a keyword field into one term: normalized, lowercased and case-folded, but neither
    /// split nor stemmed.
    ///
    /// @param value the keyword
    /// @param sink the receiver of the term, at position 0; it receives nothing if the keyword normalizes to
    ///     nothing
    public void keyword(CharSequence value, TermSink sink) {
        word.set(value);
        Normalization.normalizeAndLowercase(word);
        Normalization.fold(word);
        if (word.length() > 0) {
            sink.term(word.chars(), word.length(), 0);
        }
    }

    private static void finish(TermBuffer term, boolean removeAccents) {
        Normalization.fold(term);
        if (removeAccents) {
            Normalization.removeAccents(term);
        }
    }

    private static int splitAtApostrophes(TermBuffer term, int position, TermSink sink) {
        var chars = term.chars();
        var length = term.length();
        var start = 0;
        var next = position;
        for (var i = 0; i <= length; i++) {
            if (i == length || chars[i] == '\'') {
                if (i > start) {
                    // The sink reads from index 0: move the part there, over parts already sent.
                    if (start > 0) {
                        System.arraycopy(chars, start, chars, 0, i - start);
                    }
                    sink.term(chars, i - start, next++);
                }
                start = i + 1;
            }
        }
        return next;
    }
}
