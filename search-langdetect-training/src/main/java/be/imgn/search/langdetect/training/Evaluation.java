package be.imgn.search.langdetect.training;

import be.imgn.search.langdetect.internal.Detector;
import be.imgn.search.langdetect.internal.Model;
import be.imgn.search.langdetect.internal.Script;
import be.imgn.search.langdetect.internal.ScriptModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/// Fits the limits and the temperature of a model on labelled samples.
final class Evaluation {

    /// The share of the validation samples in a known language the limits may reject, at most, at each sample
    /// length.
    static final double REJECTED = 0.005;

    // The margins and slopes tried.
    private static final int MARGIN_STEPS = 60;
    private static final double MARGIN_STEP = 0.005;
    private static final int SLOPE_STEPS = 48;
    private static final double SLOPE_STEP = 0.25;
    // The slopes the trade-off report shows.
    private static final List<Integer> REPORTED_SLOPE_STEPS = List.of(0, 4, 8, 12, 16, 24, 32, 48);
    private static final double PERCENT = 100;

    // The temperatures tried: a geometric series from 1.
    private static final float MAX_TEMPERATURE = 100;
    private static final float TEMPERATURE_STEP = 1.05f;

    private Evaluation() {}

    /// Fits `model`, returning the fitted model and, for each script, how the slopes trade the rejection of
    /// unknown languages against that of known ones.
    ///
    /// The center of a language is its average cost per n-gram over its validation samples. The margin and the
    /// slope of a script are those that reject the most validation samples in unknown languages, the mean of the
    /// rejection rates of each language and length, among those that reject at most [#REJECTED] of the samples
    /// in known languages at each length, and none of the register texts in its languages: texts in another
    /// register than Tatoeba's, such as news and science, which cost more. At equal rejection, the lowest slope
    /// and margin win. The temperature of a script is the one that gives its known samples the lowest log loss.
    ///
    /// @param samples the validation samples, of known and unknown languages
    /// @param register the register texts, as samples of their language
    /// @throws IllegalStateException if no margin and slope meet the constraints
    static Fitted fit(Model model, List<Sample> samples, List<Sample> register) {
        var scored = score(model, samples);
        var registerScored = score(model, register);
        var scripts = new ArrayList<ScriptModel>();
        var tradeOffs = new LinkedHashMap<Script, List<String>>();
        for (var script : model.scripts()) {
            var known = scored.stream()
                    .filter(s -> s.sample().script() == script.script() && s.expected() >= 0)
                    .toList();
            var languages = centers(script, known);
            var unknown = scored.stream()
                    .filter(s -> s.sample().language() == null && s.inScript(script.script()))
                    .toList();
            var inRegister = registerScored.stream()
                    .filter(s -> s.inScript(script.script()) && s.expected() >= 0)
                    .toList();
            var search =
                    new Search(points(known, languages), points(unknown, languages), points(inRegister, languages));
            var limits = search.best(script.script());
            tradeOffs.put(script.script(), search.tradeOffs());
            var fit = new ScriptModel.Fit(limits.margin(), limits.slope(), temperature(known, model.unitsPerNat()));
            scripts.add(
                    new ScriptModel(script.script(), languages, fit, script.defaults(), script.keys(), script.costs()));
        }
        return new Fitted(new Model(model.alphabet(), model.unitsPerNat(), scripts), tradeOffs);
    }

    private static List<Point> points(List<Scored> scored, List<ScriptModel.Language> languages) {
        return scored.stream()
                .map(s -> new Point(
                        s.nats(s.best()),
                        languages.get(s.best()).center(),
                        s.count(),
                        s.sample().name(),
                        s.sample().length()))
                .toList();
    }

    private static List<Scored> score(Model model, List<Sample> samples) {
        var detector = new Detector(model);
        var scored = new ArrayList<Scored>();
        for (var sample : samples) {
            scored.add(new Scored(sample, detector.scores(sample.text()), model.unitsPerNat()));
        }
        return scored;
    }

    private static List<ScriptModel.Language> centers(ScriptModel script, List<Scored> scored) {
        var size = script.languages().size();
        var costs = new double[size];
        var counts = new long[size];
        for (var s : scored) {
            costs[s.expected()] += s.nats(s.expected()) * s.count();
            counts[s.expected()] += s.count();
        }
        var languages = new ArrayList<ScriptModel.Language>();
        for (var language = 0; language < size; language++) {
            // A language without samples has no limit.
            var center = counts[language] == 0 ? Double.POSITIVE_INFINITY : costs[language] / counts[language];
            languages.add(
                    new ScriptModel.Language(script.languages().get(language).tag(), (float) center));
        }
        return languages;
    }

    private static float temperature(List<Scored> scored, float unitsPerNat) {
        var best = 1f;
        var bestLoss = Double.POSITIVE_INFINITY;
        for (var temperature = 1f; temperature <= MAX_TEMPERATURE; temperature *= TEMPERATURE_STEP) {
            var loss = 0.0;
            for (var s : scored) {
                loss += s.logLoss(unitsPerNat * (double) temperature);
            }
            if (loss < bestLoss) {
                bestLoss = loss;
                best = temperature;
            }
        }
        return best;
    }

