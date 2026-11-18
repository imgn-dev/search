package be.imgn.search.internal.search;

import static be.imgn.search.internal.segment.DocIterator.NO_MORE_DOCS;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.Positions;
import be.imgn.search.Query;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.WordAnalyzer;
import be.imgn.search.internal.index.IndexWriter;
import be.imgn.search.internal.index.Snapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/// Checks that skipping non-competitive blocks and documents changes nothing: over random indexes of several
/// segments with deletes and replacements, and random queries, the top-k is the exhaustive one, documents and
/// scores; and every bound is at least the scores it bounds.
class TopKTest {

    private static final int SEEDS = 12;
    private static final int QUERIES = 60;
    private static final int[] KS = {1, 2, 5, 10, 50, 1000};
    // Skewed: the first words are frequent, the last ones rare. Some end with "s", which French drops.
    private static final List<String> WORDS = List.of(
            "alpha",
            "bravos",
            "charlie",
            "deltas",
            "echo",
            "foxtrot",
            "golfs",
            "hotel",
            "india",
            "juliet",
            "kilos",
            "lima",
            "mike",
            "november",
            "oscar",
            "papas");

    /// French, for the tests: drops a final "s".
    private static final LanguageAnalyzer FRENCH = new LanguageAnalyzer() {
        @Override
        public Locale language() {
            return Locale.FRENCH;
        }

        @Override
        public String id() {
            return "test/french";
        }

        @Override
        public WordAnalyzer newWordAnalyzer() {
            return word -> {
                if (word.length() > 1 && word.chars()[word.length() - 1] == 's') {
                    word.setLength(word.length() - 1);
                }
            };
        }
    };

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.NO)
            .text("body", Positions.DROP, Accents.KEEP, Stored.NO)
            .keyword("tags", Stored.NO)
            .language(FRENCH)
            .build();

    @Test
    void skippingGivesTheExhaustiveTopK() throws IOException {
        for (var seed = 0; seed < SEEDS; seed++) {
            var random = new Random(seed);
            var snapshot = index(random);
            for (var q = 0; q < QUERIES; q++) {
                var query = query(random, 0);
                var k = KS[random.nextInt(KS.length)];
                var language = random.nextBoolean() ? Locale.FRENCH : null;

                var exhaustive = Searching.search(SCHEMA, snapshot, query, language, k, false);
                var skipping = Searching.search(SCHEMA, snapshot, query, language, k, true);

                assertThat(skipping)
                        .as("seed %s, k %s, %s %s", seed, k, language, query)
                        .isEqualTo(exhaustive);
            }
        }
    }

    @Test
    void boundsAreNeverBelowTheScores() throws IOException {
        for (var seed = 0; seed < SEEDS / 2; seed++) {
            var random = new Random(seed);
            var snapshot = index(random);
            for (var q = 0; q < QUERIES; q++) {
                var query = query(random, 0);
                var language = random.nextBoolean() ? FRENCH : null;
                var context = new SearchContext(snapshot);
                var weight = new QueryCompiler(SCHEMA, context, language).compile(query);
                for (var segment = 0; segment < snapshot.segments().size(); segment++) {
                    checkBounds(weight.scorer(segment), "seed " + seed + " " + query);
                }
            }
        }
    }

    private static void checkBounds(@Nullable Scorer scorer, String context) {
        if (scorer == null) {
            return;
        }
        var maxScore = scorer.maxScore();
        for (var doc = scorer.nextDoc(); doc != NO_MORE_DOCS; doc = scorer.nextDoc()) {
            var score = (double) scorer.score();
            assertThat(score).as(context).isLessThanOrEqualTo(maxScore);
            var upTo = scorer.advanceShallow(doc);
            assertThat(upTo).as(context).isGreaterThanOrEqualTo(doc);
            assertThat(score).as(context).isLessThanOrEqualTo(scorer.maxScoreUpTo(upTo));
            assertThat(score).as(context).isLessThanOrEqualTo(scorer.maxScoreUpTo(NO_MORE_DOCS));
        }
    }

    // Up to 4000 documents in up to 4 segments, with deletes and replacements of earlier keys.
    private static Snapshot index(Random random) throws IOException {
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        var documents = 1000 + random.nextInt(3000);
        for (var doc = 0; doc < documents; doc++) {
            var key = random.nextInt(10) == 0 && doc > 0 ? "k" + random.nextInt(doc) : "k" + doc;
            var builder = Document.builder(key).text("title", text(random, 1 + random.nextInt(8)));
            if (random.nextInt(4) != 0) {
                builder.text("body", text(random, 1 + random.nextInt(60)));
            }
            if (random.nextBoolean()) {
                builder.language(Locale.FRENCH);
            }
            if (random.nextInt(3) == 0) {
                builder.keyword("tags", word(random));
            }
            writer.add(builder.build());
            if (random.nextInt(50) == 0) {
                writer.delete("k" + random.nextInt(doc + 1));
            }
            if (random.nextInt(1200) == 0) {
                writer.refresh();
            }
        }
        var snapshot = writer.refresh();
        writer.close();
        return snapshot;
    }

    private static Query query(Random random, int depth) {
        var choice = random.nextInt(depth >= 2 ? 3 : 7);
        return switch (choice) {
            case 0 -> Query.term(field(random), word(random));
            case 1 -> Query.term(random.nextBoolean() ? "title" : "body", word(random) + " " + word(random));
            case 2 -> Query.phrase("title", word(random) + " " + word(random));
            case 3 -> Query.all(children(random, depth));
            case 4 -> Query.any(children(random, depth));
            case 5 -> Query.all(query(random, depth + 1), Query.not(query(random, depth + 1)));
            default -> Query.all(everywhere(random), everywhere(random));
        };
    }

    // A word of the query language: an any over the fields.
    private static Query everywhere(Random random) {
        var word = word(random);
        return Query.any(Query.term("title", word), Query.term("body", word), Query.term("tags", word));
    }

    private static List<Query> children(Random random, int depth) {
        var children = new ArrayList<Query>();
        var count = 1 + random.nextInt(4);
        for (var i = 0; i < count; i++) {
            children.add(query(random, depth + 1));
        }
        return children;
    }

    private static String field(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> "body";
            case 1 -> "tags";
            default -> "title";
        };
    }

    private static String text(Random random, int words) {
        var text = new StringBuilder();
        for (var i = 0; i < words; i++) {
            text.append(word(random)).append(' ');
        }
        return text.toString();
    }

    private static String word(Random random) {
        return WORDS.get(Math.min(WORDS.size() - 1, (int) Math.abs(random.nextGaussian() * 4)));
    }
}
