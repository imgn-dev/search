package be.imgn.search.internal.index;

import be.imgn.gaia.core.io.CharSource;
import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.LanguageDetector;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.WordAnalyzer;
import be.imgn.search.internal.analysis.FieldAnalysis;
import be.imgn.search.internal.analysis.TermSink;
import be.imgn.search.internal.analysis.Tokenizer;
import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Utf8;
import be.imgn.search.internal.segment.StoredRecord;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Analyses documents into a [DocumentBuffer]: routes each text value to its neutral field and, in a language
/// with an analyzer, to its language field; counts the neutral fields' lengths; and encodes the stored record.
///
/// A value's language is its own, else the document's, else the one detected for the document; the detector
/// runs once per document, when the document has none, over the first [#DETECTED_LENGTH] chars of its text
/// values without a language, joined. A source that reaches into them is opened for them, then again to be
/// analysed as it is read; any other source is opened once. Not thread-safe.
final class DocumentIndexer {

    // The chars at the start of a document's values without a language that language detection reads: the
    // detector reads at most a few KB, and reading a source to its end before indexing it would double the cost of
    // a large one.
    static final int DETECTED_LENGTH = 16_384;

    // The most memory a buffer of the scratch keeps from one document to the next: enough for a document of a few
    // MB, which then reuses it rather than growing it again on every add.
    private static final int MAX_SCRATCH_BYTES = 4 << 20;

    private static final int INITIAL_TERM_BYTES = 256 * Utf8.MAX_BYTES_PER_CHAR;
    private static final int INITIAL_TERMS = 1024;
    private static final Tokenizer.CharsConsumer IGNORED_CHARS = (chars, from, to) -> {};

    private final Schema schema;
    private final List<Schema.FieldDefinition> definitions;
    private final Map<String, Integer> ordinals = new HashMap<>();
    private final Map<Locale, WordAnalyzer> wordAnalyzers = new HashMap<>();
    private final Set<Locale> withoutAnalyzer = new HashSet<>();
    private final FieldAnalysis analysis = new FieldAnalysis();
    private final int[] nextPositions;
    private final int[] lengths;
    private final boolean[] touched;
    private final ByteOutput record = new ByteOutput();
    // The current document's terms: their UTF-8 bytes one after the other, and per term its slot, the end of its
    // bytes and its position.
    private final ByteOutput termData = new ByteOutput();
    // The internal fields the current document has terms in: a field and, for a language field, its language.
    private final List<Slot> slots = new ArrayList<>();
    private final List<FieldBuffer> fields = new ArrayList<>();
    private final TermSink neutralSink = this::neutralTerm;
    private final TermSink languageSink = this::languageTerm;
    private final TermSink keywordSink = this::neutralTerm;
    // Encodes a source's stored value into the record as the tokenizer reads it.
    private final Tokenizer.CharsConsumer storedChars = record::writeUtf8;
    // The chars of the detected text read from sources, at their indexes in that text; allocated on first use.
    private char[] prefixes = new char[0];
    private byte[] termBytes = new byte[INITIAL_TERM_BYTES];
    private int[] termSlots = new int[INITIAL_TERMS];
    private int[] termEnds = new int[INITIAL_TERMS];
    private int[] termPositions = new int[INITIAL_TERMS];
    private int termCount;
    private int neutralSlot;
    private int languageSlot;
    private int length;

    DocumentIndexer(Schema schema) {
        this.schema = schema;
        this.definitions = schema.fields();
        for (var i = 0; i < definitions.size(); i++) {
            ordinals.put(definitions.get(i).name(), i);
        }
        this.nextPositions = new int[definitions.size()];
        this.lengths = new int[definitions.size()];
        this.touched = new boolean[definitions.size()];
    }

    /// The names the stored records refer to by index: every field of the schema, in order.
    List<String> storedFieldNames() {
        return definitions.stream().map(Schema.FieldDefinition::name).toList();
    }

