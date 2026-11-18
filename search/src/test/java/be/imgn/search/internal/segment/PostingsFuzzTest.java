package be.imgn.search.internal.segment;

import static be.imgn.search.internal.segment.DocIterator.NO_MORE_DOCS;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.internal.codec.ByteOutput;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/// Drives one reused iterator over random terms with random `nextDoc`, `advance` and position reads, against a
/// model of the term. Seeded, so a failure reproduces.
class PostingsFuzzTest {

    private static final int[] DOC_FREQS = {1, 2, 127, 128, 129, 255, 256, 257, 383, 384, 385, 1000, 5000};
    private static final int TERMS = 400;
    private static final int WALKS = 3;

    @Test
    void agreesWithAModelOfTheTerm() {
        var random = new Random(20_261_118);
        var iterator = new PostingsIterator();
        for (var t = 0; t < TERMS; t++) {
            var docFreq = random.nextBoolean() ? DOC_FREQS[random.nextInt(DOC_FREQS.length)] : 1 + random.nextInt(3000);
            var term = Term.random(random, docFreq);
            var written = term.write(random);
            for (var walk = 0; walk < WALKS; walk++) {
                iterator.reset(
                        written.data(), written.info(), 0, term.positions == null ? -1 : written.positionsBase());
                walk(random, iterator, term, "term " + t + " walk " + walk);
            }
        }
    }

    private static void walk(Random random, PostingsIterator iterator, Term term, String context) {
        var index = -1;
        var positionsRead = 0;
        while (true) {
            var current = index < 0 ? -1 : term.docs[index];
            int expected;
            int actual;
            var operation = random.nextInt(5);
            if (operation == 0) {
                actual = iterator.nextDoc();
                expected = index + 1;
            } else {
                var target = target(random, term, index, current);
                actual = iterator.advance(target);
                expected = index >= 0 && target <= current ? index : firstAtLeast(term.docs, target, index + 1);
            }
            var expectedDoc = expected >= term.docs.length ? NO_MORE_DOCS : term.docs[expected];
            assertThat(actual).as(context).isEqualTo(expectedDoc);
            assertThat(iterator.docID()).as(context).isEqualTo(expectedDoc);
            if (actual == NO_MORE_DOCS) {
                return;
            }
            if (expected != index) {
                positionsRead = 0;
            }
            index = expected;
            assertThat(iterator.freq()).as(context).isEqualTo(term.freqs[index]);
            positionsRead = readPositions(random, iterator, term, index, positionsRead, context);
        }
    }

    // Reads all positions at once, some one at a time, or none, so that the rest are skipped or read after a
    // no-op advance. Returns how many of the document's positions are read.
    private static int readPositions(
            Random random, PostingsIterator iterator, Term term, int index, int alreadyRead, String context) {
        var positions = term.positions;
        if (positions == null) {
            return alreadyRead;
        }
        var read = alreadyRead;
        if (read == 0 && random.nextInt(4) == 0) {
            var all = new int[term.freqs[index]];
            iterator.readPositions(all);
            assertThat(all).as(context).containsExactly(positions[index]);
            return all.length;
        }
        if (random.nextInt(3) != 0) {
            var count = random.nextInt(term.freqs[index] - read + 1);
            for (var j = 0; j < count; j++) {
                assertThat(iterator.nextPosition()).as(context).isEqualTo(positions[index][read++]);
            }
        }
        return read;
    }

    private static int target(Random random, Term term, int index, int current) {
        var docFreq = term.docs.length;
        var ahead = Math.min(docFreq - 1, index + 1 + random.nextInt(300));
        return switch (random.nextInt(6)) {
            case 0 -> index >= 0 ? Math.max(0, current - random.nextInt(3)) : 0;
            case 1 -> term.docs[ahead];
            case 2 -> Math.max(current + 1, term.docs[ahead] - 1);
            case 3 -> clamp((long) term.docs[docFreq - 1] + 1 + random.nextInt(1000));
            case 4 -> Integer.MAX_VALUE - 1;
            default -> clamp((long) current + 1 + random.nextInt(1000));
        };
    }

    private static int clamp(long target) {
        return (int) Math.min(target, Integer.MAX_VALUE - 1);
    }

    private static int firstAtLeast(int[] docs, int target, int from) {
        var i = from;
        while (i < docs.length && docs[i] < target) {
            i++;
        }
        return i;
    }

    private static final class Term {

        private final int[] docs;
        private final int[] freqs;
        private final int @Nullable [][] positions;

        private Term(int[] docs, int[] freqs, int @Nullable [][] positions) {
            this.docs = docs;
            this.freqs = freqs;
            this.positions = positions;
        }

        static Term random(Random random, int docFreq) {
            var docs = new int[docFreq];
            var freqs = new int[docFreq];
            var gaps = random.nextInt(3);
            var doc = -1;
            for (var i = 0; i < docFreq; i++) {
                var room = (Integer.MAX_VALUE - 2 - doc) / (docFreq - i + 1);
                doc += switch (gaps) {
                    case 0 -> 1;
                    case 1 -> 1 + random.nextInt(5);
                    default -> 1 + random.nextInt(Math.max(1, room));
                };
                docs[i] = doc;
                freqs[i] = random.nextInt(10) == 0 ? 1 + random.nextInt(300) : 1 + random.nextInt(3);
            }
            int[][] positions = null;
            if (random.nextInt(4) != 0) {
                positions = new int[docFreq][];
                for (var i = 0; i < docFreq; i++) {
                    positions[i] = new int[freqs[i]];
                    var position = random.nextInt(3) == 0 ? random.nextInt(1 << 30) : 0;
                    for (var j = 0; j < freqs[i]; j++) {
                        position += j == 0
                                ? 0
                                : 1 + (random.nextInt(20) == 0 ? random.nextInt(1 << 20) : random.nextInt(3));
                        positions[i][j] = position;
                    }
                }
            }
            return new Term(docs, freqs, positions);
        }

        Written write(Random random) {
            var postings = new ByteOutput();
            var positionsOut = new ByteOutput();
            // Other terms before this one, so that offsets are not 0.
            postings.writeBytes(new byte[] {1, 2, 3}, 0, random.nextInt(3));
            positionsOut.writeBytes(new byte[] {1, 2, 3}, 0, random.nextInt(3));
            int[] flat = null;
            if (positions != null) {
                flat = new int[Arrays.stream(freqs).sum()];
                var k = 0;
                for (var docPositions : positions) {
                    for (var position : docPositions) {
                        flat[k++] = position;
                    }
                }
            }
            var info = new PostingsWriter().write(docs, freqs, docs.length, flat, null, postings, positionsOut);
            var all = new ByteOutput();
            all.writeBytes(postings);
            all.writeBytes(positionsOut);
            return new Written(MemorySegment.ofArray(all.toByteArray()), info, postings.size());
        }
    }

    private record Written(MemorySegment data, TermInfo info, long positionsBase) {}
}
