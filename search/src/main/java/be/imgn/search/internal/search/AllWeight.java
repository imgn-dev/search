package be.imgn.search.internal.search;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// The documents every required weight matches and no excluded one does.
final class AllWeight implements Weight {

    private final List<Weight> required;
    private final List<Weight> excluded;

    AllWeight(List<Weight> required, List<Weight> excluded) {
        this.required = List.copyOf(required);
        this.excluded = List.copyOf(excluded);
    }

    @Override
    public @Nullable Scorer scorer(int segment) {
        var requiredScorers = new Scorer[required.size()];
        for (var i = 0; i < requiredScorers.length; i++) {
            var scorer = required.get(i).scorer(segment);
            if (scorer == null) {
                return null;
            }
            requiredScorers[i] = scorer;
        }
        var excludedScorers = new ArrayList<Scorer>();
        for (var weight : excluded) {
            var scorer = weight.scorer(segment);
            if (scorer != null) {
                excludedScorers.add(scorer);
            }
        }
        if (requiredScorers.length == 1 && excludedScorers.isEmpty()) {
            return requiredScorers[0];
        }
        return new ConjunctionScorer(requiredScorers, excludedScorers.toArray(Scorer[]::new));
    }
}