    /// The memory this indexer keeps in its buffers between documents.
    long scratchBytes() {
        return (long) record.array().length
                + termData.array().length
                + termBytes.length
                + (long) (termSlots.length + termEnds.length + termPositions.length) * Integer.BYTES
                + (long) prefixes.length * Character.BYTES
                + analysis.scratchBytes();
    }

    /// Writes the document last [analysed][#analyze] into a buffer, then gives back the memory of the scratch that
    /// a large document grew.
    ///
    /// @param key the document's key
    /// @param seq the sequence number of its add
    /// @param buffer the buffer
    /// @throws IllegalStateException if the buffer's stored records would exceed their limit, which leaves the
    ///     buffer as it was
    void write(String key, long seq, DocumentBuffer buffer) {
        try {
            // The only limit writing can hit, checked before the buffer changes.
            buffer.reserveStored(record.size());
            copy(key, seq, buffer);
        } finally {
            shrink();
        }
    }

    private void copy(String key, long seq, DocumentBuffer buffer) {
        var doc = buffer.newDocument(key, seq);
        fields.clear();
        for (var slot : slots) {
            var definition = definitions.get(slot.ordinal());
            if (definition instanceof Schema.TextField text) {
                var neutral = buffer.field(text.name(), text.positions() == Positions.KEEP, true);
                var language = slot.language();
                fields.add(language == null ? neutral : buffer.languageField(neutral, language));
            } else {
                fields.add(buffer.field(definition.name(), false, false));
            }
        }
        var start = 0;
        for (var t = 0; t < termCount; t++) {
            fields.get(termSlots[t]).add(termData.array(), start, termEnds[t] - start, doc, termPositions[t]);
            start = termEnds[t];
        }
        for (var i = 0; i < touched.length; i++) {
            if (touched[i]) {
                buffer.field(definitions.get(i).name(), false, true).setLength(doc, lengths[i]);
            }
        }
        buffer.stored(record);
    }

    /// Analyses a document, which the schema has checked, into this indexer's scratch, from which [#write] copies
    /// it into a buffer: an analyzer, detector or source that throws leaves every buffer as it was, and this
    /// indexer's scratch as small as [#write] leaves it.
    ///
    /// @throws IOException if a source throws
    void analyze(Document document) throws IOException {
        var analysed = false;
        try {
            analyzeFields(document);
            analysed = true;
        } finally {
            if (!analysed) {
                shrink();
            }
        }
    }

    // Gives back the memory of each buffer of the scratch that a large document grew beyond MAX_SCRATCH_BYTES, so
    // that a worker does not keep the size of the largest document it indexed.
    private void shrink() {
        record.reset(MAX_SCRATCH_BYTES);
        termData.reset(MAX_SCRATCH_BYTES);
        if (termBytes.length > MAX_SCRATCH_BYTES) {
            termBytes = new byte[INITIAL_TERM_BYTES];
        }
        if ((long) termSlots.length * Integer.BYTES > MAX_SCRATCH_BYTES) {
            termSlots = new int[INITIAL_TERMS];
            termEnds = new int[INITIAL_TERMS];
            termPositions = new int[INITIAL_TERMS];
        }
        analysis.shrink(MAX_SCRATCH_BYTES);
    }

    private void analyzeFields(Document document) throws IOException {
        var documentLanguage = documentLanguage(document);
        Arrays.fill(nextPositions, 0);
        Arrays.fill(lengths, 0);
        Arrays.fill(touched, false);
        slots.clear();
        termData.reset();
        termCount = 0;
        record.reset();
        StoredRecord.writeHeader(record, tag(documentLanguage), storedCount(document));
        for (var field : document.fields()) {
            var ordinal = ordinals.get(field.name());
            if (ordinal == null) {
                throw new IllegalArgumentException("Undeclared field " + field.name());
            }
            var definition = definitions.get(ordinal);
            switch (field) {
                case Document.Text text -> analyzeText(text, ordinal, documentLanguage);
                case Document.TextSource source -> analyzeSource(source, ordinal, documentLanguage);
                case Document.Keyword keyword -> {
                    neutralSlot = slot(ordinal, null);
                    analysis.keyword(keyword.value(), keywordSink);
                    if (definition.stored() == Stored.YES) {
                        StoredRecord.writeValue(record, ordinal, keyword.value(), StoredRecord.NONE, null);
                    }
                }
            }
        }
    }

