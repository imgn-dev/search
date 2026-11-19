package be.imgn.search.langdetect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BinomialTest {

    @Test
    void boundsARateAfterWilson() {
        assertThat(Binomial.lowerBound(100, 100)).isCloseTo(0.9737, within(1e-4));
        assertThat(Binomial.lowerBound(50, 100)).isCloseTo(0.4188, within(1e-4));
        assertThat(Binomial.upperBound(50, 100)).isCloseTo(0.5812, within(1e-4));
        assertThat(Binomial.upperBound(0, 0)).isEqualTo(1);
        assertThat(Binomial.lowerBound(0, 0)).isZero();
    }

    @Test
    void findsTheShareReachedWithAProbabilityOf99Percent() {
        // With p = 0.9 and 100 trials, P(X <= 81) = 0.0046 and P(X <= 82) = 0.0100: X >= 82 with 99.5%.
        assertThat(Binomial.lowerQuantile(100, 0.9)).isEqualTo(0.82);
        assertThat(Binomial.lowerQuantile(10, 0)).isZero();
        assertThat(Binomial.lowerQuantile(0, 0.5)).isZero();
    }
}
