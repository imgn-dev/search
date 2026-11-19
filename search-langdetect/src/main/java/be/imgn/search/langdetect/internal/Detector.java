package be.imgn.search.langdetect.internal;

import be.imgn.search.LanguageDetector.Guess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Detects languages with a model: samples and normalizes the text, narrows the languages to those of its
/// dominant script, scores them with naive Bayes over the n-grams, and rejects a text too unlike all of them.
public final class Detector {

    /// The lowest probability a guess needs to be returned.
    public static final double MIN_PROBABILITY = 0.01;

    private final Model model;

    /// Creates a detector of `model`.
    public Detector(Model model) {
        this.model = model;
    }

    /// The likely languages of `text`, as [be.imgn.search.LanguageDetector#detect(CharSequence)] gives them.
    public List<Guess> detect(CharSequence text) {
        var scores = scores(text);
        if (scores == null) {
            return List.of();
        }
        var script = scores.script();
        var totals = scores.totals();
        var best = scores.best();
        var unitsPerNat = model.unitsPerNat();
        if (totals[best] / (unitsPerNat * (double) scores.count()) > script.limit(best, scores.count())) {
            return List.of();
        }
        var scale = unitsPerNat * (double) script.fit().temperature();
        var weights = new double[totals.length];
        var sum = 0.0;
        for (var language = 0; language < totals.length; language++) {
            weights[language] = Math.exp((totals[best] - totals[language]) / scale);
            sum += weights[language];
        }
        var languages = script.locales();
        var guesses = new ArrayList<Guess>();
        for (var language = 0; language < totals.length; language++) {
            var probability = weights[language] / sum;
            if (probability >= MIN_PROBABILITY) {
                guesses.add(new Guess(languages.get(language), probability));
            }
        }
        guesses.sort(Comparator.comparingDouble(Guess::probability).reversed());
        return List.copyOf(guesses);
    }

    /// The cost of a sample of `text` in each language of its dominant script.
    ///
    /// @return the scores, or `null` if the text has no n-gram in a script the model knows
    public @Nullable Scores scores(CharSequence text) {
        var sample = Text.sample(text);
        var dominant = sample.normalize();
        var script = dominant == null ? null : model.script(dominant);
        if (script == null) {
            return null;
        }
        var totals = new int[script.languages().size()];
        var count = script.score(sample, model.alphabet(), totals);
        return new Scores(script, totals, count);
    }

    /// The cost of a text in each language of a script.
    public static final class Scores {

        private final ScriptModel script;
        private final int[] totals;
        private final int count;
        private final int best;

        Scores(ScriptModel script, int[] totals, int count) {
            this.script = script;
            this.totals = totals;
            this.count = count;
            var best = 0;
            for (var language = 1; language < totals.length; language++) {
                if (totals[language] < totals[best]) {
                    best = language;
                }
            }
            this.best = best;
        }

        /// The model of the text's dominant script.
        public ScriptModel script() {
            return script;
        }

        /// The cost of the text in each language of the script, in cost units: the caller must not change them.
        public int[] totals() {
            return totals;
        }

        /// The number of n-grams scored.
        public int count() {
            return count;
        }

        /// The language with the lowest cost.
        public int best() {
            return best;
        }
    }
}
