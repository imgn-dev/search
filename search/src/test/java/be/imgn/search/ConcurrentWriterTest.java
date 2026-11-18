package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

@ParameterizedClass
@EnumSource(StorageKind.class)
class ConcurrentWriterTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .keyword("all", Stored.NO)
            .keyword("version", Stored.YES)
            .languages(List.of(ToyAnalyzer.FRENCH))
            .build();
    private static final List<String> WORDS =
            List.of("fox", "dog", "cat", "foxes", "dogs", "cats", "bird", "fish", "horse", "cow");
    private static final int OWNERS = 4;
    private static final int KEYS_PER_OWNER = 30;
    private static final int OPERATIONS = 800;
    private static final int DELETED_PER_OWNER = 5;

    private final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
    private final StorageKind storage;
    private final Path directory;

    ConcurrentWriterTest(StorageKind storage, @TempDir Path directory) {
        this.storage = storage;
        this.directory = directory;
    }

    /// Each owner thread adds, replaces and deletes its own keys, so their order is known; once an owner is done, a
    /// deleter deletes some of its keys, after all of the owner's operations. Refreshes and searches run meanwhile,
    /// and a small budget makes many segments, which merge.
    @Test
    void endsWithTheLastOperationOfEachKey() throws IOException, InterruptedException {
        try (var index = storage.open(SCHEMA, directory)) {
            var expected = new ConcurrentHashMap<String, String>();
            var writer = index.writer(32 << 10);
            var done = new CountDownLatch[OWNERS];
            var threads = new ArrayList<Thread>();
            for (var o = 0; o < OWNERS; o++) {
                var owner = o;
                done[owner] = new CountDownLatch(1);
                threads.add(start(() -> {
                    own(writer, owner, expected);
                    done[owner].countDown();
                }));
            }
            threads.add(start(() -> {
                for (var owner = 0; owner < OWNERS; owner++) {
                    await(done[owner]);
                    for (var k = 0; k < DELETED_PER_OWNER; k++) {
                        var key = owner + "-" + k;
                        io(() -> writer.delete(key));
                        expected.remove(key);
                    }
                }
            }));
            var running = new AtomicBoolean(true);
            var background = new ArrayList<Thread>();
            background.add(start(() -> {
                while (running.get()) {
                    io(writer::refresh);
                }
            }));
            for (var s = 0; s < 2; s++) {
                background.add(start(() -> {
                    while (running.get()) {
                        try (var searcher = index.searcher()) {
                            var keys = searcher.search(Query.term("all", "yes"), Integer.MAX_VALUE).stream()
                                    .map(Hit::key)
                                    .toList();
                            assertThat(keys).doesNotHaveDuplicates();
                        }
                    }
                }));
            }
            join(threads);
            running.set(false);
            join(background);
            writer.close();

            assertThat(failures).isEmpty();
            assertThat(contents(index)).isEqualTo(new HashMap<>(expected));
        }
    }

    @Test
    void searchesFromSeveralThreadsGiveWhatOneThreadGives() throws IOException, InterruptedException {
        try (var index = storage.open(SCHEMA, directory)) {
            var random = new Random(7);
            try (var writer = index.writer()) {
                for (var i = 0; i < 3000; i++) {
                    writer.add(document(random, "k" + random.nextInt(2000), "v" + i));
                    if (i % 700 == 699) {
                        writer.refresh();
                    }
                }
                writer.delete("k1");
            }
            var queries = new ArrayList<Query>();
            for (var word : WORDS) {
                queries.add(Query.term("title", word));
                queries.add(Query.phrase("title", word + " fox"));
                queries.add(Query.any(Query.term("title", word), Query.term("title", "cat")));
            }
            try (var searcher = index.searcher()) {
                var expected = run(searcher, queries);
                var threads = new ArrayList<Thread>();
                var results = new ConcurrentLinkedQueue<List<String>>();
                for (var t = 0; t < 8; t++) {
                    threads.add(start(() -> {
                        for (var round = 0; round < 5; round++) {
                            results.add(run(searcher, queries));
                        }
                    }));
                }
                join(threads);

                assertThat(failures).isEmpty();
                assertThat(results)
                        .hasSize(40)
                        .allSatisfy(result -> assertThat(result).isEqualTo(expected));
            }
        }
    }

    private static void own(Writer writer, int owner, Map<String, String> expected) {
        var random = new Random(owner);
        for (var i = 0; i < OPERATIONS; i++) {
            var key = owner + "-" + random.nextInt(KEYS_PER_OWNER);
            if (random.nextInt(5) == 0) {
                io(() -> writer.delete(key));
                expected.remove(key);
            } else {
                var version = "v" + i;
                io(() -> writer.add(document(random, key, version)));
                expected.put(key, version);
            }
        }
    }

    // Each hit as its key, score and stored values.
    private static List<String> run(Searcher searcher, List<Query> queries) {
        var results = new ArrayList<String>();
        for (var query : queries) {
            var hits = new ArrayList<>(searcher.search(query, 50));
            hits.addAll(searcher.search(Locale.FRENCH, query, 50));
            for (var hit : hits) {
                results.add(hit.key() + " " + hit.score() + " "
                        + searcher.document(hit).values("title"));
            }
        }
        return results;
    }

    private static Map<String, String> contents(Index index) {
        try (var searcher = index.searcher()) {
            var contents = new HashMap<String, String>();
            for (var hit : searcher.search(Query.term("all", "yes"), Integer.MAX_VALUE)) {
                var previous = contents.put(
                        hit.key(), searcher.document(hit).value("version").orElseThrow());
                assertThat(previous).isNull();
            }
            return contents;
        }
    }

    private static Document document(Random random, String key, String version) {
        var title = new StringBuilder();
        for (var i = 0; i < 1 + random.nextInt(12); i++) {
            title.append(WORDS.get(random.nextInt(WORDS.size()))).append(' ');
        }
        var builder = Document.builder(key)
                .text("title", title.toString())
                .keyword("all", "yes")
                .keyword("version", version);
        if (random.nextBoolean()) {
            builder.language(Locale.FRENCH);
        }
        return builder.build();
    }

    private Thread start(Runnable task) {
        return Thread.ofPlatform()
                .uncaughtExceptionHandler((thread, failure) -> failures.add(failure))
                .start(task);
    }

    private static void join(List<Thread> threads) throws InterruptedException {
        for (var thread : threads) {
            thread.join();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void io(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
