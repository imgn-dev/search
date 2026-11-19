package be.imgn.search.langdetect.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NGramsTest {

    private static final Alphabet ALPHABET = new Alphabet(new char[] {'a', 'b', 'c'});
    private static final int B = Alphabet.BOUNDARY;
    private static final int A = 3;
    private static final int BB = 4;
    private static final int C = 5;

    @Test
    void packsTheNGramsOfEachWord() {
        assertThat(keys("Ab, c!"))
                .containsExactly(
                        A,
                        B << 8 | A,
                        BB,
                        A << 8 | BB,
                        B << 16 | A << 8 | BB,
                        BB << 8 | B,
                        A << 16 | BB << 8 | B,
                        C,
                        B << 8 | C,
                        C << 8 | B,
                        B << 16 | C << 8 | B);
    }

    @Test
    void encodesTheLettersOutsideTheAlphabetAsRare() {
        assertThat(keys("x"))
                .containsExactly(
                        Alphabet.RARE,
                        B << 8 | Alphabet.RARE,
                        Alphabet.RARE << 8 | B,
                        B << 16 | Alphabet.RARE << 8 | B);
    }

    @Test
    void tellsTheOrderOfAKey() {
        assertThat(NGrams.order(A)).isEqualTo(1);
        assertThat(NGrams.order(B << 8 | A)).isEqualTo(2);
        assertThat(NGrams.order(A << 8 | B)).isEqualTo(2);
        assertThat(NGrams.order(B << 16 | A << 8 | B)).isEqualTo(3);
    }

    private static int[] keys(String text) {
        var normalized = Text.of(text);
        assertThat(normalized.normalize()).isEqualTo(Script.LATIN);
        var keys = new int[NGrams.capacity(normalized.length())];
        return Arrays.copyOf(keys, NGrams.keys(normalized, ALPHABET, keys));
    }
}
