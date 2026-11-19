package be.imgn.search.langdetect.training;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.langdetect.training.Corpus.Sentence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusTest {

    @TempDir
    Path directory;

    @Test
    void readsTheSentencesInTheCodesAndSkipsTheMalformedLines() throws IOException {
        var corpus = directory.resolve("sentences.csv");
        var lines = String.join(
                "\n",
                "1\teng\tHello there.",
                "2\tfra\t  Bonjour.  ",
                "3\tdeu\tHallo.",
                "x\teng\tNo id.",
                "4\teng",
                "5\teng\t",
                "6\teng\t" + "a".repeat(Corpus.MAX_LENGTH + 1),
                "7\t\\N\tUnknown language.",
                "8\teng\tTabs\tinside.");
        Files.write(corpus, (lines + "\n").getBytes(StandardCharsets.UTF_8));

        var sentences = new ArrayList<Sentence>();
        Corpus.read(corpus, Set.of("eng", "fra"), sentences::add);

        assertThat(sentences)
                .containsExactly(
                        new Sentence(1, "eng", "Hello there."),
                        new Sentence(2, "fra", "Bonjour."),
                        new Sentence(8, "eng", "Tabs\tinside."));
    }

    @Test
    void replacesMalformedUtf8() throws IOException {
        var corpus = directory.resolve("sentences.csv");
        Files.write(corpus, new byte[] {'1', '\t', 'e', 'n', 'g', '\t', 'a', (byte) 0xC3, 'b', '\n'});

        var sentences = new ArrayList<Sentence>();
        Corpus.read(corpus, Set.of("eng"), sentences::add);

        assertThat(sentences).containsExactly(new Sentence(1, "eng", "a�b"));
    }
}
