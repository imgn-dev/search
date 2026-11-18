package be.imgn.search.internal.search;

import java.util.ArrayList;
import java.util.List;

/// Keeps the best documents seen, in a heap whose root is the worst kept: the lowest score, and among equal
/// scores the highest document number, so that ties keep document order.
final class TopDocs {

    private final int[] docs;
    private final float[] scores;
    private int size;

    TopDocs(int capacity) {
        this.docs = new int[capacity];
        this.scores = new float[capacity];
    }

    /// Offers a document; documents come in increasing order.
    void collect(int doc, float score) {
        if (size < docs.length) {
            docs[size] = doc;
            scores[size] = score;
            siftUp(size++);
        } else if (size > 0 && score > scores[0]) {
            docs[0] = doc;
            scores[0] = score;
            siftDown(0);
        }
    }

    /// The lowest score a document must beat to enter: the worst kept once full, minus infinity before.
    double minCompetitiveScore() {
        return size == docs.length ? scores[0] : Double.NEGATIVE_INFINITY;
    }

    /// The documents kept, best first.
    List<Searching.ScoredDoc> results() {
        var results = new Searching.ScoredDoc[size];
        while (size > 0) {
            results[size - 1] = new Searching.ScoredDoc(docs[0], scores[0]);
            size--;
            docs[0] = docs[size];
            scores[0] = scores[size];
            siftDown(0);
        }
        return new ArrayList<>(List.of(results));
    }

    private boolean worse(int a, int b) {
        return scores[a] < scores[b] || (scores[a] == scores[b] && docs[a] > docs[b]);
    }

    private void siftUp(int index) {
        var i = index;
        while (i > 0) {
            var parent = (i - 1) >>> 1;
            if (!worse(i, parent)) {
                return;
            }
            swap(i, parent);
            i = parent;
        }
    }

    private void siftDown(int index) {
        var i = index;
        while (true) {
            var left = 2 * i + 1;
            if (left >= size) {
                return;
            }
            var right = left + 1;
            var child = right < size && worse(right, left) ? right : left;
            if (!worse(child, i)) {
                return;
            }
            swap(i, child);
            i = child;
        }
    }

    private void swap(int a, int b) {
        var doc = docs[a];
        docs[a] = docs[b];
        docs[b] = doc;
        var score = scores[a];
        scores[a] = scores[b];
        scores[b] = score;
    }
}
