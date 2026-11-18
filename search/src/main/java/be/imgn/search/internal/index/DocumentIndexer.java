package be.imgn.search.internal.index;

import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.WordAnalyzer;
import be.imgn.search.internal.analysis.FieldAnalysis;
import be.imgn.search.internal.analysis.TermSink;
import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Utf8;
import be.imgn.search.internal.segment.StoredRecord;
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
/// runs once per document, over its text values without a language, when the document has none. Not
/// thread-safe.
final class DocumentIndexer {

    private static final int INITIAL_TERM_BYTES = 256 * Utf8.MAX_BYTES_PER_CHAR;
    private static final int INITIAL_TERMS = 1024;

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

    /// Writes the document last [analysed][#analyze] into a buffer.
    ///
    /// @param key the document's key
    /// @param seq the sequence number of its add
    /// @param buffer the buffer
    void write(String key, long seq, DocumentBuffer buffer) {
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
    /// it into a buffer: an analyzer or detector that throws leaves every buffer as it was.
    void analyze(Document document) {
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
                case Document.Text text -> {
                    var ownLanguage = text.language();
                    var valueLanguage = ownLanguage == null ? documentLanguage : ownLanguage;
                    analyzeText(text.value(), (Schema.TextField) definition, ordinal, valueLanguage);
                    if (definition.stored() == Stored.YES) {
                        var own = ownLanguage != null && !ownLanguage.equals(documentLanguage);
                        StoredRecord.writeValue(
                                record,
                                ordinal,
                                text.value(),
                                own ? StoredRecord.OWN : StoredRecord.INHERITED,
                                tag(ownLanguage));
                    }
                }
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

    private void analyzeText(String value, Schema.TextField definition, int ordinal, @Nullable Locale valueLanguage) {
        var wordAnalyzer = valueLanguage == null ? null : wordAnalyzer(valueLanguage);
        neutralSlot = slot(ordinal, null);
        languageSlot = wordAnalyzer == null ? -1 : slot(ordinal, valueLanguage);
        length = 0;
        nextPositions[ordinal] = analysis.text(
                value,
                nextPositions[ordinal],
                definition.accents() == Accents.REMOVE,
                wordAnalyzer,
                neutralSink,
                languageSink);
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

    private @Nullable Locale documentLanguage(Document document) {
        if (document.language() != null) {
            return document.language();
        }
        var detector = schema.languageDetector();
        var text = new StringBuilder();
        for (var field : document.fields()) {
            if (field instanceof Document.Text(var _, var value, var valueLanguage) && valueLanguage == null) {
                text.append(value).append('\n');
            }
        }
        if (detector.isEmpty() || text.isEmpty()) {
            return null;
        }
        var guesses = detector.get().detect(text);
        if (guesses.isEmpty() || guesses.getFirst().probability() < schema.languageThreshold()) {
            return null;
        }
        return guesses.getFirst().language();
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
