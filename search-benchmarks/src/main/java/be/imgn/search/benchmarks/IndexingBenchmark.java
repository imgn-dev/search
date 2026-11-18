package be.imgn.search.benchmarks;

import be.imgn.search.Document;
import be.imgn.search.Index;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/// Indexing [generated documents][GeneratedText] of about 200 words into a new in-memory index, from
/// [#threads] threads sharing the writer, in microseconds per document: a batch of [#BATCH] documents is split
/// between the threads, then the writer closes, which writes the last buffers into segments. The indexing rate is
/// the inverse.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class IndexingBenchmark {

    private static final int BATCH = 8_000;

    @Param({"1", "2", "4", "8"})
    private int threads = 1;

    private List<Document> batch = List.of();
    private ExecutorService pool = Executors.newSingleThreadExecutor();

    @Setup
    public void setUp() {
        batch = new GeneratedText().documents(0, BATCH);
        pool.shutdown();
        pool = Executors.newFixedThreadPool(threads);
    }

    @TearDown
    public void tearDown() {
        pool.shutdown();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public Index index() throws IOException, InterruptedException, ExecutionException {
        var index = Index.inMemory(GeneratedText.SCHEMA);
        try (var writer = index.writer()) {
            var tasks = new ArrayList<Future<Void>>(threads);
            for (var t = 0; t < threads; t++) {
                var first = t;
                tasks.add(pool.submit(() -> {
                    for (var i = first; i < BATCH; i += threads) {
                        writer.add(batch.get(i));
                    }
                    return null;
                }));
            }
            for (var task : tasks) {
                task.get();
            }
        }
        return index;
    }
}
