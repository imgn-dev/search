package be.imgn.search.internal.search;

import be.imgn.search.internal.codec.Norms;
import java.util.Arrays;

/// BM25 with k1 = 1.2 and b = 0.75: a term scores `idf × (k1 + 1) × f / (f + k1 × (1 − b + b × length /
/// average length))`, where `idf = ln(1 + (N − df + 0.5) / (df + 0.5))`.
///
/// The length part of the denominator depends only on the norm, a byte, so it is computed once per field and
/// search into a table of 256 entries.
final class Bm25 {

    static final float K1 = 1.2f;
    static final float B = 0.75f;

    private static final double HALF = 0.5;

    private Bm25() {}

    /// The inverse document frequency of a term.
    ///
    /// @param docFreq the number of documents with the term
    /// @param docCount the number of documents with the field
    static float idf(long docFreq, long docCount) {
        return (float) Math.log(1 + (docCount - docFreq + HALF) / (docFreq + HALF));
    }

    /// The length part of the denominator for each norm.
    ///
    /// @param averageLength the average length of the field
    static float[] lengthTable(double averageLength) {
        var table = new float[Norms.MAX + 1];
        for (var norm = 0; norm <= Norms.MAX; norm++) {
            table[norm] = (float) (K1 * (1 - B + B * Norms.decode(norm) / averageLength));
        }
        return table;
    }

    /// The length part of the denominator for a field without norms, such as a keyword field: every document has
    /// the average length.
    static float[] flatTable() {
        var table = new float[Norms.MAX + 1];
        Arrays.fill(table, K1);
        return table;
    }
}
