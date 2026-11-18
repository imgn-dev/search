package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SchemaTest {

    private static Schema.Builder titleAndTags() {
        return Schema.builder()
                .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
                .keyword("tags", Stored.NO);
    }

    @Test
    void keepsTheFieldsInDeclarationOrder() {
        var schema = titleAndTags().build();

        assertThat(schema.fields())
                .containsExactly(
                        new Schema.TextField("title", Positions.KEEP, Accents.KEEP, Stored.YES),
                        new Schema.KeywordField("tags", Stored.NO));
        assertThat(schema.field("tags")).contains(new Schema.KeywordField("tags", Stored.NO));
        assertThat(schema.field("body")).isEmpty();
    }

    @Test
    void acceptsLettersDigitsUnderscoresAndHyphensInFieldNames() {
        var schema = Schema.builder().keyword("é_2-x", Stored.NO).build();

        assertThat(schema.field("é_2-x")).isPresent();
    }

    @Test
    void rejectsADotInAFieldName() {
        assertThatThrownBy(() -> Schema.builder().keyword("body.fr", Stored.NO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body.fr");
    }

    @Test
    void rejectsAnEmptyFieldName() {
        assertThatThrownBy(() -> Schema.builder().keyword("", Stored.NO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAFieldDeclaredTwice() {
        assertThatThrownBy(() -> titleAndTags().keyword("title", Stored.NO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void rejectsASchemaWithoutFields() {
        assertThatThrownBy(() -> Schema.builder().build()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnUndeclaredDefaultField() {
        assertThatThrownBy(() -> titleAndTags().defaultFields("body").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("body");
    }

    @Test
    void rejectsARepeatedDefaultField() {
        assertThatThrownBy(() -> titleAndTags().defaultFields("title", "title"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacesTheDefaultFields() {
        var schema = titleAndTags().defaultFields("title").defaultFields("tags").build();

        assertThat(schema.defaultFields()).containsExactly("tags");
    }

    @Test
    void findsTheAnalyzerOfARegionalLocale() {
        var french = new Analyzer(Locale.FRENCH, "test/french");
        var schema = titleAndTags().languages(List.of(french)).build();

        assertThat(schema.languageAnalyzer(Locale.of("fr", "BE"))).contains(french);
        assertThat(schema.languageAnalyzer(Locale.GERMAN)).isEmpty();
    }

    @Test
    void findsTheNorwegianAnalyzerForBokmalAndNynorsk() {
        var norwegian = new Analyzer(Locale.of("no"), "test/norwegian");
        var schema = titleAndTags().language(norwegian).build();

        assertThat(schema.languageAnalyzer(Locale.of("nb"))).contains(norwegian);
        assertThat(schema.languageAnalyzer(Locale.of("nn"))).contains(norwegian);
    }

    @Test
    void rejectsTwoAnalyzersForOneLanguage() {
        var builder = titleAndTags().language(new Analyzer(Locale.FRENCH, "a"));

        assertThatThrownBy(() -> builder.language(new Analyzer(Locale.FRENCH, "b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnAnalyzerWithARegionalLanguage() {
        assertThatThrownBy(() -> titleAndTags().language(new Analyzer(Locale.CANADA_FRENCH, "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnAnalyzerWithAnEmptyIdentifier() {
        assertThatThrownBy(() -> titleAndTags().language(new Analyzer(Locale.FRENCH, "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasNoDetectorUnlessOneIsSet() {
        assertThat(titleAndTags().build().languageDetector()).isEmpty();
    }

    @Test
    void keepsTheDetectorAndItsThreshold() {
        LanguageDetector detector = text -> List.of();
        var schema = titleAndTags().languageDetector(detector, 0.8).build();

        assertThat(schema.languageDetector()).contains(detector);
        assertThat(schema.languageThreshold()).isEqualTo(0.8);
    }

    @Test
    void rejectsAThresholdOutsideZeroToOne() {
        LanguageDetector detector = text -> List.of();

        assertThatThrownBy(() -> titleAndTags().languageDetector(detector, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsADocumentThatFits() {
        var schema = titleAndTags().build();
        var document = Document.builder("1")
                .text("title", "a")
                .keyword("tags", "x")
                .keyword("tags", "y")
                .build();

        schema.check(document);
    }

    @Test
    void rejectsADocumentWithAnUndeclaredField() {
        var schema = titleAndTags().build();
        var document = Document.builder("1").text("body", "a").build();

        assertThatThrownBy(() -> schema.check(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void rejectsADocumentWithAValueOfTheWrongKind() {
        var schema = titleAndTags().build();
        var document = Document.builder("1").keyword("title", "a").build();

        assertThatThrownBy(() -> schema.check(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyword value")
                .hasMessageContaining("text field");
    }

    private record Analyzer(Locale language, String id) implements LanguageAnalyzer {}
}
