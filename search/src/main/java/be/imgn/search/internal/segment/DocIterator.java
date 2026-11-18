package be.imgn.search.internal.segment;

/// Iterates over document numbers in increasing order, from before the first one to [#NO_MORE_DOCS].
public interface DocIterator {

    /// The document number after the last one.
    int NO_MORE_DOCS = Integer.MAX_VALUE;

    /// The current document.
    ///
    /// @return the document number, -1 before the first call to [#nextDoc] or [#advance], or [#NO_MORE_DOCS]
    int docID();

    /// Moves to the next document.
    ///
    /// @return the new current document, or [#NO_MORE_DOCS]
    int nextDoc();

    /// Moves to the first document at or after `target`.
    ///
    /// @param target a document number greater than the current one
    /// @return the new current document, or [#NO_MORE_DOCS]
    int advance(int target);

    /// An estimate of the number of documents this iterator gives, to lead conjunctions with the rarest.
    ///
    /// @return the estimate
    long cost();
}
