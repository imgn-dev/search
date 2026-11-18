package be.imgn.search.benchmarks;

import be.imgn.search.Index;
import be.imgn.search.Writer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/// The latency of a refresh of an in-memory index of [#DOCUMENTS] [generated documents][GeneratedText], in
/// milliseconds: before each refresh, [#ADDS] adds replace documents and [#DELETES] deletes remove others, so that
/// the refresh writes a segment and resolves deletes against every segment. Merges run in the background, as they
/// would.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class RefreshBenchmark {

    private static final int DOCUMENTS = 50_000;
    private static final int SEGMENTS = 5;
    private static final int ADDS = 1_000;
    private static final int DELETES = 100;
    private static final int DELETE_STRIDE = 37;

    private final GeneratedText text = new GeneratedText();
    private Index index = Index.inMemory(GeneratedText.SCHEMA);
    private Writer writer = writer(index);
    private int next;

    @Setup
    public void setUp() throws IOException {
        var perSegment = DOCUMENTS / SEGMENTS;
        for (var segment = 0; segment < SEGMENTS; segment++) {
            for (var document : text.documents(segment * perSegment, perSegment)) {
                writer.add(document);
            }
            writer.refresh();
        }
    }

    @Setup(Level.Invocation)
    public void change() throws IOException {
        for (var document : text.documents(next % DOCUMENTS, ADDS)) {
            writer.add(document);
        }
        for (var i = 0; i < DELETES; i++) {
            writer.delete("doc-" + (next + ADDS + i * DELETE_STRIDE) % DOCUMENTS);
        }
        next += ADDS;
    }

    @Benchmark
    public void refresh() throws IOException {
        writer.refresh();
    }

    @TearDown
    public void tearDown() throws IOException {
        writer.close();
        index.close();
    }

    // JMH needs a constructor without checked exceptions.
    private static Writer writer(Index index) {
        try {
            return index.writer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
