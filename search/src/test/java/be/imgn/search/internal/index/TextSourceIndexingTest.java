package be.imgn.search.internal.index;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.gaia.core.io.CharSource;
import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.LanguageDetector;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.internal.segment.HeapSink;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TextSourceIndexingTest {

    // Pieces of the random texts: several scripts, apostrophes, a soft hyphen, a combining mark, surrogate pairs,
    // lone surrogates and words the French analyzer stems.
    private static final String[] PIECES = {
        "le",
        "renards",
        "Élève",
        "straße",
        "l’arbre",
        "aujourd'hui",
        "Donau­dampf",
        "é",
        "Καλημέρα",
        "Привет",
        "a𝒜b",
        "😀",
        "x\uD800",
        "\uDC00y",
        "COVID-19",
        "90's",
        " ",
        "\n",
        ", ",
        "-"
    };

    // French when the text starts with a letter before "n", else English: it gives the same language whether it
    // sees a value or only its start.
    private static final LanguageDetector DETECTOR = text -> text.length() > 0 && text.charAt(0) < 'n'
            ? List.of(new LanguageDetector.Guess(Locale.FRENCH, 0.9))
            : List.of(new LanguageDetector.Guess(Locale.ENGLISH, 0.9));

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.KEEP, Accents.REMOVE, Stored.YES)
            .text("notes", Positions.DROP, Accents.KEEP, Stored.NO)
            .keyword("tags", Stored.YES)
            .language(Indexes.FRENCH)
            .languageDetector(DETECTOR, 0.5)
            .build();

    private static final String[] TEXT_FIELDS = {"title", "body", "notes"};

    @Test
    void indexesASourceAsTheTextItGives() throws IOException {
        var random = new Random(42);
        var texts = new ArrayList<Document>();
        var sources = new ArrayList<Document>();
        for (var i = 0; i < 60; i++) {
            var key = "doc-" + i;
            var language = language(random);
            var text = builder(key, language);
            var source = builder(key, language);
            for (var f = random.nextInt(6); f >= 0; f--) {
                var name = TEXT_FIELDS[random.nextInt(TEXT_FIELDS.length)];
                // Long values span many blocks of the tokenizer and go past the detected start.
                var value = text(random, random.nextInt(10) == 0 ? 40_000 : random.nextInt(300));
                var valueLanguage = language(random);
                if (valueLanguage == null) {
                    text.text(name, value);
                    source.text(name, choppy(value, 1 + random.nextInt(5000)));
                } else {
                    text.text(name, value, valueLanguage);
                    source.text(name, choppy(value, 1 + random.nextInt(5000)), valueLanguage);
                }
                if (random.nextInt(3) == 0) {
                    var tag = "t" + random.nextInt(5);
                    text.keyword("tags", tag);
                    source.keyword("tags", tag);
                }
            }
            texts.add(text.build());
            sources.add(source.build());
        }

        var fromTexts = new Flushed(texts);
        var fromSources = new Flushed(sources);

        for (var i = 0; i < texts.size(); i++) {
            assertThat(fromSources.records.get(i)).as("record %d", i).isEqualTo(fromTexts.records.get(i));
        }
        assertThat(fromSources.segment).isEqualTo(fromTexts.segment);
    }

    @Test
    void storesTheSameBytesForEveryLengthOfItsPrefix() throws IOException {
        // The length of a stored value comes before its bytes: check the widths of that length around 127 and
        // 16,383 bytes, and a surrogate pair at every block boundary.
        for (var length : new int[] {0, 1, 63, 64, 126, 127, 128, 8191, 8192, 8193, 16_383, 16_384, 70_000}) {
            var value = "😀a".repeat(length / 3) + "é".repeat(length % 3);
            var text = new Flushed(List.of(Document.builder("x")
                    .language(Locale.FRENCH)
                    .text("title", value)
                    .build()));
            var source = new Flushed(List.of(Document.builder("x")
                    .language(Locale.FRENCH)
                    .text("title", choppy(value, 1000))
                    .build()));

            assertThat(source.records.getFirst()).as("%d chars", value.length()).isEqualTo(text.records.getFirst());
            assertThat(source.segment).as("%d chars", value.length()).isEqualTo(text.segment);
        }
    }

    private static Document.Builder builder(String key, @Nullable Locale language) {
        var builder = Document.builder(key);
        return language == null ? builder : builder.language(language);
    }

    private static @Nullable Locale language(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> Locale.FRENCH;
            case 1 -> Locale.ENGLISH;
            default -> null;
        };
    }

    private static String text(Random random, int length) {
        var text = new StringBuilder();
        while (text.length() < length) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
            if (random.nextBoolean()) {
                text.append(' ');
            }
        }
        return text.toString();
    }

    // A source of `text` whose readers give at most `most` chars per read.
    private static CharSource choppy(String text, int most) {
        return () -> new Reader() {
            private int at;

            @Override
            public int read(char[] buffer, int offset, int length) {
                if (at == text.length()) {
                    return -1;
                }
                var count = Math.min(Math.min(length, most), text.length() - at);
                text.getChars(at, at + count, buffer, offset);
                at += count;
                return count;
            }

            @Override
            public void close() {}
        };
    }

    /// Documents indexed by one indexer into one buffer: their stored records, then the segment flushed.
    private static final class Flushed {

        private final List<byte[]> records = new ArrayList<>();
        private final byte[] segment;

        Flushed(List<Document> documents) throws IOException {
            var indexer = new DocumentIndexer(SCHEMA);
            var buffer = new DocumentBuffer();
            var seq = 0L;
            for (var document : documents) {
                indexer.analyze(document);
                indexer.write(document.key(), seq++, buffer);
            }
            for (var doc = 0; doc < documents.size(); doc++) {
                records.add(Arrays.copyOfRange(buffer.storedBytes(), buffer.storedStart(doc), buffer.storedEnd(doc)));
            }
            var sink = new HeapSink();
            var flusher = new SegmentFlusher(indexer.storedFieldNames());
            try {
                assertThat(flusher.flush(buffer, sink)).isTrue();
            } finally {
                flusher.end();
            }
            segment = sink.bytes();
        }
    }
}
