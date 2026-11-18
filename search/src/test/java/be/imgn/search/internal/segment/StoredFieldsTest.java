package be.imgn.search.internal.segment;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.internal.codec.ByteOutput;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

class StoredFieldsTest {

    private static final List<String> FIELDS = List.of("title", "body", "tags");

    @Test
    void roundTripsRecordsAcrossBlocks() {
        var deflater = new Deflater(Deflater.BEST_SPEED);
        var writer = new StoredFieldsWriter(deflater);
        var record = new ByteOutput();
        var count = 3000;
        for (var doc = 0; doc < count; doc++) {
            record.reset();
            if (doc == 1234) {
                // Larger than a block on its own.
                StoredRecord.writeHeader(record, null, 1);
                StoredRecord.writeValue(record, 1, "x".repeat(40_000), StoredRecord.INHERITED, null);
            } else {
                StoredRecord.writeHeader(record, doc % 2 == 0 ? "fr" : null, 3);
                StoredRecord.writeValue(record, 0, "Le renard " + doc, StoredRecord.INHERITED, null);
                StoredRecord.writeValue(record, 0, "The fox " + doc, StoredRecord.OWN, "en");
                StoredRecord.writeValue(record, 2, "forêt", StoredRecord.NONE, null);
            }
            writer.add(record.array(), 0, record.size());
        }
        var bytes = writer.finish();
        deflater.end();
        assertThat(writer.blockCount()).isGreaterThan(2);

        var reader = new StoredFieldsReader(
                MemorySegment.ofArray(bytes.toByteArray()), 0, writer.indexOffset(), writer.blockCount(), FIELDS);
        var inflater = new Inflater();
        for (var doc : new int[] {0, 1, 2, 1233, 1234, 1235, count - 1}) {
            var stored = reader.document(doc, inflater);
            if (doc == 1234) {
                assertThat(stored)
                        .isEqualTo(new StoredRecord(
                                null, List.of(new StoredRecord.Value("body", "x".repeat(40_000), null))));
            } else {
                var language = doc % 2 == 0 ? "fr" : null;
                assertThat(stored)
                        .isEqualTo(new StoredRecord(
                                language,
                                List.of(
                                        new StoredRecord.Value("title", "Le renard " + doc, language),
                                        new StoredRecord.Value("title", "The fox " + doc, "en"),
                                        new StoredRecord.Value("tags", "forêt", null))));
            }
        }
        inflater.end();
    }
}
