package be.imgn.search.internal.index;

import be.imgn.search.Accents;
import be.imgn.search.Document;
import be.imgn.search.LanguageAnalyzer;
import be.imgn.search.Positions;
import be.imgn.search.Query;
import be.imgn.search.Schema;
import be.imgn.search.Stored;
import be.imgn.search.WordAnalyzer;
import be.imgn.search.internal.search.Searching;
import be.imgn.search.internal.segment.StoredRecord;
import be.imgn.search.internal.store.FsDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/// Documents, queries and readers of snapshots for the tests of the writer.
final class Indexes {

    /// French, for the tests: drops a final "s".
    static final LanguageAnalyzer FRENCH = new LanguageAnalyzer() {
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

    static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .text("body", Positions.DROP, Accents.KEEP, Stored.NO)
            .keyword("tags", Stored.YES)
            .keyword("version", Stored.YES)
            .language(FRENCH)
            .build();

    /// The identifiers of the analyzers of [#SCHEMA], by language, as a file index records them.
    static final Map<String, String> ANALYZERS = Map.of("fr", "test/french");

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

    private Indexes() {}

    /// A random document of a key and a version, which a keyword stores.
    static Document document(Random random, String key, String version) {
        var builder = Document.builder(key)
                .text("title", text(random, 3 + random.nextInt(20)))
                .keyword("version", version);
        if (random.nextInt(4) != 0) {
            builder.text("body", text(random, 1 + random.nextInt(40)));
        }
        if (random.nextBoolean()) {
            builder.language(Locale.FRENCH);
        }
        if (random.nextInt(3) == 0) {
            builder.keyword("tags", word(random));
        }
        return builder.build();
    }

    /// Queries of every shape.
    static List<Query> queries() {
        var queries = new ArrayList<Query>();
        for (var word : WORDS) {
            queries.add(Query.term("title", word));
            queries.add(Query.term("body", word));
            queries.add(Query.term("tags", word));
        }
        queries.add(Query.phrase("title", "alpha bravos"));
        queries.add(Query.phrase("title", "charlie alpha"));
        queries.add(Query.all(Query.term("title", "alpha"), Query.term("body", "echo")));
        queries.add(Query.any(Query.term("title", "papas"), Query.term("body", "oscar"), Query.term("tags", "lima")));
        queries.add(Query.all(Query.term("title", "bravos"), Query.not(Query.term("body", "alpha"))));
        return queries;
    }

    /// The hits of every query, neutral and French, as key and score.
    static List<String> results(Snapshot snapshot) {
        var results = new ArrayList<String>();
        for (var query : queries()) {
            for (var language : new Locale[] {null, Locale.FRENCH}) {
                results.add(results(snapshot, query, language).toString());
            }
        }
        return results;
    }

    static List<String> results(Snapshot snapshot, Query query, @Nullable Locale language) {
        var hits = new ArrayList<String>();
        for (var hit : Searching.search(SCHEMA, snapshot, query, language, 1000)) {
            hits.add(key(snapshot, hit.doc()) + "=" + hit.score());
        }
        return hits;
    }

    /// The stored record of every live document, by key, in the snapshot's order.
    static Map<String, StoredRecord> live(Snapshot snapshot) {
        var records = new LinkedHashMap<String, StoredRecord>();
        var inflater = new Inflater();
        try {
            for (var entry : snapshot.segments()) {
                var segment = entry.segment();
                for (var doc = 0; doc < segment.maxDoc(); doc++) {
                    if (!entry.deletions().isDeleted(doc)) {
                        var previous = records.put(segment.key(doc), segment.stored(doc, inflater));
                        if (previous != null) {
                            throw new AssertionError("Key " + segment.key(doc) + " is live twice");
                        }
                    }
                }
            }
        } finally {
            inflater.end();
        }
        return records;
    }

    /// The version of a stored record.
    static String version(StoredRecord record) {
        return record.values().stream()
                .filter(value -> value.field().equals("version"))
                .findFirst()
                .orElseThrow()
                .value();
    }

    /// Per key, the version the last operation up to a cut added, if an add: what a snapshot of that cut holds.
    static Map<String, String> expected(Iterable<Operation> operations, long cut) {
        var sorted = new ArrayList<Operation>();
        operations.forEach(sorted::add);
        sorted.sort(Comparator.comparingLong(Operation::seq));
        var versions = new HashMap<String, String>();
        for (var operation : sorted) {
            if (operation.seq() > cut) {
                break;
            }
            var version = operation.version();
            if (version == null) {
                versions.remove(operation.key());
            } else {
                versions.put(operation.key(), version);
            }
        }
        return versions;
    }

    /// Per key, the version of the live document of a snapshot.
    static Map<String, String> versions(Snapshot snapshot) {
        var versions = new HashMap<String, String>();
        live(snapshot).forEach((key, record) -> versions.put(key, version(record)));
        return versions;
    }

    /// The version of a document.
    static String documentVersion(Document document) {
        return document.fields().stream()
                .filter(field -> field.name().equals("version"))
                .map(field -> switch (field) {
                    case Document.Keyword keyword -> keyword.value();
                    case Document.Text text -> text.value();
                    case Document.TextSource _ -> throw new IllegalArgumentException("A version is never a source");
                })
                .findFirst()
                .orElseThrow();
    }

    /// The documents of the last commit of a file index, by key, with their versions. Checks every segment in full.
    static Map<String, String> committed(Path path) throws IOException {
        var storage = FileStorage.open(new FsDirectory(path), SCHEMA.fields(), ANALYZERS);
        try {
            var snapshot = storage.snapshot();
            snapshot.segments().forEach(entry -> entry.segment().checkIntegrity());
            var versions = versions(snapshot);
            storage.release(snapshot);
            return versions;
        } finally {
            storage.close();
        }
    }

    /// The files of a file index that its newest manifest does not list, the lock aside: what nothing should keep
    /// once the index is closed.
    static List<String> unlisted(Path path) throws IOException {
        var names = new FsDirectory(path).list();
        var newest = names.stream().mapToLong(Manifest::generationOf).max().orElseThrow();
        var manifest = Manifest.decode(Files.readAllBytes(path.resolve(Manifest.fileName(newest))), newest);
        var listed = new HashSet<String>();
        listed.add("write.lock");
        listed.add(Manifest.fileName(newest));
        for (var segment : manifest.segments()) {
            listed.add("segment-" + segment.id());
            if (segment.deletionGeneration() > 0) {
                listed.add("segment-" + segment.id() + ".del-" + segment.deletionGeneration());
            }
        }
        return names.stream().filter(name -> !listed.contains(name)).sorted().toList();
    }

    private static String key(Snapshot snapshot, int doc) {
        var entry = snapshot.segments().get(snapshot.segmentOf(doc));
        return entry.segment().key(doc - entry.base());
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

    /// A task that may throw an `IOException`, as a `Runnable` that throws it unchecked.
    static Runnable unchecked(IoTask task) {
        return () -> {
            try {
                task.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /// An add or a delete, with its sequence number.
    ///
    /// @param seq the sequence number
    /// @param key the key
    /// @param version the version an add wrote, or `null` for a delete
    record Operation(long seq, String key, @Nullable String version) {}

    /// A task that may throw an `IOException`.
    @FunctionalInterface
    interface IoTask {

        /// Runs the task.
        void run() throws IOException;
    }
}