    private void analyzeText(Document.Text text, int ordinal, @Nullable Locale documentLanguage) {
        var definition = (Schema.TextField) definitions.get(ordinal);
        var ownLanguage = text.language();
        var wordAnalyzer = startText(ordinal, ownLanguage == null ? documentLanguage : ownLanguage);
        endText(
                ordinal,
                analysis.text(
                        text.value(),
                        nextPositions[ordinal],
                        definition.accents() == Accents.REMOVE,
                        wordAnalyzer,
                        neutralSink,
                        languageSink));
        if (definition.stored() == Stored.YES) {
            StoredRecord.writeValue(
                    record, ordinal, text.value(), languageKind(ownLanguage, documentLanguage), tag(ownLanguage));
        }
    }

    // Analyses a source as it reads it, and encodes its stored value meanwhile: the same terms and record as a
    // Text value of its content.
    private void analyzeSource(Document.TextSource source, int ordinal, @Nullable Locale documentLanguage)
            throws IOException {
        var definition = (Schema.TextField) definitions.get(ordinal);
        var ownLanguage = source.language();
        var stored = definition.stored() == Stored.YES;
        var start = 0;
        if (stored) {
            StoredRecord.writeValueStart(
                    record, ordinal, languageKind(ownLanguage, documentLanguage), tag(ownLanguage));
            start = record.startString();
        }
        var wordAnalyzer = startText(ordinal, ownLanguage == null ? documentLanguage : ownLanguage);
        try (var reader = source.content().openStream()) {
            endText(
                    ordinal,
                    analysis.text(
                            reader,
                            nextPositions[ordinal],
                            definition.accents() == Accents.REMOVE,
                            wordAnalyzer,
                            neutralSink,
                            languageSink,
                            stored ? storedChars : IGNORED_CHARS));
        }
        if (stored) {
            record.endString(start);
        }
    }

    private static int languageKind(@Nullable Locale ownLanguage, @Nullable Locale documentLanguage) {
        return ownLanguage != null && !ownLanguage.equals(documentLanguage) ? StoredRecord.OWN : StoredRecord.INHERITED;
    }

    // Prepares the sinks for a text value, and returns the analyzer of its language, if any.
    private @Nullable WordAnalyzer startText(int ordinal, @Nullable Locale valueLanguage) {
        var wordAnalyzer = valueLanguage == null ? null : wordAnalyzer(valueLanguage);
        neutralSlot = slot(ordinal, null);
        languageSlot = wordAnalyzer == null ? -1 : slot(ordinal, valueLanguage);
        length = 0;
        return wordAnalyzer;
    }

    // Counts a text value analysed into the field's length.
    private void endText(int ordinal, int nextPosition) {
        nextPositions[ordinal] = nextPosition;
        lengths[ordinal] += length;
        touched[ordinal] = true;
    }

    private int slot(int ordinal, @Nullable Locale language) {
        for (var i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            if (slot.ordinal() == ordinal && Objects.equals(slot.language(), language)) {
                return i;
            }
        }
        slots.add(new Slot(ordinal, language));
        return slots.size() - 1;
    }

    private void neutralTerm(char[] chars, int termLength, int position) {
        addTerm(neutralSlot, chars, termLength, position);
        length++;
    }

    private void languageTerm(char[] chars, int termLength, int position) {
        addTerm(languageSlot, chars, termLength, position);
    }

