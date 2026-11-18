package be.imgn.search.internal.index;

import java.util.Arrays;

/// The postings of one term read back from a buffer, in reusable arrays.
final class TermPostings {

    private static final int INITIAL_CAPACITY = 128;

    private int[] docs = new int[INITIAL_CAPACITY];
    private int[] freqs = new int[INITIAL_CAPACITY];
    private int[] positions = new int[INITIAL_CAPACITY];
    private int docCount;
    private int positionCount;

    int[] docs() {
        return docs;
    }

    int[] freqs() {
        return freqs;
    }

    int[] positions() {
        return positions;
    }

    int docCount() {
        return docCount;
    }

    void clear() {
        docCount = 0;
        positionCount = 0;
    }

    void addDoc(int doc, int freq) {
        if (docCount == docs.length) {
            docs = Arrays.copyOf(docs, docCount * 2);
            freqs = Arrays.copyOf(freqs, docCount * 2);
        }
        docs[docCount] = doc;
        freqs[docCount] = freq;
        docCount++;
    }

    void addPosition(int position) {
        if (positionCount == positions.length) {
            positions = Arrays.copyOf(positions, positionCount * 2);
        }
        positions[positionCount++] = position;
    }
}
