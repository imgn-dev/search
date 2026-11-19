package be.imgn.search.internal.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.TermBuffer;
import be.imgn.search.WordAnalyzer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FieldAnalysisTest {

    /// A toy French analyzer: drops an elided "l'" and a final "s".
    private static final WordAnalyzer FRENCH = word -> {
        var text = word.toString();
        if (text.startsWith("l'")) {
            text = text.substring(2);
        }
        if (text.endsWith("s")) {
            text = text.substring(0, text.length() - 1);
        }
        word.set(text);
    };

    private final FieldAnalysis analysis = new FieldAnalysis();

    @Test
    void givesNeutralTermsAtConsecutivePositions() {
        var neutral = new Recorder();
        var language = new Recorder();

        var next = analysis.text("Le Renard BRUN", 0, false, null, neutral, language);

        assertThat(neutral.terms()).containsExactly(new Term("le", 0), new Term("renard", 1), new Term("brun", 2));
        assertThat(language.terms()).isEmpty();
        assertThat(next).isEqualTo(2 + FieldAnalysis.VALUE_GAP);
    }

    @Test
    void splitsNeutralTermsAtApostrophes() {
        var neutral = new Recorder();

        analysis.text("l’arbre d'aujourd'hui", 0, false, null, neutral, new Recorder());

        assertThat(neutral.terms())
                .containsExactly(
                        new Term("l", 0),
                        new Term("arbre", 1),
                        new Term("d", 2),
                        new Term("aujourd", 3),
                        new Term("hui", 4));
    }

    @Test
    void givesLanguageTermsFromTheWholeWord() {
        var neutral = new Recorder();
        var language = new Recorder();

        analysis.text("L’arbres verts", 10, false, FRENCH, neutral, language);

        assertThat(language.terms()).containsExactly(new Term("arbre", 10), new Term("vert", 12));
        assertThat(neutral.terms()).containsExactly(new Term("l", 10), new Term("arbres", 11), new Term("verts", 12));
    }

    @Test
    void dropsALanguageTermTheAnalyzerEmpties() {
        var language = new Recorder();

        analysis.text("s", 0, false, FRENCH, new Recorder(), language);

        assertThat(language.terms()).isEmpty();
    }

    @Test
    void removesAccentsAfterTheLanguageAnalysis() {
        var neutral = new Recorder();
        var language = new Recorder();
        WordAnalyzer seesAccents = word -> assertThat(word.toString()).isEqualTo("élèves");

        analysis.text("Élèves", 0, true, seesAccents, neutral, language);

        assertThat(neutral.terms()).containsExactly(new Term("eleves", 0));
        assertThat(language.terms()).containsExactly(new Term("eleves", 0));
    }

    @Test
    void foldsBothKindsOfTerms() {
        var neutral = new Recorder();
        var language = new Recorder();

        analysis.text("STRASSE Straße", 0, false, word -> {}, neutral, language);

        assertThat(neutral.terms()).extracting(Term::text).containsExactly("strasse", "strasse");
        assertThat(language.terms()).extracting(Term::text).containsExactly("strasse", "strasse");
    }

    @Test
    void keepsThePositionOfAValueWithoutWords() {
        var neutral = new Recorder();

        assertThat(analysis.text("!!!", 7, false, null, neutral, new Recorder()))
                .isEqualTo(7);
        assertThat(neutral.terms()).isEmpty();
    }

    @Test
    void makesAKeywordOneTerm() {
        var sink = new Recorder();

        analysis.keyword("New York – Straße", sink);

        assertThat(sink.terms()).containsExactly(new Term("new york – strasse", 0));
    }

    @Test
    void givesNothingForAnEmptyKeyword() {
        var sink = new Recorder();

        analysis.keyword("", sink);

        assertThat(sink.terms()).isEmpty();
    }

    @Test
    void analysesAReaderIntoTheTermsOfTheWholeText() throws IOException {
        var random = new Random(7);
        for (var round = 0; round < 1000; round++) {
            var text = RandomTexts.text(random, random.nextInt(800));
            var blockSize = 3 + random.nextInt(40);
            var removeAccents = random.nextBoolean();
            var position = random.nextInt(1000);
            var whole = new FieldAnalysis();
            var wholeNeutral = new Recorder();
            var wholeLanguage = new Recorder();
            var streamed = new FieldAnalysis(blockSize);
            var streamedNeutral = new Recorder();
            var streamedLanguage = new Recorder();
            var read = new StringBuilder();

            var next = whole.text(text, position, removeAccents, FRENCH, wholeNeutral, wholeLanguage);
            var streamedNext = streamed.text(
                    RandomTexts.choppy(text, 1 + random.nextInt(blockSize)),
                    position,
                    removeAccents,
                    FRENCH,
                    streamedNeutral,
                    streamedLanguage,
                    (chars, from, to) -> read.append(chars, from, to - from));

            assertThat(streamedNeutral.terms()).as(text).isEqualTo(wholeNeutral.terms());
            assertThat(streamedLanguage.terms()).as(text).isEqualTo(wholeLanguage.terms());
            assertThat(streamedNext).isEqualTo(next);
            assertThat(read.toString()).isEqualTo(text);
        }
    }

    @Test
    void reusesItsBuffersAcrossValues() {
        var neutral = new Recorder();
        var buffer = new TermBuffer();
        buffer.set("unrelated");

        analysis.text("a", 0, false, null, neutral, new Recorder());
        analysis.text("b", 0, false, null, neutral, new Recorder());

        assertThat(neutral.terms()).extracting(Term::text).containsExactly("a", "b");
    }

    private record Term(String text, int position) {}

    private static final class Recorder implements TermSink {
        private final List<Term> terms = new ArrayList<>();

        @Override
        public void term(char[] chars, int length, int position) {
            terms.add(new Term(new String(chars, 0, length), position));
        }

        List<Term> terms() {
            return terms;
        }
    }
}
