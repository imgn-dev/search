package be.imgn.search.internal.search;

import java.util.Arrays;
import java.util.Comparator;

/// The documents every required scorer matches and no excluded one does, scored by the sum of the required
/// scores. The rarest required scorer leads, the others advance to its documents.
final class ConjunctionScorer extends Scorer {

    private final Scorer[] required;
    private final Scorer[] excluded;
    private int doc = -1;

    ConjunctionScorer(Scorer[] required, Scorer[] excluded) {
        this.required = required.clone();
        Arrays.sort(this.required, Comparator.comparingLong(Scorer::cost));
        this.excluded = excluded;
    }

    @Override
    public int docID() {
        return doc;
    }

    @Override
    public long cost() {
        return required[0].cost();
    }

    @Override
    public int nextDoc() {
        return next(required[0].nextDoc());
    }

    @Override
    public int advance(int target) {
        return next(required[0].advance(target));
    }

    @Override
    float score() {
        var score = 0f;
        for (var scorer : required) {
            score += scorer.score();
        }
        return score;
    }

    private int next(int candidate) {
        var current = Conjunctions.align(required[0], required, candidate);
        while (current != NO_MORE_DOCS && isExcluded(current)) {
            current = Conjunctions.align(required[0], required, required[0].nextDoc());
        }
        doc = current;
        return doc;
    }

    private boolean isExcluded(int target) {
        for (var scorer : excluded) {
            if (scorer.docID() < target) {
                scorer.advance(target);
            }
            if (scorer.docID() == target) {
                return true;
            }
        }
        return false;
    }
}
