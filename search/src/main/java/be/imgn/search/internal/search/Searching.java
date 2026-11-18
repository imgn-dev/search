package be.imgn.search.internal.search;

import be.imgn.search.Query;
import be.imgn.search.Schema;
import be.imgn.search.internal.index.Snapshot;
import be.imgn.search.internal.segment.DocIterator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/// Runs a query over a snapshot: every segment is scored document at a time into a top-k, skipping deleted
/// documents, and, once the top-k is full, the blocks and documents whose score bounds cannot beat its lowest
/// score.
public final class Searching {

    private Searching() {}

    /// Checks the fields of a query against the schema.
    ///
    /// @param schema the schema
    /// @param query the query
    /// @throws IllegalArgumentException if a field is not declared, or a phrase is on a text field that drops
    ///     positions
    public static void validate(Schema schema, Query query) {
        QueryCompiler.validate(schema, query);
    }

    /// The best documents for a query.
    ///
    /// @param schema the schema, which must accept the query: see [#validate]
    /// @param snapshot the snapshot to search
    /// @param query the query
    /// @param language the language whose fields are also searched, or `null` for the neutral fields only
    /// @param k the number of documents to return at most, at least 1
    /// @return the documents, best first, equal scores in document order
    public static List<ScoredDoc> search(
            Schema schema, Snapshot snapshot, Query query, @Nullable Locale language, int k) {
        return search(schema, snapshot, query, language, k, true);
    }

    /// The best documents for a query, skipping the documents that cannot enter the top-k or not: both give the
    /// same documents and scores.
    static List<ScoredDoc> search(
            Schema schema, Snapshot snapshot, Query query, @Nullable Locale language, int k, boolean skip) {
        if (snapshot.maxDoc() == 0) {
            return List.of();
        }
        var analyzer =
                language == null ? null : schema.languageAnalyzer(language).orElse(null);
        var context = new SearchContext(snapshot);
        var weight = new QueryCompiler(schema, context, analyzer).compile(query);
        var top = new TopDocs(Math.min(k, snapshot.maxDoc()));
        for (var i = 0; i < snapshot.segments().size(); i++) {
            var scorer = weight.scorer(i);
            if (scorer != null) {
                collect(scorer, snapshot.segments().get(i), top, skip);
            }
        }
        return top.results();
    }

    private static void collect(Scorer scorer, Snapshot.Entry entry, TopDocs top, boolean skip) {
        var min = top.minCompetitiveScore();
        if (skip && min > Double.NEGATIVE_INFINITY) {
            scorer.setMinCompetitiveScore(min);
        }
        var deletions = entry.deletions();
        for (var doc = scorer.nextDoc(); doc != DocIterator.NO_MORE_DOCS; doc = scorer.nextDoc()) {
            if (deletions.isDeleted(doc)) {
                continue;
            }
            top.collect(entry.base() + doc, scorer.score());
            var newMin = top.minCompetitiveScore();
            if (skip && newMin > min) {
                min = newMin;
                scorer.setMinCompetitiveScore(min);
            }
        }
    }

    /// A document of a snapshot and its score.
    ///
    /// @param doc the document's number in the snapshot
    /// @param score its score
    public record ScoredDoc(int doc, float score) {}
}
