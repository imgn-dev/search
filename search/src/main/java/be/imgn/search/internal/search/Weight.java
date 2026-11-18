package be.imgn.search.internal.search;

import org.jspecify.annotations.Nullable;

/// A query prepared for a snapshot: analysed, with its statistics, ready to give a [Scorer] per segment.
sealed interface Weight permits TermWeight, PhraseWeight, AllWeight, AnyWeight, Weight.Nothing {

    /// A weight that matches nothing.
    Weight NOTHING = new Nothing();

    /// The scorer of a segment.
    ///
    /// @param segment the index of the segment in the snapshot
    /// @return the scorer, or `null` if nothing matches in that segment
    @Nullable
    Scorer scorer(int segment);

    /// Matches nothing.
    final class Nothing implements Weight {

        private Nothing() {}

        @Override
        public @Nullable Scorer scorer(int segment) {
            return null;
        }
    }
}
