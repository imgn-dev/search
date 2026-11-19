package be.imgn.search.benchmarks;

import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/// Documents of generated words drawn with a Zipf distribution from a vocabulary of [#VOCABULARY] words, as in
/// natural text: the most frequent words are in almost every document, the rare ones in few. The same seed gives
/// the same documents.
final class GeneratedText {

    static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.KEEP, Accents.KEEP, Stored.NO)
            .defaultFields("title", "body")
            .build();

    static final int VOCABULARY = 20_000;
    static final int TITLE_WORDS = 8;
    static final int BODY_WORDS = 200;

    private static final long SEED = 42;
    private static final int MIN_LETTERS = 2;
    private static final int MORE_LETTERS = 9;

    private final Random random = new Random(SEED);
    private final List<String> vocabulary = new ArrayList<>();
    private final double[] cumulative = new double[VOCABULARY];

    GeneratedText() {
        var seen = new HashSet<String>();
        while (vocabulary.size() < VOCABULARY) {
            var word = new StringBuilder();
            var length = MIN_LETTERS + random.nextInt(MORE_LETTERS);
            for (var i = 0; i < length; i++) {
                word.append((char) ('a' + random.nextInt('z' - 'a' + 1)));
            }
            if (seen.add(word.toString())) {
                vocabulary.add(word.toString());
            }
        }
        var sum = 0.0;
        for (var rank = 0; rank < VOCABULARY; rank++) {
            sum += 1.0 / (rank + 1);
            cumulative[rank] = sum;
        }
        for (var rank = 0; rank < VOCABULARY; rank++) {
            cumulative[rank] /= sum;
        }
    }

    /// The word of a rank, 0 the most frequent.
    String word(int rank) {
        return vocabulary.get(rank);
    }

    /// The next documents, with a title of [#TITLE_WORDS] words and a body of [#BODY_WORDS].
    List<Document> documents(int first, int count) {
        var documents = new ArrayList<Document>(count);
        for (var i = 0; i < count; i++) {
            documents.add(Document.builder("doc-" + (first + i))
                    .text("title", text(TITLE_WORDS))
                    .text("body", text(BODY_WORDS))
                    .build());
        }
        return documents;
    }

    /// A text of `words` words, each followed by a space.
    String text(int words) {
        var text = new StringBuilder();
        for (var i = 0; i < words; i++) {
            var found = Arrays.binarySearch(cumulative, random.nextDouble());
            var rank = Math.min(found < 0 ? -found - 1 : found, VOCABULARY - 1);
            text.append(vocabulary.get(rank)).append(' ');
        }
        return text.toString();
    }
}
