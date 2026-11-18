package be.imgn.search.internal.analysis;

/// Receives the terms of an analysis, one at a time, without allocating them.
@FunctionalInterface
public interface TermSink {

    /// Receives a term. The characters are valid only during the call.
    ///
    /// @param chars the term's characters, from index 0
    /// @param length the term's length, at least 1
    /// @param position the term's position in its field
    void term(char[] chars, int length, int position);
}
