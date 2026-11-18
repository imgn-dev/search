package be.imgn.search.benchmarks;

import be.imgn.search.Index;
import java.io.IOException;
import java.nio.file.Path;
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

/// Opening and closing a file index of [#DOCUMENTS] [generated documents][GeneratedText] in [#segments]
/// segments, with deletions, in milliseconds: locking it, reading its manifest and deletion files in full, and
/// mapping its segments and checking their headers and footers. The files stay in the page cache.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class OpenBenchmark {

    private static final int DOCUMENTS = 50_000;
    private static final int DELETE_STRIDE = 97;

    @Param({"1", "10"})
    private int segments = 1;

    private Path directory = Path.of("");

    @Setup
    public void setUp() throws IOException {
        directory = Storages.newDirectory();
        var text = new GeneratedText();
        var perSegment = DOCUMENTS / segments;
        try (var index = Index.open(directory, GeneratedText.SCHEMA);
                var writer = index.writer()) {
            for (var segment = 0; segment < segments; segment++) {
                for (var document : text.documents(segment * perSegment, perSegment)) {
                    writer.add(document);
                }
                writer.commit();
            }
            for (var doc = 0; doc < DOCUMENTS; doc += DELETE_STRIDE) {
                writer.delete("doc-" + doc);
            }
        }
    }

    @Benchmark
    public Index open() throws IOException {
        var index = Index.open(directory, GeneratedText.SCHEMA);
        index.close();
        return index;
    }

    @TearDown
    public void tearDown() throws IOException {
        Storages.delete(directory);
    }
}
