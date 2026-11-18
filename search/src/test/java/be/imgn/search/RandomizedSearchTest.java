package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Compares searches over several segments, with blocks and skip tables, against scores computed by brute force.
/// Titles are shorter than 24 words, so their norms are exact.
class RandomizedSearchTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.NO)
            .build();
    // Skewed: the first words are in most documents, the last ones in few.
    private static final List<String> WORDS =
            List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel");
    private static final int DOCUMENTS = 3000;
    private static final int REFRESH_EVERY = 700;
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<List<String>> titles = new ArrayList<>();
    private final Map<String, Double> idfs = new HashMap<>();
    private double average;
    private final Index index = Index.inMemory(SCHEMA);

    @BeforeEach
    void setUp() throws IOException {
        var random = new Random(42);
        try (var writer = index.writer()) {
            for (var doc = 0; doc < DOCUMENTS; doc++) {
                var title = new ArrayList<String>();
                var length = 1 + random.nextInt(20);
                for (var i = 0; i < length; i++) {
                    var word = Math.min(WORDS.size() - 1, (int) Math.abs(random.nextGaussian() * 3));
                    title.add(WORDS.get(word));
                }
                titles.add(title);
                writer.add(Document.builder("k" + doc)
                        .text("title", String.join(" ", title))
                        .build());
                if (doc % REFRESH_EVERY == REFRESH_EVERY - 1) {
                    writer.refresh();
                }
            }
        }
        average = titles.stream().mapToInt(List::size).average().orElseThrow();
    }

    @AfterEach
    void tearDown() throws IOException {
        index.close();
    }

    @Test
    void scoresTermsAsBm25() {
        for (var word : WORDS) {
            check(Query.term("title", word), List.of(word), title -> count(title, word));
        }
    }

    @Test
    void scoresAllAnyAndNot() {
        for (var a : WORDS) {
            for (var b : WORDS) {
                if (a.equals(b)) {
                    continue;
                }
                var both = List.of(a, b);
                check(
                        Query.all(Query.term("title", a), Query.term("title", b)),
                        both,
                        title -> count(title, a) > 0 && count(title, b) > 0 ? 1 : 0);
                check(
                        Query.any(Query.term("title", a), Query.term("title", b)),
                        both,
                        title -> count(title, a) > 0 || count(title, b) > 0 ? 1 : 0);
                check(
                        Query.all(Query.term("title", a), Query.not(Query.term("title", b))),
                        List.of(a),
                        title -> count(title, a) > 0 && count(title, b) == 0 ? 1 : 0);
            }
        }
    }

    @Test
    void scoresPhrases() {
        for (var a : WORDS) {
            for (var b : WORDS) {
                check(Query.phrase("title", a + " " + b), List.of(a, b), title -> phrases(title, a, b));
            }
        }
    }

    /// Checks the hits of a query against brute force.
    ///
    /// @param words the words whose BM25 scores add up, or whose idfs add up for a phrase
    /// @param matches the frequency of the query in a title, 0 if it does not match; for a phrase, the phrase's
    private void check(Query query, List<String> words, ToIntFunction<List<String>> matches) {
        var expected = new HashMap<String, Double>();
        var phrase = query instanceof Query.Phrase;
        for (var doc = 0; doc < DOCUMENTS; doc++) {
            var title = titles.get(doc);
            var frequency = matches.applyAsInt(title);
            if (frequency == 0) {
                continue;
            }
            var score = 0.0;
            if (phrase) {
                var idf = words.stream().mapToDouble(this::idf).sum();
                score = bm25(idf, frequency, title.size());
            } else {
                for (var word : words) {
                    var f = count(title, word);
                    if (f > 0) {
                        score += bm25(idf(word), f, title.size());
                    }
                }
            }
            expected.put("k" + doc, score);
        }
        try (var searcher = index.searcher()) {
            var hits = searcher.search(query, DOCUMENTS);
            assertThat(hits).as(query.toString()).hasSize(expected.size());
            var previous = Float.POSITIVE_INFINITY;
            for (var hit : hits) {
                var score = expected.get(hit.key());
                assertThat(score).as("%s in %s", hit.key(), query).isNotNull();
                assertThat((double) hit.score())
                        .as("%s in %s", hit.key(), query)
                        .isCloseTo(score, within(1e-4));
                assertThat(hit.score()).isLessThanOrEqualTo(previous);
                previous = hit.score();
            }
        }
    }

    private double idf(String word) {
        return idfs.computeIfAbsent(word, this::computeIdf);
    }

    private double computeIdf(String word) {
        var docFreq = titles.stream().filter(title -> title.contains(word)).count();
        return Math.log(1 + (DOCUMENTS - docFreq + 0.5) / (docFreq + 0.5));
    }

    private double bm25(double idf, int frequency, int length) {
        return idf * (K1 + 1) * frequency / (frequency + K1 * (1 - B + B * length / average));
    }

    private static int count(List<String> title, String word) {
        return (int) title.stream().filter(word::equals).count();
    }

    private static int phrases(List<String> title, String a, String b) {
        var count = 0;
        for (var i = 0; i + 1 < title.size(); i++) {
            if (title.get(i).equals(a) && title.get(i + 1).equals(b)) {
                count++;
            }
        }
        return count;
    }
}
