package be.imgn.search.internal.search;

import be.imgn.search.internal.index.Snapshot;
import be.imgn.search.internal.segment.Segment;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// What one search call shares between its weights: the snapshot and the statistics of its fields.
final class SearchContext {

    private final Snapshot snapshot;
    private final Map<String, FieldStats> stats = new HashMap<>();
    private @Nullable FieldStats flat;

    SearchContext(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    int segmentCount() {
        return snapshot.segments().size();
    }

    Segment segment(int index) {
        return snapshot.segments().get(index).segment();
    }

    /// The statistics a field is scored with.
    ///
    /// @param normsField the neutral text field whose norms and statistics score the field, or `null` for a
    ///     keyword field, scored against every document of the snapshot and without length
    FieldStats stats(@Nullable String normsField) {
        if (normsField == null) {
            var result = flat;
            if (result == null) {
                result = new FieldStats(snapshot.maxDoc(), Bm25.flatTable());
                flat = result;
            }
            return result;
        }
        var result = stats.get(normsField);
        if (result == null) {
            long docCount = 0;
            long totalLength = 0;
            for (var entry : snapshot.segments()) {
                var field = entry.segment().field(normsField);
                if (field != null) {
                    docCount += field.docCount();
                    totalLength += field.totalLength();
                }
            }
            var average = docCount == 0 ? 1 : (double) totalLength / docCount;
            result = new FieldStats(docCount, Bm25.lengthTable(average));
            stats.put(normsField, result);
        }
        return result;
    }
}
