package be.imgn.search.langdetect.training;

import be.imgn.search.langdetect.internal.Detector;
import be.imgn.search.langdetect.internal.Model;
import be.imgn.search.langdetect.internal.Script;
import be.imgn.search.langdetect.internal.ScriptModel;
import be.imgn.search.langdetect.internal.Text;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Prints what the tool made: the model's size and fit, and its accuracy on samples.
final class Report {

    private static final double PERCENT = 100;

    private final PrintStream out;

    Report(PrintStream out) {
        this.out = out;
    }

    /// Prints the size and the fit of a model, and the training chars of each language.
    void model(Model model, int bytes, Map<Language, List<Text>> training) {
        out.printf(
                "Model: %,d bytes, %d chars in the alphabet%n",
                bytes, model.alphabet().chars().length);
        for (var script : model.scripts()) {
            out.printf("  %s: %,d n-grams, %s%n", script.script(), script.keys().length, script.fit());
        }
        out.println("Training chars and center (nats per n-gram) of each language:");
        for (var language : Language.ALL) {
            var chars = training.getOrDefault(language, List.of()).stream()
                    .mapToLong(Text::length)
                    .sum();
            var script = model.script(language.script());
            var center = script == null
                    ? Float.NaN
                    : script.languages().stream()
                            .filter(l -> l.tag().equals(language.tag()))
                            .map(ScriptModel.Language::center)
                            .findFirst()
                            .orElse(Float.NaN);
            out.printf("  %-8s %,10d %.3f%n", language.name(), chars, center);
        }
    }

    /// Prints the share of the samples of each language a model detects right, by sample length, and the errors
    /// that happen at least twice.
    ///
    /// @return the number of samples and of those detected right, by language name, then length
    Map<String, int[]> accuracy(String title, Model model, List<Sample> samples) {
        var detector = new Detector(model);
        out.printf(
                "%s: share of the samples detected right (%%), and number of samples, by length %s%n",
                title, Samples.LENGTHS);
        var rows = new LinkedHashMap<String, int[]>();
        var errors = new TreeMap<String, Integer>();
        for (var sample : samples) {
            var guesses = detector.detect(sample.text());
            var guess = guesses.isEmpty() ? "-" : guesses.getFirst().language().getLanguage();
            var expected = sample.language() == null ? "-" : sample.language();
            var row = rows.computeIfAbsent(sample.name(), n -> new int[2 * Samples.LENGTHS.size()]);
            var column = Samples.LENGTHS.indexOf(sample.length());
            row[2 * column]++;
            if (guess.equals(expected)) {
                row[2 * column + 1]++;
            } else {
                errors.merge("%5d %s -> %s".formatted(sample.length(), sample.name(), guess), 1, Integer::sum);
            }
        }
        rows.forEach((name, row) -> {
            var line = new StringBuilder("  %-8s".formatted(name));
            for (var column = 0; column < Samples.LENGTHS.size(); column++) {
                line.append(" %6.1f (%3d)"
                        .formatted(PERCENT * row[2 * column + 1] / Math.max(1, row[2 * column]), row[2 * column]));
            }
            out.println(line);
        });
        out.println("  Errors that happen at least twice (- is no language):");
        errors.forEach((error, count) -> {
            if (count >= 2) {
                out.printf("   %s: %d%n", error, count);
            }
        });
        return rows;
    }

    /// Prints how many register texts a model detects right, and those it does not.
    void register(Model model, List<Sample> register) {
        var detector = new Detector(model);
        var wrong = register.stream()
                .filter(sample -> {
                    var guesses = detector.detect(sample.text());
                    return guesses.isEmpty()
                            || !guesses.getFirst().language().getLanguage().equals(sample.language());
                })
                .toList();
        out.printf("Register texts: %d of %d detected right%n", register.size() - wrong.size(), register.size());
        wrong.forEach(sample ->
                out.printf("  %s -> %s: %s%n", sample.language(), detector.detect(sample.text()), sample.text()));
    }

    /// Prints how the margin and slope of each script trade the rejection of unknown languages against that of
    /// known ones on the validation samples, by sample length.
    void tradeOffs(Map<Script, List<String>> tradeOffs) {
        out.println("Trade-offs: the lowest margin each slope allows, and the rejection rates it gives:");
        tradeOffs.forEach((script, lines) -> {
            out.printf("  %s%n", script);
            lines.forEach(line -> out.printf("    %s%n", line));
        });
    }
}
