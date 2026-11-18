package be.imgn.search.internal.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.TermBuffer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NormalizationTest {

    private static String apply(Consumer<TermBuffer> step, String text) {
        var word = new TermBuffer();
        word.set(text);
        step.accept(word);
        return word.toString();
    }

    @Test
    void lowercasesAscii() {
        assertThat(apply(Normalization::normalizeAndLowercase, "HeLLo42")).isEqualTo("hello42");
    }

    @Test
    void lowercasesOtherScripts() {
        assertThat(apply(Normalization::normalizeAndLowercase, "ÉLÈVE ΣΟΦΙΑ ПРИВЕТ"))
                .isEqualTo("élève σοφια привет");
    }

    @Test
    void lowercasesTheDottedCapitalIWithoutAddingADot() {
        assertThat(apply(Normalization::normalizeAndLowercase, "İstanbul")).isEqualTo("istanbul");
    }

    @Test
    void composesDecomposedAccents() {
        assertThat(apply(Normalization::normalizeAndLowercase, "Élève")).isEqualTo("élève");
    }

    @Test
    void foldsCompatibilityForms() {
        assertThat(apply(Normalization::normalizeAndLowercase, "ﬁＦ")).isEqualTo("fif");
    }

    @Test
    void foldsSharpSAndFinalSigma() {
        assertThat(apply(Normalization::fold, "straße")).isEqualTo("strasse");
        assertThat(apply(Normalization::fold, "ßßa")).isEqualTo("ssssa");
        assertThat(apply(Normalization::fold, "λόγος")).isEqualTo("λόγοσ");
    }

    @Test
    void matchesCapitalAndSmallSharpS() {
        var capital = apply(
                w -> {
                    Normalization.normalizeAndLowercase(w);
                    Normalization.fold(w);
                },
                "STRASSE");
        var small = apply(
                w -> {
                    Normalization.normalizeAndLowercase(w);
                    Normalization.fold(w);
                },
                "Straße");

        assertThat(capital).isEqualTo(small).isEqualTo("strasse");
    }

    @Test
    void removesAccents() {
        assertThat(apply(Normalization::removeAccents, "élève")).isEqualTo("eleve");
        assertThat(apply(Normalization::removeAccents, "ąčęėįšųūž")).isEqualTo("aceeisuuz");
        assertThat(apply(Normalization::removeAccents, "καλημέρα")).isEqualTo("καλημερα");
    }

    @Test
    void replacesLettersThatDoNotDecompose() {
        assertThat(apply(Normalization::removeAccents, "søster łódź đak æble cœur ðið þorn"))
                .isEqualTo("soster lodz dak aeble coeur did thorn");
    }

    @Test
    void leavesAsciiAlone() {
        assertThat(apply(Normalization::removeAccents, "plain")).isEqualTo("plain");
    }
}
