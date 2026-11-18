package be.imgn.search.internal.search;

import java.util.Arrays;
import java.util.Comparator;

/// The documents every required scorer matches and no excluded one does, scored by the sum of the required
/// scores. The rarest required scorer leads, the others advance to its documents.
///
/// Given a minimum competitive score, it skips the blocks where the sum of its required scorers' block bounds
/// cannot beat it (block-max), and tells each required scorer the score it must beat itself: the minimum less
/// the bounds of the others.
final class ConjunctionScorer extends Scorer {

    private final Scorer[] required;
    private final Scorer[] excluded;
    private final double[] maxScores;
    private final double maxScore;
    private int doc = -1;

    ConjunctionScorer(Scorer[] required, Scorer[] excluded) {
        this.required = required.clone();
        Arrays.sort(this.required, Comparator.comparingLong(Scorer::cost));
        this.excluded = excluded;
        this.maxScores = new double[required.length];
        var sum = 0.0;
        for (var i = 0; i < maxScores.length; i++) {
            maxScores[i] = this.required[i].maxScore();
            sum += maxScores[i];
        }
        this.maxScore = sum;
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
        return skips() ? nextCompetitive() : nextMatching();
    }

    @Override
    public int advance(int target) {
        return skips() ? advanceCompetitive(target) : next(required[0].advance(target));
    }

    @Override
    int advanceMatching(int target) {
        return next(required[0].advance(target));
    }

    @Override
    int nextMatching() {
        return next(required[0].nextDoc());
    }

    @Override
    float score() {
        var score = 0f;
        for (var scorer : required) {
            score += scorer.score();
        }
        return score;
    }

    @Override
    double maxScore() {
        return maxScore;
    }

    @Override
    int advanceShallow(int target) {
        var upTo = NO_MORE_DOCS;
        for (var scorer : required) {
            upTo = Math.min(upTo, scorer.advanceShallow(target));
        }
        return upTo;
    }

    @Override
    double maxScoreUpTo(int upTo) {
        var sum = 0.0;
        for (var scorer : required) {
            sum += scorer.maxScoreUpTo(upTo);
        }
        return sum;
    }

    @Override
    void setMinCompetitiveScore(double min) {
        super.setMinCompetitiveScore(min);
        for (var i = 0; i < required.length; i++) {
            // Scores are positive: a minimum that is not leaves nothing to skip.
            var childMin = min - (maxScore - maxScores[i]);
            if (childMin > 0) {
                required[i].setMinCompetitiveScore(childMin);
            }
        }
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
