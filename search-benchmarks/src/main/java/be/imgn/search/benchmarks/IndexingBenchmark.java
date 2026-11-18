package be.imgn.search.benchmarks;

import be.imgn.search.Document;
import be.imgn.search.Index;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/// Indexing [generated documents][GeneratedText] of about 200 words into a new in-memory index, one thread, in
/// microseconds per document: a batch of [#BATCH] documents is added, then the writer closes, which writes them
/// into a segment.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class IndexingBenchmark {

    private static final int BATCH = 1_000;

    private List<Document> batch = List.of();

    @Setup
    public void setUp() {
        batch = new GeneratedText().documents(0, BATCH);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public Index index() throws IOException {
        var index = Index.inMemory(GeneratedText.SCHEMA);
        try (var writer = index.writer()) {
            for (var document : batch) {
                writer.add(document);
            }
        }
        return index;
    }
}
