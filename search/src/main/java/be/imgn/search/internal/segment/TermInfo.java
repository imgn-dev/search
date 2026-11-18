package be.imgn.search.internal.segment;

/// What a field's term dictionary holds about a term.
///
/// @param docFreq the number of documents that have the term
/// @param totalTermFreq the number of occurrences of the term, which is also its number of positions
/// @param maxFreq the highest frequency of the term in a document, for its score bound
/// @param minNorm the lowest norm of a document that has the term, for its score bound; 0 in a field without
///     norms
/// @param postingsOffset the offset of the term's postings, skip table first, from the start of the postings
/// @param positionsOffset the offset of the term's positions from the start of the positions; 0 in a field
///     without positions
public record TermInfo(
        int docFreq, long totalTermFreq, int maxFreq, int minNorm, long postingsOffset, long positionsOffset) {

    /// The number of values per term in a field's dictionary.
    static final int COLUMNS = 6;

    /// The values written as deltas: the offsets, which grow with the terms.
    static final long DELTA_MASK = 0b110000;

    private static final int DOC_FREQ = 0;
    private static final int TOTAL_TERM_FREQ = 1;
    private static final int MAX_FREQ = 2;
    private static final int MIN_NORM = 3;
    private static final int POSTINGS_OFFSET = 4;
    private static final int POSITIONS_OFFSET = 5;

    static TermInfo of(long[] values) {
        return new TermInfo(
                (int) values[DOC_FREQ],
                values[TOTAL_TERM_FREQ],
                (int) values[MAX_FREQ],
                (int) values[MIN_NORM],
                values[POSTINGS_OFFSET],
                values[POSITIONS_OFFSET]);
    }

    void toValues(long[] values) {
        values[DOC_FREQ] = docFreq;
        values[TOTAL_TERM_FREQ] = totalTermFreq;
        values[MAX_FREQ] = maxFreq;
        values[MIN_NORM] = minNorm;
        values[POSTINGS_OFFSET] = postingsOffset;
        values[POSITIONS_OFFSET] = positionsOffset;
    }
}
