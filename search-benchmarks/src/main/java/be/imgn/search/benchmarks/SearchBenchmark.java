package be.imgn.search.benchmarks;

import be.imgn.search.Hit;
import be.imgn.search.Index;
import be.imgn.search.Query;
import be.imgn.search.Searcher;
import java.io.IOException;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/// Searching an in-memory index of [#DOCUMENTS] [generated documents][GeneratedText] in [#SEGMENTS] segments,
/// top [k][#k], in microseconds per query.
///
/// The query shapes, on the body: `rare` and `common` terms, `allCommon` and `anyCommon` of two common terms,
/// `allMixed` of a middling and a rare term, `anyMixed` of a common and a middling term, `phrase` of two common
/// terms, and `allOfAny` of two common words each searched in the title and the body, the shape of a query-language
/// query of two words.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class SearchBenchmark {

    private static final int DOCUMENTS = 50_000;
    private static final int SEGMENTS = 5;
    private static final int TOP_10 = 10;
    // Ranks of the words the queries use.
    private static final int COMMON = 3;
    private static final int OTHER_COMMON = 5;
    private static final int MIDDLING = 200;
    private static final int RARE = 5_000;

    @Param({"rare", "common", "allCommon", "allMixed", "anyCommon", "anyMixed", "phrase", "allOfAny"})
    private String shape = "rare";

    @Param({"10", "1000"})
    private int k = TOP_10;

    private Index index = Index.inMemory(GeneratedText.SCHEMA);
    private Searcher searcher = index.searcher();
    private Query query = Query.term("body", "");

    @Setup
    public void setUp() throws IOException {
        var text = new GeneratedText();
        var perSegment = DOCUMENTS / SEGMENTS;
        try (var writer = index.writer()) {
            for (var segment = 0; segment < SEGMENTS; segment++) {
                for (var document : text.documents(segment * perSegment, perSegment)) {
                    writer.add(document);
                }
                writer.refresh();
            }
        }
        searcher = index.searcher();
        query = switch (shape) {
            case "rare" -> term(text, RARE);
            case "common" -> term(text, COMMON);
            case "allCommon" -> Query.all(term(text, COMMON), term(text, OTHER_COMMON));
            case "allMixed" -> Query.all(term(text, MIDDLING), term(text, RARE));
            case "anyCommon" -> Query.any(term(text, COMMON), term(text, OTHER_COMMON));
            case "anyMixed" -> Query.any(term(text, COMMON), term(text, MIDDLING));
            case "phrase" -> Query.phrase("body", text.word(COMMON) + " " + text.word(OTHER_COMMON));
            case "allOfAny" -> Query.all(everywhere(text, COMMON), everywhere(text, OTHER_COMMON));
            default -> throw new IllegalArgumentException("Unknown shape " + shape);
        };
    }

    @TearDown
    public void tearDown() throws IOException {
        searcher.close();
        index.close();
    }

    @Benchmark
    public List<Hit> search() {
        return searcher.search(query, k);
    }

    private static Query everywhere(GeneratedText text, int rank) {
        return Query.any(Query.term("title", text.word(rank)), Query.term("body", text.word(rank)));
    }

    private static Query term(GeneratedText text, int rank) {
        return Query.term("body", text.word(rank));
    }
}
