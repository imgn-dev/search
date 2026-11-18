package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredDocumentTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.KEEP, Accents.KEEP, Stored.NO)
            .keyword("tags", Stored.YES)
            .build();

    private static final StoredDocument DOCUMENT = new StoredDocument(
            SCHEMA,
            "doc-42",
            Locale.FRENCH,
            Map.of(
                    "title",
                    List.of(new StoredText("Le renard", Locale.FRENCH), new StoredText("The fox", Locale.ENGLISH)),
                    "tags",
                    List.of(new StoredText("animal", null), new StoredText("forêt", null))));

    @Test
    void returnsTheKeyAndLanguage() {
        assertThat(DOCUMENT.key()).isEqualTo("doc-42");
        assertThat(DOCUMENT.language()).contains(Locale.FRENCH);
    }

    @Test
    void returnsTheValuesInOrder() {
        assertThat(DOCUMENT.values("title")).containsExactly("Le renard", "The fox");
        assertThat(DOCUMENT.values("tags")).containsExactly("animal", "forêt");
        assertThat(DOCUMENT.value("title")).contains("Le renard");
    }

    @Test
    void returnsTheTextsWithTheirLanguages() {
        assertThat(DOCUMENT.texts("title"))
                .containsExactly(new StoredText("Le renard", Locale.FRENCH), new StoredText("The fox", Locale.ENGLISH));
    }

    @Test
    void returnsNothingForAStoredFieldTheDocumentLacks() {
        var document = new StoredDocument(SCHEMA, "1", null, Map.of());

        assertThat(document.values("title")).isEmpty();
        assertThat(document.value("title")).isEmpty();
        assertThat(document.language()).isEmpty();
    }

    @Test
    void rejectsAFieldThatIsNotStored() {
        assertThatThrownBy(() -> DOCUMENT.values("body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not stored");
    }

    @Test
    void rejectsAnUndeclaredField() {
        assertThatThrownBy(() -> DOCUMENT.value("summary")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTheTextsOfAKeywordField() {
        assertThatThrownBy(() -> DOCUMENT.texts("tags")).isInstanceOf(IllegalArgumentException.class);
    }
}
