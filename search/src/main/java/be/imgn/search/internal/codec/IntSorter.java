package be.imgn.search.internal.codec;

/// Sorts `int`s with a comparator, without boxing them: a stable merge sort.
public final class IntSorter {

    private static final int INSERTION_THRESHOLD = 16;

    private IntSorter() {}

    /// Sorts the first `length` values of `values`.
    ///
    /// @param values the values, sorted in place
    /// @param length the number of values to sort
    /// @param comparator the order
    public static void sort(int[] values, int length, IntComparator comparator) {
        var scratch = new int[length];
        sort(values, scratch, 0, length, comparator);
    }

    private static void sort(int[] values, int[] scratch, int from, int to, IntComparator comparator) {
        if (to - from <= INSERTION_THRESHOLD) {
            insertionSort(values, from, to, comparator);
            return;
        }
        var middle = (from + to) >>> 1;
        sort(values, scratch, from, middle, comparator);
        sort(values, scratch, middle, to, comparator);
        if (comparator.compare(values[middle - 1], values[middle]) <= 0) {
            return;
        }
        System.arraycopy(values, from, scratch, from, to - from);
        var left = from;
        var right = middle;
        for (var i = from; i < to; i++) {
            if (right >= to || (left < middle && comparator.compare(scratch[left], scratch[right]) <= 0)) {
                values[i] = scratch[left++];
            } else {
                values[i] = scratch[right++];
            }
        }
    }

    private static void insertionSort(int[] values, int from, int to, IntComparator comparator) {
        for (var i = from + 1; i < to; i++) {
            var value = values[i];
            var j = i - 1;
            while (j >= from && comparator.compare(values[j], value) > 0) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = value;
        }
    }

    /// An order of `int`s.
    @FunctionalInterface
    public interface IntComparator {

        /// Compares two values.
        ///
        /// @param a the first value
        /// @param b the second value
        /// @return negative, zero or positive as `a` is before, equal to or after `b`
        int compare(int a, int b);
    }
}
