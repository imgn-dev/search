package be.imgn.search.langdetect.internal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.zip.CRC32C;

/// The binary format of a model, big-endian:
///
/// - the magic number `LANG` and the format version, two `int`s;
/// - the cost units per nat, a `float`;
/// - the alphabet: its size, an `unsigned short`, then its chars;
/// - the number of scripts, a byte, then for each:
///   - the script, a byte: 1 for Latin, 2 for Greek, 3 for Cyrillic;
///   - the number of languages, a byte, then for each its tag, a byte length then ASCII, and its center, a
///     `float`;
///   - the margin, the slope and the temperature, three `float`s;
///   - the default costs, by order then language, one byte each;
///   - the number of n-grams, an `int`, then for each in ascending key order: the difference from the previous
///     key (from 0 for the first) as a varint, the mask of the languages whose cost is not the default as a
///     varint, and the cost in each of those languages, one byte each;
/// - the CRC-32C of everything before it, an `int`.
///
/// A varint holds 7 bits per byte, the lowest first, with the high bit set on every byte but the last.
public final class ModelFormat {

    /// The magic number a model starts with: `LANG` in ASCII.
    public static final int MAGIC = 0x4C414E47;

    /// The version of the format this class reads and writes.
    public static final int VERSION = 1;

    private static final int MAX_LANGUAGES = Integer.SIZE - 1;
    private static final int HEADER_LENGTH = 2 * Integer.BYTES;
    private static final int CHECKSUM_LENGTH = Integer.BYTES;
    private static final int BYTE_MASK = 0xFF;
    private static final int VARINT_BITS = 7;
    private static final int VARINT_MASK = (1 << VARINT_BITS) - 1;
    private static final int VARINT_MORE = 1 << VARINT_BITS;

    private ModelFormat() {}

    /// Writes a model. The same model always gives the same bytes.
    public static byte[] write(Model model) {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        try {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeFloat(model.unitsPerNat());
            var chars = model.alphabet().chars();
            out.writeShort(chars.length);
            for (var c : chars) {
                out.writeChar(c);
            }
            var scripts = model.scripts();
            out.writeByte(scripts.size());
            for (var script : scripts) {
                write(script, out);
            }
            out.flush();
            var crc = new CRC32C();
            crc.update(bytes.toByteArray());
            out.writeInt((int) crc.getValue());
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(ScriptModel script, DataOutputStream out) throws IOException {
        var languages = script.languages();
        if (languages.size() > MAX_LANGUAGES) {
            throw new IllegalArgumentException("A script has at most " + MAX_LANGUAGES + " languages");
        }
        out.writeByte(script.script().index());
        out.writeByte(languages.size());
        for (var language : languages) {
            var tag = language.tag().getBytes(StandardCharsets.US_ASCII);
            out.writeByte(tag.length);
            out.write(tag);
            out.writeFloat(language.center());
        }
        var fit = script.fit();
        out.writeFloat(fit.margin());
        out.writeFloat(fit.slope());
        out.writeFloat(fit.temperature());
        var defaults = script.defaults();
        out.write(defaults);
        var keys = script.keys();
        var costs = script.costs();
        var size = languages.size();
        out.writeInt(keys.length);
        var previous = 0;
        for (var row = 0; row < keys.length; row++) {
            writeVarint(keys[row] - previous, out);
            previous = keys[row];
            var base = (NGrams.order(keys[row]) - 1) * size;
            var mask = 0;
            for (var language = 0; language < size; language++) {
                if (costs[row * size + language] != defaults[base + language]) {
                    mask |= 1 << language;
                }
            }
            writeVarint(mask, out);
            for (var language = 0; language < size; language++) {
                if ((mask & (1 << language)) != 0) {
                    out.writeByte(costs[row * size + language]);
                }
            }
        }
    }

    private static void writeVarint(int value, DataOutputStream out) throws IOException {
        while ((value & ~VARINT_MASK) != 0) {
            out.writeByte((value & VARINT_MASK) | VARINT_MORE);
            value >>>= VARINT_BITS;
        }
        out.writeByte(value);
    }

    /// Reads a model.
    ///
    /// @throws IOException if `bytes` are not a model in this format, or are corrupt
    public static Model read(byte[] bytes) throws IOException {
        if (bytes.length < HEADER_LENGTH + CHECKSUM_LENGTH) {
            throw new IOException("The language model is truncated");
        }
        var buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) {
            throw new IOException("Not a language model: wrong magic number");
        }
        var version = buffer.getInt();
        if (version != VERSION) {
            throw new IOException("Unsupported language model format version " + version + ", expected " + VERSION);
        }
        var crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - CHECKSUM_LENGTH);
        if ((int) crc.getValue() != buffer.getInt(bytes.length - CHECKSUM_LENGTH)) {
            throw new IOException("The language model is corrupt: its checksum does not match");
        }
        try {
            var unitsPerNat = check("units per nat", buffer.getFloat(), Float.MIN_VALUE, false);
            var chars = new char[Short.toUnsignedInt(buffer.getShort())];
            for (var i = 0; i < chars.length; i++) {
                chars[i] = buffer.getChar();
            }
            var alphabet = new Alphabet(chars);
            var count = buffer.get();
            var scripts = new ArrayList<ScriptModel>(count);
            for (var i = 0; i < count; i++) {
                scripts.add(readScript(buffer));
            }
            if (buffer.position() != bytes.length - CHECKSUM_LENGTH) {
                throw new IOException("The language model has trailing bytes");
            }
            return new Model(alphabet, unitsPerNat, scripts);
        } catch (BufferUnderflowException | IllegalArgumentException | CharacterCodingException e) {
            throw new IOException("The language model is malformed", e);
        }
    }

