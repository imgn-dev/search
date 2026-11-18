package be.imgn.search.internal.search;

import static be.imgn.search.internal.segment.DocIterator.NO_MORE_DOCS;

import be.imgn.search.internal.segment.DocIterator;

/// Moves iterators to the documents they all have.
final class Conjunctions {

    private Conjunctions() {}

    /// Moves every iterator to the first document at or after `candidate` that they all have.
    ///
    /// @param lead the iterator at `candidate`, the rarest
    /// @param iterators all the iterators, `lead` included
    /// @param candidate the lead's current document
    /// @return the common document, or [DocIterator#NO_MORE_DOCS]
    static int align(DocIterator lead, DocIterator[] iterators, int candidate) {
        var doc = candidate;
        var aligned = false;
        while (!aligned && doc != NO_MORE_DOCS) {
            aligned = true;
            for (var iterator : iterators) {
                if (iterator.docID() < doc) {
                    var other = iterator.advance(doc);
                    if (other > doc) {
                        doc = lead.advance(other);
                        aligned = false;
                        break;
                    }
                }
            }
        }
        return doc;
    }
}
