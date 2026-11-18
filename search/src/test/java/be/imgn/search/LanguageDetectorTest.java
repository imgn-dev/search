package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

    @Test
    void normalizesTheLanguageOfAGuess() {
        assertThat(new LanguageDetector.Guess(Locale.CANADA_FRENCH, 0.9).language())
                .isEqualTo(Locale.FRENCH);
    }

    @Test
    void rejectsAProbabilityOutsideZeroToOne() {
        assertThatThrownBy(() -> new LanguageDetector.Guess(Locale.FRENCH, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LanguageDetector.Guess(Locale.FRENCH, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
