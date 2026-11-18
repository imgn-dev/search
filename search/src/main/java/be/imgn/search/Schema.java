package be.imgn.search;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// What an index holds and how it analyses it: the fields, the default fields of the query language, the
/// language analyzers and the language detector.
///
/// A schema is immutable; [#builder()] makes one.
///
/// @since 0.1.0
public final class Schema {

    private final Map<String, FieldDefinition> fields;
    private final List<FieldDefinition> fieldOrder;
    private final List<String> defaultFields;
    private final Map<Locale, LanguageAnalyzer> languages;
    private final @Nullable LanguageDetector languageDetector;
    private final double languageThreshold;

    private Schema(Builder builder) {
        this.fields = Map.copyOf(builder.fields);
        this.fieldOrder = List.copyOf(builder.fields.values());
        this.defaultFields = List.copyOf(builder.defaultFields);
        this.languages = Map.copyOf(builder.languages);
        this.languageDetector = builder.languageDetector;
        this.languageThreshold = builder.languageThreshold;
    }

    /// A builder of schemas, empty.
    ///
    /// @return a new builder
    public static Builder builder() {
        return new Builder();
    }

    /// The fields, in the order they were declared.
    ///
    /// @return the fields, not empty
    public List<FieldDefinition> fields() {
        return fieldOrder;
    }

    /// The field named `name`.
    ///
    /// @param name the field's name
    /// @return the field, or empty if this schema declares no field of that name
    /// @throws NullPointerException if `name` is `null`
    public Optional<FieldDefinition> field(String name) {
        requireNonNull(name, "name");
        return Optional.ofNullable(fields.get(name));
    }

    /// The fields that query-language words without a field search, in the order they were declared.
    ///
    /// @return the default fields, possibly empty
    public List<String> defaultFields() {
        return defaultFields;
    }

    /// The analyzer of `language`.
    ///
    /// @param language the language; only its language subtag counts, so `fr-BE` gives the analyzer of `fr`
    /// @return the analyzer, or empty if this schema has none for that language
    /// @throws NullPointerException if `language` is `null`
    /// @throws IllegalArgumentException if `language` has no language subtag, such as [Locale#ROOT]
    public Optional<LanguageAnalyzer> languageAnalyzer(Locale language) {
        requireNonNull(language, "language");
        return Optional.ofNullable(languages.get(Languages.normalize(language)));
    }

    /// The language detector.
    ///
    /// @return the detector, or empty if this schema detects no language
    public Optional<LanguageDetector> languageDetector() {
        return Optional.ofNullable(languageDetector);
    }

    /// The probability a detected language must reach to be used.
    ///
    /// @return the threshold, from 0 to 1; meaningless without a [detector][#languageDetector()]
    public double languageThreshold() {
        return languageThreshold;
    }

    /// Parses a query written in the query language, strictly: for queries written by developers or in
    /// configuration. [#parseLenient] reads a search box.
    ///
    /// ```
    /// renard "le renard" title:fox tags:animal -brouillon (chat OR chien)
    /// ```
    ///
    /// The language builds [Query] objects and nothing they cannot express:
    ///
    /// | Syntax | Query |
    /// |---|---|
    /// | `word` | [Query#term] in each default field, combined with [Query#any] |
    /// | `"some words"` | [Query#phrase] in each default field that can answer it, combined with [Query#any] |
    /// | `field:word`, `field:"some words"` | A term or a phrase in that field only |
    /// | `a b`, `a AND b` | [Query#all]: a space means AND |
    /// | `a OR b` | [Query#any] |
    /// | `-a`, `NOT a` | [Query#not] |
    /// | `( … )` | Grouping |
    /// | `\` | Escapes the next character, which is then read as text |
    ///
    /// Its grammar, where `NOT` binds tighter than `AND`, which binds tighter than `OR`:
    ///
    /// ```
    /// query       = [ disjunction ]
    /// disjunction = conjunction { "OR" conjunction }
    /// conjunction = unary { [ "AND" ] unary }
    /// unary       = { "NOT" | "-" } primary
    /// primary     = "(" disjunction ")" | [ field ":" ] ( word | phrase )
    /// ```
    ///
    /// - `AND`, `OR` and `NOT` are operators in capitals only, as whole words: `and`, `or` and `not` are words.
    /// - `-` is an operator only at the start of a word, directly followed by what it excludes: `e-mail` is a
    ///   word. The word right after it is never an operator: `-AND` excludes the word `and`.
    /// - `name:` starts a field only when this schema declares `name`: `12:30` is a word. What follows it is a
    ///   word or a phrase, never an operator.
    /// - Words end at a space, a parenthesis or a quote, unless escaped; `\"` and `\\` escape inside a phrase.
    /// - A phrase is searched in the default text fields that keep positions, and in the default keyword fields,
    ///   where the quoted text is one term.
    /// - An `all` or `any` of one query is that query, so `(a)` is `a`, and a word is a plain [Query#term]
    ///   when it has one field to search.
    /// - A word or a phrase whose text gives no term in any of its fields, such as `!!!`, is dropped, and so is
    ///   a part of the query left with nothing to search. A query left with nothing to search matches nothing:
    ///   it is `any(term(field, ""))` on the first field of this schema, which matches nothing even inside an
    ///   `all`. So is an empty query.
    /// - A part of the query made only of exclusions, such as `-a`, or `-b` in `x OR -b`, would match nothing
    ///   on its own: it is an error.
    /// - Parentheses nest at most 100 deep.
    ///
    /// @param text the query
    /// @return the query
    /// @throws NullPointerException if `text` is `null`
    /// @throws QuerySyntaxException if `text` is not valid: an unclosed quote or parenthesis, a `)` that closes
    ///     none, empty parentheses, an operator missing a query on one side, a field with nothing after it, a
    ///     trailing `\`, a part made only of exclusions, a phrase in a field that drops positions or when no
    ///     default field keeps them, a word or phrase without a field when this schema has no default fields,
    ///     or parentheses nested too deep
    public Query parse(String text) {
        requireNonNull(text, "text");
        return QueryParser.parse(this, text, true);
    }

