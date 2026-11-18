package be.imgn.search.benchmarks;

import be.imgn.search.internal.codec.BitPacking;
import be.imgn.search.internal.codec.ByteOutput;
import java.lang.foreign.MemorySegment;
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

/// Unpacking one block of 128 bit-packed values, in nanoseconds per block, for the widths of document gaps and
/// frequencies in common and rare terms.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BitPackingBenchmark {

    private static final long SEED = 42;

    @Param({"1", "3", "7", "12", "20"})
    private int width = 1;

    private final int[] values = new int[BitPacking.BLOCK];
    private final long[] scratch = new long[BitPacking.SCRATCH_LONGS];
    private MemorySegment block = MemorySegment.NULL;

    @Setup
    public void setUp() {
        var random = new Random(SEED);
        for (var i = 0; i < values.length; i++) {
            values[i] = random.nextInt(1 << width);
        }
        var out = new ByteOutput();
        BitPacking.pack(values, width, out);
        block = MemorySegment.ofArray(out.toByteArray());
    }

    @Benchmark
    public int[] unpack() {
        BitPacking.unpack(block, 0, width, values, scratch);
        return values;
    }
}
