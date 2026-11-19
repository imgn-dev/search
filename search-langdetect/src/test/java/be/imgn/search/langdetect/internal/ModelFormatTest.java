package be.imgn.search.langdetect.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class ModelFormatTest {

    private static final byte[] BUNDLED = bundled();

    @Test
    void readsTheBundledModel() throws IOException {
        var model = ModelFormat.read(BUNDLED);

        assertThat(model.scripts()).extracting(ScriptModel::script).containsExactly(Script.values());
        assertThat(script(model, Script.GREEK).languages())
                .extracting(ScriptModel.Language::tag)
                .containsExactly("el");
        assertThat(script(model, Script.CYRILLIC).languages())
                .extracting(ScriptModel.Language::tag)
                .containsExactlyInAnyOrder("ru", "uk", "sr");
        assertThat(script(model, Script.LATIN).languages()).hasSize(22);
    }

    @Test
    void writesTheBytesItReads() throws IOException {
        assertThat(ModelFormat.write(ModelFormat.read(BUNDLED))).isEqualTo(BUNDLED);
    }

    @Test
    void writesAModelItReads() throws IOException {
        var model = new Model(
                new Alphabet(new char[] {'a', 'b'}),
                10,
                List.of(new ScriptModel(
                        Script.LATIN,
                        List.of(new ScriptModel.Language("en", 4.5f), new ScriptModel.Language("fr", 4.75f)),
                        new ScriptModel.Fit(0.25f, 5, 2),
                        new byte[] {50, 60, 100, 110, (byte) 200, (byte) 210},
                        new int[] {3, 4, 1 << 8 | 3, 3 << 16 | 4 << 8 | 1},
                        new byte[] {20, 60, 30, 31, 100, 110, (byte) 250, 7})));

        var read = ModelFormat.read(ModelFormat.write(model));

        assertThat(read.unitsPerNat()).isEqualTo(10);
        assertThat(read.alphabet().chars()).containsExactly('a', 'b');
        var script = script(read, Script.LATIN);
        assertThat(script.languages())
                .containsExactly(new ScriptModel.Language("en", 4.5f), new ScriptModel.Language("fr", 4.75f));
        assertThat(script.fit()).isEqualTo(new ScriptModel.Fit(0.25f, 5, 2));
        assertThat(script.keys()).containsExactly(3, 4, 1 << 8 | 3, 3 << 16 | 4 << 8 | 1);
        assertThat(script.costs()).containsExactly(20, 60, 30, 31, 100, 110, 250, 7);
        assertThat(read.script(Script.GREEK)).isNull();
    }

    @Test
    void rejectsACorruptModel() {
        for (var position : new int[] {8, BUNDLED.length / 2, BUNDLED.length - 5}) {
            var corrupt = BUNDLED.clone();
            corrupt[position] ^= 1;
            assertThatIOException()
                    .isThrownBy(() -> ModelFormat.read(corrupt))
                    .withMessage("The language model is corrupt: its checksum does not match");
        }
    }

    @Test
    void rejectsATruncatedModel() {
        assertThatIOException()
                .isThrownBy(() -> ModelFormat.read(Arrays.copyOf(BUNDLED, BUNDLED.length - 1)))
                .withMessage("The language model is corrupt: its checksum does not match");
        assertThatIOException()
                .isThrownBy(() -> ModelFormat.read(Arrays.copyOf(BUNDLED, 10)))
                .withMessage("The language model is truncated");
    }

    @Test
    void rejectsAnotherFile() {
        var other = BUNDLED.clone();
        other[0] = 'P';
        assertThatIOException()
                .isThrownBy(() -> ModelFormat.read(other))
                .withMessage("Not a language model: wrong magic number");
    }

    @Test
    void rejectsAnotherVersion() {
        var other = BUNDLED.clone();
        ByteBuffer.wrap(other).putInt(Integer.BYTES, ModelFormat.VERSION + 1);
        assertThatIOException()
                .isThrownBy(() -> ModelFormat.read(other))
                .withMessage("Unsupported language model format version 2, expected 1");
    }

    @Test
    void rejectsAMalformedModelWithAValidChecksum() {
        // An unknown script, with the checksum recomputed.
        var malformed = BUNDLED.clone();
        var alphabetSize = ByteBuffer.wrap(malformed).getShort(3 * Integer.BYTES);
        var scriptIndex = 3 * Integer.BYTES + Short.BYTES + alphabetSize * Character.BYTES + 1;
        malformed[scriptIndex] = 9;
        var crc = new CRC32C();
        crc.update(malformed, 0, malformed.length - Integer.BYTES);
        ByteBuffer.wrap(malformed).putInt(malformed.length - Integer.BYTES, (int) crc.getValue());

        assertThatIOException()
                .isThrownBy(() -> ModelFormat.read(malformed))
                .withMessage("The language model has an unknown script 9");
    }

    @Test
    void rejectsFieldsOutOfRange() {
        assertMalformed(model(4.5f, new ScriptModel.Fit(0.15f, 0, Float.NaN)), "temperature NaN");
        assertMalformed(model(4.5f, new ScriptModel.Fit(0.15f, 0, 0)), "temperature 0.0");
        assertMalformed(model(4.5f, new ScriptModel.Fit(0.15f, 0, -1)), "temperature -1.0");
        assertMalformed(model(Float.NaN, new ScriptModel.Fit(0.15f, 0, 1)), "center NaN");
        assertMalformed(model(-1, new ScriptModel.Fit(0.15f, 0, 1)), "center -1.0");
        assertMalformed(model(4.5f, new ScriptModel.Fit(Float.NaN, 0, 1)), "margin NaN");
        assertMalformed(model(4.5f, new ScriptModel.Fit(-0.5f, 0, 1)), "margin -0.5");
        assertMalformed(model(4.5f, new ScriptModel.Fit(0.15f, -1, 1)), "slope -1.0");
        assertMalformed(model(4.5f, new ScriptModel.Fit(0.15f, Float.POSITIVE_INFINITY, 1)), "slope Infinity");
    }

    @Test
    void rejectsAVarintLongerThanAnInt() {
        var overflowing = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x1F};
        assertThatIOException()
                .isThrownBy(() -> ModelFormat.readVarint(ByteBuffer.wrap(overflowing)))
                .withMessage("The language model has a malformed varint");
        var tooLong = new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0};
        assertThatIOException()
                .isThrownBy(() -> ModelFormat.readVarint(ByteBuffer.wrap(tooLong)))
                .withMessage("The language model has a malformed varint");
    }

    @Test
    void readsTheLargestVarint() throws IOException {
        var largest = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F};
        assertThat(ModelFormat.readVarint(ByteBuffer.wrap(largest))).isEqualTo(-1);
    }

    private static void assertMalformed(Model model, String field) {
        var bytes = ModelFormat.write(model);
        assertThatIOException()
                .isThrownBy(() -> ModelFormat.read(bytes))
                .withMessage("The language model is malformed: " + field);
    }

    private static Model model(float center, ScriptModel.Fit fit) {
        return new Model(
                new Alphabet(new char[] {'a'}),
                10,
                List.of(new ScriptModel(
                        Script.LATIN,
                        List.of(new ScriptModel.Language("en", center)),
                        fit,
                        new byte[] {10, 20, 30},
                        new int[] {3},
                        new byte[] {5})));
    }

    private static ScriptModel script(Model model, Script script) {
        return Objects.requireNonNull(model.script(script), script.name());
    }

    private static byte[] bundled() {
        try (var in = Objects.requireNonNull(Model.class.getResourceAsStream("languages.model"), "languages.model")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
