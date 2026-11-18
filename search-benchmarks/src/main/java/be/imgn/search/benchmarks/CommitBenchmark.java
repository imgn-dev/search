package be.imgn.search.benchmarks;

import be.imgn.search.Index;
import be.imgn.search.Writer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/// The latency of a commit of an index of [#DOCUMENTS] [generated documents][GeneratedText], in milliseconds:
/// before each commit, [#ADDS] adds replace documents and [#DELETES] deletes remove others, as in
/// [RefreshBenchmark]. In memory a commit is a refresh; in files it also forces the new segment and deletion files
/// and writes a manifest. Merges run in the background, as they would.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class CommitBenchmark {

    private static final int DOCUMENTS = 50_000;
    private static final int SEGMENTS = 5;
    private static final int ADDS = 1_000;
    private static final int DELETES = 100;
    private static final int DELETE_STRIDE = 37;

    @Param({Storages.MEMORY, Storages.FILES})
    private String storage = Storages.MEMORY;

    private final GeneratedText text = new GeneratedText();
    private Path directory = Path.of("");
    private Index index = Index.inMemory(GeneratedText.SCHEMA);
    private Writer writer = RefreshBenchmark.writer(index);
    private int next;

    @Setup
    public void setUp() throws IOException {
        writer.close();
        index.close();
        directory = Storages.newDirectory();
        index = Storages.open(storage, directory);
        writer = index.writer();
        var perSegment = DOCUMENTS / SEGMENTS;
        for (var segment = 0; segment < SEGMENTS; segment++) {
            for (var document : text.documents(segment * perSegment, perSegment)) {
                writer.add(document);
            }
            writer.commit();
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
    public void commit() throws IOException {
        writer.commit();
    }

    @TearDown
    public void tearDown() throws IOException {
        writer.close();
        index.close();
        Storages.delete(directory);
    }
}
