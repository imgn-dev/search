package be.imgn.search.benchmarks;

import be.imgn.gaia.core.io.CharSource;
import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.Index;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.Writer;
import java.io.IOException;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/// Adding a document of about 1 MB of [generated text][GeneratedText] to an in-memory index, in milliseconds,
/// given as a `String` or as a `CharSource` of the same text, with the field stored or not. Each add replaces the
/// previous one, so the writer's buffers are flushed now and then, as when indexing many such documents. Run with
/// `-prof gc` for the allocations per add.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class LargeDocumentBenchmark {

    // About 1 MB: the generated words average about 7.5 chars with their space.
    private static final int WORDS = 140_000;

    @Param
    private Input input = Input.TEXT;

    @Param({"false", "true"})
    private boolean stored;

    private String text = "";
    private CharSource source = CharSource.empty();
    private Index index = Index.inMemory(schema(Stored.NO));
    private Writer writer = RefreshBenchmark.writer(index);

    @Setup
    public void setUp() throws IOException {
        writer.close();
        index.close();
        text = new GeneratedText().text(WORDS);
        source = CharSource.wrap(text);
        index = Index.inMemory(schema(stored ? Stored.YES : Stored.NO));
        writer = index.writer();
    }

    @TearDown
    public void tearDown() throws IOException {
        writer.close();
        index.close();
    }

    @Benchmark
    public Writer add() throws IOException {
        var document = input == Input.TEXT
                ? Document.builder("doc").text("body", text).build()
                : Document.builder("doc").text("body", source).build();
        writer.add(document);
        return writer;
    }

    private static Schema schema(Stored stored) {
        return Schema.builder()
                .text("body", Positions.KEEP, Accents.KEEP, stored)
                .defaultFields("body")
                .build();
    }

    /// How the text is given.
    public enum Input {
        /// As a `String`.
        TEXT,
        /// As a `CharSource`.
        SOURCE
    }
}
