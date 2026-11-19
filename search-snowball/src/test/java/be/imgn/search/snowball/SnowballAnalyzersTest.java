package be.imgn.search.snowball;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.TermBuffer;
import be.imgn.search.WordAnalyzer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class SnowballAnalyzersTest {

    private static final Map<String, String> ALGORITHMS = Map.ofEntries(
            Map.entry("eu", "basque"),
            Map.entry("ca", "catalan"),
            Map.entry("cs", "czech"),
            Map.entry("da", "danish"),
            Map.entry("nl", "dutch"),
            Map.entry("en", "english"),
            Map.entry("eo", "esperanto"),
            Map.entry("et", "estonian"),
            Map.entry("fi", "finnish"),
            Map.entry("fr", "french"),
            Map.entry("de", "german"),
            Map.entry("el", "greek"),
            Map.entry("hu", "hungarian"),
            Map.entry("ga", "irish"),
            Map.entry("it", "italian"),
            Map.entry("lt", "lithuanian"),
            Map.entry("no", "norwegian"),
            Map.entry("pl", "polish"),
            Map.entry("pt", "portuguese"),
            Map.entry("ro", "romanian"),
            Map.entry("ru", "russian"),
            Map.entry("sr", "serbian"),
            Map.entry("es", "spanish"),
            Map.entry("sv", "swedish"),
            Map.entry("uk", "ukrainian"));

    @Test
    void coversTheEuropeanLanguagesInOrder() {
        assertThat(SnowballAnalyzers.all())
                .extracting(analyzer -> analyzer.language().toLanguageTag())
                .containsExactlyInAnyOrderElementsOf(ALGORITHMS.keySet());
        assertThat(SnowballAnalyzers.all())
                .extracting(SnowballAnalyzersTest::algorithm)
                .isSorted();
    }

    @Test
    void returnsAnImmutableList() {
        var all = SnowballAnalyzers.all();
        var first = all.getFirst();

        assertThatThrownBy(() -> all.add(first)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void namesTheBuildsSnowballReleaseInEachIdentifier() {
        var release = Objects.requireNonNull(System.getProperty("snowball.version"), "snowball.version");

        assertThat(SnowballAnalyzers.all())
                .allSatisfy(analyzer -> assertThat(analyzer.id())
                        .isEqualTo("snowball-" + release + "/"
                                + ALGORITHMS.get(analyzer.language().getLanguage())));
    }

    @ParameterizedTest
    @CsvSource({
        "fr, fr",
        "fr-BE, fr",
        "fr-CA-x-test, fr",
        "de-CH, de",
        "no, no",
        "nb, no",
        "nn-NO, no",
        "el-GR, el",
        "sr-Latn-RS, sr",
    })
    void findsTheAnalyzerOfALanguageByItsPrimarySubtag(String tag, String language) {
        var analyzer = SnowballAnalyzers.forLanguage(Locale.forLanguageTag(tag));

        assertThat(analyzer).map(LanguageAnalyzer::language).contains(Locale.of(language));
        assertThat(analyzer)
                .map(LanguageAnalyzer::id)
                .hasValueSatisfying(id -> assertThat(id).endsWith("/" + ALGORITHMS.get(language)));
    }

    @Test
    void findsNoAnalyzerForALanguageWithoutAStemmer() {
        assertThat(SnowballAnalyzers.forLanguage(Locale.JAPANESE)).isEmpty();
        assertThat(SnowballAnalyzers.forLanguage(Locale.of("tr"))).isEmpty();
    }

    @Test
    void rejectsALocaleWithoutALanguage() {
        assertThatThrownBy(() -> SnowballAnalyzers.forLanguage(Locale.ROOT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SnowballAnalyzers.forLanguage(Locale.forLanguageTag("und-FR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        // Through Function and Consumer, whose parameters are unannotated, so the null reaches the methods as
        // callers can send it.
        Function<Locale, Optional<LanguageAnalyzer>> forLanguage = SnowballAnalyzers::forLanguage;
        Consumer<TermBuffer> words = analyzer("fr").newWordAnalyzer()::analyze;

        assertThatNullPointerException().isThrownBy(() -> forLanguage.apply(null));
        assertThatNullPointerException().isThrownBy(() -> words.accept(null));
    }

    @Test
    void returnsTheSameAnalyzersFromBothMethods() {
        for (var analyzer : SnowballAnalyzers.all()) {
            assertThat(SnowballAnalyzers.forLanguage(analyzer.language())).containsSame(analyzer);
        }
    }

    static List<LanguageAnalyzer> analyzers() {
        return SnowballAnalyzers.all();
    }

    @ParameterizedTest
    @MethodSource("analyzers")
    void stemsTheWordsOfSnowballsOwnVocabulary(LanguageAnalyzer analyzer) throws IOException {
        var pairs = samples(algorithm(analyzer));
        var words = analyzer.newWordAnalyzer();

        assertThat(pairs).hasSizeGreaterThanOrEqualTo(8);
        for (var pair : pairs) {
            assertThat(analyze(words, pair.getFirst())).as(pair.getFirst()).isEqualTo(pair.getLast());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "fr, l'arbre, arbre",
        "fr, d'arbres, arbres",
        "fr, qu'importe, importe",
        "fr, jusqu'alors, alors",
        "fr, lorsqu'elle, elle",
        "fr, c'était, était",
        "it, dell'albero, albero",
        "it, l'amica, amica",
        "it, un'amica, amica",
        "it, sugl'alberi, alberi",
        "ca, l'home, home",
        "ca, d'aigua, aigua",
        "ga, d'ól, ól",
        "ga, b'fhéidir, fhéidir",
    })
    void elidesTheArticleBeforeStemming(String language, String word, String rest) {
        var words = analyzer(language).newWordAnalyzer();

        assertThat(analyze(words, word)).isEqualTo(analyze(analyzer(language).newWordAnalyzer(), rest));
    }

    @ParameterizedTest
    @CsvSource({"fr, aujourd'hui", "fr, prud'homme", "it, senz'altro", "en, l'arbre", "de, d'arbre"})
    void keepsAnApostropheThatEndsNoArticle(String language, String word) {
        assertThat(analyze(analyzer(language).newWordAnalyzer(), word)).contains("'");
    }

    @Test
    void elidesOnlyBeforeSomething() {
        var words = analyzer("fr").newWordAnalyzer();

        assertThat(analyze(words, "l")).isEqualTo("l");
    }

    @Test
    void makesAFreshWordAnalyzerEachTime() {
        for (var analyzer : SnowballAnalyzers.all()) {
            assertThat(analyzer.newWordAnalyzer()).isNotSameAs(analyzer.newWordAnalyzer());
        }
    }

    @Test
    void makesIndependentWordAnalyzers() {
        var french = analyzer("fr");
        var first = french.newWordAnalyzer();
        var second = french.newWordAnalyzer();
        var firstWord = new TermBuffer();
        var secondWord = new TermBuffer();

        firstWord.set("renards");
        secondWord.set("continuellement");
        first.analyze(firstWord);
        second.analyze(secondWord);

        assertThat(firstWord).hasToString("renard");
        assertThat(secondWord).hasToString("continuel");
        assertThat(analyze(first, "maisons")).isEqualTo("maison");
        assertThat(secondWord).hasToString("continuel");
    }

    @Test
    void stemsWordsOneAfterTheOtherInTheSameBuffer() {
        var words = analyzer("de").newWordAnalyzer();
        var word = new TermBuffer();

        word.set("häuser");
        words.analyze(word);
        assertThat(word).hasToString("haus");
        word.set("katzen");
        words.analyze(word);
        assertThat(word).hasToString("katz");
    }

    private static LanguageAnalyzer analyzer(String language) {
        return SnowballAnalyzers.forLanguage(Locale.of(language)).orElseThrow();
    }

    private static String algorithm(LanguageAnalyzer analyzer) {
        return analyzer.id().substring(analyzer.id().indexOf('/') + 1);
    }

    private static String analyze(WordAnalyzer words, String text) {
        var word = new TermBuffer();
        word.set(text);
        words.analyze(word);
        return word.toString();
    }

    /// The word and stem pairs of a language, sampled from snowball-data.
    private static List<List<String>> samples(String algorithm) throws IOException {
        var resource = "data/" + algorithm + ".txt";
        try (var in = SnowballAnalyzersTest.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(in, resource);
            var reader = new BufferedReader(new InputStreamReader(in, UTF_8));
            return reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> List.of(line.split(" ")))
                    .toList();
        }
    }
}
