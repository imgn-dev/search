package be.imgn.search;

import be.imgn.search.internal.analysis.FieldAnalysis;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Parses the query language into a [Query]: a recursive-descent parser over tokens lexed on demand. See
/// [Schema#parse] for the language.
///
/// A word or phrase is dropped when its text gives no term in any field it targets, so that `hello !!!` searches
/// `hello`: an `any` over the default fields whose terms are all dropped would otherwise match nothing, and
/// take its whole conjunction with it. A part left with nothing to search is dropped in turn.
final class QueryParser {

    /// The deepest parentheses may nest, which bounds the parser's recursion.
    static final int MAX_DEPTH = 100;

    private final Schema schema;
    private final String text;
    private final boolean strict;
    // The characters read as text although they are special: only in lenient parsing.
    private final boolean[] literal;
    private final List<Token> tokens = new ArrayList<>();
    private final FieldAnalysis analysis = new FieldAnalysis();
    // The lexer's position in text, and the index of the next token to parse.
    private int offset;
    private int next;
    private int depth;
    private boolean afterMinus;
    // Counted by hasTerms, from the analysis's callbacks.
    private int terms;

    private QueryParser(Schema schema, String text, boolean strict) {
        this.schema = schema;
        this.text = text;
        this.strict = strict;
        this.literal = strict ? new boolean[text.length()] : literals(text);
    }

    /// Parses a query.
    ///
    /// @throws QuerySyntaxException if `strict` and the query is not valid
    static Query parse(Schema schema, String text, boolean strict) {
        var query = new QueryParser(schema, text, strict).query();
        // An any whose only term gives no word matches nothing, even inside an all.
        return query == null ? Query.any(Query.term(schema.fields().getFirst().name(), "")) : query;
    }

    // Null when nothing is left to search.
    private @Nullable Query query() {
        if (peek(0).kind() == Kind.EOF) {
            return null;
        }
        var query = disjunction();
        var rest = peek(0);
        if (strict && rest.kind() != Kind.EOF) {
            throw error(rest.start(), "This ')' closes no '('");
        }
        return query;
    }

    private @Nullable Query disjunction() {
        var parts = new ArrayList<Query>();
        addIfPresent(parts, conjunction());
        while (peek(0).kind() == Kind.OR) {
            advance();
            addIfPresent(parts, conjunction());
        }
        return parts.isEmpty() ? null : parts.size() == 1 ? parts.getFirst() : Query.any(parts);
    }

    private @Nullable Query conjunction() {
        var start = peek(0).start();
        var operands = new ArrayList<Operand>();
        operands.add(unary());
        while (continuesConjunction(peek(0))) {
            var token = peek(0);
            if (token.kind() == Kind.AND || token.kind() == Kind.OR) {
                if (!startsOperand(peek(1))) {
                    operands.add(new Operand(asWord(advance(), "has no query after it"), false));
                    continue;
                }
                advance();
            }
            operands.add(unary());
        }
        if (operands.stream().allMatch(Operand::excluded)) {
            if (strict) {
                throw error(start, "This part of the query only excludes, so it would match nothing");
            }
            return null;
        }
        var children = new ArrayList<Query>();
        var searches = false;
        for (var operand : operands) {
            var query = operand.query();
            if (query != null) {
                children.add(operand.excluded() ? Query.not(query) : query);
                searches |= !operand.excluded();
            }
        }
        if (!searches) {
            return null;
        }
        return children.size() == 1 ? children.getFirst() : Query.all(children);
    }

    private boolean continuesConjunction(Token token) {
        return switch (token.kind()) {
            case EOF, RPAREN -> false;
            // An OR followed by an operand ends the conjunction; one that is not is dangling.
            case OR -> !startsOperand(peek(1));
            default -> true;
        };
    }

    // Iterative, so that a long run of NOTs cannot overflow the stack.
    private Operand unary() {
        var excluded = false;
        while ((peek(0).kind() == Kind.NOT || peek(0).kind() == Kind.MINUS) && startsOperand(peek(1))) {
            advance();
            excluded = !excluded;
        }
        return new Operand(primary(), excluded);
    }

    private @Nullable Query primary() {
        var token = peek(0);
        return switch (token.kind()) {
            case NOT, MINUS -> asWord(advance(), "has no query after it");
            case AND, OR -> asWord(advance(), "has no query before it");
            case RPAREN -> asWord(advance(), "closes no '('");
            case LPAREN -> group();
            case WORD -> word(advance());
            case PHRASE -> phrase(advance());
            // Never reached: an operand is only parsed where a token starts one.
            case EOF -> null;
        };
    }

    private @Nullable Query group() {
        var open = advance();
        if (strict && depth == MAX_DEPTH) {
            throw error(open.start(), "Parentheses nest more than " + MAX_DEPTH + " deep");
        }
        if (peek(0).kind() == Kind.RPAREN) {
            if (strict) {
                throw error(open.start(), "These parentheses hold no query");
            }
            advance();
            return null;
        }
        depth++;
        var query = disjunction();
        depth--;
        if (peek(0).kind() == Kind.RPAREN) {
            advance();
        } else if (strict) {
            throw error(open.start(), "This '(' is never closed");
        }
        return query;
    }

