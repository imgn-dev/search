package be.imgn.search.langdetect.training;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.langdetect.internal.Detector;
import be.imgn.search.langdetect.internal.ModelFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The tool end to end, on a small corpus of made-up sentences.
class TrainTest {

    private static final Map<String, List<String>> WORDS = Map.of(
            "eng", List.of("the", "house", "is", "green", "and", "we", "walk", "with", "our", "dog", "every", "day"),
            "fra",
                    List.of(
                            "la",
                            "maison",
                            "est",
                            "verte",
                            "et",
                            "nous",
                            "marchons",
                            "avec",
                            "notre",
                            "chien",
                            "chaque"),
            "rus", List.of("дом", "зелёный", "и", "мы", "гуляем", "с", "нашей", "собакой", "каждый", "день"),
            "tur", List.of("ev", "yeşil", "ve", "biz", "her", "gün", "köpeğimizle", "yürüyoruz", "çok", "güzel"));

    @TempDir
    Path directory;

    @Test
    void writesTheSameFilesForTheSameCorpus() throws IOException {
        var corpus = corpus();

        train(corpus, "first");
        train(corpus, "second");

        assertThat(directory.resolve("first.model")).hasSameBinaryContentAs(directory.resolve("second.model"));
        assertThat(directory.resolve("first.tsv")).hasSameTextualContentAs(directory.resolve("second.tsv"));
    }

    @Test
    void writesAModelThatDetectsTheLanguages() throws IOException {
        train(corpus(), "model");

        var detector = new Detector(ModelFormat.read(Files.readAllBytes(directory.resolve("model.model"))));
        assertThat(detector.detect("we walk with our dog every day")
                        .getFirst()
                        .language()
                        .getLanguage())
                .isEqualTo("en");
        assertThat(detector.detect("nous marchons avec notre chien")
                        .getFirst()
                        .language()
                        .getLanguage())
                .isEqualTo("fr");
        assertThat(detector.detect("мы гуляем с собакой").getFirst().language().getLanguage())
                .isEqualTo("ru");
    }

    @Test
    void writesHeldOutSamplesOfKnownAndUnknownLanguages() throws IOException {
        train(corpus(), "model");

        var lines = Files.readAllLines(directory.resolve("model.tsv"));
        assertThat(lines).first().asString().startsWith("# ");
        assertThat(lines.stream().filter(line -> !line.startsWith("#")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line.split("\t", -1)).hasSize(4))
                .anySatisfy(line -> assertThat(line).startsWith("en\teng\t50\t"))
                .anySatisfy(line -> assertThat(line).startsWith("-\ttur\t50\t"));
    }

    private void train(Path corpus, String name) throws IOException {
        // Register texts in the made-up languages: the real ones are in languages this corpus does not have.
        var register = List.of(
                new Sample("en", "en", null, 0, "Every day we walk with our green dog and the house."),
                new Sample("fr", "fr", null, 0, "Nous marchons avec notre chien chaque jour."));
        Train.run(corpus, directory.resolve(name + ".model"), directory.resolve(name + ".tsv"), register);
    }

    private Path corpus() throws IOException {
        var random = new Random(42);
        var lines = new ArrayList<String>();
        var id = 0;
        for (var entry : new TreeMap<>(WORDS).entrySet()) {
            var code = entry.getKey();
            var words = entry.getValue();
            for (var i = 0; i < 2_000; i++) {
                var sentence = new StringBuilder();
                for (var w = 0; w < 4 + random.nextInt(6); w++) {
                    sentence.append(w == 0 ? "" : " ").append(words.get(random.nextInt(words.size())));
                }
                lines.add(++id + "\t" + code + "\t" + sentence + ".");
            }
        }
        var corpus = directory.resolve("sentences.csv");
        Files.write(corpus, lines, StandardCharsets.UTF_8);
        return corpus;
    }
}
