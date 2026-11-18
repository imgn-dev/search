package be.imgn.search;

/// Whether a text field removes accents from its terms, so that `élève` and `eleve` match.
///
/// @since 0.1.0
public enum Accents {
    /// Accents are kept: `é` and `e` are different letters.
    KEEP,
    /// Accents are removed, along with the strokes and ligatures of letters such as `ø`, `ł` and `æ`.
    REMOVE
}
