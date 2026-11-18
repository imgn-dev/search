package be.imgn.search.internal.search;

import be.imgn.search.internal.segment.FieldInfo;
import be.imgn.search.internal.segment.PostingsIterator;
import be.imgn.search.internal.segment.Segment;
import org.jspecify.annotations.Nullable;

/// Scores the documents of one term with BM25. Reset for each segment, so a search reuses it.
final class TermScorer extends Scorer {

    private final PostingsIterator postings = new PostingsIterator();
    private final float weight;
    private final float[] lengthTable;
    private @Nullable Segment segment;
    private @Nullable FieldInfo field;

    /// A scorer of a term.
    ///
    /// @param weight the idf times k1 + 1
    /// @param lengthTable the length part of BM25 per norm
    TermScorer(float weight, float[] lengthTable) {
        this.weight = weight;
        this.lengthTable = lengthTable;
    }

    PostingsIterator postings() {
        return postings;
    }

    void reset(Segment newSegment, FieldInfo newField) {
        this.segment = newSegment;
        this.field = newField;
    }

    @Override
    public int docID() {
        return postings.docID();
    }

    @Override
    public int nextDoc() {
        return postings.nextDoc();
    }

    @Override
    public int advance(int target) {
        return postings.advance(target);
    }

    @Override
    public long cost() {
        return postings.cost();
    }

    @Override
    float score() {
        float freq = postings.freq();
        return weight * freq / (freq + lengthTable[norm(postings.docID())]);
    }

    private int norm(int doc) {
        var current = field;
        var currentSegment = segment;
        if (current == null || currentSegment == null || !current.hasNorms()) {
            return 0;
        }
        return currentSegment.norm(current, doc);
    }
}
