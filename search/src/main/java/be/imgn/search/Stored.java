package be.imgn.search;

/// Whether a field's values are stored in the index, so that a search can return them.
///
/// @since 0.1.0
public enum Stored {
    /// The values are stored, compressed, and returned with the search results.
    YES,
    /// The values are only indexed.
    NO
}
