package be.imgn.search.internal.search;

/// The statistics a field is scored with, summed over a snapshot's segments: the number of documents, for the
/// inverse document frequencies, and the length part of BM25 per norm.
final class FieldStats {

    private final long docCount;
    private final float[] lengthTable;

    FieldStats(long docCount, float[] lengthTable) {
        this.docCount = docCount;
        this.lengthTable = lengthTable;
    }

    long docCount() {
        return docCount;
    }

    float[] lengthTable() {
        return lengthTable;
    }
}
