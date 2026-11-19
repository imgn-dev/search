package be.imgn.search.snowball;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.Hit;
import be.imgn.search.Index;
import be.imgn.search.Positions;
import be.imgn.search.Query;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnowballSearchTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("body", Positions.KEEP, Accents.REMOVE, Stored.NO)
            .defaultFields("body")
            .languages(SnowballAnalyzers.all())
            .build();

    private final Index index = Index.inMemory(SCHEMA);

    @BeforeEach
    void addDocuments() throws IOException {
        try (var writer = index.writer()) {
            writer.add(Document.builder("fr")
                    .language(Locale.FRENCH)
                    .text("body", "Le renard saute par-dessus l’arbre")
                    .build());
            writer.add(Document.builder("de")
                    .language(Locale.GERMAN)
                    .text("body", "Die Häuser am Fluss")
                    .build());
            writer.add(Document.builder("ru")
                    .language(Locale.of("ru"))
                    .text("body", "Рыжая лисица бежит по лесу")
                    .build());
            writer.add(Document.builder("el")
                    .language(Locale.of("el"))
                    .text("body", "Οι αλεπούδες τρέχουν στο δάσος")
                    .build());
        }
    }

    @AfterEach
    void close() throws IOException {
        index.close();
    }

    @Test
    void findsAFrenchDocumentByAnotherFormOfItsWords() {
        assertThat(search(Locale.FRENCH, "renards")).containsExactly("fr");
        assertThat(search(Locale.CANADA_FRENCH, "arbres")).containsExactly("fr");
        assertThat(searchNeutral("renards")).isEmpty();
    }

    @Test
    void findsAGermanDocumentByAnotherFormOfItsWords() {
        assertThat(search(Locale.GERMAN, "haus")).containsExactly("de");
        assertThat(search(Locale.GERMAN, "HAUSES")).containsExactly("de");
        assertThat(searchNeutral("haus")).isEmpty();
    }

    @Test
    void findsARussianDocumentByAnotherFormOfItsWords() {
        assertThat(search(Locale.of("ru"), "лисицы")).containsExactly("ru");
        assertThat(search(Locale.of("ru"), "Лисицу")).containsExactly("ru");
        assertThat(searchNeutral("лисицы")).isEmpty();
    }

    @Test
    void findsAGreekDocumentByAnotherFormOfItsWords() {
        assertThat(search(Locale.of("el"), "αλεπού")).containsExactly("el");
        assertThat(search(Locale.of("el"), "ΔΆΣΗ")).containsExactly("el");
        assertThat(searchNeutral("αλεπού")).isEmpty();
    }

    @Test
    void searchesOnlyTheLanguageOfTheQuery() {
        assertThat(search(Locale.GERMAN, "renards")).isEmpty();
    }

    private List<String> search(Locale language, String text) {
        try (var searcher = index.searcher()) {
            return keys(searcher.search(language, Query.term("body", text), 10));
        }
    }

    private List<String> searchNeutral(String text) {
        try (var searcher = index.searcher()) {
            return keys(searcher.search(Query.term("body", text), 10));
        }
    }

    private static List<String> keys(List<Hit> hits) {
        return hits.stream().map(Hit::key).toList();
    }
}
