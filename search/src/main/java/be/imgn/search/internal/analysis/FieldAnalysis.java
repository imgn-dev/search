package be.imgn.search.internal.analysis;

import be.imgn.search.TermBuffer;
import be.imgn.search.WordAnalyzer;
import java.io.IOException;
import java.io.Reader;
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
    private TermBuffer keywordWord = new TermBuffer();
    private final Tokenizer tokenizer;

    /// An analysis.
    public FieldAnalysis() {
        this(Tokenizer.BLOCK_SIZE);
    }

    /// An analysis that reads `blockSize` chars at a time from a [Reader], so that tests can cut texts into many
    /// blocks.
    FieldAnalysis(int blockSize) {
        tokenizer = new Tokenizer(word, blockSize);
    }

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
        tokenizer.tokenize(value, words(next, removeAccents, language, neutral, languageTerms));
        return next(next[0], position);
    }

    /// Analyses a value of a text field that a reader gives, reading it to its end, into the same terms as
    /// [#text(CharSequence, int, boolean, WordAnalyzer, TermSink, TermSink)] gives for the whole value, without
    /// holding it whole.
    ///
    /// @param value the reader of the text, which the caller closes
    /// @param position the position of the first neutral term
    /// @param removeAccents whether accents are removed
    /// @param language the analyzer of the value's language, or `null` for neutral terms only
    /// @param neutral the receiver of the neutral terms
    /// @param languageTerms the receiver of the language terms
    /// @param chars the receiver of the value's chars, in order, as they are analysed
    /// @return the position of the next value's first neutral term: [#VALUE_GAP] after the last one of this
    ///     value, or `position` if this value has none
    /// @throws IOException if the reader throws
    public int text(
            Reader value,
            int position,
            boolean removeAccents,
            @Nullable WordAnalyzer language,
            TermSink neutral,
            TermSink languageTerms,
            Tokenizer.CharsConsumer chars)
            throws IOException {
        var next = new int[] {position};
        tokenizer.tokenize(value, words(next, removeAccents, language, neutral, languageTerms), chars);
        return next(next[0], position);
    }

    // The receiver of a value's words, which keeps the position of the next neutral term in `next[0]`.
    private Tokenizer.WordConsumer words(
            int[] next,
            boolean removeAccents,
            @Nullable WordAnalyzer language,
            TermSink neutral,
            TermSink languageTerms) {
        return w -> {
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
        };
    }

    private static int next(int next, int position) {
        return next == position ? position : next - 1 + VALUE_GAP;
    }

    /// Analyses a value of a keyword field into one term: normalized, lowercased and case-folded, but neither
    /// split nor stemmed.
    ///
    /// @param value the keyword
    /// @param sink the receiver of the term, at position 0; it receives nothing if the keyword normalizes to
    ///     nothing
    public void keyword(CharSequence value, TermSink sink) {
        keywordWord.set(value);
        Normalization.normalizeAndLowercase(keywordWord);
        Normalization.fold(keywordWord);
        if (keywordWord.length() > 0) {
            sink.term(keywordWord.chars(), keywordWord.length(), 0);
        }
    }

    /// The memory this analysis keeps in its buffers.
    ///
    /// @return the number of bytes
    public long scratchBytes() {
        return (long) (word.chars().length + languageWord.chars().length + keywordWord.chars().length) * Character.BYTES
                + tokenizer.scratchBytes();
    }

    /// Gives back the memory of a buffer that a long keyword grew beyond `maxBytes`: the words of a text are at
    /// most [Tokenizer#MAX_LENGTH] chars, but a keyword has no limit.
    ///
    /// @param maxBytes the most memory kept by the buffer of a keyword
    public void shrink(int maxBytes) {
        if ((long) keywordWord.chars().length * Character.BYTES > maxBytes) {
            keywordWord = new TermBuffer();
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
