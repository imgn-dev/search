package be.imgn.search.langdetect.training;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/// The register texts: news, science and other formal texts, written for this tool, in another register than
/// Tatoeba's short conversational sentences. The limits of the model must accept all of them.
final class Register {

    private static final String RESOURCE = "register.tsv";

    private Register() {}

    /// The register texts, as samples of their language.
    static List<Sample> read() {
        try (var in =
                Objects.requireNonNull(Register.class.getResourceAsStream(RESOURCE), "Missing resource " + RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> {
                        var tab = line.indexOf('\t');
                        var language = line.substring(0, tab);
                        var text = line.substring(tab + 1);
                        return new Sample(language, language, null, text.length(), text);
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