    // An operator that cannot be one: an error, or a word in lenient parsing.
    private @Nullable Query asWord(Token token, String problem) {
        if (strict) {
            throw error(token.start(), "'" + token.text() + "' " + problem);
        }
        return word(new Token(Kind.WORD, token.start(), token.text(), null));
    }

    private @Nullable Query word(Token token) {
        var field = token.field();
        if (field != null) {
            return hasTerms(definition(field), token.text()) ? Query.term(field, token.text()) : null;
        }
        var queries = new ArrayList<Query>();
        for (var name : defaultFields(token)) {
            if (hasTerms(definition(name), token.text())) {
                queries.add(Query.term(name, token.text()));
            }
        }
        return anyOf(queries);
    }

    private @Nullable Query phrase(Token token) {
        var field = token.field();
        if (field != null) {
            var definition = definition(field);
            if (!keepsPositions(definition)) {
                if (strict) {
                    throw error(token.start(), "Field " + field + " drops positions, so it cannot search a phrase");
                }
                return word(token);
            }
            return hasTerms(definition, token.text()) ? Query.phrase(field, token.text()) : null;
        }
        var defaults = defaultFields(token);
        var targets = defaults.stream()
                .map(this::definition)
                .filter(QueryParser::keepsPositions)
                .toList();
        if (targets.isEmpty() && !defaults.isEmpty()) {
            if (strict) {
                throw error(token.start(), "No default field keeps positions, so none can search a phrase");
            }
            return word(token);
        }
        var queries = new ArrayList<Query>();
        for (var definition : targets) {
            if (hasTerms(definition, token.text())) {
                queries.add(Query.phrase(definition.name(), token.text()));
            }
        }
        return anyOf(queries);
    }

    private List<String> defaultFields(Token token) {
        var defaults = schema.defaultFields();
        if (strict && defaults.isEmpty()) {
            throw error(token.start(), "The schema has no default fields, so this needs a field, as in name:text");
        }
        return defaults;
    }

    private Schema.FieldDefinition definition(String field) {
        return schema.field(field).orElseThrow();
    }

    private boolean hasTerms(Schema.FieldDefinition definition, String value) {
        terms = 0;
        switch (definition) {
            case Schema.TextField field ->
                analysis.text(value, 0, field.accents() == Accents.REMOVE, null, (_, _, _) -> terms++, (_, _, _) -> {});
            case Schema.KeywordField _ -> analysis.keyword(value, (_, _, _) -> terms++);
        }
        return terms > 0;
    }

    private Token peek(int ahead) {
        while (tokens.size() <= next + ahead) {
            tokens.add(lex());
        }
        return tokens.get(next + ahead);
    }

    private Token advance() {
        var token = peek(0);
        next++;
        return token;
    }

    private QuerySyntaxException error(int position, String reason) {
        return new QuerySyntaxException(reason, position);
    }

    private Token lex() {
        // The word right after a '-' is never an operator: -AND excludes the word "and".
        var operators = !afterMinus;
        afterMinus = false;
        while (offset < text.length() && isSpace(text.codePointAt(offset))) {
            offset += Character.charCount(text.codePointAt(offset));
        }
        var start = offset;
        if (start == text.length()) {
            return new Token(Kind.EOF, start, "", null);
        }
        if (isSpecial(start, '(') || isSpecial(start, ')')) {
            offset++;
            var open = text.charAt(start) == '(';
            return new Token(open ? Kind.LPAREN : Kind.RPAREN, start, open ? "(" : ")", null);
        }
        return isSpecial(start, '"') ? lexPhrase(start, null) : lexWord(start, operators);
    }

    private Token lexWord(int start, boolean operators) {
        if (operators && text.charAt(start) == '-' && startsMinus(start)) {
            offset++;
            afterMinus = true;
            return new Token(Kind.MINUS, start, "-", null);
        }
        var field = fieldName(start);
        if (field != null) {
            return value(start, field);
        }
        var word = readWord();
        return new Token(operators ? operator(start, offset) : Kind.WORD, start, word, null);
    }

    private boolean startsMinus(int start) {
        var after = start + 1;
        if (after < text.length() && !isSpace(text.codePointAt(after)) && !isSpecial(after, ')')) {
            return true;
        }
        if (strict) {
            throw error(start, "'-' has no query after it");
        }
        return false;
    }

