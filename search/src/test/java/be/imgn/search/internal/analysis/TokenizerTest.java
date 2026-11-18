package be.imgn.search.internal.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.TermBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    private static List<String> words(String text) {
        var words = new ArrayList<String>();
        new Tokenizer(new TermBuffer()).tokenize(text, word -> words.add(word.toString()));
        return words;
    }

    @Test
    void splitsOnSpacesAndPunctuation() {
        assertThat(words("Le renard, brun; saute!")).containsExactly("Le", "renard", "brun", "saute");
    }

    @Test
    void splitsOnHyphens() {
        assertThat(words("COVID-19 e-mail")).containsExactly("COVID", "19", "e", "mail");
    }

    @Test
    void keepsAnApostropheBetweenTwoLetters() {
        assertThat(words("l'arbre aujourd'hui")).containsExactly("l'arbre", "aujourd'hui");
    }

    @Test
    void readsTheTypographicApostropheAsAnAsciiOne() {
        assertThat(words("l’arbre")).containsExactly("l'arbre");
    }

    @Test
    void dropsAnApostropheThatIsNotBetweenTwoLetters() {
        assertThat(words("'quoted' rock'n'roll 90's")).containsExactly("quoted", "rock'n'roll", "90", "s");
    }

    @Test
    void keepsADecomposedAccentOnItsLetter() {
        assertThat(words("élève")).containsExactly("élève");
    }

    @Test
    void dropsACombiningMarkWithoutALetter() {
        assertThat(words("́abc")).containsExactly("abc");
    }

    @Test
    void dropsASoftHyphenInsideAWord() {
        assertThat(words("Donau­dampf­schiff")).containsExactly("Donaudampfschiff");
    }

    @Test
    void splitsGreekAndCyrillic() {
        assertThat(words("Καλημέρα κόσμε, Привет мир")).containsExactly("Καλημέρα", "κόσμε", "Привет", "мир");
    }

    @Test
    void keepsSupplementaryLetters() {
        assertThat(words("a𝐀b c")).containsExactly("a𝐀b", "c");
    }

    @Test
    void skipsAWordLongerThanTheMaximum() {
        var tooLong = "x".repeat(Tokenizer.MAX_LENGTH + 1);
        var longest = "y".repeat(Tokenizer.MAX_LENGTH);

        assertThat(words("a " + tooLong + " b " + longest)).containsExactly("a", "b", longest);
    }

    @Test
    void findsNoWordInPunctuation() {
        assertThat(words("!!! --- ...")).isEmpty();
        assertThat(words("")).isEmpty();
    }
}
