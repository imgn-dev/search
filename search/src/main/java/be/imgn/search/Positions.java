package be.imgn.search;

/// Whether a text field keeps the positions of its terms, which phrase queries need.
///
/// @since 0.1.0
public enum Positions {
    /// Positions are kept: the field answers phrase queries.
    KEEP,
    /// Positions are dropped: the field is smaller, and a phrase query on it is rejected.
    DROP
}
