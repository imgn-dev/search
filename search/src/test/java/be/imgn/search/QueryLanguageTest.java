package be.imgn.search;

import static be.imgn.search.Query.all;
import static be.imgn.search.Query.any;
import static be.imgn.search.Query.not;
import static be.imgn.search.Query.phrase;
import static be.imgn.search.Query.term;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class QueryLanguageTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.DROP, Accents.REMOVE, Stored.NO)
            .keyword("tags", Stored.YES)
            .keyword("e-mail", Stored.NO)
            .defaultFields("title", "body")
            .language(ToyAnalyzer.FRENCH)
            .build();
    private static final Schema NO_DEFAULTS = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.NO)
            .keyword("tags", Stored.NO)
            .build();
    private static final Schema NO_POSITIONS = Schema.builder()
            .text("title", Positions.DROP, Accents.KEEP, Stored.NO)
            .text("body", Positions.DROP, Accents.KEEP, Stored.NO)
            .defaultFields("title", "body")
            .build();
    private static final Query NOTHING = any(term("title", ""));
    private static final int RANDOM_QUERIES = 5000;
    private static final int MAX_PIECES = 12;
    private static final int DEEP = 10_000;
    private static final List<String> PIECES = List.of(
            "fox",
            "renards",
            "AND",
            "OR",
            "NOT",
            "and",
            "or",
            "-",
            "(",
            ")",
            "\"",
            "\\",
            ":",
            "title:",
            "body:",
            "tags:",
            "e-mail:",
            "unknown:",
            " ",
            " ",
            " ",
            "\t",
            " ",
            "é",
            "中",
            "😀",
            "\uD800",
            "\uDC00",
            "!",
            "12:30",
            "e-mail",
            "l'arbre",
            "a",
            "́");

    @Test
    void searchesAWordInEachDefaultField() {
        assertThat(SCHEMA.parse("fox")).isEqualTo(word("fox"));
    }

    @Test
    void searchesAWordAsATermWhenThereIsOneDefaultField() {
        var schema = Schema.builder()
                .text("title", Positions.KEEP, Accents.KEEP, Stored.NO)
                .defaultFields("title")
                .build();

        assertThat(schema.parse("fox")).isEqualTo(term("title", "fox"));
    }

    @Test
    void searchesAPhraseInTheDefaultFieldsThatCanAnswerIt() {
        var schema = Schema.builder()
                .text("title", Positions.KEEP, Accents.KEEP, Stored.NO)
                .text("body", Positions.DROP, Accents.KEEP, Stored.NO)
                .keyword("tags", Stored.NO)
                .defaultFields("title", "body", "tags")
                .build();

        assertThat(SCHEMA.parse("\"quick fox\"")).isEqualTo(phrase("title", "quick fox"));
        assertThat(schema.parse("\"quick fox\""))
                .isEqualTo(any(phrase("title", "quick fox"), phrase("tags", "quick fox")));
    }

    @Test
    void searchesAFieldOnly() {
        assertThat(SCHEMA.parse("title:fox")).isEqualTo(term("title", "fox"));
        assertThat(SCHEMA.parse("title:\"quick fox\"")).isEqualTo(phrase("title", "quick fox"));
        assertThat(SCHEMA.parse("tags:\"red fox\"")).isEqualTo(phrase("tags", "red fox"));
        assertThat(SCHEMA.parse("e-mail:a@b.c")).isEqualTo(term("e-mail", "a@b.c"));
        assertThat(SCHEMA.parse("title:12:30")).isEqualTo(term("title", "12:30"));
    }

    @Test
    void readsWhatFollowsAFieldAsAWord() {
        assertThat(SCHEMA.parse("title:-fox")).isEqualTo(term("title", "-fox"));
        assertThat(SCHEMA.parse("title:AND")).isEqualTo(term("title", "AND"));
    }

    @Test
    void startsAFieldOnlyWhenTheSchemaDeclaresIt() {
        assertThat(SCHEMA.parse("12:30")).isEqualTo(word("12:30"));
        assertThat(SCHEMA.parse("unknown:fox")).isEqualTo(word("unknown:fox"));
        assertThat(SCHEMA.parse("Title:fox")).isEqualTo(word("Title:fox"));
    }

    @Test
    void readsASpaceAsAnd() {
        var expected = all(word("a"), word("b"));

        assertThat(SCHEMA.parse("a b")).isEqualTo(expected);
        assertThat(SCHEMA.parse("a AND b")).isEqualTo(expected);
        assertThat(SCHEMA.parse("  a \t b ")).isEqualTo(expected);
        assertThat(SCHEMA.parse("a b c")).isEqualTo(all(word("a"), word("b"), word("c")));
    }

    @Test
    void readsOr() {
        assertThat(SCHEMA.parse("a OR b")).isEqualTo(any(word("a"), word("b")));
        assertThat(SCHEMA.parse("a OR b OR c")).isEqualTo(any(word("a"), word("b"), word("c")));
    }

    @Test
    void readsExclusions() {
        var expected = all(word("a"), not(word("b")));

        assertThat(SCHEMA.parse("a -b")).isEqualTo(expected);
        assertThat(SCHEMA.parse("a NOT b")).isEqualTo(expected);
        assertThat(SCHEMA.parse("a AND NOT b")).isEqualTo(expected);
        assertThat(SCHEMA.parse("-b a")).isEqualTo(all(not(word("b")), word("a")));
        assertThat(SCHEMA.parse("a -title:b -\"c d\""))
                .isEqualTo(all(word("a"), not(term("title", "b")), not(phrase("title", "c d"))));
    }

    @Test
    void cancelsDoubleNegations() {
        assertThat(SCHEMA.parse("NOT NOT a")).isEqualTo(word("a"));
        assertThat(SCHEMA.parse("NOT -a")).isEqualTo(word("a"));
        assertThat(SCHEMA.parse("a NOT NOT NOT b")).isEqualTo(all(word("a"), not(word("b"))));
    }

    @Test
    void bindsNotTighterThanAndTighterThanOr() {
        assertThat(SCHEMA.parse("a b OR c")).isEqualTo(any(all(word("a"), word("b")), word("c")));
        assertThat(SCHEMA.parse("a OR b c")).isEqualTo(any(word("a"), all(word("b"), word("c"))));
        assertThat(SCHEMA.parse("a AND b OR c AND d"))
                .isEqualTo(any(all(word("a"), word("b")), all(word("c"), word("d"))));
        assertThat(SCHEMA.parse("NOT a b")).isEqualTo(all(not(word("a")), word("b")));
        assertThat(SCHEMA.parse("a OR NOT b c")).isEqualTo(any(word("a"), all(not(word("b")), word("c"))));
    }

    @Test
    void groupsWithParentheses() {
        assertThat(SCHEMA.parse("(a OR b) c")).isEqualTo(all(any(word("a"), word("b")), word("c")));
        assertThat(SCHEMA.parse("a (b OR c)")).isEqualTo(all(word("a"), any(word("b"), word("c"))));
        assertThat(SCHEMA.parse("-(a OR b) c")).isEqualTo(all(not(any(word("a"), word("b"))), word("c")));
        assertThat(SCHEMA.parse("NOT(a b) c")).isEqualTo(all(not(all(word("a"), word("b"))), word("c")));
        assertThat(SCHEMA.parse("((a))")).isEqualTo(word("a"));
        assertThat(SCHEMA.parse("a(b)")).isEqualTo(all(word("a"), word("b")));
    }

    @Test
    void escapesTheNextCharacter() {
        assertThat(SCHEMA.parse("\\(a\\)")).isEqualTo(word("(a)"));
        assertThat(SCHEMA.parse("a\\ b")).isEqualTo(word("a b"));
        assertThat(SCHEMA.parse("\\-a")).isEqualTo(word("-a"));
        assertThat(SCHEMA.parse("a \\AND b")).isEqualTo(all(word("a"), word("AND"), word("b")));
        assertThat(SCHEMA.parse("title\\:fox")).isEqualTo(word("title:fox"));
        assertThat(SCHEMA.parse("a\\\\b")).isEqualTo(word("a\\b"));
        assertThat(SCHEMA.parse("a\\\"b")).isEqualTo(word("a\"b"));
        assertThat(SCHEMA.parse("\"say \\\"hi\\\" \\\\ (now)\"")).isEqualTo(phrase("title", "say \"hi\" \\ (now)"));
        assertThat(SCHEMA.parse("\\😀a")).isEqualTo(word("😀a"));
    }

    @Test
    void readsOperatorsInCapitalsOnly() {
        assertThat(SCHEMA.parse("a and b")).isEqualTo(all(word("a"), word("and"), word("b")));
        assertThat(SCHEMA.parse("a or b")).isEqualTo(all(word("a"), word("or"), word("b")));
        assertThat(SCHEMA.parse("not a")).isEqualTo(all(word("not"), word("a")));
        assertThat(SCHEMA.parse("a Or b")).isEqualTo(all(word("a"), word("Or"), word("b")));
        assertThat(SCHEMA.parse("ANDROID ORANGE NOTE")).isEqualTo(all(word("ANDROID"), word("ORANGE"), word("NOTE")));
    }

    @Test
    void readsAMinusAsAnOperatorOnlyAtTheStartOfAWord() {
        assertThat(SCHEMA.parse("e-mail")).isEqualTo(word("e-mail"));
        assertThat(SCHEMA.parse("a-")).isEqualTo(word("a-"));
        assertThat(SCHEMA.parse("a -AND")).isEqualTo(all(word("a"), not(word("AND"))));
        assertThat(SCHEMA.parse("a --b")).isEqualTo(all(word("a"), not(word("-b"))));
    }

    @Test
    void dropsWhatGivesNoTerm() {
        assertThat(SCHEMA.parse("fox !!!")).isEqualTo(word("fox"));
        assertThat(SCHEMA.parse("fox OR (!!! ...)")).isEqualTo(word("fox"));
        assertThat(SCHEMA.parse("fox -!!!")).isEqualTo(word("fox"));
        assertThat(SCHEMA.parse("fox title:\"!!\"")).isEqualTo(word("fox"));
        assertThat(SCHEMA.parse("fox tags:!!!")).isEqualTo(all(word("fox"), term("tags", "!!!")));
    }

    @Test
    void matchesNothingWhenNothingIsLeftToSearch() {
        for (var text : List.of("", "   ", "!!!", "\"\"", "title:!!!", "(!!!)", "!!! -fox")) {
            assertThat(SCHEMA.parse(text)).as(text).isEqualTo(NOTHING);
            assertThat(SCHEMA.parseLenient(text)).as(text).isEqualTo(NOTHING);
        }
        assertThat(NO_DEFAULTS.parseLenient("fox")).isEqualTo(NOTHING);
    }

    @Test
    void rejectsPartsMadeOnlyOfExclusions() {
        assertSyntaxError("-a", 0);
        assertSyntaxError("NOT a", 0);
        assertSyntaxError("-a -b", 0);
        assertSyntaxError("x OR -b", 5);
        assertSyntaxError("x (-b)", 3);
        assertSyntaxError("x OR (y -b) OR NOT c", 15);
    }

    @Test
    void dropsPartsMadeOnlyOfExclusionsLeniently() {
        assertThat(SCHEMA.parseLenient("x OR -b")).isEqualTo(word("x"));
        assertThat(SCHEMA.parseLenient("-a")).isEqualTo(NOTHING);
        assertThat(SCHEMA.parseLenient("-a -b")).isEqualTo(NOTHING);
        assertThat(SCHEMA.parseLenient("x (-b)")).isEqualTo(word("x"));
        assertThat(SCHEMA.parseLenient("x OR (y -b) OR NOT c"))
                .isEqualTo(any(word("x"), all(word("y"), not(word("b")))));
    }

    @Test
    void rejectsUnbalancedParentheses() {
        assertSyntaxError("(a b", 0);
        assertSyntaxError("a (b (c)", 2);
        assertSyntaxError("a)", 1);
        assertSyntaxError(") a", 0);
        assertSyntaxError("(a) b)", 5);
        assertSyntaxError("()", 0);
        assertSyntaxError("a ( )", 2);
    }

    @Test
    void rejectsUnclosedQuotes() {
        assertSyntaxError("a \"b c", 2);
        assertSyntaxError("\"a\\\"", 0);
        assertSyntaxError("title:\"a", 6);
    }

    @Test
    void rejectsDanglingOperators() {
        assertSyntaxError("AND a", 0);
        assertSyntaxError("OR a", 0);
        assertSyntaxError("a AND", 2);
        assertSyntaxError("a OR", 2);
        assertSyntaxError("a OR OR b", 2);
        assertSyntaxError("a AND OR b", 2);
        assertSyntaxError("(a OR) b", 3);
        assertSyntaxError("(AND a)", 1);
        assertSyntaxError("NOT", 0);
        assertSyntaxError("a NOT", 2);
        assertSyntaxError("a NOT NOT", 6);
        assertSyntaxError("a -", 2);
        assertSyntaxError("a - b", 2);
        assertSyntaxError("(a -)", 3);
    }

    @Test
    void rejectsATrailingEscape() {
        assertSyntaxError("a\\", 1);
        assertSyntaxError("a b\\", 3);
        assertSyntaxError("title:\\", 6);
    }

    @Test
    void rejectsAFieldWithNothingAfterIt() {
        assertSyntaxError("title:", 0);
        assertSyntaxError("a title: b", 2);
        assertSyntaxError("title:(a b)", 0);
    }

    @Test
    void rejectsAPhraseWhereNoFieldKeepsPositions() {
        assertSyntaxError("body:\"a b\"", 0);
        assertSyntaxError("x body:\"a b\"", 2);
        assertSyntaxError(NO_POSITIONS, "x \"a b\"", 2);
    }

    @Test
    void rejectsAWordWithoutAFieldWhenThereAreNoDefaultFields() {
        assertSyntaxError(NO_DEFAULTS, "title:a b", 8);
        assertSyntaxError(NO_DEFAULTS, "title:a \"b c\"", 8);
        assertThat(NO_DEFAULTS.parse("title:a tags:b")).isEqualTo(all(term("title", "a"), term("tags", "b")));
    }

    @Test
    void dropsAWordWithoutAFieldLenientlyWhenThereAreNoDefaultFields() {
        assertThat(NO_DEFAULTS.parseLenient("title:a b \"c d\"")).isEqualTo(term("title", "a"));
    }

    @Test
    void searchesTheWordsOfAPhraseLenientlyWhereNoFieldKeepsPositions() {
        assertThat(SCHEMA.parseLenient("body:\"a b\"")).isEqualTo(term("body", "a b"));
        assertThat(NO_POSITIONS.parseLenient("\"a b\"")).isEqualTo(any(term("title", "a b"), term("body", "a b")));
    }

    @Test
    void readsLenientlyAsTextWhatWouldBeAnError() {
        assertThat(SCHEMA.parseLenient("(a b")).isEqualTo(all(word("(a"), word("b")));
        assertThat(SCHEMA.parseLenient("a)")).isEqualTo(word("a)"));
        assertThat(SCHEMA.parseLenient("(a) b)")).isEqualTo(all(word("a"), word("b)")));
        assertThat(SCHEMA.parseLenient("a \"b c")).isEqualTo(all(word("a"), word("\"b"), word("c")));
        assertThat(SCHEMA.parseLenient("a \"b\" \"c")).isEqualTo(all(word("a"), phrase("title", "b"), word("\"c")));
        assertThat(SCHEMA.parseLenient("a OR")).isEqualTo(all(word("a"), word("OR")));
        assertThat(SCHEMA.parseLenient("AND a")).isEqualTo(all(word("AND"), word("a")));
        assertThat(SCHEMA.parseLenient("a OR OR b")).isEqualTo(any(all(word("a"), word("OR")), word("b")));
        assertThat(SCHEMA.parseLenient("a NOT")).isEqualTo(all(word("a"), word("NOT")));
        assertThat(SCHEMA.parseLenient("a - b")).isEqualTo(all(word("a"), word("b")));
        assertThat(SCHEMA.parseLenient("a\\")).isEqualTo(word("a\\"));
        assertThat(SCHEMA.parseLenient("title:")).isEqualTo(word("title:"));
        assertThat(SCHEMA.parseLenient("title:(a)")).isEqualTo(all(word("title:"), word("a")));
        assertThat(SCHEMA.parseLenient("a ()")).isEqualTo(word("a"));
    }

    @Test
    void boundsTheNestingOfParentheses() {
        var allowed = "(".repeat(QueryParser.MAX_DEPTH) + "a" + ")".repeat(QueryParser.MAX_DEPTH);
        var deeper = "(" + allowed + ")";

        assertThat(SCHEMA.parse(allowed)).isEqualTo(word("a"));
        assertSyntaxError(deeper, QueryParser.MAX_DEPTH);
        assertThat(SCHEMA.parseLenient(deeper)).isEqualTo(word("(a)"));
    }

    @Test
    void parsesDeepInputLenientlyWithoutOverflowingTheStack() {
        assertThat(SCHEMA.parseLenient("(".repeat(DEEP) + "a" + ")".repeat(DEEP)))
                .isInstanceOf(Query.Any.class);
        assertThat(SCHEMA.parseLenient("(".repeat(DEEP))).isEqualTo(NOTHING);
        assertThat(SCHEMA.parseLenient("NOT ".repeat(DEEP) + "a")).isEqualTo(word("a"));
        assertThat(SCHEMA.parseLenient("a ".repeat(DEEP))).isInstanceOf(Query.All.class);
    }

    @Test
    void givesThePositionInTheMessage() {
        var exception = catchThrowableOfType(QuerySyntaxException.class, () -> SCHEMA.parse("a OR"));

        assertThat(exception.position()).isEqualTo(2);
        assertThat(exception).hasMessage("'OR' has no query after it, at position 2 of the query");
    }

    @Test
    void rejectsNull() {
        // Through Function, whose parameter is unannotated, so the null reaches the parsers as callers can send it.
        Function<String, Query> strict = SCHEMA::parse;
        Function<String, Query> lenient = SCHEMA::parseLenient;

        assertThatThrownBy(() -> strict.apply(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> lenient.apply(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parsesRandomInputLenientlyIntoSearchableQueries() throws IOException {
        var random = new Random(20_261_118);
        for (var schema : List.of(SCHEMA, NO_DEFAULTS)) {
            try (var index = Index.inMemory(schema)) {
                try (var writer = index.writer()) {
                    writer.add(Document.builder("x")
                            .text("title", "le renard é 中 12 30")
                            .keyword("tags", "!")
                            .build());
                }
                try (var searcher = index.searcher()) {
                    for (var i = 0; i < RANDOM_QUERIES; i++) {
                        var text = randomText(random);
                        var lenient = schema.parseLenient(text);

                        assertThat(searcher.search(lenient, 10)).as(text).hasSizeLessThanOrEqualTo(1);
                        assertThat(searcher.search(Locale.FRENCH, lenient, 10))
                                .as(text)
                                .hasSizeLessThanOrEqualTo(1);
                        assertStrictAgrees(schema, text, lenient);
                    }
                }
            }
        }
    }

    @Test
    void searchesThroughTheParser() throws IOException {
        try (var index = Index.inMemory(SCHEMA)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("a")
                        .text("title", "The quick fox")
                        .text("body", "jumps over the dog")
                        .keyword("tags", "animal")
                        .build());
                writer.add(Document.builder("b")
                        .language(Locale.FRENCH)
                        .text("title", "Le renard")
                        .text("body", "Les renards sont bruns")
                        .keyword("tags", "animal")
                        .keyword("tags", "Forêt")
                        .build());
                writer.add(Document.builder("c")
                        .text("title", "Lazy dog")
                        .text("body", "sleeps")
                        .keyword("tags", "pet")
                        .build());
            }
            try (var searcher = index.searcher()) {
                assertThat(keys(searcher, "fox")).containsExactly("a");
                assertThat(keys(searcher, "fox OR renard")).containsExactlyInAnyOrder("a", "b");
                assertThat(keys(searcher, "tags:animal -fox")).containsExactly("b");
                assertThat(keys(searcher, "dog -tags:pet")).containsExactly("a");
                assertThat(keys(searcher, "\"quick fox\"")).containsExactly("a");
                assertThat(keys(searcher, "\"fox quick\"")).isEmpty();
                assertThat(keys(searcher, "tags:forêt")).containsExactly("b");
                assertThat(keys(searcher, "(lazy OR quick) dog !!!")).containsExactlyInAnyOrder("a", "c");
                assertThat(keys(searcher, "-dog")).isEmpty();
                assertThat(searcher.search(Locale.FRENCH, SCHEMA.parseLenient("renards"), 10))
                        .extracting(Hit::key)
                        .containsExactly("b");
            }
        }
    }

    private static List<String> keys(Searcher searcher, String text) {
        return searcher.search(SCHEMA.parseLenient(text), 10).stream()
                .map(Hit::key)
                .toList();
    }

    // Strict parsing fails with a syntax error, or gives what lenient parsing does.
    private static void assertStrictAgrees(Schema schema, String text, Query lenient) {
        try {
            assertThat(schema.parse(text)).as(text).isEqualTo(lenient);
        } catch (QuerySyntaxException e) {
            assertThat(e.position()).as(text).isBetween(0, text.length());
        }
    }

    private static String randomText(Random random) {
        var builder = new StringBuilder();
        var pieces = random.nextInt(MAX_PIECES + 1);
        for (var i = 0; i < pieces; i++) {
            builder.append(PIECES.get(random.nextInt(PIECES.size())));
        }
        return builder.toString();
    }

    private static Query word(String text) {
        return any(term("title", text), term("body", text));
    }

    private static void assertSyntaxError(String text, int position) {
        assertSyntaxError(SCHEMA, text, position);
    }

    private static void assertSyntaxError(Schema schema, String text, int position) {
        var exception = catchThrowableOfType(QuerySyntaxException.class, () -> schema.parse(text));

        assertThat(exception).as(text).isNotNull();
        assertThat(exception.position()).as(text).isEqualTo(position);
    }
}
