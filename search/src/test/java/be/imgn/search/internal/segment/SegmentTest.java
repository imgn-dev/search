package be.imgn.search.internal.segment;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.CorruptDataException;
import be.imgn.search.internal.codec.Norms;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SegmentTest {

    @Test
    void readsBackWhatWasWritten() {
        var segment = Segment.open(MemorySegment.ofArray(write()));

        assertThat(segment.maxDoc()).isEqualTo(3);
        assertThat(segment.fields()).extracting(FieldInfo::name).containsExactly("body", "body.fr", "tags");
        var body = requireNonNull(segment.field("body"));
        var french = requireNonNull(segment.field("body.fr"));
        var tags = requireNonNull(segment.field("tags"));
        assertThat(body.positions()).isTrue();
        assertThat(body.docCount()).isEqualTo(2);
        assertThat(body.totalLength()).isEqualTo(5);
        assertThat(french.normsOffset()).isEqualTo(body.normsOffset());
        assertThat(tags.hasNorms()).isFalse();
        assertThat(segment.norm(body, 0)).isEqualTo(Norms.encode(2));
        assertThat(segment.norm(body, 1)).isZero();
        assertThat(segment.norm(body, 2)).isEqualTo(Norms.encode(3));
        assertThat(segment.field("title")).isNull();

        var fox = requireNonNull(segment.term(body, bytes("fox"), 3));
        assertThat(fox.docFreq()).isEqualTo(2);
        assertThat(segment.term(body, bytes("cat"), 3)).isNull();
        var iterator = new PostingsIterator();
        segment.postings(iterator, body, fox, true);
        assertThat(iterator.nextDoc()).isEqualTo(0);
        assertThat(iterator.nextPosition()).isEqualTo(1);
        assertThat(iterator.nextDoc()).isEqualTo(2);
        assertThat(iterator.freq()).isEqualTo(2);
        assertThat(iterator.nextPosition()).isEqualTo(0);
        assertThat(iterator.nextPosition()).isEqualTo(2);

        assertThat(segment.key(1)).isEqualTo("clé-b");
        assertThat(segment.document(bytes("clé-b"), bytes("clé-b").length)).isEqualTo(1);
        assertThat(segment.document(bytes("a"), 1)).isEqualTo(0);
        assertThat(segment.document(bytes("zz"), 2)).isEqualTo(-1);

        var inflater = new Inflater();
        assertThat(segment.stored(2, inflater))
                .isEqualTo(new StoredRecord("fr", List.of(new StoredRecord.Value("body", "fox and fox", "fr"))));
        inflater.end();
        segment.checkIntegrity();
    }

    @Test
    void detectsCorruption() {
        var bytes = write();
        bytes[bytes.length / 2] ^= 1;

        assertThatThrownBy(() -> Segment.open(MemorySegment.ofArray(bytes)).checkIntegrity())
                .isInstanceOf(CorruptDataException.class);
    }

    @Test
    void rejectsAnotherKindOfFile() {
        var bytes = write();
        bytes[0] ^= 1;

        assertThatThrownBy(() -> Segment.open(MemorySegment.ofArray(bytes)))
                .isInstanceOf(CorruptDataException.class)
                .hasMessageContaining("Magic");
    }

    // Documents: 0 "a fox" (keyword animal), 1 no body (keyword animal), 2 "fox and fox" in French.
    private static byte[] write() {
        var deflater = new Deflater(Deflater.BEST_SPEED);
        var writer = new SegmentWriter(3, List.of("body", "tags"), deflater);
        var norms = new byte[] {(byte) Norms.encode(2), 0, (byte) Norms.encode(3)};
        writer.startField("body", true, norms, true);
        addTerm(writer, "a", new int[] {0}, new int[] {1}, new int[] {0});
        addTerm(writer, "and", new int[] {2}, new int[] {1}, new int[] {1});
        addTerm(writer, "fox", new int[] {0, 2}, new int[] {1, 2}, new int[] {1, 0, 2});
        writer.finishField(2, 5);
        writer.startField("body.fr", false, norms, false);
        addTerm(writer, "fox", new int[] {2}, new int[] {2}, null);
        writer.finishField(0, 0);
        writer.startField("tags", false, null, false);
        addTerm(writer, "animal", new int[] {0, 1}, new int[] {1, 1}, null);
        writer.finishField(0, 0);
        for (var key : List.of("a", "clé-b", "c")) {
            writer.addKey(key);
        }
        var record = new ByteOutput();
        for (var doc = 0; doc < 3; doc++) {
            record.reset();
            if (doc == 2) {
                StoredRecord.writeHeader(record, "fr", 1);
                StoredRecord.writeValue(record, 0, "fox and fox", StoredRecord.INHERITED, null);
            } else {
                StoredRecord.writeHeader(record, null, 0);
            }
            writer.addStored(record.array(), 0, record.size());
        }
        var bytes = writer.finish();
        deflater.end();
        return bytes;
    }

    private static void addTerm(
            SegmentWriter writer, String term, int[] docs, int[] freqs, int @Nullable [] positions) {
        var bytes = bytes(term);
        writer.addTerm(bytes, bytes.length, docs, freqs, docs.length, positions);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
