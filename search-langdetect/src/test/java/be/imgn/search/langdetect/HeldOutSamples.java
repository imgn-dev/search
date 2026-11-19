package be.imgn.search.langdetect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// The held-out samples the training tool writes: Tatoeba sentences the model never learnt from, and the counts
/// of the validation samples the tool fitted the model on.
record HeldOutSamples(@Nullable String expected, String name, int length, String text) {

    private static final String RESOURCE = "held-out.tsv";
    private static final String VALIDATION = "#validation";
    private static final int COLUMNS = 4;
    private static final int VALIDATION_COLUMNS = 5;

    private static final List<String> LINES = lines();

    private static final List<HeldOutSamples> ALL = LINES.stream()
            .filter(line -> !line.startsWith("#"))
            .map(line -> {
                var columns = line.split("\t", COLUMNS);
                return new HeldOutSamples(
                        "-".equals(columns[0]) ? null : columns[0],
                        columns[1],
                        Integer.parseInt(columns[2]),
                        columns[3]);
            })
            .toList();

    private static final Map<String, Counts> VALIDATIONS = validations();

    /// The samples.
    static List<HeldOutSamples> all() {
        return ALL;
    }

    /// The validation counts of a language and length.
    static Counts validation(String name, int length) {
        return VALIDATIONS.getOrDefault(name + "/" + length, new Counts(0, 0));
    }

    private static List<String> lines() {
        try (var in = Objects.requireNonNull(
                HeldOutSamples.class.getResourceAsStream(RESOURCE), "Missing test resource " + RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Counts> validations() {
        var validations = new HashMap<String, Counts>();
        for (var line : LINES) {
            if (line.startsWith(VALIDATION + "\t")) {
                var columns = line.split("\t", VALIDATION_COLUMNS);
                validations.put(
                        columns[1] + "/" + columns[2],
                        new Counts(Integer.parseInt(columns[3]), Integer.parseInt(columns[4])));
            }
        }
        return validations;
    }

    /// The number of validation samples right, detected or rejected as they should, and of all of them.
    record Counts(int right, int total) {}
}
