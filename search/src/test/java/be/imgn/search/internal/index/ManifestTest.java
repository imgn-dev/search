package be.imgn.search.internal.index;

import static be.imgn.search.internal.index.Indexes.ANALYZERS;
import static be.imgn.search.internal.index.Indexes.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.imgn.search.CorruptIndexException;
import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Format;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManifestTest {

    @Test
    void roundTrips() throws CorruptIndexException {
        var manifest = new Manifest(7, SCHEMA.fields(), ANALYZERS, List.of(new Manifest.Entry(3, 1234, -5, 2)));

        assertThat(Manifest.decode(manifest.encode(), 7)).isEqualTo(manifest);
    }

    @Test
    void readsOnlyCanonicalGenerations() {
        assertThat(Manifest.generationOf("manifest-0")).isZero();
        assertThat(Manifest.generationOf("manifest-12")).isEqualTo(12);
        assertThat(Manifest.generationOf("manifest-01")).isEqualTo(-1);
        assertThat(Manifest.generationOf("manifest-99999999999999999999")).isEqualTo(-1);
        assertThat(Manifest.generationOf("manifest-1.tmp")).isEqualTo(-1);
    }

    @Test
    void refusesCountsBeyondItsBytesThoughItsChecksumMatches() {
        for (var count : List.of(Integer.MAX_VALUE, -1, 1000)) {
            var out = new ByteOutput();
            Format.writeHeader(out, Format.MANIFEST_MAGIC);
            out.writeVLong(1);
            out.writeVInt(count);
            Format.writeChecksum(out);

            assertThatThrownBy(() -> Manifest.decode(out.toByteArray(), 1))
                    .as("count %s", count)
                    .isInstanceOf(CorruptIndexException.class);
        }
    }

    @Test
    void refusesAStringLongerThanItsBytes() {
        var out = new ByteOutput();
        Format.writeHeader(out, Format.MANIFEST_MAGIC);
        out.writeVLong(1);
        out.writeVInt(1);
        out.writeVInt(Integer.MAX_VALUE);
        Format.writeChecksum(out);

        assertThatThrownBy(() -> Manifest.decode(out.toByteArray(), 1)).isInstanceOf(CorruptIndexException.class);
    }
}
