package be.imgn.search.internal.search;

import be.imgn.search.internal.segment.DocIterator;

/// Iterates over the documents a query matches in a segment, scores the current one, and bounds the scores it
/// can give.
///
/// Bounds come from the frequencies and norms the segment stores per term and per block of 128 documents, turned
/// into scores with the search's statistics. They are `double`s, each leaf bound raised by [#SLACK], so that the
/// rounding of the `float` scores, however they are added up, never takes a score above its bound.
///
/// Once told the [lowest score that can still enter the top-k][#setMinCompetitiveScore], a scorer may skip the
/// documents whose bound is at most that score, since a document scoring no more than it never enters: the
/// top-k keeps equal scores in document order, and the documents to come have higher numbers. The scores of the
/// documents it does give stay exact.
abstract class Scorer implements DocIterator {

    /// How much higher a leaf bound is than the exact bound, relatively: far above the error of the `float`
    /// operations of a score, about 1e-7 each.
    static final double SLACK = 1e-5;

    private double minCompetitiveScore = Double.NEGATIVE_INFINITY;
    // The last document of the block competitiveTarget last found competitive, -1 when none.
    private int competitiveUpTo = -1;

    /// The score of the current document.
    abstract float score();

    /// A bound of the scores of every document of the segment.
    abstract double maxScore();

    /// Finds the blocks that hold `target` for [#maxScoreUpTo], without moving the iterator.
    ///
    /// @return the last document of the range where the bound holds: at least `target`, [#NO_MORE_DOCS] if the
    ///     range runs to the end
    abstract int advanceShallow(int target);

    /// A bound of the scores of the documents from the last target of [#advanceShallow] to `upTo`.
    abstract double maxScoreUpTo(int upTo);

    /// Sets the lowest score that can still enter the top-k: from now on, the scorer may skip the documents that
    /// score no more than it. It only grows, but for minus infinity, which starts a reused scorer afresh.
    void setMinCompetitiveScore(double min) {
        minCompetitiveScore = min;
        // The block found competitive stays so under a higher minimum, only less tightly: keeping it spares a
        // search of the skip table at every raise, which comes at every document while a large top-k fills.
        if (min == Double.NEGATIVE_INFINITY) {
            competitiveUpTo = -1;
        }
    }

    final double minCompetitiveScore() {
        return minCompetitiveScore;
    }

    /// Whether a minimum competitive score was set, so that the scorer skips.
    final boolean skips() {
        return minCompetitiveScore > Double.NEGATIVE_INFINITY;
    }

    /// Moves to the first match at or after `target`, without skipping on scores. Scorers that use
    /// [#advanceCompetitive] implement it.
    int advanceMatching(int target) {
        throw new UnsupportedOperationException();
    }

    /// Moves to the next match, without skipping on scores. Scorers that use [#nextCompetitive] implement it.
    int nextMatching() {
        throw new UnsupportedOperationException();
    }

    /// Moves to the next match in a block whose bound beats the minimum competitive score: with [#nextMatching]
    /// while in the block last found competitive, which is cheaper than advancing, through [#advanceCompetitive]
    /// beyond.
    final int nextCompetitive() {
        var doc = docID();
        if (doc == NO_MORE_DOCS) {
            return doc;
        }
        if (doc >= competitiveUpTo) {
            return advanceCompetitive(doc + 1);
        }
        var next = nextMatching();
        if (next <= competitiveUpTo) {
            return next;
        }
        var target = competitiveTarget(next);
        return target == next ? next : advanceCompetitive(target);
    }

    /// Moves to the first match at or after `target` in a block whose bound beats the minimum competitive score,
    /// moving through [#advanceMatching].
    final int advanceCompetitive(int target) {
        var next = target <= competitiveUpTo ? target : competitiveTarget(target);
        while (true) {
            var doc = advanceMatching(next);
            if (doc == NO_MORE_DOCS || doc <= competitiveUpTo) {
                return doc;
            }
            next = competitiveTarget(doc);
            if (next == doc) {
                return doc;
            }
        }
    }

    /// The bound of a term or phrase whose frequency is at most `freq` in documents whose length part of BM25 is
    /// at least `length`.
    static double bound(float weight, int freq, float length) {
        return freq == 0 ? 0 : (double) weight * freq / (freq + length) * (1 + SLACK);
    }

    // The first document at or after target whose block may be competitive, or NO_MORE_DOCS.
    private int competitiveTarget(int target) {
        var next = target;
        while (true) {
            var upTo = advanceShallow(next);
            if (maxScoreUpTo(upTo) > minCompetitiveScore) {
                competitiveUpTo = upTo;
                return next;
            }
            if (upTo == NO_MORE_DOCS) {
                competitiveUpTo = NO_MORE_DOCS;
                return NO_MORE_DOCS;
            }
            next = upTo + 1;
        }
    }
}
