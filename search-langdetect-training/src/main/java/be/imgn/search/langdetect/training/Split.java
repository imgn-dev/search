package be.imgn.search.langdetect.training;

/// The part of the corpus a sentence belongs to, decided by a hash of its normalized text alone: Tatoeba repeats
/// some texts under several ids, and a held-out sentence must not be learnt under another id. The split never
/// depends on the order of the corpus.
enum Split {
    /// About 80% of the sentences: the model learns from them.
    TRAINING,
    /// About 10%: the limits and the temperatures of the model are fitted on them.
    VALIDATION,
    /// About 10%: the held-out samples of the tests come from them.
    TEST;

    // A sentence falls in one of this many buckets: one for validation, one for test, the rest for training.
    private static final int BUCKETS = 10;

    /// The split of a sentence whose normalized text is `text`.
    static Split of(String text) {
        return switch ((int) Long.remainderUnsigned(Hashes.mix(Hashes.of(text)), BUCKETS)) {
            case 0 -> TEST;
            case 1 -> VALIDATION;
            default -> TRAINING;
        };
    }
}
