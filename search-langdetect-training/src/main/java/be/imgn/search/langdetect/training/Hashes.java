package be.imgn.search.langdetect.training;

/// The hashes of sentence ids and texts that make every choice of the tool deterministic.
final class Hashes {

    // The constants of SplitMix64's finalizer.
    private static final long GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MULTIPLIER1 = 0xBF58476D1CE4E5B9L;
    private static final long MULTIPLIER2 = 0x94D049BB133111EBL;
    private static final int SHIFT1 = 30;
    private static final int SHIFT2 = 27;
    private static final int SHIFT3 = 31;

    // Salts, so that the selection and the order do not follow the split.
    private static final long SELECTION = 0x2545F4914F6CDD1DL;
    private static final long ORDER = 0x5DEECE66DL;

    // A double in [0, 1) from the 53 high bits of a hash.
    private static final int FRACTION_SHIFT = Long.SIZE - 53;
    private static final double FRACTION_SCALE = 0x1.0p-53;

    // The constants of 64-bit FNV-1a.
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private Hashes() {}

    /// A 64-bit hash of `text`, FNV-1a over its chars.
    static long of(String text) {
        var hash = FNV_OFFSET;
        for (var i = 0; i < text.length(); i++) {
            hash = (hash ^ text.charAt(i)) * FNV_PRIME;
        }
        return hash;
    }

    /// The SplitMix64 finalizer: spreads the bits of `value` over the whole `long`.
    static long mix(long value) {
        var x = value + GAMMA;
        x = (x ^ (x >>> SHIFT1)) * MULTIPLIER1;
        x = (x ^ (x >>> SHIFT2)) * MULTIPLIER2;
        return x ^ (x >>> SHIFT3);
    }

    /// Whether to keep the sentence `id` when keeping a fraction `rate` of the sentences, independently of the
    /// split.
    static boolean selected(long id, double rate) {
        return (mix(id ^ SELECTION) >>> FRACTION_SHIFT) * FRACTION_SCALE < rate;
    }

    /// The rank of the sentence `id` in the order samples are built in.
    static long order(long id) {
        return mix(id ^ ORDER);
    }
}