    /// A fitted model, and how the slopes trade the rejection of unknown languages against that of known ones.
    record Fitted(Model model, Map<Script, List<String>> tradeOffs) {}

    /// A sample's average cost per n-gram in its best language, in nats, the center of that language, its number
    /// of n-grams, its language name and its length.
    private record Point(double nats, double center, int count, String name, int length) {

        boolean rejected(double margin, double slope) {
            return nats > center * (1 + margin) + slope / Math.sqrt(count);
        }
    }

    /// The search of the margin and slope of a script.
    private record Search(List<Point> known, List<Point> unknown, List<Point> register) {

        ScriptModel.Fit best(Script script) {
            ScriptModel.@Nullable Fit best = null;
            var bestRate = -1.0;
            for (var slopeStep = 0; slopeStep <= SLOPE_STEPS; slopeStep++) {
                var slope = slopeStep * SLOPE_STEP;
                var margin = lowestMargin(slope);
                if (margin < 0) {
                    continue;
                }
                var rate = mean(unknown, margin, slope);
                if (rate > bestRate) {
                    bestRate = rate;
                    best = new ScriptModel.Fit((float) margin, (float) slope, 1);
                }
            }
            if (best == null) {
                throw new IllegalStateException("No margin and slope meet the constraints in " + script);
            }
            return best;
        }

        List<String> tradeOffs() {
            var lines = new ArrayList<String>();
            for (var slopeStep : REPORTED_SLOPE_STEPS) {
                var slope = slopeStep * SLOPE_STEP;
                var margin = lowestMargin(slope);
                if (margin >= 0) {
                    lines.add("slope %5.2f, margin %.3f: known rejected %s, unknown rejected %s (mean %.1f%%)"
                            .formatted(
                                    slope,
                                    margin,
                                    byLength(known, margin, slope),
                                    byLength(unknown, margin, slope),
                                    PERCENT * mean(unknown, margin, slope)));
                }
            }
            return lines;
        }

        // The lowest margin that meets the constraints with this slope, or -1 if none does.
        private double lowestMargin(double slope) {
            for (var step = 0; step <= MARGIN_STEPS; step++) {
                var margin = step * MARGIN_STEP;
                if (register.stream().noneMatch(p -> p.rejected(margin, slope))
                        && maxByLength(known, margin, slope) <= REJECTED) {
                    return margin;
                }
            }
            return -1;
        }

        private static double maxByLength(List<Point> points, double margin, double slope) {
            return rates(points, margin, slope, Point::length).values().stream()
                    .mapToDouble(Double::doubleValue)
                    .max()
                    .orElse(0);
        }

        // The mean of the rejection rates of each language and length.
        private static double mean(List<Point> points, double margin, double slope) {
            return rates(points, margin, slope, p -> p.name() + "/" + p.length()).values().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
        }

        private static String byLength(List<Point> points, double margin, double slope) {
            return rates(points, margin, slope, Point::length).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> "%.1f%%".formatted(PERCENT * entry.getValue()))
                    .collect(Collectors.joining("/"));
        }

        private static <K> Map<K, Double> rates(
                List<Point> points, double margin, double slope, Function<Point, K> group) {
            return points.stream()
                    .collect(Collectors.groupingBy(
                            group, Collectors.averagingDouble(p -> p.rejected(margin, slope) ? 1 : 0)));
        }
    }

    /// A sample and its scores.
    private static final class Scored {

        private final Sample sample;
        private final Detector.@Nullable Scores scores;
        private final float unitsPerNat;
        private final int expected;

        Scored(Sample sample, Detector.@Nullable Scores scores, float unitsPerNat) {
            this.sample = sample;
            this.scores = scores;
            this.unitsPerNat = unitsPerNat;
            var expected = -1;
            if (scores != null) {
                var languages = scores.script().languages();
                for (var language = 0; language < languages.size(); language++) {
                    if (languages.get(language).tag().equals(sample.language())) {
                        expected = language;
                    }
                }
            }
            this.expected = expected;
        }

        Sample sample() {
            return sample;
        }

        /// Whether the text has scores in `script`.
        boolean inScript(Script script) {
            return scores != null && scores.script().script() == script;
        }

        /// The index of the sample's language in the scores, or -1 if the text has no scores or the sample's
        /// language is not among them.
        int expected() {
            return expected;
        }

        int best() {
            return requireScores().best();
        }

        int count() {
            return requireScores().count();
        }

        /// The average cost per n-gram of the text in `language`, in nats.
        double nats(int language) {
            var scores = requireScores();
            return scores.totals()[language] / (unitsPerNat * (double) scores.count());
        }

        /// The log loss of the sample's language when the cost differences are divided by `scale`.
        double logLoss(double scale) {
            var totals = requireScores().totals();
            var min = Arrays.stream(totals).min().orElseThrow();
            var sum = 0.0;
            for (var total : totals) {
                sum += StrictMath.exp((min - total) / scale);
            }
            return (totals[expected] - min) / scale + StrictMath.log(sum);
        }

        private Detector.Scores requireScores() {
            if (scores == null) {
                throw new IllegalStateException("The text has no scores");
            }
            return scores;
        }
    }
}
