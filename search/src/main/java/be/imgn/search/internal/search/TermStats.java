package be.imgn.search.internal.search;

import be.imgn.search.internal.segment.TermInfo;
import org.jspecify.annotations.Nullable;

/// A term of an internal field looked up in every segment of a snapshot, with its document frequency summed.
final class TermStats {

    private final String field;
    private final @Nullable TermInfo[] infos;
    private final long docFreq;

    TermStats(SearchContext context, String field, byte[] term) {
        this.field = field;
        this.infos = new TermInfo[context.segmentCount()];
        long sum = 0;
        for (var i = 0; i < infos.length; i++) {
            var segment = context.segment(i);
            var info = segment.field(field);
            infos[i] = info == null ? null : segment.term(info, term, term.length);
            var found = infos[i];
            if (found != null) {
                sum += found.docFreq();
            }
        }
        this.docFreq = sum;
    }

    String field() {
        return field;
    }

    long docFreq() {
        return docFreq;
    }

    /// The term in a segment.
    ///
    /// @return the term, or `null` if the segment does not have it
    @Nullable
    TermInfo info(int segment) {
        return infos[segment];
    }
}
