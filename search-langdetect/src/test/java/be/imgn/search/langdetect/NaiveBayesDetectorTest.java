package be.imgn.search.langdetect;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.search.LanguageDetector.Guess;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NaiveBayesDetectorTest {

    private static final NaiveBayesDetector DETECTOR = new NaiveBayesDetector();

    private static final List<Integer> LENGTHS = List.of(50, 200, 1_000);

    // What the detector must reach on the validation samples of each language, by sample length: the share of
    // the samples of a known language detected right, and of an unknown language rejected. The validation counts
    // meet a requirement unless they show, with 95% confidence, that the detector is below it: with few samples,
    // as for Irish, they cannot show much. The requirements are set somewhat below the rates the current model
    // reached, so that they catch a worse model rather than a retraining.
    private static final List<Double> ACCURACY = List.of(0.97, 0.99, 0.99);

    // Danish and Norwegian (Bokmål and Nynorsk) are close enough that a sentence often fits both: 95% and 94% of
    // their validation samples of 50 chars were detected right, the others mostly as each other.
    private static final Map<String, List<Double>> ACCURACY_EXCEPTIONS =
            Map.of("dan", List.of(0.90, 0.99, 0.99), "nno+nob", List.of(0.90, 0.99, 0.99));

    // Turkish, Vietnamese and Kazakh were rejected at 97% to 100% at every length.
    private static final List<Double> REJECTION = List.of(0.90, 0.97, 0.97);

    // Indonesian and Tagalog, with their simple syllables, look like Estonian, Basque, English or Esperanto in a
    // sentence or two: 43% and 50% of their validation samples were rejected at 50 chars, 95% and 99% at 200.
    // Swahili looks like Polish at 50 chars: 80% rejected, then all.
    private static final Map<String, List<Double>> REJECTION_EXCEPTIONS = Map.of(
            "ind", List.of(0.35, 0.90, 0.97),
            "tgl", List.of(0.40, 0.97, 0.97),
            "swh", List.of(0.70, 0.97, 0.97));

    @ParameterizedTest
    @ValueSource(ints = {50, 200, 1_000})
    void detectsTheLanguageOfHeldOutSamples(int length) {
        check(length, true, ACCURACY, ACCURACY_EXCEPTIONS);
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 200, 1_000})
    void rejectsHeldOutSamplesOfUnknownLanguages(int length) {
        check(length, false, REJECTION, REJECTION_EXCEPTIONS);
    }

    // Checks the validation counts against the requirements, and the test samples against the validation counts:
    // the test samples of a language must reach the share they reach with a probability of 99% if the detector is
    // right as often as the 95% lower confidence bound of its validation rate. A test set's luck neither makes nor
    // breaks the test.
    private static void check(
            int length, boolean known, List<Double> requirements, Map<String, List<Double>> exceptions) {
        var softly = new SoftAssertions();
        var languages = samplesOfLength(length, known);
        assertThat(languages).isNotEmpty();
        languages.forEach((name, samples) -> {
            var validation = HeldOutSamples.validation(name, length);
            var requirement = exceptions.getOrDefault(name, requirements).get(LENGTHS.indexOf(length));
            softly.assertThat(Binomial.upperBound(validation.right(), validation.total()))
                    .as(
                            "Validation rate of %s at %d chars, %d of %d",
                            name, length, validation.right(), validation.total())
                    .isGreaterThanOrEqualTo(requirement);
            var minimum =
                    Binomial.lowerQuantile(samples.size(), Binomial.lowerBound(validation.right(), validation.total()));
            var right = samples.stream()
                    .filter(sample -> known
                            ? top(DETECTOR.detect(sample.text())).equals(sample.expected())
                            : DETECTOR.detect(sample.text()).isEmpty())
                    .count();
            softly.assertThat((double) right / samples.size())
                    .as("Share of the %d test samples of %s right at %d chars", samples.size(), name, length)
                    .isGreaterThanOrEqualTo(minimum);
        });
        softly.assertAll();
    }

    @Test
    void detectsTextsInAnotherRegisterThanTatoeba() {
        // Weather, sport, cooking, law and technology, in a register Tatoeba's short conversational sentences do
        // not have. Written after the model was fitted, and never used to fit it, unlike the training tool's
        // register texts.
        assertDetected("en", "Heavy rain is expected across the region on Saturday, with a risk of local flooding.");
        assertDetected("en", """
                The firmware update improves battery management and fixes a vulnerability in the Bluetooth stack \
                that allowed an attacker within range to execute arbitrary code.""");
        assertDetected("fr", "De fortes pluies sont attendues samedi sur l’ensemble de la région.");
        assertDetected("fr", """
                Le locataire est tenu de restituer les lieux dans l’état où il les a reçus, sous réserve de \
                l’usure normale résultant d’un usage conforme à leur destination.""");
        assertDetected("de", "Für den Teig Mehl, Zucker und Butter verkneten und eine Stunde kühl stellen.");
        assertDetected("de", """
                Die Mannschaft gewann das Auswärtsspiel nach einem späten Treffer in der Nachspielzeit und \
                übernimmt damit vorläufig die Tabellenführung.""");
        assertDetected("es", "El equipo local ganó el partido gracias a un gol en el último minuto.");
        assertDetected("it", "Per l’impasto, lavorare la farina con lo zucchero e il burro.");
        assertDetected("nl", "Voor het deeg meng je de bloem met de suiker en de boter.");
        assertDetected("pt", "O arrendatário deve devolver o imóvel no estado em que o recebeu.");
        assertDetected("pl", "W sobotę w całym regionie spodziewane są intensywne opady deszczu.");
        assertDetected("sv", "Laget vann bortamatchen efter ett sent mål på övertid.");
        assertDetected("ru", "В субботу во всём регионе ожидаются сильные дожди.");
        assertDetected("ru", """
                Обновление прошивки улучшает управление батареей и устраняет уязвимость, позволявшую \
                злоумышленнику выполнить произвольный код.""");
        assertDetected("uk", "У суботу в усьому регіоні очікуються сильні дощі.");
        assertDetected("el", "Ισχυρές βροχοπτώσεις αναμένονται το Σάββατο σε όλη την περιοχή.");
        assertDetected("cs", "V sobotu se v celém regionu očekávají silné deště.");
        assertDetected("hu", "Szombaton az egész régióban heves esőzések várhatók.");
    }

    @Test
    void sortsTheGuessesAndKeepsTheirProbabilitiesWithinOne() {
        for (var sample : HeldOutSamples.all()) {
            var guesses = DETECTOR.detect(sample.text());
            assertThat(guesses)
                    .extracting(Guess::probability)
                    .isSortedAccordingTo((a, b) -> Double.compare(b, a))
                    .allSatisfy(probability -> assertThat(probability).isBetween(0.01, 1.0));
            // Rounding may add an ulp or so to the sum of the probabilities.
            assertThat(guesses.stream().mapToDouble(Guess::probability).sum()).isLessThanOrEqualTo(1 + 1e-9);
        }
    }

    @Test
    void narrowsGreekTextToGreek() {
        assertThat(DETECTOR.detect("Η αλεπού πηδάει πάνω από τον τεμπέλη σκύλο και τρέχει στο δάσος."))
                .containsExactly(new Guess(Locale.of("el"), 1));
    }

    @Test
    void narrowsCyrillicTextToRussianUkrainianOrSerbian() {
        var cyrillic = List.of(
                "Мы долго гуляли по парку, а потом пили чай с вареньем.",
                "Ми довго гуляли парком, а потім пили чай з варенням.",
                "Дуго смо шетали парком, а онда смо пили чај са џемом.");
        for (var text : cyrillic) {
            assertThat(DETECTOR.detect(text))
                    .isNotEmpty()
                    .extracting(guess -> guess.language().getLanguage())
                    .isSubsetOf("ru", "uk", "sr");
        }
        assertThat(top(DETECTOR.detect(cyrillic.get(0)))).isEqualTo("ru");
        assertThat(top(DETECTOR.detect(cyrillic.get(1)))).isEqualTo("uk");
        assertThat(top(DETECTOR.detect(cyrillic.get(2)))).isEqualTo("sr");
    }

    @Test
    void neverGuessesALanguageOfAnotherScriptForLatinText() {
        assertThat(DETECTOR.detect("Dugo smo šetali parkom, a onda smo pili čaj sa džemom."))
                .isNotEmpty()
                .extracting(guess -> guess.language().getLanguage())
                .doesNotContain("el", "ru", "uk")
                .first()
                .isEqualTo("sr");
    }

    @Test
    void readsTheDominantScriptOnly() {
        assertThat(top(DETECTOR.detect("Москва — столица России, а Paris — столица Франции.")))
                .isEqualTo("ru");
    }

    @Test
    void returnsNorwegianAsNo() {
        assertThat(top(DETECTOR.detect(
                        "Jeg har ikke noe imot at du låner bilen min i morgen, hvis du kjører forsiktig.")))
                .isEqualTo("no");
    }

    @Test
    void rejectsTextInOtherScripts() {
        assertThat(DETECTOR.detect("私は毎朝コーヒーを飲みます。")).isEmpty();
        assertThat(DETECTOR.detect("أنا أشرب القهوة كل صباح.")).isEmpty();
        assertThat(DETECTOR.detect("אני שותה קפה כל בוקר, OK.")).isEmpty();
    }

    @Test
    void detectsTextWithMoreDigitsAndPunctuationThanLetters() {
        assertDetected("fr", "Le 12/03/2024 à 14:30, 1 234 567 € ont été versés.");
        assertDetected("de", "Der Umsatz stieg 2023 um 12,5 % auf 1.234.567 Euro.");
        // Too few words to tell which language: "prix", "TVA" and "total" could be French or Catalan.
        assertThat(DETECTOR.detect("Prix : 12,50 € — TVA 21 % — total 15,13 €")).isNotEmpty();
        assertThat(DETECTOR.detect("Il a 25 ans.")).isNotEmpty();
        assertThat(DETECTOR.detect("I am 25.")).isNotEmpty();
        assertThat(DETECTOR.detect("Tel.: +32 2 123 45 67 — Fax: +32 2 123 45 68 — Bruxelles"))
                .isNotEmpty();
    }

    @Test
    void rejectsTextWithoutLetters() {
        assertThat(DETECTOR.detect("")).isEmpty();
        assertThat(DETECTOR.detect(" \t\n ")).isEmpty();
        assertThat(DETECTOR.detect("1234 5678, 90.12 -- 3/4")).isEmpty();
        assertThat(DETECTOR.detect("!?…—«»")).isEmpty();
    }

    @Test
    void readsDecomposedAccentsAsComposedOnes() {
        var text = "Le garçon a été très étonné de voir l’éléphant traverser la forêt près de chez lui.";
        assertThat(DETECTOR.detect(Normalizer.normalize(text, Normalizer.Form.NFD)))
                .isEqualTo(DETECTOR.detect(text));
        assertThat(top(DETECTOR.detect(text))).isEqualTo("fr");
    }

    @Test
    void ignoresCase() {
        var text = "Der Hund läuft schnell über die Straße, weil er seinen Ball sucht.";
        assertThat(DETECTOR.detect(text.toUpperCase(Locale.ROOT))).isEqualTo(DETECTOR.detect(text));
    }

    @Test
    void readsOnlyASampleOfALongText() {
        var sentence = "Le renard brun saute par-dessus le chien paresseux, puis il retourne dans la forêt. ";
        var text = new CountingText(sentence, 50_000_000);
        assertThat(top(DETECTOR.detect(text))).isEqualTo("fr");
        assertThat(text.reads()).isBetween(2_000L, 3_000L);
    }

    @Test
    void sharesTheModelBetweenDetectors() {
        var text = "This is a sentence in English, with a few common words.";
        assertThat(new NaiveBayesDetector().detect(text)).isEqualTo(DETECTOR.detect(text));
    }

    @Test
    void rejectsNull() {
        Function<CharSequence, List<Guess>> detect = DETECTOR::detect;
        assertThatNullPointerException().isThrownBy(() -> detect.apply(null)).withMessage("text");
    }

    private static Map<String, List<HeldOutSamples>> samplesOfLength(int length, boolean known) {
        return HeldOutSamples.all().stream()
                .filter(sample -> sample.length() == length && (sample.expected() != null) == known)
                .collect(groupingBy(HeldOutSamples::name, TreeMap::new, toList()));
    }

    private static void assertDetected(String language, String text) {
        assertThat(top(DETECTOR.detect(text))).as(text).isEqualTo(language);
    }

    private static String top(List<Guess> guesses) {
        return guesses.isEmpty() ? "-" : guesses.getFirst().language().getLanguage();
    }

    /// A long text that counts the chars read from it.
    private static final class CountingText implements CharSequence {

        private final String unit;
        private final int length;
        private long reads;

        CountingText(String unit, int length) {
            this.unit = unit;
            this.length = length;
        }

        long reads() {
            return reads;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            reads++;
            return unit.charAt(index % unit.length());
        }

        @Override
        public void getChars(int start, int end, char[] destination, int offset) {
            reads += end - start;
            for (var i = start; i < end; i++) {
                destination[offset + i - start] = unit.charAt(i % unit.length());
            }
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            throw new UnsupportedOperationException("The detector reads chars one by one");
        }

        @Override
        public String toString() {
            throw new UnsupportedOperationException("The detector reads chars one by one");
        }
    }
}
