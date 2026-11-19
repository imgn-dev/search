package be.imgn.search.langdetect;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.Hit;
import be.imgn.search.Index;
import be.imgn.search.Positions;
import be.imgn.search.Query;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.snowball.SnowballAnalyzers;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// An index with this detector routes the documents given no language to the analyzer of the detected one.
class LanguageDetectionSearchTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("body", Positions.KEEP, Accents.REMOVE, Stored.NO)
            .keyword("id", Stored.NO)
            .defaultFields("body")
            .languages(SnowballAnalyzers.all())
            .languageDetector(new NaiveBayesDetector(), 0.8)
            .build();

    private final Index index = Index.inMemory(SCHEMA);

    @BeforeEach
    void addDocuments() throws IOException {
        try (var writer = index.writer()) {
            writer.add(Document.builder("fr")
                    .keyword("id", "fr")
                    .text("body", "Les renards gris traversent la forêt pendant la nuit et chassent les souris.")
                    .build());
            writer.add(Document.builder("de")
                    .keyword("id", "de")
                    .text("body", "Die alten Häuser am Fluss wurden im letzten Jahrhundert von Fischern gebaut.")
                    .build());
            writer.add(Document.builder("ru")
                    .keyword("id", "ru")
                    .text("body", "Рыжие лисицы бегут по лесу и ищут себе еду на зиму.")
                    .build());
            writer.add(Document.builder("unknown")
                    .keyword("id", "unknown")
                    .text("body", "Saya suka makan nasi goreng dengan telur setiap pagi bersama keluarga saya.")
                    .build());
            writer.add(Document.builder("given")
                    .keyword("id", "given")
                    .language(Locale.ITALIAN)
                    .text("body", "Les renards gris traversent la forêt pendant la nuit.")
                    .build());
        }
    }

    @AfterEach
    void close() throws IOException {
        index.close();
    }

    @Test
    void routesADocumentToTheAnalyzerOfItsDetectedLanguage() {
        assertThat(search(Locale.FRENCH, "renard")).containsExactly("fr");
        assertThat(search(Locale.GERMAN, "haus")).containsExactly("de");
        assertThat(search(Locale.of("ru"), "лисица")).containsExactly("ru");
    }

    @Test
    void storesTheDetectedLanguage() {
        assertThat(language("fr")).contains(Locale.FRENCH);
        assertThat(language("de")).contains(Locale.GERMAN);
        assertThat(language("ru")).contains(Locale.of("ru"));
    }

    @Test
    void leavesADocumentInAnUnknownLanguageWithoutLanguage() {
        assertThat(language("unknown")).isEmpty();
    }

    @Test
    void keepsTheLanguageTheCallerGives() {
        assertThat(language("given")).contains(Locale.ITALIAN);
        assertThat(search(Locale.FRENCH, "renard")).doesNotContain("given");
    }

    private List<String> search(Locale language, String text) {
        try (var searcher = index.searcher()) {
            return searcher.search(language, Query.term("body", text), 10).stream()
                    .map(Hit::key)
                    .toList();
        }
    }

    private Optional<Locale> language(String key) {
        try (var searcher = index.searcher()) {
            var hits = searcher.search(Query.term("id", key), 1);
            assertThat(hits).hasSize(1);
            return searcher.document(hits.getFirst()).language();
        }
    }
}
