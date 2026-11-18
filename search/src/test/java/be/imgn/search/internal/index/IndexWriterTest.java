package be.imgn.search.internal.index;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.Positions;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexWriterTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.NO)
            .build();

    @Test
    void flushesTheBufferBeyondItsBudgetAndKeepsDeletesInOrder() {
        // A budget of one byte: every add becomes a segment of its own.
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, 1);
        writer.add(document("a", "one"));
        writer.add(document("b", "one"));
        writer.add(document("a", "two"));
        writer.delete("b");
        writer.add(document("b", "three"));
        writer.delete("c");

        var snapshot = writer.refresh();
        writer.close();

        assertThat(liveKeys(snapshot)).containsExactly("a", "b");
        assertThat(snapshot.segments()).hasSize(2);
    }

    @Test
    void dropsTheDocumentsReplacedInTheBuffer() {
        var writer = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        writer.add(document("a", "one"));
        writer.add(document("b", "one"));
        writer.add(document("a", "two"));
        writer.delete("b");

        var snapshot = writer.refresh();
        writer.close();

        assertThat(snapshot.maxDoc()).isEqualTo(1);
        assertThat(liveKeys(snapshot)).containsExactly("a");
    }

    @Test
    void continuesFromASnapshot() {
        var first = new IndexWriter(SCHEMA, Snapshot.EMPTY, IndexWriter.DEFAULT_BUDGET);
        first.add(document("a", "one"));
        first.add(document("b", "one"));
        var snapshot = first.refresh();
        first.close();

        var second = new IndexWriter(SCHEMA, snapshot, IndexWriter.DEFAULT_BUDGET);
        second.delete("a");
        second.add(document("c", "one"));
        var next = second.refresh();
        second.close();

        assertThat(liveKeys(snapshot)).containsExactly("a", "b");
        assertThat(liveKeys(next)).containsExactly("b", "c");
        assertThat(next.segmentOf(2)).isEqualTo(1);
    }

    private static Document document(String key, String title) {
        return Document.builder(key).text("title", title).build();
    }

    private static List<String> liveKeys(Snapshot snapshot) {
        var keys = new ArrayList<String>();
        for (var entry : snapshot.segments()) {
            for (var doc = 0; doc < entry.segment().maxDoc(); doc++) {
                if (!entry.deletions().isDeleted(doc)) {
                    keys.add(entry.segment().key(doc));
                }
            }
        }
        return keys;
    }
}
