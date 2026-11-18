package be.imgn.search.internal.segment;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.internal.codec.ByteOutput;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TermDictionaryTest {

    private static final int COLUMNS = 2;
    // The second column grows with the terms, as offsets do.
    private static final long DELTA_MASK = 0b10;

    @Test
    void findsEveryTermWithItsValues() {
        var terms = terms();
        var dictionary = write(terms);

        assertThat(dictionary.size()).isEqualTo(terms.size());
        for (var i = 0; i < terms.size(); i++) {
            var values = new long[COLUMNS];
            assertThat(lookup(dictionary, terms.get(i), values))
                    .as(terms.get(i))
                    .isTrue();
            assertThat(values).containsExactly(i % 7, 1000L * i);
        }
    }

    @Test
    void findsTermsThatArePrefixesOfOthers() {
        var dictionary = write(List.of("a", "ab", "abc", "abcd", "abd", "b"));
        var values = new long[COLUMNS];

        assertThat(lookup(dictionary, "ab", values)).isTrue();
        assertThat(values[1]).isEqualTo(1000);
        assertThat(lookup(dictionary, "abcd", values)).isTrue();
        assertThat(values[1]).isEqualTo(3000);
        assertThat(lookup(dictionary, "abce", values)).isFalse();
        assertThat(lookup(dictionary, "abcde", values)).isFalse();
    }

    @Test
    void missesAbsentTerms() {
        var dictionary = write(terms());
        var values = new long[COLUMNS];

        for (var absent : List.of("", "0", "aaa", "term-0005x", "term-0005", "term-01", "term-1000", "zzz", "é")) {
            assertThat(lookup(dictionary, absent, values)).as(absent).isFalse();
        }
    }

    @Test
    void ordersBytesAsUnsigned() {
        var dictionary = write(List.of("e", "z", "é", "€"));
        var values = new long[COLUMNS];

        assertThat(lookup(dictionary, "é", values)).isTrue();
        assertThat(lookup(dictionary, "€", values)).isTrue();
        assertThat(lookup(dictionary, "f", values)).isFalse();
    }

    @Test
    void missesEverythingWhenEmpty() {
        var dictionary = write(List.of());

        assertThat(lookup(dictionary, "a", new long[COLUMNS])).isFalse();
    }

    // Many blocks of shared prefixes: term-0000 to term-0999 every 3rd.
    private static List<String> terms() {
        var terms = new ArrayList<String>();
        for (var i = 0; i < 1000; i += 3) {
            terms.add(String.format(Locale.ROOT, "term-%04d", i));
        }
        return terms;
    }

    private static TermDictionary write(List<String> terms) {
        var out = new ByteOutput();
        out.writeLong(-1);
        var writer = new TermDictionaryWriter(out, COLUMNS, DELTA_MASK);
        for (var i = 0; i < terms.size(); i++) {
            var bytes = terms.get(i).getBytes(StandardCharsets.UTF_8);
            writer.add(bytes, bytes.length, new long[] {i % 7, 1000L * i});
        }
        var index = writer.finish();
        out.writeLong(-1);
        var data = MemorySegment.ofArray(out.toByteArray());
        return new TermDictionary(data, writer.start(), index, writer.termCount(), COLUMNS, DELTA_MASK);
    }

    private static boolean lookup(TermDictionary dictionary, String term, long[] values) {
        var bytes = term.getBytes(StandardCharsets.UTF_8);
        // A longer array than the term, as callers reuse buffers.
        var padded = Arrays.copyOf(bytes, bytes.length + 5);
        return dictionary.lookup(padded, bytes.length, values);
    }
}