    private void addTerm(int slot, char[] chars, int termLength, int position) {
        if (termBytes.length < termLength * Utf8.MAX_BYTES_PER_CHAR) {
            termBytes = new byte[termLength * Utf8.MAX_BYTES_PER_CHAR];
        }
        termData.writeBytes(termBytes, 0, Utf8.encode(chars, termLength, termBytes));
        if (termCount == termSlots.length) {
            termSlots = Arrays.copyOf(termSlots, termCount * 2);
            termEnds = Arrays.copyOf(termEnds, termCount * 2);
            termPositions = Arrays.copyOf(termPositions, termCount * 2);
        }
        termSlots[termCount] = slot;
        termEnds[termCount] = termData.size();
        termPositions[termCount] = position;
        termCount++;
    }

    private @Nullable WordAnalyzer wordAnalyzer(Locale language) {
        var analyzer = wordAnalyzers.get(language);
        if (analyzer == null && !withoutAnalyzer.contains(language)) {
            analyzer = schema.languageAnalyzer(language)
                    .map(LanguageAnalyzer::newWordAnalyzer)
                    .orElse(null);
            if (analyzer == null) {
                withoutAnalyzer.add(language);
            } else {
                wordAnalyzers.put(language, analyzer);
            }
        }
        return analyzer;
    }

    private @Nullable Locale documentLanguage(Document document) throws IOException {
        var language = document.language();
        if (language == null) {
            var detector = schema.languageDetector();
            if (detector.isPresent()) {
                language = detectedLanguage(detector.get(), document);
            }
        }
        return language;
    }

    private @Nullable Locale detectedLanguage(LanguageDetector detector, Document document) throws IOException {
        var count = (int)
                document.fields().stream().filter(DocumentIndexer::detected).count();
        if (count == 0) {
            return null;
        }
        var values = new CharSequence[count];
        var i = 0;
        // The length of the values joined so far, line feeds included, up to the first one past the limit.
        var length = 0L;
        for (var field : document.fields()) {
            if (length >= DETECTED_LENGTH) {
                // The detector reads no more: a later source is opened once, to be indexed.
                break;
            }
            if (!detected(field)) {
                continue;
            }
            switch (field) {
                case Document.Text text -> {
                    values[i++] = text.value();
                    length += text.value().length() + 1L;
                }
                case Document.TextSource source -> {
                    var start = (int) length;
                    var end = readPrefix(source.content(), start);
                    values[i++] = CharBuffer.wrap(prefixes, start, end - start);
                    length = end + 1L;
                }
                case Document.Keyword _ -> throw new AssertionError("A keyword is never detected");
            }
        }
        var text = new JoinedValues(i == count ? values : Arrays.copyOf(values, i), DETECTED_LENGTH);
        var guesses = detector.detect(text);
        return guesses.isEmpty() || guesses.getFirst().probability() < schema.languageThreshold()
                ? null
                : guesses.getFirst().language();
    }

    // Whether the detector reads a value: a text value without a language.
    private static boolean detected(Document.Field field) {
        return switch (field) {
            case Document.Text text -> text.language() == null;
            case Document.TextSource source -> source.language() == null;
            case Document.Keyword _ -> false;
        };
    }

    // Reads the first chars of a source into the prefixes, from where the detected text has reached, `start`, up
    // to the end of the detected text, and returns where they end: the prefixes hold the detected text's chars at
    // the same indexes, so they never need more than DETECTED_LENGTH.
    private int readPrefix(CharSource content, int start) throws IOException {
        if (prefixes.length == 0) {
            prefixes = new char[DETECTED_LENGTH];
        }
        var end = start;
        try (var reader = content.openStream()) {
            int read;
            while (end < DETECTED_LENGTH && (read = reader.read(prefixes, end, DETECTED_LENGTH - end)) >= 0) {
                end += read;
            }
        }
        return end;
    }

    private int storedCount(Document document) {
        var count = 0;
        for (var field : document.fields()) {
            var ordinal = ordinals.get(field.name());
            if (ordinal != null && definitions.get(ordinal).stored() == Stored.YES) {
                count++;
            }
        }
        return count;
    }

    private static @Nullable String tag(@Nullable Locale language) {
        return language == null ? null : language.getLanguage();
    }

    /// An internal field of a document: a schema field and, for a language field, the language.
    private record Slot(int ordinal, @Nullable Locale language) {}
}
