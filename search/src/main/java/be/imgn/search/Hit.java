package be.imgn.search;

/// A document found by a search: its key and its score.
///
/// A hit also remembers, without exposing them, where its document sits in the snapshot it was found in, so the
/// searcher that returned it reads its stored fields without looking its key up. Another searcher refuses it.
///
/// @since 0.1.0
public final class Hit {

    private final String key;
    private final float score;
    private final Object snapshot;
    private final int document;

    Hit(String key, float score, Object snapshot, int document) {
        this.key = key;
        this.score = score;
        this.snapshot = snapshot;
        this.document = document;
    }

    /// The document's key.
    ///
    /// @return the key
    public String key() {
        return key;
    }

    /// The document's BM25 score: higher is more relevant.
    ///
    /// @return the score
    public float score() {
        return score;
    }

    Object snapshot() {
        return snapshot;
    }

    int document() {
        return document;
    }

    @Override
    public String toString() {
        return "Hit[key=" + key + ", score=" + score + "]";
    }
}