    /// Parses a query written in the query language, leniently: for a search box shown to end users. It reads
    /// the language as [#parse] does, but never fails:
    ///
    /// - A character that would be an error is read as text: an unclosed quote or parenthesis, a `)` that
    ///   closes none, parentheses nested too deep, an operator missing a query on one side, a field with
    ///   nothing after it, and a trailing `\`. So `a OR` searches the words `a` and `or`.
    /// - A part that cannot match is dropped: a part made only of exclusions (`x OR -b` is `x`, and `-a`
    ///   matches nothing), empty parentheses, and a word or phrase without a field when this schema has no
    ///   default fields.
    /// - A phrase that no field of it keeps positions for searches its words instead, as a [Query#term] does.
    ///
    /// @param text the query
    /// @return the query
    /// @throws NullPointerException if `text` is `null`
    public Query parseLenient(String text) {
        requireNonNull(text, "text");
        return QueryParser.parse(this, text, false);
    }

    /// Checks that `document` fits this schema: every field declared, and of the declared kind.
    ///
    /// @throws IllegalArgumentException if a field of `document` is not declared, or is of another kind
    void check(Document document) {
        for (var field : document.fields()) {
            var definition = fields.get(field.name());
            if (definition == null) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "Document %s has field %s, which the schema does not declare",
                        document.key(),
                        field.name()));
            }
            var kindMatches = switch (field) {
                case Document.Text _ -> definition instanceof TextField;
                case Document.Keyword _ -> definition instanceof KeywordField;
            };
            checkArgument(
                    kindMatches,
                    "Document %s gives field %s a %s value, but the schema declares it a %s field",
                    document.key(),
                    field.name(),
                    kind(field),
                    kind(definition));
        }
    }

    private static String kind(Document.Field field) {
        return switch (field) {
            case Document.Text _ -> "text";
            case Document.Keyword _ -> "keyword";
        };
    }

    private static String kind(FieldDefinition definition) {
        return switch (definition) {
            case TextField _ -> "text";
            case KeywordField _ -> "keyword";
        };
    }

    // The '.' is left out: it separates a text field from its language, as in body.fr.
    private static void checkName(String name) {
        requireNonNull(name, "name");
        checkArgument(!name.isEmpty(), "A field name cannot be empty");
        checkArgument(
                name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-'),
                "Field name %s has a character other than a letter, a digit, '_' or '-'",
                name);
    }

    /// A field a schema declares.
    ///
    /// @since 0.1.0
    public sealed interface FieldDefinition {

        /// The field's name: letters, digits, `_` and `-`.
        ///
        /// @return the name
        String name();

        /// Whether the field's values are stored.
        ///
        /// @return whether the values are stored
        Stored stored();
    }

    /// A text field: its values are split into words and analysed, in their language when they have one.
    ///
    /// @param name the field's name
    /// @param positions whether the positions of the words are kept, for phrase queries
    /// @param accents whether accents are removed
    /// @param stored whether the values are stored
    /// @since 0.1.0
    public record TextField(String name, Positions positions, Accents accents, Stored stored)
            implements FieldDefinition {

        /// Checks the field.
        ///
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `name` is not a valid field name
        public TextField {
            checkName(name);
            requireNonNull(positions, "positions");
            requireNonNull(accents, "accents");
            requireNonNull(stored, "stored");
        }
    }

    /// A keyword field: each value is one term, such as a tag, normalized and lowercased but never split or
    /// stemmed.
    ///
    /// @param name the field's name
    /// @param stored whether the values are stored
    /// @since 0.1.0
    public record KeywordField(String name, Stored stored) implements FieldDefinition {

        /// Checks the field.
        ///
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `name` is not a valid field name
        public KeywordField {
            checkName(name);
            requireNonNull(stored, "stored");
        }
    }

    /// A builder of [Schema]s. Not thread-safe.
    ///
    /// @since 0.1.0
    public static final class Builder {

        private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        private final List<String> defaultFields = new ArrayList<>();
        private final Map<Locale, LanguageAnalyzer> languages = new LinkedHashMap<>();
        private @Nullable LanguageDetector languageDetector;
        private double languageThreshold;

        private Builder() {}

        /// Declares a text field.
        ///
        /// @param name the field's name: letters, digits, `_` and `-`
        /// @param positions whether the positions of the words are kept, for phrase queries
        /// @param accents whether accents are removed
        /// @param stored whether the values are stored
        /// @return this builder
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `name` is not a valid field name, or is already declared
        public Builder text(String name, Positions positions, Accents accents, Stored stored) {
            return add(new TextField(name, positions, accents, stored));
        }

        /// Declares a keyword field.
        ///
        /// @param name the field's name: letters, digits, `_` and `-`
        /// @param stored whether the values are stored
        /// @return this builder
        /// @throws NullPointerException if an argument is `null`
        /// @throws IllegalArgumentException if `name` is not a valid field name, or is already declared
        public Builder keyword(String name, Stored stored) {
            return add(new KeywordField(name, stored));
        }

        private Builder add(FieldDefinition field) {
            checkArgument(!fields.containsKey(field.name()), "Field %s is already declared", field.name());
            fields.put(field.name(), field);
            return this;
        }

        /// Sets the fields that query-language words without a field search, replacing any set before.
        ///
        /// @param names the fields' names, each declared by [#build()] time
        /// @return this builder
        /// @throws NullPointerException if `names` or one of them is `null`
        /// @throws IllegalArgumentException if a name appears twice
        public Builder defaultFields(String... names) {
            requireNonNull(names, "names");
            var list = List.of(names);
            checkArgument(list.stream().distinct().count() == list.size(), "Default fields %s repeat a field", list);
            defaultFields.clear();
            defaultFields.addAll(list);
            return this;
        }

        /// Registers a language analyzer.
        ///
        /// @param analyzer the analyzer
        /// @return this builder
        /// @throws NullPointerException if `analyzer` is `null`
        /// @throws IllegalArgumentException if an analyzer is already registered for its language, or if its
        ///     language is not a bare language subtag, or its identifier is empty
        public Builder language(LanguageAnalyzer analyzer) {
            requireNonNull(analyzer, "analyzer");
            var language = requireNonNull(analyzer.language(), "analyzer.language()");
            checkArgument(
                    language.equals(Languages.normalize(language)),
                    "Analyzer %s has language %s, which is not a bare language subtag",
                    analyzer.id(),
                    language);
            checkArgument(!analyzer.id().isEmpty(), "Analyzer of %s has an empty identifier", language);
            checkArgument(!languages.containsKey(language), "Language %s already has an analyzer", language);
            languages.put(language, analyzer);
            return this;
        }

        /// Registers language analyzers, as [#language] does for each.
        ///
        /// @param analyzers the analyzers
        /// @return this builder
        /// @throws NullPointerException if `analyzers` or one of them is `null`
        /// @throws IllegalArgumentException if two analyzers share a language, or one is invalid
        public Builder languages(Collection<? extends LanguageAnalyzer> analyzers) {
            requireNonNull(analyzers, "analyzers");
            analyzers.forEach(this::language);
            return this;
        }

        /// Sets the language detector, which guesses the language of the documents given none.
        ///
        /// @param detector the detector
        /// @param threshold the probability a guess must reach to be used, from 0 to 1
        /// @return this builder
        /// @throws NullPointerException if `detector` is `null`
        /// @throws IllegalArgumentException if `threshold` is not between 0 and 1
        public Builder languageDetector(LanguageDetector detector, double threshold) {
            requireNonNull(detector, "detector");
            checkArgument(threshold >= 0 && threshold <= 1, "Threshold %s is not between 0 and 1", threshold);
            this.languageDetector = detector;
            this.languageThreshold = threshold;
            return this;
        }

        /// Builds the schema.
        ///
        /// @return the schema
        /// @throws IllegalStateException if no field is declared, or a default field is not
        public Schema build() {
            checkState(!fields.isEmpty(), "A schema needs at least one field");
            for (var name : defaultFields) {
                checkState(fields.containsKey(name), "Default field %s is not declared", name);
            }
            return new Schema(this);
        }
    }
}
