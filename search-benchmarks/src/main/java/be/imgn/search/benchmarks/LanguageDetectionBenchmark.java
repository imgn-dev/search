package be.imgn.search.benchmarks;

import be.imgn.search.LanguageDetector;
import be.imgn.search.langdetect.NaiveBayesDetector;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/// Language detection of a French text, in microseconds per text: `short` is about 50 chars, `medium` about 200
/// and `long` about 10 KB, of which the detector samples about 2 KB.
///
/// The text is a `string`, or a `view` over a document made of several values, which copies chars in bulk with
/// `getChars`, or only one by one with `charAt` for `charView`.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class LanguageDetectionBenchmark {

    private static final String PARAGRAPH = """
            Le renard brun traverse la forêt au petit matin, quand la brume couvre encore les prés. Il s’arrête \
            près du ruisseau, écoute les oiseaux qui s’éveillent, puis reprend sa route vers les collines où il a \
            creusé son terrier. Les habitants du village voisin le voient parfois passer à l’orée du bois, et les \
            enfants racontent qu’il connaît tous les chemins de la région.\s""";

    private static final int SHORT = 50;
    private static final int MEDIUM = 200;
    private static final int LONG = 10_000;

    @Param({"short", "medium", "long"})
    private String length = "short";

    @Param({"string", "view", "charView"})
    private String source = "string";

    private final LanguageDetector detector = new NaiveBayesDetector();
    private CharSequence text = "";

    @Setup
    public void setUp() {
        var chars = switch (length) {
            case "short" -> SHORT;
            case "medium" -> MEDIUM;
            default -> LONG;
        };
        var string = PARAGRAPH.repeat(chars / PARAGRAPH.length() + 1).substring(0, chars);
        text = switch (source) {
            case "string" -> string;
            case "view" -> new View(string);
            default -> new CharView(string);
        };
    }

    @Benchmark
    public List<LanguageDetector.Guess> detect() {
        return detector.detect(text);
    }

    /// A view over a document's values, as the indexer may pass to a detector: each char is found by a binary
    /// search over the values, and chars are copied one by one.
    private static class CharView implements CharSequence {

        // The values are slices of the text of this many chars, with their offsets in it.
        private static final int VALUE_LENGTH = 64;

        private final String[] values;
        private final int[] offsets;
        private final int length;

        CharView(String text) {
            var count = (text.length() + VALUE_LENGTH - 1) / VALUE_LENGTH;
            values = new String[count];
            offsets = new int[count];
            for (var i = 0; i < count; i++) {
                offsets[i] = i * VALUE_LENGTH;
                values[i] = text.substring(offsets[i], Math.min(text.length(), offsets[i] + VALUE_LENGTH));
            }
            length = text.length();
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            var value = value(index);
            return values[value].charAt(index - offsets[value]);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return toString().substring(start, end);
        }

        @Override
        public String toString() {
            return String.join("", values);
        }

        // The value holding the char at `index`.
        int value(int index) {
            var value = Arrays.binarySearch(offsets, index);
            return value >= 0 ? value : -value - 2;
        }

        String text(int value) {
            return values[value];
        }

        int offset(int value) {
            return offsets[value];
        }
    }

    /// The same view, copying chars in bulk, value by value.
    private static final class View extends CharView {

        View(String text) {
            super(text);
        }

        @Override
        public void getChars(int start, int end, char[] destination, int offset) {
            var index = start;
            while (index < end) {
                var value = value(index);
                var text = text(value);
                var from = index - offset(value);
                var to = Math.min(text.length(), end - offset(value));
                text.getChars(from, to, destination, offset + index - start);
                index += to - from;
            }
        }
    }
}