    // The field the text at start names, when the schema declares it and a ':' follows.
    private @Nullable String fieldName(int start) {
        var end = start;
        while (end < text.length() && isNameCharacter(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end));
        }
        if (end == start || end == text.length() || text.charAt(end) != ':') {
            return null;
        }
        var name = text.substring(start, end);
        return schema.field(name).isPresent() ? name : null;
    }

    private Token value(int start, String field) {
        var valueStart = start + field.length() + 1;
        if (valueStart < text.length()
                && !isSpace(text.codePointAt(valueStart))
                && !isSpecial(valueStart, '(')
                && !isSpecial(valueStart, ')')) {
            offset = valueStart;
            return isSpecial(valueStart, '"')
                    ? lexPhrase(start, field)
                    : new Token(Kind.WORD, start, readWord(), field);
        }
        if (strict) {
            throw error(start, "Field " + field + " has no word or phrase after it");
        }
        return new Token(Kind.WORD, start, readWord(), null);
    }

    private Token lexPhrase(int start, @Nullable String field) {
        var open = offset;
        var builder = new StringBuilder();
        offset++;
        while (offset < text.length() && !isSpecial(offset, '"')) {
            append(builder);
        }
        if (offset < text.length()) {
            offset++;
        } else if (strict) {
            throw error(open, "This quote is never closed");
        }
        return new Token(Kind.PHRASE, start, builder.toString(), field);
    }

    private String readWord() {
        var builder = new StringBuilder();
        while (offset < text.length() && !endsWord(offset)) {
            if (strict && text.charAt(offset) == '\\' && offset + 1 == text.length()) {
                throw error(offset, "This '\\' has nothing after it to escape");
            }
            append(builder);
        }
        return builder.toString();
    }

    // Appends the character at offset, or the one it escapes, and moves past it.
    private void append(StringBuilder builder) {
        var codePoint = text.codePointAt(offset);
        if (codePoint == '\\' && offset + 1 < text.length()) {
            offset++;
            codePoint = text.codePointAt(offset);
        }
        builder.appendCodePoint(codePoint);
        offset += Character.charCount(codePoint);
    }

    private boolean endsWord(int index) {
        return isSpace(text.codePointAt(index))
                || isSpecial(index, '(')
                || isSpecial(index, ')')
                || isSpecial(index, '"');
    }

    private boolean isSpecial(int index, char special) {
        return text.charAt(index) == special && !literal[index];
    }

    private Kind operator(int start, int end) {
        return switch (text.substring(start, end)) {
            case "AND" -> Kind.AND;
            case "OR" -> Kind.OR;
            case "NOT" -> Kind.NOT;
            default -> Kind.WORD;
        };
    }

    private static boolean startsOperand(Token token) {
        return switch (token.kind()) {
            case WORD, PHRASE, LPAREN, NOT, MINUS -> true;
            case AND, OR, RPAREN, EOF -> false;
        };
    }

    private static boolean keepsPositions(Schema.FieldDefinition definition) {
        return !(definition instanceof Schema.TextField field && field.positions() == Positions.DROP);
    }

    private static @Nullable Query anyOf(List<Query> queries) {
        return queries.isEmpty() ? null : queries.size() == 1 ? queries.getFirst() : Query.any(queries);
    }

    private static void addIfPresent(List<Query> queries, @Nullable Query query) {
        if (query != null) {
            queries.add(query);
        }
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isNameCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-';
    }

    /// The special characters that lenient parsing reads as text: the quotes never closed, the parentheses never
    /// matched, and those nested more than [#MAX_DEPTH] deep, each with its match. It scans as the lexer does, so
    /// that the parser then meets no unbalanced quote or parenthesis.
    private static boolean[] literals(String text) {
        var literal = new boolean[text.length()];
        // The opening parentheses not closed yet. Those from index MAX_DEPTH of the stack up are read as text.
        var opens = new ArrayDeque<Integer>();
        var i = 0;
        while (i < text.length()) {
            var c = text.charAt(i);
            if (c == '\\') {
                i = afterEscape(text, i);
                continue;
            }
            if (c == '"') {
                var close = closingQuote(text, i);
                literal[i] = close < 0;
                i = close < 0 ? i + 1 : close + 1;
                continue;
            }
            if (c == '(') {
                literal[i] = opens.size() >= MAX_DEPTH;
                opens.push(i);
            } else if (c == ')') {
                literal[i] = opens.isEmpty() || opens.size() > MAX_DEPTH;
                opens.poll();
            }
            i++;
        }
        for (var open : opens) {
            literal[open] = true;
        }
        return literal;
    }

    private static int afterEscape(String text, int index) {
        var escaped = index + 1;
        return escaped < text.length() ? escaped + Character.charCount(text.codePointAt(escaped)) : escaped;
    }

    // The index of the quote that closes the one at open, or -1.
    private static int closingQuote(String text, int open) {
        var i = open + 1;
        while (i < text.length()) {
            var c = text.charAt(i);
            if (c == '"') {
                return i;
            }
            i = c == '\\' ? afterEscape(text, i) : i + 1;
        }
        return -1;
    }

    private enum Kind {
        WORD,
        PHRASE,
        LPAREN,
        RPAREN,
        AND,
        OR,
        NOT,
        MINUS,
        EOF
    }

    // field is the field a word or phrase names, null when it names none.
    private record Token(
            Kind kind, int start, String text, @Nullable String field) {}

    // query is null when the operand was dropped; excluded when it is negated an odd number of times.
    private record Operand(@Nullable Query query, boolean excluded) {}
}
