package be.imgn.search.langdetect;

import static java.util.Objects.requireNonNull;

import be.imgn.search.LanguageDetector;
import be.imgn.search.langdetect.internal.Detector;
import be.imgn.search.langdetect.internal.ModelFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A language detector for the European languages the search library stems: Basque, Catalan, Czech, Danish,
/// Dutch, English, Esperanto, Estonian, Finnish, French, German, Greek, Hungarian, Irish, Italian, Lithuanian,
/// Norwegian, Polish, Portuguese, Romanian, Russian, Serbian, Spanish, Swedish and Ukrainian.
///
/// It narrows the candidates by script, Greek text being Greek and Cyrillic text Russian, Ukrainian or Serbian,
/// then scores them with naive Bayes over character 1- to 3-grams. It reads at most about 2 KB, sampled across
/// the text, and returns no guess when the text looks like none of its languages. Its model is trained on
/// sentences from Tatoeba.
///
/// Instances are thread-safe and share the bundled model, which is loaded once.
///
/// @since 0.1.0
public final class NaiveBayesDetector implements LanguageDetector {

    private final Detector detector;

    /// Creates a detector with the bundled model.
    ///
    /// @throws UncheckedIOException if the bundled model cannot be read or is corrupt
    public NaiveBayesDetector() {
        detector = Bundled.detector();
    }

    /// {@inheritDoc}
    ///
    /// The guesses are the languages of the text's dominant script whose probability reaches 1%, with the
    /// probabilities normalized over all the languages of that script. The languages are primary language
    /// subtags, `no` for Norwegian.
    ///
    /// @throws NullPointerException if `text` is `null`
    @Override
    public List<Guess> detect(CharSequence text) {
        requireNonNull(text, "text");
        return detector.detect(text);
    }

    /// The bundled model, loaded with the first detector.
    private static final class Bundled {

        private static final String RESOURCE = "internal/languages.model";

        private static final @Nullable Detector DETECTOR;
        private static final @Nullable IOException FAILURE;

        static {
            Detector detector = null;
            IOException failure = null;
            try (var in = NaiveBayesDetector.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    failure = new IOException("The bundled language model " + RESOURCE + " is missing");
                } else {
                    detector = new Detector(ModelFormat.read(in.readAllBytes()));
                }
            } catch (IOException e) {
                failure = e;
            }
            DETECTOR = detector;
            FAILURE = failure;
        }

        static Detector detector() {
            if (DETECTOR == null) {
                throw new UncheckedIOException(
                        "Cannot load the bundled language model", requireNonNull(FAILURE, "failure"));
            }
            return DETECTOR;
        }
    }
}
