package be.imgn.search.internal.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;

import be.imgn.search.Accents;
import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.Positions;
import be.imgn.search.Query;
import be.imgn.search.Schema;
import be.imgn.search.WordAnalyzer;
import be.imgn.search.internal.analysis.FieldAnalysis;
import be.imgn.search.internal.codec.Utf8;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Turns a [Query] into a [Weight]: analyses its texts as the fields' values were, and applies its rules.
///
/// - A term giving several words is a phrase in a neutral text field that keeps positions, and an `all`
///   elsewhere; giving none, it is dropped from its parent.
/// - With a language, a term of a text field giving one word is also searched in the field's language variant,
///   and scores the best of the two; a term giving several words searches the neutral field only.
/// - An `all` or `any` whose children are all dropped matches nothing; a `not` only excludes documents from the
///   other children of an `all`, and matches nothing elsewhere.
final class QueryCompiler {

    private final Schema schema;
    private final SearchContext context;
    private final @Nullable WordAnalyzer wordAnalyzer;
    private final String languageSuffix;
    private final FieldAnalysis analysis = new FieldAnalysis();
    private final List<byte[]> neutralTerms = new ArrayList<>();
    private final List<Integer> neutralPositions = new ArrayList<>();
    private final List<byte[]> languageTerms = new ArrayList<>();
    private byte[] scratch = new byte[0];

    QueryCompiler(Schema schema, SearchContext context, @Nullable LanguageAnalyzer language) {
        this.schema = schema;
        this.context = context;
        this.wordAnalyzer = language == null ? null : language.newWordAnalyzer();
        this.languageSuffix = language == null ? "" : "." + language.language().getLanguage();
    }

    /// Checks the fields of a query against the schema.
    ///
    /// @throws IllegalArgumentException if a field is not declared, or a phrase is on a text field that drops
    ///     positions
    static void validate(Schema schema, Query query) {
        switch (query) {
            case Query.Term(var field, var _) -> definition(schema, field);
            case Query.Phrase(var field, var _) -> {
                var definition = definition(schema, field);
                checkArgument(
                        !(definition instanceof Schema.TextField text && text.positions() == Positions.DROP),
                        "Field %s drops positions, so it cannot answer a phrase",
                        field);
            }
            case Query.All(var queries) -> queries.forEach(child -> validate(schema, child));
            case Query.Any(var queries) -> queries.forEach(child -> validate(schema, child));
            case Query.Not(var excluded) -> validate(schema, excluded);
        }
    }

    /// The weight of a query.
    Weight compile(Query query) {
        var weight = node(query);
        return weight == null ? Weight.NOTHING : weight;
    }

    // Null when the query is dropped.
    private @Nullable Weight node(Query query) {
        return switch (withoutDoubleNots(query)) {
            case Query.Term term -> term(term.field(), term.text());
            case Query.Phrase phrase -> phrase(phrase.field(), phrase.text());
            case Query.All all -> all(all.queries());
            case Query.Any any -> any(any.queries());
            case Query.Not _ -> Weight.NOTHING;
        };
    }

    private Weight all(List<Query> queries) {
        var required = new ArrayList<Weight>();
        var excluded = new ArrayList<Weight>();
        for (var child : queries) {
            var query = withoutDoubleNots(child);
            var excluding = query instanceof Query.Not;
            var weight = node(query instanceof Query.Not(var inner) ? inner : query);
            if (weight != null) {
                (excluding ? excluded : required).add(weight);
            }
        }
        if (required.isEmpty()) {
            return Weight.NOTHING;
        }
        return required.size() == 1 && excluded.isEmpty() ? required.getFirst() : new AllWeight(required, excluded);
    }

    private Weight any(List<Query> queries) {
        var children = new ArrayList<Weight>();
        for (var child : queries) {
            var weight = node(child);
            if (weight != null && weight != Weight.NOTHING) {
                children.add(weight);
            }
        }
        if (children.isEmpty()) {
            return Weight.NOTHING;
        }
        return children.size() == 1 ? children.getFirst() : new AnyWeight(children, false);
    }

    private @Nullable Weight term(String field, String text) {
        var definition = schema.field(field).orElseThrow();
        if (definition instanceof Schema.KeywordField) {
            return keyword(field, text);
        }
        var textField = (Schema.TextField) definition;
        analyze(textField, text, wordAnalyzer);
        if (neutralTerms.isEmpty()) {
            return null;
        }
        if (neutralTerms.size() > 1) {
            return textField.positions() == Positions.KEEP
                    ? new PhraseWeight(context, field, neutralTerms, positions())
                    : allTerms(field, field, neutralTerms);
        }
        var neutral = new TermWeight(context, field, field, neutralTerms.getFirst());
        // One neutral term comes from one word, which gives at most one language term.
        return languageTerms.isEmpty()
                ? neutral
                : new AnyWeight(
                        List.of(
                                neutral,
                                new TermWeight(context, field + languageSuffix, field, languageTerms.getFirst())),
                        true);
    }

    private @Nullable Weight phrase(String field, String text) {
        var definition = schema.field(field).orElseThrow();
        if (definition instanceof Schema.KeywordField) {
            return keyword(field, text);
        }
        analyze((Schema.TextField) definition, text, null);
        if (neutralTerms.isEmpty()) {
            return null;
        }
        return neutralTerms.size() == 1
                ? new TermWeight(context, field, field, neutralTerms.getFirst())
                : new PhraseWeight(context, field, neutralTerms, positions());
    }

    private @Nullable Weight keyword(String field, String text) {
        neutralTerms.clear();
        analysis.keyword(text, (chars, length, position) -> neutralTerms.add(utf8(chars, length)));
        return neutralTerms.isEmpty() ? null : new TermWeight(context, field, null, neutralTerms.getFirst());
    }

    private Weight allTerms(String field, String normsField, List<byte[]> terms) {
        var weights = new ArrayList<Weight>(terms.size());
        for (var term : terms) {
            weights.add(new TermWeight(context, field, normsField, term));
        }
        return new AllWeight(weights, List.of());
    }

    private void analyze(Schema.TextField field, String text, @Nullable WordAnalyzer language) {
        neutralTerms.clear();
        neutralPositions.clear();
        languageTerms.clear();
        analysis.text(
                text,
                0,
                field.accents() == Accents.REMOVE,
                language,
                (chars, length, position) -> {
                    neutralTerms.add(utf8(chars, length));
                    neutralPositions.add(position);
                },
                (chars, length, position) -> languageTerms.add(utf8(chars, length)));
    }

    private int[] positions() {
        return neutralPositions.stream().mapToInt(Integer::intValue).toArray();
    }

    private byte[] utf8(char[] chars, int length) {
        if (scratch.length < length * Utf8.MAX_BYTES_PER_CHAR) {
            scratch = new byte[length * Utf8.MAX_BYTES_PER_CHAR];
        }
        return Arrays.copyOf(scratch, Utf8.encode(chars, length, scratch));
    }

    // not(not(q)) is q, however the nots were built.
    private static Query withoutDoubleNots(Query query) {
        var result = query;
        while (result instanceof Query.Not(Query.Not(var inner))) {
            result = inner;
        }
        return result;
    }

    private static Schema.FieldDefinition definition(Schema schema, String field) {
        return schema.field(field)
                .orElseThrow(() -> new IllegalArgumentException("The schema declares no field " + field));
    }
}
