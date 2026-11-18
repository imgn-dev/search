package be.imgn.search.internal.search;

/// The documents any scorer matches, scored by the sum of the matching scores, or by the best of them for the
/// variants of one term.
final class DisjunctionScorer extends Scorer {

    private final Scorer[] scorers;
    private final boolean max;
    private int doc = -1;

    DisjunctionScorer(Scorer[] scorers, boolean max) {
        this.scorers = scorers;
        this.max = max;
    }

    @Override
    public int docID() {
        return doc;
    }

    @Override
    public long cost() {
        long cost = 0;
        for (var scorer : scorers) {
            cost += scorer.cost();
        }
        return cost;
    }

    @Override
    public int nextDoc() {
        for (var scorer : scorers) {
            if (scorer.docID() == doc) {
                scorer.nextDoc();
            }
        }
        doc = minimum();
        return doc;
    }

    @Override
    public int advance(int target) {
        for (var scorer : scorers) {
            if (scorer.docID() < target) {
                scorer.advance(target);
            }
        }
        doc = minimum();
        return doc;
    }

    @Override
    float score() {
        var score = 0f;
        for (var scorer : scorers) {
            if (scorer.docID() == doc) {
                var value = scorer.score();
                score = max ? Math.max(score, value) : score + value;
            }
        }
        return score;
    }

    private int minimum() {
        var minimum = NO_MORE_DOCS;
        for (var scorer : scorers) {
            minimum = Math.min(minimum, scorer.docID());
        }
        return minimum;
    }
}
