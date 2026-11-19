package be.imgn.search.internal.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.CharBuffer;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JoinedValuesTest {

    private static final String[] VALUES = {"le chat", "", "renards", "a"};
    private static final String JOINED = "le chat\n\nrenards\na\n";

    @Test
    void readsTheValuesEachFollowedByALineFeed() {
        var text = new JoinedValues(VALUES);

        assertThat(text.length()).isEqualTo(JOINED.length());
        assertThat(text.toString()).isEqualTo(JOINED);
        for (var i = 0; i < JOINED.length(); i++) {
            assertThat(text.charAt(i)).as("char %d", i).isEqualTo(JOINED.charAt(i));
        }
    }

    @Test
    void readsInAnyOrder() {
        var text = new JoinedValues(VALUES);
        var random = new Random(42);

        for (var i = 0; i < 1000; i++) {
            var index = random.nextInt(JOINED.length());
            assertThat(text.charAt(index)).as("char %d", index).isEqualTo(JOINED.charAt(index));
        }
    }

    @Test
    void cutsSubsequencesAcrossValues() {
        var text = new JoinedValues(VALUES);

        for (var start = 0; start <= JOINED.length(); start++) {
            for (var end = start; end <= JOINED.length(); end++) {
                assertThat(text.subSequence(start, end).toString()).isEqualTo(JOINED.substring(start, end));
            }
        }
    }

    @Test
    void copiesRangesAcrossValues() {
        var text = new JoinedValues(VALUES);

        for (var start = 0; start <= JOINED.length(); start++) {
            for (var end = start; end <= JOINED.length(); end++) {
                var chars = new char[end - start + 2];
                text.getChars(start, end, chars, 1);
                assertThat(new String(chars, 1, end - start)).isEqualTo(JOINED.substring(start, end));
                assertThat(chars[0]).isEqualTo('\0');
                assertThat(chars[chars.length - 1]).isEqualTo('\0');
            }
        }
    }

    @Test
    void rejectsIndexesOutsideTheText() {
        var text = new JoinedValues(VALUES);

        assertThatThrownBy(() -> text.charAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> text.charAt(JOINED.length())).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> text.subSequence(3, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> text.subSequence(0, JOINED.length() + 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> text.getChars(0, 4, new char[3], 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void readsASingleValue() {
        var text = new JoinedValues(new String[] {"renards"});

        assertThat(text.toString()).isEqualTo("renards\n");
        assertThat(text.charAt(7)).isEqualTo('\n');
    }

    @Test
    void readsCharBuffersAsStrings() {
        var buffer = "xxle chatrenardsx".toCharArray();
        var text = new JoinedValues(
                new CharSequence[] {CharBuffer.wrap(buffer, 2, 7), "", CharBuffer.wrap(buffer, 9, 7), "a"});

        assertThat(text.toString()).isEqualTo(JOINED);
        for (var i = 0; i < JOINED.length(); i++) {
            assertThat(text.charAt(i)).as("char %d", i).isEqualTo(JOINED.charAt(i));
        }
        for (var start = 0; start <= JOINED.length(); start++) {
            for (var end = start; end <= JOINED.length(); end++) {
                var chars = new char[end - start];
                text.getChars(start, end, chars, 0);
                assertThat(new String(chars)).isEqualTo(JOINED.substring(start, end));
            }
        }
    }

    @Test
    void cutsTheTextAfterALimit() {
        for (var limit = 0; limit <= JOINED.length() + 2; limit++) {
            var text = new JoinedValues(VALUES, limit);
            var expected = JOINED.substring(0, Math.min(limit, JOINED.length()));

            assertThat(text.toString()).isEqualTo(expected);
            assertThat(text.length()).isEqualTo(expected.length());
            assertThatThrownBy(() -> text.charAt(expected.length())).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }
}
