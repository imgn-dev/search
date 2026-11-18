package be.imgn.search.internal.segment;

import static be.imgn.search.internal.segment.DocIterator.NO_MORE_DOCS;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Layouts;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostingsTest {

    private static final int MAX_DOC = 5000;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 127, 128, 129, 256, 300, 1000, MAX_DOC})
    void iteratesOverEveryDocumentWithItsFrequencyAndPositions(int docFreq) {
        var term = new Term(docFreq, docFreq);
        var written = term.write();
        var iterator = written.iterator(true);

        for (var i = 0; i < docFreq; i++) {
            assertThat(iterator.nextDoc()).isEqualTo(term.docs[i]);
            assertThat(iterator.freq()).isEqualTo(term.freqs[i]);
            for (var position : term.positions.get(i)) {
                assertThat(iterator.nextPosition()).isEqualTo(position);
            }
        }
        assertThat(iterator.nextDoc()).isEqualTo(NO_MORE_DOCS);
        assertThat(iterator.nextDoc()).isEqualTo(NO_MORE_DOCS);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 127, 128, 129, 1000, MAX_DOC})
    void advancesThroughTheSkipTableAndKeepsPositionsInStep(int docFreq) {
        var term = new Term(docFreq, docFreq + 17);
        var written = term.write();
        var random = new Random(docFreq);
        var iterator = written.iterator(true);

        var target = 0;
        var index = 0;
        while (true) {
            target += 1 + random.nextInt(400);
            while (index < docFreq && term.docs[index] < target) {
                index++;
            }
            var doc = iterator.advance(target);
            if (index == docFreq) {
                assertThat(doc).isEqualTo(NO_MORE_DOCS);
                break;
            }
            assertThat(doc).isEqualTo(term.docs[index]);
            assertThat(iterator.freq()).isEqualTo(term.freqs[index]);
            // Read the positions of only some documents, so that others are skipped.
            if (random.nextBoolean()) {
                for (var position : term.positions.get(index)) {
                    assertThat(iterator.nextPosition()).isEqualTo(position);
                }
            }
            target = doc;
            if (random.nextBoolean()) {
                index++;
                var next = iterator.nextDoc();
                assertThat(next).isEqualTo(index < docFreq ? term.docs[index] : NO_MORE_DOCS);
                if (next == NO_MORE_DOCS) {
                    break;
                }
                target = next;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 128, 129, 1000})
    void storesTheBoundsOfTheTermAndOfEachBlock(int docFreq) {
        var term = new Term(docFreq, 3);
        var written = term.write();

        var maxFreq = 0;
        var minNorm = 255;
        for (var i = 0; i < docFreq; i++) {
            maxFreq = Math.max(maxFreq, term.freqs[i]);
            minNorm = Math.min(minNorm, term.norms[term.docs[i]] & 0xFF);
        }
        assertThat(written.info.maxFreq()).isEqualTo(maxFreq);
        assertThat(written.info.minNorm()).isEqualTo(minNorm);
        assertThat(written.info.docFreq()).isEqualTo(docFreq);
        var blocks = (docFreq + 127) / 128;
        if (blocks > 1) {
            for (var b = 0; b < blocks; b++) {
                var entry = written.info.postingsOffset() + (long) b * PostingsWriter.SKIP_ENTRY_SIZE;
                var to = Math.min(docFreq, (b + 1) * 128);
                var blockMaxFreq = 0;
                var blockMinNorm = 255;
                for (var i = b * 128; i < to; i++) {
                    blockMaxFreq = Math.max(blockMaxFreq, term.freqs[i]);
                    blockMinNorm = Math.min(blockMinNorm, term.norms[term.docs[i]] & 0xFF);
                }
                var data = written.data;
                assertThat(data.get(Layouts.INT, entry)).isEqualTo(term.docs[to - 1]);
                assertThat(data.get(Layouts.INT, entry + PostingsWriter.SKIP_MAX_FREQ))
                        .isEqualTo(blockMaxFreq);
                assertThat(data.get(Layouts.BYTE, entry + PostingsWriter.SKIP_MIN_NORM) & 0xFF)
                        .isEqualTo(blockMinNorm);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 129, 1000})
    void staysOnTheCurrentDocumentWhenAdvancedToItOrBefore(int docFreq) {
        var term = new Term(docFreq, 5);
        var iterator = term.write().iterator(true);
        // A document with several positions, past the first block of positions.
        var index = docFreq - 1;
        for (var i = docFreq - 1; i >= 0; i--) {
            if (term.freqs[i] > 2) {
                index = i;
                break;
            }
        }
        var doc = term.docs[index];

        assertThat(iterator.advance(doc)).isEqualTo(doc);
        assertThat(iterator.nextPosition()).isEqualTo(term.positions.get(index)[0]);
        assertThat(iterator.advance(doc)).isEqualTo(doc);
        assertThat(iterator.advance(Math.max(0, doc - 1))).isEqualTo(doc);
        assertThat(iterator.freq()).isEqualTo(term.freqs[index]);
        for (var j = 1; j < term.freqs[index]; j++) {
            assertThat(iterator.nextPosition()).isEqualTo(term.positions.get(index)[j]);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 129, 1000})
    void readsPostingsWithoutPositions(int docFreq) {
        var term = new Term(docFreq, 11);
        var iterator = term.write().iterator(false);

        for (var i = 0; i < docFreq; i++) {
            assertThat(iterator.nextDoc()).isEqualTo(term.docs[i]);
            assertThat(iterator.freq()).isEqualTo(term.freqs[i]);
        }
        assertThat(iterator.nextDoc()).isEqualTo(NO_MORE_DOCS);
    }

    /// A term with random documents, frequencies, positions and norms.
    private static final class Term {

        private final int[] docs;
        private final int[] freqs;
        private final List<int[]> positions = new ArrayList<>();
        private final byte[] norms = new byte[MAX_DOC];

        Term(int docFreq, long seed) {
            var random = new Random(seed);
            docs = random.ints(0, MAX_DOC).distinct().limit(docFreq).sorted().toArray();
            freqs = new int[docFreq];
            for (var i = 0; i < docFreq; i++) {
                // Mostly 1, sometimes large, so that widths vary.
                freqs[i] = random.nextInt(4) == 0 ? 1 + random.nextInt(300) : 1;
                var docPositions = new int[freqs[i]];
                var position = random.nextInt(5);
                for (var j = 0; j < freqs[i]; j++) {
                    docPositions[j] = position;
                    position += 1 + random.nextInt(j % 50 == 49 ? 100_000 : 20);
                }
                positions.add(docPositions);
            }
            random.nextBytes(norms);
        }

        Written write() {
            var postings = new ByteOutput();
            var positionsOut = new ByteOutput();
            // Something before, so that offsets are not 0.
            postings.writeLong(-1);
            positionsOut.writeInt(-1);
            var flat = positions.stream().flatMapToInt(Arrays::stream).toArray();
            var info = new PostingsWriter().write(docs, freqs, docs.length, flat, norms, postings, positionsOut);
            var all = new ByteOutput();
            all.writeBytes(postings);
            all.writeBytes(positionsOut);
            return new Written(MemorySegment.ofArray(all.toByteArray()), info, postings.size());
        }
    }

    private record Written(MemorySegment data, TermInfo info, long positionsBase) {

        PostingsIterator iterator(boolean withPositions) {
            var iterator = new PostingsIterator();
            iterator.reset(data, info, 0, withPositions ? positionsBase : -1);
            return iterator;
        }
    }
}