    private static ScriptModel readScript(ByteBuffer buffer) throws IOException {
        var index = buffer.get();
        var script = Script.ofIndex(index);
        if (script == null) {
            throw new IOException("The language model has an unknown script " + index);
        }
        var size = buffer.get();
        if (size <= 0 || size > MAX_LANGUAGES) {
            throw new IOException("The language model has a script with " + size + " languages");
        }
        var languages = new ArrayList<ScriptModel.Language>(size);
        for (var i = 0; i < size; i++) {
            var tag = new byte[buffer.get() & BYTE_MASK];
            buffer.get(tag);
            var decoded = StandardCharsets.US_ASCII
                    .newDecoder()
                    .decode(ByteBuffer.wrap(tag))
                    .toString();
            // A language without validation texts has an infinite center: no limit.
            languages.add(new ScriptModel.Language(decoded, check("center", buffer.getFloat(), 0, true)));
        }
        var margin = check("margin", buffer.getFloat(), 0, true);
        var slope = check("slope", buffer.getFloat(), 0, false);
        var temperature = check("temperature", buffer.getFloat(), Float.MIN_VALUE, false);
        var fit = new ScriptModel.Fit(margin, slope, temperature);
        var defaults = new byte[NGrams.MAX_ORDER * size];
        buffer.get(defaults);
        var count = buffer.getInt();
        // Each n-gram takes at least two bytes.
        if (count < 0 || count > buffer.remaining() / 2) {
            throw new IOException("The language model has a script with " + count + " n-grams");
        }
        var keys = new int[count];
        var costs = new byte[count * size];
        var key = 0;
        for (var row = 0; row < count; row++) {
            key += readVarint(buffer);
            keys[row] = key;
            var base = (NGrams.order(key) - 1) * size;
            var mask = readVarint(buffer);
            if (mask >>> size != 0) {
                throw new IOException("The language model has an n-gram in unknown languages");
            }
            for (var language = 0; language < size; language++) {
                costs[row * size + language] = (mask & (1 << language)) != 0 ? buffer.get() : defaults[base + language];
            }
        }
        return new ScriptModel(script, languages, fit, defaults, keys, costs);
    }

    // The value, if it is at least `min` and not NaN, and finite unless `infinite`.
    private static float check(String field, float value, float min, boolean infinite) throws IOException {
        if (!(value >= min) || (!infinite && value == Float.POSITIVE_INFINITY)) {
            throw new IOException("The language model is malformed: " + field + " " + value);
        }
        return value;
    }

    static int readVarint(ByteBuffer buffer) throws IOException {
        var value = 0;
        for (var shift = 0; shift < Integer.SIZE; shift += VARINT_BITS) {
            var b = buffer.get();
            // The last byte holds the 4 bits left of an int, and ends the varint.
            if (shift + VARINT_BITS > Integer.SIZE && (b & -(1 << (Integer.SIZE - shift))) != 0) {
                break;
            }
            value |= (b & VARINT_MASK) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw new IOException("The language model has a malformed varint");
    }
}
