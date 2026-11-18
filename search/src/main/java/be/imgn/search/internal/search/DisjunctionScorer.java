package be.imgn.search.internal.search;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/// The documents any scorer matches, scored by the sum of the matching scores, or by the best of them for the
/// variants of one term.
///
/// Given a minimum competitive score, a sum uses MaxScore: its scorers with the lowest bounds, as many as
/// together cannot beat the minimum, stop leading; they are only advanced to the documents the others give, and
/// not even there when the others' scores plus their bounds cannot beat it. A best-of skips the blocks where its
/// scorers' block bounds cannot beat the minimum.
final class DisjunctionScorer extends Scorer {

    private final Scorer[] scorers;
    private final boolean max;
    private final double[] maxScores;
    // The scorers by increasing bound: those before essentialFrom only follow.
    private final int[] byBound;
    private int essentialFrom;
    private double nonEssentialBound;
    private int doc = -1;

    DisjunctionScorer(Scorer[] scorers, boolean max) {
        this.scorers = scorers;
        this.max = max;
        this.maxScores = new double[scorers.length];
        for (var i = 0; i < scorers.length; i++) {
            maxScores[i] = scorers[i].maxScore();
        }
        this.byBound = IntStream.range(0, scorers.length)
                .boxed()
                .sorted(Comparator.comparingDouble(i -> maxScores[i]))
                .mapToInt(Integer::intValue)
                .toArray();
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
        if (!skips()) {
            return nextMatching();
        }
        if (max) {
            return nextCompetitive();
        }
        return doc == NO_MORE_DOCS ? doc : advanceMaxScore(doc + 1);
    }

    @Override
    int nextMatching() {
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
        if (skips()) {
            return max ? advanceCompetitive(target) : advanceMaxScore(target);
        }
        return advanceMatching(target);
    }

    @Override
    int advanceMatching(int target) {
        for (var scorer : scorers) {
            if (scorer.docID() < target) {
                scorer.advance(target);
            }
        }
        doc = minimum();
        return doc;
    }

    // Exactly the exhaustive score: the scorers at the document, added in their order.
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

    @Override
    double maxScore() {
        return combine(maxScores);
    }

    @Override
    int advanceShallow(int target) {
        var upTo = NO_MORE_DOCS;
        for (var scorer : scorers) {
            upTo = Math.min(upTo, scorer.advanceShallow(target));
        }
        return upTo;
    }

    @Override
    double maxScoreUpTo(int upTo) {
        var bounds = 0.0;
        for (var scorer : scorers) {
            var bound = scorer.maxScoreUpTo(upTo);
            bounds = max ? Math.max(bounds, bound) : bounds + bound;
        }
        return bounds;
    }

    @Override
    void setMinCompetitiveScore(double min) {
        super.setMinCompetitiveScore(min);
        if (max) {
            return;
        }
        var sum = 0.0;
        var i = 0;
        while (i < byBound.length && sum + maxScores[byBound[i]] <= min) {
            sum += maxScores[byBound[i]];
            i++;
        }
        essentialFrom = i;
        nonEssentialBound = sum;
    }

    private int advanceMaxScore(int target) {
        var next = target;
        while (true) {
            var candidate = NO_MORE_DOCS;
            for (var i = essentialFrom; i < byBound.length; i++) {
                var scorer = scorers[byBound[i]];
                if (scorer.docID() < next) {
                    scorer.advance(next);
                }
                candidate = Math.min(candidate, scorer.docID());
            }
            if (candidate == NO_MORE_DOCS || essentialFrom == 0 || isCompetitive(candidate)) {
                doc = candidate;
                return doc;
            }
            next = candidate + 1;
        }
    }

    // Advances the non-essential scorers to the candidate, unless the essential ones at the candidate plus the
    // bounds of the others cannot beat the minimum.
    private boolean isCompetitive(int candidate) {
        var essential = 0.0;
        for (var i = essentialFrom; i < byBound.length; i++) {
            var scorer = scorers[byBound[i]];
            if (scorer.docID() == candidate) {
                essential += scorer.score();
            }
        }
        if (essential * (1 + SLACK) + nonEssentialBound <= minCompetitiveScore()) {
            return false;
        }
        for (var i = 0; i < essentialFrom; i++) {
            var scorer = scorers[byBound[i]];
            if (scorer.docID() < candidate) {
                scorer.advance(candidate);
            }
        }
        return true;
    }

    private double combine(double[] values) {
        return max
                ? Arrays.stream(values).max().orElse(0)
                : Arrays.stream(values).sum();
    }

    private int minimum() {
        var minimum = NO_MORE_DOCS;
        for (var scorer : scorers) {
            minimum = Math.min(minimum, scorer.docID());
        }
        return minimum;
    }
}
