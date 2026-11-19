package be.imgn.search.langdetect.training;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.LanguageDetector.Guess;
import be.imgn.search.langdetect.NaiveBayesDetector;
import java.util.Locale;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

class RegisterTest {

    @Test
    void readsTheRegisterTexts() {
        assertThat(Register.read())
                .hasSizeGreaterThan(50)
                .allSatisfy(sample -> assertThat(sample.text()).isNotBlank())
                .extracting(Sample::language)
                .contains("en", "fr", "ru", "el", "sr");
    }

    @Test
    void theBundledModelWasFittedToKeepTheRegisterTexts() {
        // Fails if the register texts change without the model being trained again.
        var detector = new NaiveBayesDetector();
        var softly = new SoftAssertions();
        for (var sample : Register.read()) {
            softly.assertThat(detector.detect(sample.text()))
                    .as(sample.text())
                    .extracting(Guess::language)
                    .first()
                    .extracting(Locale::getLanguage)
                    .isEqualTo(sample.language());
        }
        softly.assertAll();
    }
}
