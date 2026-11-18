package be.imgn.search.internal.search;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// The documents any weight matches, scored by the sum of their scores, or by the best of them for the neutral
/// and language variants of one term.
final class AnyWeight implements Weight {

    private final List<Weight> weights;
    private final boolean max;

    AnyWeight(List<Weight> weights, boolean max) {
        this.weights = List.copyOf(weights);
        this.max = max;
    }

    @Override
    public @Nullable Scorer scorer(int segment) {
        var scorers = new ArrayList<Scorer>(weights.size());
        for (var weight : weights) {
            var scorer = weight.scorer(segment);
            if (scorer != null) {
                scorers.add(scorer);
            }
        }
        if (scorers.isEmpty()) {
            return null;
        }
        return scorers.size() == 1 ? scorers.getFirst() : new DisjunctionScorer(scorers.toArray(Scorer[]::new), max);
    }
}
