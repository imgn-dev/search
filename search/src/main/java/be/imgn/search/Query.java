package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

/// A query: terms and phrases in fields, combined with [all][#all(Query...)], [any][#any(Query...)] and
/// [not][#not(Query)].
///
/// Texts are analysed with their field's analysis when the query runs. A term whose text gives no word, such as
/// `!!!`, is dropped from its parent; an `all` or `any` whose children are all dropped matches nothing. A `not`
/// only excludes documents from the other children of an `all`, and on its own matches nothing.
///
/// @since 0.1.0
public sealed interface Query {

    /// A term: the documents whose field has the text's words.
    ///
    /// @param field the field's name
    /// @param text the text, analysed as the field's values are; when it gives several words, they must follow
    ///     each other in a field that keeps positions, and all appear elsewhere, and only the neutral field is
    ///     searched, never its language variant
    /// @return the query
    /// @throws NullPointerException if an argument is `null`
    static Query term(String field, String text) {
        return new Term(field, text);
    }

    /// A phrase: the documents whose field has the text's words, one after the other.
    ///
    /// @param field the field's name: a text field that keeps positions, or a keyword field, where the text is one
    ///     term
    /// @param text the text, analysed as the field's values are
    /// @return the query
    /// @throws NullPointerException if an argument is `null`
    static Query phrase(String field, String text) {
        return new Phrase(field, text);
    }

    /// The documents that match every query, scored by the sum of their scores.
    ///
    /// @param queries the queries, at least one
    /// @return the query
    /// @throws NullPointerException if `queries` or one of them is `null`
    /// @throws IllegalArgumentException if `queries` is empty
    static Query all(Query... queries) {
        return new All(List.of(queries));
    }

    /// The documents that match every query, scored by the sum of their scores.
    ///
    /// @param queries the queries, at least one
    /// @return the query
    /// @throws NullPointerException if `queries` or one of them is `null`
    /// @throws IllegalArgumentException if `queries` is empty
    static Query all(List<? extends Query> queries) {
        return new All(List.copyOf(queries));
    }

    /// The documents that match at least one query, scored by the sum of the scores of those that match.
    ///
    /// @param queries the queries, at least one
    /// @return the query
    /// @throws NullPointerException if `queries` or one of them is `null`
    /// @throws IllegalArgumentException if `queries` is empty
    static Query any(Query... queries) {
        return new Any(List.of(queries));
    }

    /// The documents that match at least one query, scored by the sum of the scores of those that match.
    ///
    /// @param queries the queries, at least one
    /// @return the query
    /// @throws NullPointerException if `queries` or one of them is `null`
    /// @throws IllegalArgumentException if `queries` is empty
    static Query any(List<? extends Query> queries) {
        return new Any(List.copyOf(queries));
    }

    /// Excludes the documents that match `query` from the other children of an `all`.
    ///
    /// @param query the query to exclude
    /// @return the exclusion; `not(not(q))` is `q`
    /// @throws NullPointerException if `query` is `null`
    static Query not(Query query) {
        requireNonNull(query, "query");
        return query instanceof Not(var excluded) ? excluded : new Not(query);
    }

    /// A term query; see [Query#term].
    ///
    /// @param field the field's name
    /// @param text the text
    /// @since 0.1.0
    record Term(String field, String text) implements Query {

        /// Checks the query.
        ///
        /// @throws NullPointerException if an argument is `null`
        public Term {
            requireNonNull(field, "field");
            requireNonNull(text, "text");
        }
    }

    /// A phrase query; see [Query#phrase].
    ///
    /// @param field the field's name
    /// @param text the text
    /// @since 0.1.0
    record Phrase(String field, String text) implements Query {

        /// Checks the query.
        ///
        /// @throws NullPointerException if an argument is `null`
        public Phrase {
            requireNonNull(field, "field");
            requireNonNull(text, "text");
        }
    }

    /// A conjunction; see [Query#all(Query...)].
    ///
    /// @param queries the queries, at least one
    /// @since 0.1.0
    record All(List<Query> queries) implements Query {

        /// Checks and copies the queries.
        ///
        /// @throws NullPointerException if `queries` or one of them is `null`
        /// @throws IllegalArgumentException if `queries` is empty
        public All {
            queries = List.copyOf(queries);
            checkArgument(!queries.isEmpty(), "An all query needs at least one query");
        }
    }

    /// A disjunction; see [Query#any(Query...)].
    ///
    /// @param queries the queries, at least one
    /// @since 0.1.0
    record Any(List<Query> queries) implements Query {

        /// Checks and copies the queries.
        ///
        /// @throws NullPointerException if `queries` or one of them is `null`
        /// @throws IllegalArgumentException if `queries` is empty
        public Any {
            queries = List.copyOf(queries);
            checkArgument(!queries.isEmpty(), "An any query needs at least one query");
        }
    }

    /// An exclusion; see [Query#not].
    ///
    /// @param query the query to exclude
    /// @since 0.1.0
    record Not(Query query) implements Query {

        /// Checks the query.
        ///
        /// @throws NullPointerException if `query` is `null`
        public Not {
            requireNonNull(query, "query");
        }
    }
}
