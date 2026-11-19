package be.imgn.search.langdetect.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SamplesTest {

    @Test
    void joinsSentencesUntilTheLengthThenCutsAtASpace() {
        var sentences = List.of("One two three.", "Four five six.", "Seven eight nine.", "Ten eleven.");

        assertThat(Samples.build(sentences.iterator(), 20, 10))
                .containsExactly("One two three. Four", "Seven eight nine.");
    }

    @Test
    void cutsAtTheLengthWithoutASpaceLateEnough() {
        assertThat(Samples.build(List.of("Abcdefghijklmnopqrstuvwxyz").iterator(), 10, 10))
                .containsExactly("Abcdefghij");
    }

    @Test
    void buildsAtMostTheMaximum() {
        var sentences = List.of("aaaa bbbb.", "cccc dddd.", "eeee ffff.");
        assertThat(Samples.build(sentences.iterator(), 5, 2)).hasSize(2);
    }

    @Test
    void givesEachLengthItsOwnSentences() {
        var sentences = new ArrayList<String>();
        for (var i = 0; i < 1_000; i++) {
            sentences.add("Sentence number " + i + " is here.");
        }

        var samples = Samples.of(Map.of("eng", sentences), List.of(10, 10, 2));

        var used = new HashSet<String>();
        for (var sample : samples) {
            for (var sentence : sample.text().split("(?<=\\.) ", -1)) {
                if (sentence.endsWith(".")) {
                    assertThat(used.add(sentence)).as(sentence).isTrue();
                }
            }
        }
        assertThat(samples).filteredOn(sample -> sample.length() == 50).hasSize(10);
        assertThat(samples).filteredOn(sample -> sample.length() == 200).hasSize(10);
        assertThat(samples).filteredOn(sample -> sample.length() == 1_000).hasSize(2);
    }

    @Test
    void sharesTheSentencesEquallyBetweenTheLengthsWhenTheyAreTooFew() {
        var sentences = new ArrayList<String>();
        for (var i = 0; i < 50; i++) {
            sentences.add("Sentence number " + i + " is here.");
        }

        // About 1,400 chars for 10 * 50 + 10 * 200 + 2 * 1,000 = 4,500 wanted: about 460 chars per length.
        var samples = Samples.of(Map.of("eng", sentences), List.of(10, 10, 2));

        assertThat(samples).filteredOn(sample -> sample.length() == 50).hasSize(9);
        assertThat(samples).filteredOn(sample -> sample.length() == 200).hasSize(2);
        assertThat(samples).filteredOn(sample -> sample.length() == 1_000).isEmpty();
    }

    @Test
    void turnsControlCharsIntoSpaces() {
        var lineSeparator = Character.toString(0x2028);
        assertThat(Samples.build(
                        List.of("Tab\there," + lineSeparator + "line\nend.").iterator(), 19, 1))
                .containsExactly("Tab here, line end.");
    }
}
