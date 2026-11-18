package be.imgn.search.internal.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IntSorterTest {

    @Test
    void sortsStably() {
        var random = new Random(7);
        var keys = new int[1000];
        for (var i = 0; i < keys.length; i++) {
            keys[i] = random.nextInt(50);
        }
        var values = new int[keys.length];
        Arrays.setAll(values, i -> i);

        IntSorter.sort(values, values.length, (a, b) -> Integer.compare(keys[a], keys[b]));

        for (var i = 1; i < values.length; i++) {
            assertThat(keys[values[i - 1]]).isLessThanOrEqualTo(keys[values[i]]);
            if (keys[values[i - 1]] == keys[values[i]]) {
                assertThat(values[i - 1]).isLessThan(values[i]);
            }
        }
    }
}
