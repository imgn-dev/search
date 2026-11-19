package be.imgn.search.langdetect.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/// The part of a model for one script: its languages, and the cost of each n-gram in each of them.
///
/// A cost is a negated natural log-probability, quantized to an unsigned byte in units of
/// `1 / Model.unitsPerNat()` nat. An n-gram missing from the table costs the default of its order in each
/// language: the smoothed probability of an n-gram the language never showed in training.
public final class ScriptModel {

    private static final int GOLDEN_RATIO = 0x9E3779B9;
    private static final int COST_MASK = 0xFF;
    // The costs of a row are packed four languages to a long, 16 bits each, so that scoring adds four at once. A
    // lane holds up to 65,535: the sum of 257 costs of 255 at most, so the sums are flushed every 256 n-grams at
    // most.
    private static final int LANE_BITS = Short.SIZE;
    private static final int LANES = Long.SIZE / LANE_BITS;
    private static final int LANE_MASK = 0xFFFF;
    private static final int MAX_PENDING = 256;

    private final Script script;
    private final List<Language> languages;
    private final List<Locale> locales;
    private final Fit fit;
    private final byte[] defaults;
    private final int[] keys;
    private final int lanes;
    private final long[] packed;

    // An open-addressing hash table from key to row, each slot holding the key in its high half and the offset of
    // the row in its low half, so that a lookup reads one array: 0 marks an empty slot, since no key is 0.
    private final long[] slots;
    private final int shift;

