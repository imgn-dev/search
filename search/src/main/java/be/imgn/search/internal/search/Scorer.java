package be.imgn.search.internal.search;

import be.imgn.search.internal.segment.DocIterator;

/// Iterates over the documents a query matches in a segment, and scores the current one.
abstract class Scorer implements DocIterator {

    /// The score of the current document.
    abstract float score();
}
