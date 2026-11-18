package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import be.imgn.search.internal.index.Snapshot;
import be.imgn.search.internal.search.Searching;
import be.imgn.search.internal.segment.StoredRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/// Searches a snapshot of an [Index]: the documents as of the refresh before it was opened.
///
/// Thread-safe. It must be closed, which releases what it holds.
///
/// @since 0.1.0
public final class Searcher implements AutoCloseable {

    private final Schema schema;
    private final Snapshot snapshot;
    private final ArrayDeque<Inflater> inflaters = new ArrayDeque<>();
    private volatile boolean closed;

    Searcher(Schema schema, Snapshot snapshot) {
        this.schema = schema;
        this.snapshot = snapshot;
    }

    /// The best documents for a query in the neutral fields.
    ///
    /// @param query the query
    /// @param k the number of hits to return at most
    /// @return the hits, best first; equal scores keep document order
    /// @throws NullPointerException if `query` is `null`
    /// @throws IllegalArgumentException if `k` is not positive, a field of the query is not declared by the
    ///     schema, or a phrase is on a text field that drops positions
    /// @throws IllegalStateException if the searcher is closed
    public List<Hit> search(Query query, int k) {
        return run(null, query, k);
    }

    /// The best documents for a query in the neutral fields and, for each text field, in its variant for a
    /// language: a term that gives one word scores the best of the two, and one that gives several searches the
    /// neutral field only. For a language without an analyzer in the schema, it is
    /// [#search(Query, int)].
    ///
    /// @param language the language of the query
    /// @param query the query
    /// @param k the number of hits to return at most
    /// @return the hits, best first; equal scores keep document order
    /// @throws NullPointerException if an argument is `null`
    /// @throws IllegalArgumentException if `language` has no language subtag, `k` is not positive, a field of the
    ///     query is not declared by the schema, or a phrase is on a text field that drops positions
    /// @throws IllegalStateException if the searcher is closed
    public List<Hit> search(Locale language, Query query, int k) {
        requireNonNull(language, "language");
        return run(Languages.normalize(language), query, k);
    }

    /// The stored fields of a hit's document.
    ///
    /// @param hit a hit this searcher returned
    /// @return the document's key, language and stored values
    /// @throws NullPointerException if `hit` is `null`
    /// @throws IllegalArgumentException if another searcher returned the hit
    /// @throws IllegalStateException if the searcher is closed
    public StoredDocument document(Hit hit) {
        requireNonNull(hit, "hit");
        checkArgument(hit.snapshot() == this, "Hit %s comes from another searcher", hit);
        checkState(!closed, "The searcher is closed");
        var entry = snapshot.segments().get(snapshot.segmentOf(hit.document()));
        var inflater = takeInflater();
        StoredRecord record;
        try {
            record = entry.segment().stored(hit.document() - entry.base(), inflater);
        } finally {
            giveBack(inflater);
        }
        var values = new LinkedHashMap<String, List<StoredText>>();
        for (var value : record.values()) {
            values.computeIfAbsent(value.field(), _ -> new ArrayList<>())
                    .add(new StoredText(value.value(), locale(value.language())));
        }
        return new StoredDocument(schema, hit.key(), locale(record.language()), Map.copyOf(values));
    }

    /// Closes the searcher. Closing a closed searcher does nothing.
    @Override
    public void close() {
        closed = true;
        synchronized (inflaters) {
            inflaters.forEach(Inflater::end);
            inflaters.clear();
        }
    }

    private List<Hit> run(@Nullable Locale language, Query query, int k) {
        requireNonNull(query, "query");
        checkArgument(k > 0, "k is %s, but must be positive", k);
        checkState(!closed, "The searcher is closed");
        Searching.validate(schema, query);
        var results = Searching.search(schema, snapshot, query, language, k);
        var hits = new ArrayList<Hit>(results.size());
        for (var result : results) {
            var entry = snapshot.segments().get(snapshot.segmentOf(result.doc()));
            var key = entry.segment().key(result.doc() - entry.base());
            hits.add(new Hit(key, result.score(), this, result.doc()));
        }
        return hits;
    }

    private Inflater takeInflater() {
        synchronized (inflaters) {
            var inflater = inflaters.poll();
            return inflater == null ? new Inflater() : inflater;
        }
    }

    private void giveBack(Inflater inflater) {
        synchronized (inflaters) {
            if (closed) {
                inflater.end();
            } else {
                inflaters.push(inflater);
            }
        }
    }

    private static @Nullable Locale locale(@Nullable String language) {
        return language == null ? null : Locale.of(language);
    }
}
