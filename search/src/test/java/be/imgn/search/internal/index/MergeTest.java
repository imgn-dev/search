package be.imgn.search.internal.index;

import static be.imgn.search.internal.index.Indexes.SCHEMA;
import static be.imgn.search.internal.index.Indexes.document;
import static be.imgn.search.internal.index.Indexes.live;
import static be.imgn.search.internal.index.Indexes.results;
import static be.imgn.search.internal.index.Indexes.version;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.Document;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MergeTest {

    @Test
    void keepsTheResultsOfSegmentsWithoutDeletions() throws IOException {
        var random = new Random(1);
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        for (var segment = 0; segment < 5; segment++) {
            for (var i = 0; i < 300; i++) {
                writer.add(document(random, "k" + segment + "-" + i, "v0"));
            }
            writer.refresh();
        }
        var before = writer.refresh();

        writer.forceMerge();
        var after = writer.refresh();
        writer.close();

        assertThat(before.segments()).hasSize(5);
        assertThat(after.segments()).hasSize(1);
        assertThat(after.maxDoc()).isEqualTo(1500);
        assertThat(results(after)).isEqualTo(results(before));
        assertThat(live(after)).containsExactlyEntriesOf(live(before));
    }

    @Test
    void dropsDeletedDocumentsAsAFreshIndexWouldHoldThem() throws IOException {
        var random = new Random(2);
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        var model = new LinkedHashMap<String, Document>();
        for (var segment = 0; segment < 4; segment++) {
            for (var i = 0; i < 1000; i++) {
                var key = "k" + (segment * 1000 + i);
                add(writer, model, document(random, key, "v0"));
            }
            writer.refresh();
        }
        // A run of deletes empties some blocks of stored fields, scattered ones touch others, and others stay whole.
        for (var i = 100; i < 400; i++) {
            delete(writer, model, "k" + i);
        }
        for (var i = 1000; i < 4000; i += 97) {
            delete(writer, model, "k" + i);
        }
        for (var i = 2000; i < 2010; i++) {
            add(writer, model, document(random, "k" + i, "v1"));
        }
        var before = writer.refresh();

        writer.forceMerge();
        var after = writer.refresh();
        writer.close();

        // The same documents in the same order, written at once.
        var fresh = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        for (var key : live(before).keySet()) {
            fresh.add(requireNonNull(model.get(key)));
        }
        var expected = fresh.refresh();
        fresh.close();
        assertThat(after.segments()).hasSize(1);
        assertThat(after.maxDoc()).isEqualTo(model.size()).isEqualTo(expected.maxDoc());
        assertThat(results(after)).isEqualTo(results(expected));
        assertThat(live(after)).containsExactlyEntriesOf(live(expected));
        assertThat(live(after).keySet()).containsExactlyInAnyOrderElementsOf(model.keySet());
    }

    @Test
    void replaysTheDeletesThatReachItsSegmentsMeanwhile() throws IOException {
        var random = new Random(3);
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        var model = new LinkedHashMap<String, Document>();
        for (var segment = 0; segment < 3; segment++) {
            for (var i = 0; i < 200; i++) {
                add(writer, model, document(random, "k" + (segment * 200 + i), "v0"));
            }
            writer.refresh();
        }
        delete(writer, model, "k5");
        writer.refresh();

        var merge = requireNonNull(writer.startMergeOfAll());
        delete(writer, model, "k10");
        delete(writer, model, "k250");
        add(writer, model, document(random, "k420", "v1"));
        var during = writer.refresh();
        delete(writer, model, "k599");
        assertThat(writer.runMerge(merge)).isTrue();
        var after = writer.refresh();
        writer.close();

        assertThat(live(during).keySet()).doesNotContain("k5", "k10", "k250").contains("k420", "k599");
        assertThat(after.segments()).hasSize(2);
        assertThat(after.segments().getFirst().segment().maxDoc()).isEqualTo(599);
        var records = live(after);
        assertThat(records.keySet()).containsExactlyInAnyOrderElementsOf(model.keySet());
        assertThat(version(requireNonNull(records.get("k420")))).isEqualTo("v1");
        assertThat(version(requireNonNull(records.get("k421")))).isEqualTo("v0");
    }

    @Test
    void leavesOpenSnapshotsAsTheyWere() throws IOException {
        var random = new Random(4);
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        for (var segment = 0; segment < 4; segment++) {
            for (var i = 0; i < 200; i++) {
                writer.add(document(random, "k" + random.nextInt(600), "v" + segment));
            }
            writer.delete("k" + random.nextInt(600));
            writer.refresh();
        }
        var open = writer.refresh();
        var results = results(open);
        var records = live(open);

        writer.forceMerge();
        writer.delete("k1");
        writer.add(document(random, "k2", "v9"));
        var after = writer.refresh();
        writer.close();

        assertThat(after.segments()).hasSize(2);
        assertThat(results(open)).isEqualTo(results);
        assertThat(live(open)).containsExactlyEntriesOf(records);
    }

    @Test
    void mergesInTheBackgroundBeyondTenSegments() throws IOException {
        var random = new Random(5);
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        var model = new LinkedHashMap<String, Document>();
        for (var segment = 0; segment < 25; segment++) {
            for (var i = 0; i < 40; i++) {
                add(writer, model, document(random, "k" + random.nextInt(800), "v" + segment));
            }
            delete(writer, model, "k" + random.nextInt(800));
            writer.refresh();
        }
        writer.awaitMerges();
        var snapshot = writer.refresh();
        writer.close();

        assertThat(snapshot.segments()).hasSizeLessThanOrEqualTo(10);
        var records = live(snapshot);
        assertThat(records.keySet()).containsExactlyInAnyOrderElementsOf(model.keySet());
        model.forEach((key, document) ->
                assertThat(version(requireNonNull(records.get(key)))).isEqualTo(Indexes.documentVersion(document)));
    }

    @Test
    void flushesTheLargestBufferBeyondTheBudget() throws InterruptedException, IOException {
        var random = new Random(6);
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, 256 << 10);
        var documents = new ArrayList<Document>();
        for (var i = 0; i < 4000; i++) {
            documents.add(document(random, "k" + i, "v0"));
        }
        var threads = new ArrayList<Thread>();
        for (var t = 0; t < 4; t++) {
            var first = t * 1000;
            threads.add(Thread.ofPlatform().start(Indexes.unchecked(() -> {
                for (var i = first; i < first + 1000; i++) {
                    writer.add(documents.get(i));
                }
            })));
        }
        for (var thread : threads) {
            thread.join();
        }

        var flushed = writer.segmentCount();
        var snapshot = writer.refresh();
        writer.close();

        assertThat(flushed).isGreaterThan(1);
        assertThat(live(snapshot)).hasSize(4000);
    }

    private static void add(IndexWriter writer, Map<String, Document> model, Document document) throws IOException {
        writer.add(document);
        model.remove(document.key());
        model.put(document.key(), document);
    }

    private static void delete(IndexWriter writer, Map<String, Document> model, String key) throws IOException {
        writer.delete(key);
        model.remove(key);
    }
}
