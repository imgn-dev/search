package be.imgn.search;

import java.io.Serial;
import java.util.Locale;

/// Thrown by [Schema#parse] when a query is not valid in the query language.
///
/// @since 0.1.0
public final class QuerySyntaxException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int position;

    QuerySyntaxException(String reason, int position) {
        super(String.format(Locale.ROOT, "%s, at position %d of the query", reason, position));
        this.position = position;
    }

    /// The position of the error: the index of the character in the query's text, as [String#charAt] counts,
    /// or the text's length when the query ends too early.
    ///
    /// @return the position, from 0 to the text's length
    public int position() {
        return position;
    }
}
