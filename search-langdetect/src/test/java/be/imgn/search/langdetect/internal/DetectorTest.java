package be.imgn.search.langdetect.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import be.imgn.search.LanguageDetector.Guess;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/// The detector on a hand-made model of two languages: `a` writes `a`s, `b` writes `b`s.
class DetectorTest {

    private static final int B = Alphabet.BOUNDARY;
    private static final int A = 3;
    private static final int BB = 4;

    @Test
    void normalizesTheProbabilitiesWithTheTemperature() {
        // "a" costs 0.1 nat as an `a` 1-gram in language a and 1.1 nats in language b: one nat apart. Every
        // other n-gram costs the same in both.
        var detector = new Detector(model(new ScriptModel.Fit(Float.POSITIVE_INFINITY, 0, 2)));

        var guesses = detector.detect("a");

        var odds = Math.exp(-1 / 2.0);
        assertThat(guesses).hasSize(2);
        assertThat(guesses.get(0).language()).isEqualTo(Locale.of("aa"));
        assertThat(guesses.get(0).probability()).isCloseTo(1 / (1 + odds), within(1e-9));
        assertThat(guesses.get(1).language()).isEqualTo(Locale.of("bb"));
        assertThat(guesses.get(1).probability()).isCloseTo(odds / (1 + odds), within(1e-9));
    }

    @Test
    void rejectsATextAboveTheLimitOfItsBestLanguage() {
        // " a " has 4 n-grams costing 0.1 + 1 + 1 + 1 nats in language a: 0.775 nat each, against a limit of
        // 0.5 * (1 + 0.5) + 0.1 / sqrt(4) = 0.8 nat, then 0.75 nat without the slope.
        assertThat(new Detector(model(new ScriptModel.Fit(0.5f, 0.1f, 1))).detect("a"))
                .isNotEmpty();
        assertThat(new Detector(model(new ScriptModel.Fit(0.5f, 0, 1))).detect("a"))
                .isEmpty();
    }

    @Test
    void scoresTheMissingNGramsWithTheDefaults() {
        var scores = Objects.requireNonNull(new Detector(model(ScriptModel.Fit.NONE)).scores("c"));
        // " c " gives a rare 1-gram, two 2-grams and a 3-gram, all missing: 1 + 2 * 2 + 3 nats.
        assertThat(scores.count()).isEqualTo(4);
        assertThat(scores.totals()).containsExactly(80, 80);
    }

    @Test
    void leavesOutTheUnlikelyLanguages() {
        var guesses = new Detector(model(ScriptModel.Fit.NONE)).detect("a a a a a a a");
        assertThat(guesses).extracting(Guess::language).containsExactly(Locale.of("aa"));
    }

    private static Model model(ScriptModel.Fit fit) {
        return new Model(
                new Alphabet(new char[] {'a', 'b'}),
                10,
                List.of(new ScriptModel(
                        Script.LATIN,
                        List.of(new ScriptModel.Language("aa", 0.5f), new ScriptModel.Language("bb", 0.5f)),
                        fit,
                        // Missing n-grams cost 1, 2 and 3 nats by order.
                        new byte[] {10, 10, 20, 20, 30, 30},
                        new int[] {A, BB, B << 8 | A, A << 8 | B, B << 16 | A << 8 | B},
                        new byte[] {1, 11, 11, 1, 10, 10, 10, 10, 10, 10})));
    }
}
