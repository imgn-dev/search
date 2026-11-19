package be.imgn.search.langdetect.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ScriptModelTest {

    @Test
    void scoresTheNGramsTheTrainingToolCounts() throws IOException {
        Model model;
        try (var in = Objects.requireNonNull(Model.class.getResourceAsStream("languages.model"))) {
            model = ModelFormat.read(in.readAllBytes());
        }
        var latin = Objects.requireNonNull(model.script(Script.LATIN));
        var random = new Random(42);
        var letters = "abcdefghijklmnopqrstuvwxyzéèàçñøåäöüßłżšœ' ".toCharArray();
        for (var round = 0; round < 200; round++) {
            // Long enough for the sums of the lanes to be flushed on the way.
            var chars = new char[1 + random.nextInt(3_000)];
            for (var i = 0; i < chars.length; i++) {
                chars[i] = letters[random.nextInt(letters.length)];
            }
            var text = Text.of(new String(chars) + "x");
            assertThat(text.normalize()).isEqualTo(Script.LATIN);

            var totals = new int[latin.languages().size()];
            var count = latin.score(text, model.alphabet(), totals);

            assertThat(count).isEqualTo(expectedCount(text, model.alphabet()));
            assertThat(totals).containsExactly(expectedTotals(latin, text, model.alphabet()));
        }
    }

    private static int expectedCount(Text text, Alphabet alphabet) {
        return NGrams.keys(text, alphabet, new int[NGrams.capacity(text.length())]);
    }

    // The totals from the keys the training tool counts, looked up one by one in the model's tables.
    private static int[] expectedTotals(ScriptModel script, Text text, Alphabet alphabet) {
        var keys = new int[NGrams.capacity(text.length())];
        var count = NGrams.keys(text, alphabet, keys);
        var size = script.languages().size();
        var tableKeys = script.keys();
        var costs = script.costs();
        var defaults = script.defaults();
        var totals = new int[size];
        for (var i = 0; i < count; i++) {
            var row = Arrays.binarySearch(tableKeys, keys[i]);
            for (var language = 0; language < size; language++) {
                totals[language] += row >= 0
                        ? costs[row * size + language] & 0xFF
                        : defaults[(NGrams.order(keys[i]) - 1) * size + language] & 0xFF;
            }
        }
        return totals;
    }
}
