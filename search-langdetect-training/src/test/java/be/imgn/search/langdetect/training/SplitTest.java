package be.imgn.search.langdetect.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class SplitTest {

    @Test
    void splitsTheTextsAboutEightyTenTen() {
        var counts = new EnumMap<Split, Integer>(Split.class);
        for (var i = 1; i <= 100_000; i++) {
            counts.merge(Split.of("sentence " + i + " "), 1, Integer::sum);
        }
        assertThat(counts.get(Split.TRAINING)).isBetween(79_000, 81_000);
        assertThat(counts.get(Split.VALIDATION)).isBetween(9_500, 10_500);
        assertThat(counts.get(Split.TEST)).isBetween(9_500, 10_500);
    }

    @Test
    void putsTheSameTextInTheSamePart() {
        for (var i = 1; i <= 1_000; i++) {
            assertThat(Split.of("sentence " + i + " ")).isEqualTo(Split.of(new String("sentence " + i + " ")));
        }
        assertThat(Split.of("hello world ")).isEqualTo(Split.TRAINING);
    }

    @Test
    void selectsAboutTheRate() {
        var selected = 0;
        for (var id = 1L; id <= 100_000; id++) {
            if (Hashes.selected(id, 0.25)) {
                selected++;
            }
        }
        assertThat(selected).isBetween(24_000, 26_000);
        assertThat(Hashes.selected(42, 1)).isTrue();
        assertThat(Hashes.selected(42, 0)).isFalse();
    }
}
