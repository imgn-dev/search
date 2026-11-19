package be.imgn.search.internal.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.search.TermBuffer;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    private static final int[] BLOCK_SIZES = {3, 4, 5, 7, 16, 61, Tokenizer.BLOCK_SIZE};

    private static List<String> words(String text) {
        var words = new ArrayList<String>();
        new Tokenizer(new TermBuffer()).tokenize(text, word -> words.add(word.toString()));
        return words;
    }

    private static List<String> words(Reader text, int blockSize, StringBuilder read) throws IOException {
        var words = new ArrayList<String>();
        new Tokenizer(new TermBuffer(), blockSize)
                .tokenize(
                        text,
                        word -> words.add(word.toString()),
                        (chars, from, to) -> read.append(chars, from, to - from));
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

    @Test
    void splitsAReaderIntoTheWordsOfTheWholeText() throws IOException {
        var random = new Random(42);
        for (var round = 0; round < 2000; round++) {
            var text = RandomTexts.text(random, random.nextInt(600));
            var blockSize = BLOCK_SIZES[random.nextInt(BLOCK_SIZES.length)];
            var most = 1 + random.nextInt(blockSize + 1);

            var read = new StringBuilder();
            var streamed = words(RandomTexts.choppy(text, most), blockSize, read);

            assertThat(streamed)
                    .as("%s in blocks of %d, reads of %d", text, blockSize, most)
                    .isEqualTo(words(text));
            assertThat(read.toString()).isEqualTo(text);
        }
    }

    @Test
    void splitsAReaderAtEveryBlockBoundary() throws IOException {
        // Each edge case placed at every offset around the end of the first block.
        String[] cases = {
            "l'arbre",
            "l’arbre",
            "a' b",
            "x'𝐀y",
            "Donau­dampf",
            "é́t́e",
            "a𝐀b",
            "\uD835 a",
            "a \uDC00b",
            "x".repeat(Tokenizer.MAX_LENGTH) + " y",
            "x".repeat(Tokenizer.MAX_LENGTH + 1) + " y"
        };
        for (var blockSize : BLOCK_SIZES) {
            for (var edge : cases) {
                for (var shift = Math.max(0, blockSize - 8); shift < blockSize + 3; shift++) {
                    var text = "ab cd efgh ".repeat(shift / 11 + 1).substring(0, shift) + edge + " fin";
                    var read = new StringBuilder();

                    assertThat(words(new StringReader(text), blockSize, read))
                            .as("%s in blocks of %d", text, blockSize)
                            .isEqualTo(words(text));
                    assertThat(read.toString()).isEqualTo(text);
                }
            }
        }
    }

    @Test
    void splitsAnEmptyReader() throws IOException {
        var read = new StringBuilder();

        assertThat(words(new StringReader(""), 3, read)).isEmpty();
        assertThat(read.toString()).isEmpty();
    }

    @Test
    void forgetsAFailedReader() {
        var tokenizer = new Tokenizer(new TermBuffer(), 4);
        var failing = new Reader() {
            private boolean readOnce;

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                if (readOnce) {
                    throw new IOException("Broken");
                }
                readOnce = true;
                "unfinished".getChars(0, length, buffer, offset);
                return length;
            }

            @Override
            public void close() {}
        };

        assertThatThrownBy(() -> tokenizer.tokenize(failing, word -> {}, (chars, from, to) -> {}))
                .isInstanceOf(IOException.class);
        var words = new ArrayList<String>();
        tokenizer.tokenize("le renard", word -> words.add(word.toString()));
        assertThat(words).containsExactly("le", "renard");
    }
}
