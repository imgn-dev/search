package be.imgn.search.langdetect;

/// Confidence bounds of a rate measured on samples, and quantiles of the binomial distribution.
final class Binomial {

    // The one-sided 95% quantile of the standard normal distribution.
    private static final double Z = 1.6449;
    // The chance a test may fail by bad luck.
    private static final double RISK = 0.01;

    private Binomial() {}

    /// The 95% lower confidence bound of a rate of `successes` in `trials`, after Wilson; 0 without trials.
    static double lowerBound(int successes, int trials) {
        return trials == 0 ? 0 : Math.max(0, wilson(successes, trials, -1));
    }

    /// The 95% upper confidence bound of a rate of `successes` in `trials`, after Wilson; 1 without trials.
    static double upperBound(int successes, int trials) {
        return trials == 0 ? 1 : Math.min(1, wilson(successes, trials, 1));
    }

    /// The highest share of `trials` that a binomial variable of probability `p`, below 1, reaches with a
    /// probability of at least 99%.
    static double lowerQuantile(int trials, double p) {
        if (trials == 0) {
            return 0;
        }
        // P(X = k) and P(X <= k), from k = 0 up, until P(X <= k) exceeds the risk: X > k - 1 with at least 99%.
        var probability = Math.pow(1 - p, trials);
        var cumulative = probability;
        var k = 0;
        while (cumulative <= RISK && k < trials) {
            probability *= (double) (trials - k) / (k + 1) * p / (1 - p);
            k++;
            cumulative += probability;
        }
        return (double) k / trials;
    }

    private static double wilson(int successes, int trials, int sign) {
        var p = (double) successes / trials;
        var z2 = Z * Z;
        var center = p + z2 / (2 * trials);
        var spread = Z * Math.sqrt(p * (1 - p) / trials + z2 / (4.0 * trials * trials));
        return (center + sign * spread) / (1 + z2 / trials);
    }
}
