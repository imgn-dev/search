package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TermBufferTest {

    @Test
    void growsAndKeepsItsWord() {
        var buffer = new TermBuffer();
        buffer.set("abc");

        buffer.ensureCapacity(1000);

        assertThat(buffer.chars().length).isGreaterThanOrEqualTo(1000);
        assertThat(buffer.toString()).isEqualTo("abc");
    }

    @Test
    void setsARangeOfAnArray() {
        var buffer = new TermBuffer();

        buffer.set("xhelloy".toCharArray(), 1, 5);

        assertThat(buffer.toString()).isEqualTo("hello");
    }

    @Test
    void rejectsALengthBeyondItsCapacity() {
        var buffer = new TermBuffer();

        assertThatThrownBy(() -> buffer.setLength(buffer.chars().length + 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.set("abc".toCharArray(), 2, 2)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
