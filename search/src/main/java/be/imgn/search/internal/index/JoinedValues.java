package be.imgn.search.internal.index;

import static java.util.Objects.checkFromToIndex;
import static java.util.Objects.checkIndex;

/// Text values, each followed by a line feed, read where they are rather than copied, and cut after a number of
/// chars: the language detector reads only the start of a document, so copying all of a large document's values
/// would cost more than detecting. The values are strings, or views over the starts of the sources read for
/// detection; each is read in bulk by `CharSequence.getChars`.
///
/// Not thread-safe: it remembers the value it last read, so that reading forward costs no search.
final class JoinedValues implements CharSequence {

    private final CharSequence[] values;
    // Where each value starts in the joined values, then where they end.
    private final int[] starts;
    private final int length;
    private int current;

    /// Joins `values`, which it keeps without copying; at least one.
    JoinedValues(CharSequence[] values) {
        this(values, Integer.MAX_VALUE);
    }

    /// Joins `values`, which it keeps without copying, and cuts the text after `limit` chars; at least one value.
    JoinedValues(CharSequence[] values, int limit) {
        this.values = values;
        starts = new int[values.length + 1];
        for (var i = 0; i < values.length; i++) {
            starts[i + 1] = (int) Math.min((long) starts[i] + values[i].length() + 1, Integer.MAX_VALUE);
        }
        length = Math.min(starts[values.length], limit);
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        checkIndex(index, length());
        if (index < starts[current] || index >= starts[current + 1]) {
            current = valueAt(index);
        }
        var value = values[current];
        var offset = index - starts[current];
        return offset < value.length() ? value.charAt(offset) : '\n';
    }

    @Override
    public void getChars(int start, int end, char[] destination, int offset) {
        checkFromToIndex(start, end, length());
        checkFromToIndex(offset, offset + end - start, destination.length);
        var i = start == end ? 0 : valueAt(start);
        for (var at = start; at < end; i++) {
            var value = values[i];
            var from = at - starts[i];
            var to = Math.min(end - starts[i], value.length());
            if (from < to) {
                value.getChars(from, to, destination, offset + at - start);
                at += to - from;
            }
            if (at < end && at == starts[i + 1] - 1) {
                destination[offset + at - start] = '\n';
                at++;
            }
        }
    }

    @Override
    public String subSequence(int start, int end) {
        checkFromToIndex(start, end, length());
        var chars = new char[end - start];
        getChars(start, end, chars, 0);
        return new String(chars);
    }

    @Override
    public String toString() {
        return subSequence(0, length());
    }

    // The last value starting at or before `index`.
    private int valueAt(int index) {
        var low = 0;
        var high = values.length - 1;
        while (low < high) {
            var middle = (low + high + 1) >>> 1;
            if (starts[middle] <= index) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }
}
