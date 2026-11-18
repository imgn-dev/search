package be.imgn.search.benchmarks;

import be.imgn.search.WordAnalyzer;
import be.imgn.search.internal.analysis.FieldAnalysis;
import be.imgn.search.internal.analysis.TermSink;
import java.util.List;
import java.util.Random;
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
import org.openjdk.jmh.infra.Blackhole;

/// Analysis of a text value of about 100 KB into terms, in microseconds per value.
///
/// `english` is ASCII only, the fast path; `mixed` mixes French, German, Polish, Russian and Greek, with accents,
/// apostrophes and the sharp s. `language` adds language terms through an analyzer that changes nothing, so it
/// measures what routing costs on top of the neutral terms.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class AnalysisBenchmark {

    private static final List<String> ENGLISH = words("""
            the quick brown fox jumps over lazy dog search index documents query Ranking LANGUAGE performance
            is critical and every choice favours speed
            """);

    private static final List<String> MIXED = words("""
            l’arbre aujourd'hui élève forêt Straße Donaudampfschiffahrt über Größe łódź źdźbło zażółć gęślą
            Привет мир поисковый индекс Καλημέρα κόσμε λόγος the fox renard brun saute
            """);

    private static final long SEED = 42;
    private static final int LENGTH = 100_000;
    // One word in this many ends a sentence.
    private static final int SENTENCE = 12;

    private static final WordAnalyzer UNCHANGED = word -> {};

    @Param({"english", "mixed"})
    private String text = "english";

    private final FieldAnalysis analysis = new FieldAnalysis();
    private String value = "";

    @Setup
    public void setUp() {
        var words = "english".equals(text) ? ENGLISH : MIXED;
        var random = new Random(SEED);
        var builder = new StringBuilder();
        while (builder.length() < LENGTH) {
            builder.append(words.get(random.nextInt(words.size())));
            builder.append(random.nextInt(SENTENCE) == 0 ? ". " : " ");
        }
        value = builder.toString();
    }

    @Benchmark
    public int neutral(Blackhole blackhole) {
        return analysis.text(value, 0, false, null, sink(blackhole), sink(blackhole));
    }

    @Benchmark
    public int neutralWithoutAccents(Blackhole blackhole) {
        return analysis.text(value, 0, true, null, sink(blackhole), sink(blackhole));
    }

    @Benchmark
    public int language(Blackhole blackhole) {
        return analysis.text(value, 0, false, UNCHANGED, sink(blackhole), sink(blackhole));
    }

    private static List<String> words(String text) {
        return List.of(text.strip().split("\\s+"));
    }

    private static TermSink sink(Blackhole blackhole) {
        return (chars, length, position) -> {
            blackhole.consume(chars[0]);
            blackhole.consume(length);
            blackhole.consume(position);
        };
    }
}
