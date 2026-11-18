package be.imgn.search.internal.search;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// A term of an internal field, scored with the statistics of its neutral field.
final class TermWeight implements Weight {

    private final SearchContext context;
    private final TermStats term;
    private final TermScorer scorer;

    /// A weight of a term.
    ///
    /// @param context the search
    /// @param field the internal field, such as `body` or `body.fr`
    /// @param normsField the neutral field that scores it, or `null` for a keyword field
    /// @param term the term's UTF-8 bytes
    TermWeight(SearchContext context, String field, @Nullable String normsField, byte[] term) {
        this.context = context;
        this.term = new TermStats(context, field, term);
        var stats = context.stats(normsField);
        var idf = Bm25.idf(this.term.docFreq(), stats.docCount());
        this.scorer = new TermScorer(idf * (Bm25.K1 + 1), stats.lengthTable());
    }

    @Override
    public @Nullable Scorer scorer(int segment) {
        var info = term.info(segment);
        if (info == null) {
            return null;
        }
        var data = context.segment(segment);
        var field = Objects.requireNonNull(data.field(term.field()));
        data.postings(scorer.postings(), field, info, false);
        scorer.reset(data, field);
        return scorer;
    }
}
