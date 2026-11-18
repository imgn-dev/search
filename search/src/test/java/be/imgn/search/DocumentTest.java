package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DocumentTest {

    @Test
    void keepsRepeatedFieldsInOrder() {
        var document = Document.builder("doc-42")
                .language(Locale.FRENCH)
                .text("title", "Le renard")
                .text("title", "The fox", Locale.ENGLISH)
                .keyword("tags", "animal")
                .keyword("tags", "forêt")
                .build();

        assertThat(document.key()).isEqualTo("doc-42");
        assertThat(document.language()).isEqualTo(Locale.FRENCH);
        assertThat(document.fields())
                .containsExactly(
                        new Document.Text("title", "Le renard", null),
                        new Document.Text("title", "The fox", Locale.ENGLISH),
                        new Document.Keyword("tags", "animal"),
                        new Document.Keyword("tags", "forêt"));
    }

    @Test
    void rejectsKeysAndKeywordsWithALoneSurrogate() {
        for (var value : List.of("x\uD800", "x\uDC00", "\uDC00\uD800x")) {
            assertThatThrownBy(() -> Document.builder(value)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Document(value, null, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Document.Keyword("tags", value)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Document.builder("1").keyword("tags", value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var paired = "x\uD83E\uDD8A";
        assertThat(Document.builder(paired).keyword("tags", paired).build().key())
                .isEqualTo(paired);
        assertThat(Document.builder("1").text("title", "x\uD800").build().fields())
                .hasSize(1);
    }

    @Test
    void hasNoLanguageUnlessOneIsGiven() {
        assertThat(Document.builder("1").build().language()).isNull();
    }

    @Test
    void keepsOnlyTheLanguageSubtag() {
        var document = Document.builder("1")
                .language(Locale.of("fr", "BE"))
                .text("title", "x", Locale.of("nl", "BE"))
                .build();

        assertThat(document.language()).isEqualTo(Locale.FRENCH);
        assertThat(document.fields()).containsExactly(new Document.Text("title", "x", Locale.of("nl")));
    }

    @Test
    void readsBokmalAndNynorskAsNorwegian() {
        assertThat(Document.builder("1").language(Locale.of("nb")).build().language())
                .isEqualTo(Locale.of("no"));
        assertThat(Document.builder("1").language(Locale.of("nn")).build().language())
                .isEqualTo(Locale.of("no"));
    }

    @Test
    void rejectsALocaleWithoutLanguage() {
        assertThatThrownBy(() -> Document.builder("1").language(Locale.ROOT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEmptyKey() {
        assertThatThrownBy(() -> Document.builder("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isNotChangedByTheBuilderAfterwards() {
        var builder = Document.builder("1").keyword("tags", "a");
        var document = builder.build();
        builder.keyword("tags", "b");

        assertThat(document.fields()).hasSize(1);
    }
}
