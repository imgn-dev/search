package be.imgn.search.internal.search;

import be.imgn.search.internal.segment.PostingsIterator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A phrase in a neutral text field that keeps positions, scored as a term whose inverse document frequency is
/// the sum of its words'.
final class PhraseWeight implements Weight {

    private final SearchContext context;
    private final String field;
    private final TermStats[] terms;
    private final int[] offsets;
    private final float weight;
    private final float[] lengthTable;

    /// A weight of a phrase.
    ///
    /// @param context the search
    /// @param field the neutral text field
    /// @param terms the UTF-8 bytes of the terms, at least two
    /// @param positions the position of each term in the query
    PhraseWeight(SearchContext context, String field, List<byte[]> terms, int[] positions) {
        this.context = context;
        this.field = field;
        this.terms = new TermStats[terms.size()];
        this.offsets = new int[terms.size()];
        var stats = context.stats(field);
        var idf = 0f;
        for (var i = 0; i < this.terms.length; i++) {
            this.terms[i] = new TermStats(context, field, terms.get(i));
            this.offsets[i] = positions[i] - positions[0];
            idf += Bm25.idf(this.terms[i].docFreq(), stats.docCount());
        }
        this.weight = idf * (Bm25.K1 + 1);
        this.lengthTable = stats.lengthTable();
    }

    @Override
    public @Nullable Scorer scorer(int segment) {
        var data = context.segment(segment);
        var info = data.field(field);
        if (info == null) {
            return null;
        }
        var iterators = new PostingsIterator[terms.length];
        for (var i = 0; i < terms.length; i++) {
            var term = terms[i].info(segment);
            if (term == null) {
                return null;
            }
            iterators[i] = new PostingsIterator();
            data.postings(iterators[i], info, term, true);
        }
        return new PhraseScorer(iterators, offsets, weight, lengthTable, data, info);
    }
}
