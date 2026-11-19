package be.imgn.search.langdetect.training;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

/// Reads Tatoeba's `sentences.csv`: one sentence per line, as its id, its ISO 639-3 language code and its text,
/// separated by tabs.
///
/// The file is untrusted: a line that does not have that form, or whose text is empty or very long, is skipped,
/// and malformed UTF-8 is replaced.
final class Corpus {

    /// The longest sentence kept, in chars: longer ones are rare, and are lists or pasted texts rather than
    /// sentences.
    static final int MAX_LENGTH = 1_000;

    private static final int BUFFER_SIZE = 1 << 16;
    private static final int DECIMAL = 10;

    private Corpus() {}

    /// Passes each sentence in one of the `codes` to `consumer`, in the order of the file.
    static void read(Path path, Set<String> codes, Consumer<Sentence> consumer) throws IOException {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder), BUFFER_SIZE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var first = line.indexOf('\t');
                var second = first < 0 ? -1 : line.indexOf('\t', first + 1);
                if (second < 0) {
                    continue;
                }
                var code = line.substring(first + 1, second);
                var text = line.substring(second + 1).strip();
                if (!codes.contains(code) || text.isEmpty() || text.length() > MAX_LENGTH) {
                    continue;
                }
                long id;
                try {
                    id = Long.parseLong(line, 0, first, DECIMAL);
                } catch (NumberFormatException e) {
                    continue;
                }
                consumer.accept(new Sentence(id, code, text));
            }
        }
    }

    /// A sentence of the corpus.
    record Sentence(long id, String code, String text) {}
}
