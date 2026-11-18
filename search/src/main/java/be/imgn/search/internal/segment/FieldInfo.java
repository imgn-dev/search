package be.imgn.search.internal.segment;

/// An internal field of a segment: a neutral text field such as `body`, a language field such as `body.fr`, or a
/// keyword field.
///
/// @param name the field's name
/// @param positions whether the field has positions
/// @param normsOffset the offset in the segment of the norms that score the field: its own for a neutral text
///     field, its neutral field's for a language field; -1 for a keyword field, which has none
/// @param docCount the number of documents with a non-empty value, for a neutral text field; 0 otherwise
/// @param totalLength the sum of their lengths, for a neutral text field; 0 otherwise
/// @param terms the field's term dictionary
public record FieldInfo(
        String name, boolean positions, long normsOffset, long docCount, long totalLength, TermDictionary terms) {

    /// Whether the field has norms to score with.
    ///
    /// @return whether it has norms
    public boolean hasNorms() {
        return normsOffset >= 0;
    }
}
