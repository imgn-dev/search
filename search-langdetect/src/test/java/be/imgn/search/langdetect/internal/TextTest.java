package be.imgn.search.langdetect.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void keepsTheLettersOfTheDominantScriptLowercased() {
        assertThat(normalize("Hello, World! 42 times.")).isEqualTo("hello world times ");
        assertThat(script("Hello, World!")).isEqualTo(Script.LATIN);
    }

    @Test
    void dropsTheLettersOfOtherScripts() {
        assertThat(normalize("Москва — столица России (Moscow)")).isEqualTo("москва столица россии ");
        assertThat(script("Москва — столица России (Moscow)")).isEqualTo(Script.CYRILLIC);
        assertThat(script("Καλημέρα κόσμε")).isEqualTo(Script.GREEK);
    }

    @Test
    void findsTheDominantScriptAmongTheLettersOnly() {
        assertThat(normalize("Il a 25 ans.")).isEqualTo("il a ans ");
        assertThat(normalize("I am 25.")).isEqualTo("i am ");
        assertThat(script("Tel.: +32 2 123 45 67 — Fax: +32 2 123 45 68 — Bruxelles"))
                .isEqualTo(Script.LATIN);
        assertThat(script("Δ: 12,5 % — 1.234.567")).isEqualTo(Script.GREEK);
    }

    @Test
    void keepsTheApostrophesWithinWords() {
        assertThat(normalize("L’arbre d'aujourd'hui, 'quoted' l' ")).isEqualTo("l'arbre d'aujourd'hui quoted l ");
        assertThat(normalize("м’ясо пʼять")).isEqualTo("м'ясо п'ять ");
    }

    @Test
    void composesAccents() {
        var text = "Élève à l’école";
        assertThat(normalize(Normalizer.normalize(text, Normalizer.Form.NFD))).isEqualTo("élève à l'école ");
    }

    @Test
    void keepsAWordWhoseMarkCannotBeComposed() {
        assertThat(normalize("мо́локо")).isEqualTo("молоко ");
    }

    @Test
    void findsNoScriptWithoutLetters() {
        assertThat(Text.of("").normalize()).isNull();
        assertThat(Text.of("12 34 !?").normalize()).isNull();
    }

    @Test
    void findsNoScriptWhenOtherScriptsDominate() {
        var text = "東京は日本の首都 Tokyo";
        assertThat(Text.of(text).normalize()).isNull();
    }

    @Test
    void samplesAShortTextWhole() {
        var text = "Short text.";
        assertThat(Text.sample(text)).hasToString(text);
    }

    @Test
    void samplesALongTextInChunksOfWholeWords() {
        var text = "lorem ipsum dolor sit amet ".repeat(10_000);
        var sample = Text.sample(text);

        assertThat(sample.length()).isBetween(Text.SAMPLE_LENGTH, Text.SAMPLE_LENGTH * 2);
        for (var word : sample.toString().split(" +", -1)) {
            assertThat(word).isIn("lorem", "ipsum", "dolor", "sit", "amet", "");
        }
    }

    private static String normalize(String text) {
        var normalized = Text.of(text);
        script(normalized);
        return normalized.toString();
    }

    private static Script script(String text) {
        return script(Text.of(text));
    }

    private static Script script(Text text) {
        var script = text.normalize();
        if (script == null) {
            throw new AssertionError("No script in " + text);
        }
        return script;
    }
}
