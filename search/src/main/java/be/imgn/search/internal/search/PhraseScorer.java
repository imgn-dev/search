package be.imgn.search.internal.search;

import be.imgn.search.internal.segment.FieldInfo;
import be.imgn.search.internal.segment.PostingsIterator;
import be.imgn.search.internal.segment.Segment;
import java.util.Arrays;
import java.util.Comparator;

/// Scores the documents where terms follow each other at given distances, as a term whose frequency is the number
/// of times the phrase occurs.
final class PhraseScorer extends Scorer {

    private static final int INITIAL_POSITIONS = 16;

    private final PostingsIterator[] terms;
    private final PostingsIterator[] byCost;
    private final int[] offsets;
    private final int[][] positions;
    private final int[] counts;
    private final int[] cursors;
    private final float weight;
    private final float[] lengthTable;
    private final Segment segment;
    private final FieldInfo field;
    private int doc = -1;
    private int phraseFreq;

    /// A scorer of a phrase.
    ///
    /// @param terms the phrase's terms in order, reset with their positions
    /// @param offsets each term's position in the phrase minus the first one's
    PhraseScorer(
            PostingsIterator[] terms,
            int[] offsets,
            float weight,
            float[] lengthTable,
            Segment segment,
            FieldInfo field) {
        this.terms = terms;
        this.byCost = terms.clone();
        Arrays.sort(byCost, Comparator.comparingLong(PostingsIterator::cost));
        this.offsets = offsets;
        this.positions = new int[terms.length][INITIAL_POSITIONS];
        this.counts = new int[terms.length];
        this.cursors = new int[terms.length];
        this.weight = weight;
        this.lengthTable = lengthTable;
        this.segment = segment;
        this.field = field;
    }

    @Override
    public int docID() {
        return doc;
    }

    @Override
    public long cost() {
        return byCost[0].cost();
    }

    @Override
    public int nextDoc() {
        return next(byCost[0].nextDoc());
    }

    @Override
    public int advance(int target) {
        return next(byCost[0].advance(target));
    }

    @Override
    float score() {
        float freq = phraseFreq;
        return weight * freq / (freq + lengthTable[segment.norm(field, doc)]);
    }

    private int next(int candidate) {
        var current = candidate;
        while (current != NO_MORE_DOCS) {
            current = Conjunctions.align(byCost[0], byCost, current);
            if (current == NO_MORE_DOCS) {
                break;
            }
            phraseFreq = countPhrases();
            if (phraseFreq > 0) {
                break;
            }
            current = byCost[0].nextDoc();
        }
        doc = current;
        return doc;
    }

    private int countPhrases() {
        for (var i = 0; i < terms.length; i++) {
            var freq = terms[i].freq();
            if (positions[i].length < freq) {
                positions[i] = new int[Math.max(freq, positions[i].length * 2)];
            }
            for (var j = 0; j < freq; j++) {
                positions[i][j] = terms[i].nextPosition();
            }
            counts[i] = freq;
            cursors[i] = 0;
        }
        var found = 0;
        for (var j = 0; j < counts[0]; j++) {
            var start = positions[0][j];
            if (matchesAt(start)) {
                found++;
            }
        }
        return found;
    }

    private boolean matchesAt(int start) {
        for (var i = 1; i < terms.length; i++) {
            var target = start + offsets[i];
            var cursor = cursors[i];
            while (cursor < counts[i] && positions[i][cursor] < target) {
                cursor++;
            }
            cursors[i] = cursor;
            if (cursor == counts[i] || positions[i][cursor] != target) {
                return false;
            }
        }
        return true;
    }
}