    /// Creates the model of a script.
    ///
    /// @param languages the languages; a language may appear in several scripts
    /// @param fit the limits and the temperature, fitted on validation texts
    /// @param defaults the cost of a missing n-gram for each order then each language: `defaults[(order - 1) *
    ///     languages.size() + language]`
    /// @param keys the n-gram keys, in ascending order
    /// @param costs the cost of each n-gram then each language: `costs[row * languages.size() + language]`
    public ScriptModel(Script script, List<Language> languages, Fit fit, byte[] defaults, int[] keys, byte[] costs) {
        var size = languages.size();
        if (size == 0 || defaults.length != NGrams.MAX_ORDER * size || costs.length != keys.length * size) {
            throw new IllegalArgumentException("The model of " + script + " has inconsistent sizes");
        }
        for (var i = 0; i < keys.length; i++) {
            if (keys[i] <= 0 || (i > 0 && keys[i] <= keys[i - 1])) {
                throw new IllegalArgumentException("The keys of the model of " + script + " are not ascending");
            }
        }
        this.script = script;
        this.languages = List.copyOf(languages);
        locales = this.languages.stream()
                .map(language -> Locale.of(language.tag()))
                .toList();
        this.fit = fit;
        this.defaults = defaults.clone();
        this.keys = keys.clone();
        lanes = (size + LANES - 1) / LANES;
        packed = new long[keys.length * lanes];
        for (var row = 0; row < keys.length; row++) {
            for (var language = 0; language < size; language++) {
                packed[row * lanes + language / LANES] |=
                        (long) (costs[row * size + language] & COST_MASK) << (language % LANES * LANE_BITS);
            }
        }
        var capacity = Integer.highestOneBit(Math.max(2, keys.length) * 2 - 1) << 1;
        slots = new long[capacity];
        shift = Integer.numberOfLeadingZeros(capacity) + 1;
        var mask = capacity - 1;
        for (var row = 0; row < keys.length; row++) {
            var slot = hash(keys[row]);
            while (slots[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            slots[slot] = ((long) keys[row] << Integer.SIZE) | Integer.toUnsignedLong(row * lanes);
        }
    }

    /// The script.
    public Script script() {
        return script;
    }

    /// The languages.
    public List<Language> languages() {
        return languages;
    }

    /// The languages, as locales.
    public List<Locale> locales() {
        return locales;
    }

    /// The limits and the temperature.
    public Fit fit() {
        return fit;
    }

    /// The highest average cost per n-gram, in nats, of a text of `count` n-grams in `language`: the text is in
    /// none of the languages if its cost in its best language is above the limit of that language.
    public double limit(int language, int count) {
        return languages.get(language).center() * (1 + fit.margin()) + fit.slope() / Math.sqrt(count);
    }

    /// The default costs, by order then language.
    public byte[] defaults() {
        return defaults.clone();
    }

    /// The n-gram keys, in ascending order.
    public int[] keys() {
        return keys.clone();
    }

    /// The costs, by n-gram then language.
    public byte[] costs() {
        var size = languages.size();
        var costs = new byte[keys.length * size];
        for (var row = 0; row < keys.length; row++) {
            for (var language = 0; language < size; language++) {
                costs[row * size + language] =
                        (byte) (packed[row * lanes + language / LANES] >>> (language % LANES * LANE_BITS));
            }
        }
        return costs;
    }

    /// Adds the cost of the n-grams of a normalized text in each language to `totals`, building each n-gram as
    /// [NGrams#keys(Text, Alphabet, int[])] does and looking it up at once.
    ///
    /// @param totals the totals, one per language
    /// @return the number of n-grams
    public int score(Text text, Alphabet alphabet, int[] totals) {
        var chars = text.chars();
        var sums = new long[lanes];
        var pending = 0;
        var count = 0;
        // The missing n-grams of each order.
        var missing1 = 0;
        var missing2 = 0;
        var missing3 = 0;
        var previous2 = 0;
        var previous1 = Alphabet.BOUNDARY;
        for (var i = 0; i < text.length(); i++) {
            var symbol = alphabet.symbol(chars[i]);
            if (symbol != Alphabet.BOUNDARY) {
                var row = row(symbol);
                if (row < 0) {
                    missing1++;
                } else {
                    add(sums, row);
                    pending++;
                }
                count++;
            }
            var bigram = (previous1 << NGrams.SYMBOL_BITS) | symbol;
            var row = row(bigram);
            if (row < 0) {
                missing2++;
            } else {
                add(sums, row);
                pending++;
            }
            count++;
            if (previous2 != 0 && previous1 != Alphabet.BOUNDARY) {
                row = row((previous2 << (2 * NGrams.SYMBOL_BITS)) | bigram);
                if (row < 0) {
                    missing3++;
                } else {
                    add(sums, row);
                    pending++;
                }
                count++;
            }
            // Each position adds at most three n-grams.
            if (pending > MAX_PENDING - NGrams.MAX_ORDER) {
                flush(sums, totals);
                pending = 0;
            }
            previous2 = previous1;
            previous1 = symbol;
        }
        flush(sums, totals);
        var size = languages.size();
        for (var language = 0; language < size; language++) {
            totals[language] += missing1 * (defaults[language] & COST_MASK)
                    + missing2 * (defaults[size + language] & COST_MASK)
                    + missing3 * (defaults[2 * size + language] & COST_MASK);
        }
        return count;
    }

    // The offset of the costs of an n-gram, or -1 if the table does not have it.
    private int row(int key) {
        var mask = slots.length - 1;
        var slot = hash(key);
        long found;
        while ((found = slots[slot]) != 0) {
            if ((int) (found >>> Integer.SIZE) == key) {
                return (int) found;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private void add(long[] sums, int row) {
        for (var lane = 0; lane < lanes; lane++) {
            sums[lane] += packed[row + lane];
        }
    }

    // Adds the sums of the lanes to the totals of their languages, and clears them.
    private void flush(long[] sums, int[] totals) {
        for (var language = 0; language < totals.length; language++) {
            totals[language] += (int) (sums[language / LANES] >>> (language % LANES * LANE_BITS)) & LANE_MASK;
        }
        Arrays.fill(sums, 0);
    }

    private int hash(int key) {
        return (key * GOLDEN_RATIO) >>> shift;
    }

    /// A language of a script.
    ///
    /// @param tag the language tag, as [Locale#getLanguage()] gives it
    /// @param center the average cost per n-gram of texts in the language, in nats
    public record Language(String tag, float center) {}

    /// What is fitted on validation texts once the costs are learnt.
    ///
    /// The highest average cost per n-gram of a text of `n` n-grams in a language, beyond which it is in none
    /// of the languages, is `center * (1 + margin) + slope / sqrt(n)`: the average varies less in a longer text.
    ///
    /// @param margin how much the average cost per n-gram of a long text may exceed its language's center, as a
    ///     fraction of the center
    /// @param slope how much more the average cost per n-gram of a text of one n-gram may, in nats
    /// @param temperature what the cost differences between languages are divided by before they become
    ///     probabilities, since the n-grams of a text overlap and are not independent
    public record Fit(float margin, float slope, float temperature) {

        /// No limit and a temperature of 1, before the fit.
        public static final Fit NONE = new Fit(Float.POSITIVE_INFINITY, 0, 1);
    }
}
