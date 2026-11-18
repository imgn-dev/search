package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/// What only a file index does: survive its closing, lock its directory, check the schema it records, detect
/// corruption, and delete the files nothing uses any more, once nothing uses them.
class FileIndexTest {

    private static final Schema SCHEMA = Schema.builder()
            .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
            .keyword("tags", Stored.NO)
            .languages(List.of(ToyAnalyzer.FRENCH))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    private Path root;

    @Test
    void reopensWhatWasCommitted() throws IOException {
        var path = root.resolve("index");
        try (var index = Index.open(path, SCHEMA)) {
            try (var writer = index.writer()) {
                writer.add(document("a", "fox"));
                writer.add(document("b", "fox"));
                writer.commit();
                writer.delete("a");
                writer.add(document("c", "fox"));
            }
        }
        try (var index = Index.open(path, SCHEMA)) {
            assertThat(keys(index, "fox")).containsExactly("b", "c");
            try (var writer = index.writer()) {
                writer.delete("b");
                writer.add(Document.builder("b")
                        .language(Locale.FRENCH)
                        .text("title", "dogs")
                        .build());
            }
        }
        try (var index = Index.open(path, SCHEMA)) {
            assertThat(keys(index, "fox")).containsExactly("c");
            try (var searcher = index.searcher()) {
                assertThat(searcher.search(Locale.FRENCH, Query.term("title", "dog"), 10))
                        .extracting(Hit::key)
                        .containsExactly("b");
            }
            index.check();
        }
    }

    @Test
    void createsAnIndexInAMissingDirectoryOrOneWithOnlyALockAndTemporaryFiles() throws IOException {
        var missing = root.resolve("a").resolve("b");
        try (var index = Index.open(missing, SCHEMA)) {
            assertThat(keys(index, "fox")).isEmpty();
        }
        assertThat(names(missing)).containsExactlyInAnyOrder("manifest-0", "write.lock");

        var leftovers = Files.createDirectory(root.resolve("leftovers"));
        Files.writeString(leftovers.resolve("write.lock"), "");
        Files.writeString(leftovers.resolve("manifest-0.tmp"), "half a manifest");
        try (var index = Index.open(leftovers, SCHEMA)) {
            assertThat(keys(index, "fox")).isEmpty();
        }
        assertThat(names(leftovers)).containsExactlyInAnyOrder("manifest-0", "write.lock");
    }

    @Test
    void refusesADirectoryWithOtherFilesButNoManifest() throws IOException {
        var other = Files.createDirectory(root.resolve("other"));
        Files.writeString(other.resolve("notes.txt"), "not an index");

        assertThatThrownBy(() -> Index.open(other, SCHEMA))
                .isExactlyInstanceOf(IOException.class)
                .hasMessageContaining("notes.txt");
        assertThat(names(other)).containsExactlyInAnyOrder("notes.txt", "write.lock");
        // The lock is released.
        Files.delete(other.resolve("notes.txt"));
        Index.open(other, SCHEMA).close();
    }

    @Test
    void refusesAnIndexOpenInThisProcessAndKeepsItsLock() throws IOException, InterruptedException {
        var path = root.resolve("index");
        try (var index = Index.open(path, SCHEMA)) {
            assertThatThrownBy(() -> Index.open(path, SCHEMA))
                    .isInstanceOf(IndexLockedException.class)
                    .hasMessageContaining("this process");
            assertThat(keys(index, "fox")).isEmpty();

            // Another process still finds the lock held.
            assertThat(probeLock(path.resolve("write.lock"))).isEqualTo("held");
        }
        assertThat(probeLock(path.resolve("write.lock"))).isEqualTo("free");
        Index.open(path, SCHEMA).close();
    }

    @Test
    void refusesAnIndexOpenInAnotherProcess() throws IOException, InterruptedException {
        var path = root.resolve("index");
        Index.open(path, SCHEMA).close();
        var program = root.resolve("LockHolder.java");
        Files.writeString(program, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;

                class LockHolder {
                    public static void main(String[] args) throws Exception {
                        try (var channel = FileChannel.open(
                                Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                            var lock = channel.lock();
                            System.out.println("locked");
                            System.out.flush();
                            System.in.read();
                            lock.release();
                        }
                    }
                }
                """);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(
                        java, program.toString(), path.resolve("write.lock").toString())
                .redirectErrorStream(true)
                .start();
        try {
            var output = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
            var line = new StringBuilder();
            for (var c = output.read(); c >= 0 && c != '\n'; c = output.read()) {
                line.append((char) c);
            }
            assertThat(line.toString()).isEqualTo("locked");

            assertThatThrownBy(() -> Index.open(path, SCHEMA))
                    .isInstanceOf(IndexLockedException.class)
                    .hasMessageContaining("another process");
        } finally {
            process.getOutputStream().close();
            assertThat(process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
        Index.open(path, SCHEMA).close();
    }

    @Test
    void refusesASchemaThatChangesAField() throws IOException {
        var path = root.resolve("index");
        Index.open(path, SCHEMA).close();

        for (var builder : List.of(
                Schema.builder()
                        .text("title", Positions.DROP, Accents.KEEP, Stored.YES)
                        .keyword("tags", Stored.NO),
                Schema.builder().text("title", Positions.KEEP, Accents.KEEP, Stored.YES),
                Schema.builder()
                        .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
                        .keyword("tags", Stored.NO)
                        .keyword("extra", Stored.NO),
                Schema.builder().keyword("tags", Stored.NO).text("title", Positions.KEEP, Accents.KEEP, Stored.YES))) {
            var schema = builder.languages(List.of(ToyAnalyzer.FRENCH)).build();
            assertThatThrownBy(() -> Index.open(path, schema))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fields");
        }
        Index.open(path, SCHEMA).close();
    }

    @Test
    void refusesASchemaThatLacksOrChangesAnAnalyzerTheIndexUsed() throws IOException {
        var path = root.resolve("index");
        Index.open(path, SCHEMA).close();
        var without = schema();
        var changed = schema(new ToyAnalyzer(Locale.FRENCH, "toy/french-2", "s"));

        assertThatThrownBy(() -> Index.open(path, without.build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toy/french");
        assertThatThrownBy(() -> Index.open(path, changed.build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toy/french-2");
    }

    @Test
    void acceptsANewAnalyzerAndRecordsItOnceCommitted() throws IOException {
        var path = root.resolve("index");
        Index.open(path, SCHEMA).close();
        var withEnglish = schema(ToyAnalyzer.FRENCH, ToyAnalyzer.ENGLISH).build();

        try (var index = Index.open(path, withEnglish)) {
            try (var writer = index.writer()) {
                writer.add(Document.builder("a")
                        .language(Locale.ENGLISH)
                        .text("title", "boxes")
                        .build());
            }
            try (var searcher = index.searcher()) {
                assertThat(searcher.search(Locale.ENGLISH, Query.term("title", "box"), 10))
                        .extracting(Hit::key)
                        .containsExactly("a");
            }
        }

        assertThatThrownBy(() -> Index.open(path, SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toy/english");
        Index.open(path, withEnglish).close();
    }

    @Test
    void throwsOnACorruptManifestRatherThanFallBack() throws IOException {
        var path = root.resolve("index");
        try (var index = Index.open(path, SCHEMA);
                var writer = index.writer()) {
            writer.add(document("a", "fox"));
        }
        var manifest = path.resolve("manifest-1");
        flip(manifest, Files.size(manifest) / 2);

        assertThatThrownBy(() -> Index.open(path, SCHEMA))
                .isInstanceOf(CorruptIndexException.class)
                .hasMessageContaining("manifest-1");
    }

    @Test
    void throwsOnACorruptDeletionFile() throws IOException {
        var path = root.resolve("index");
        try (var index = Index.open(path, SCHEMA);
                var writer = index.writer()) {
            writer.add(document("a", "fox"));
            writer.add(document("b", "fox"));
            writer.commit();
            writer.delete("a");
        }
        var deletions = names(path).stream()
                .filter(name -> name.contains(".del-"))
                .findFirst()
                .orElseThrow();
        flip(path.resolve(deletions), Files.size(path.resolve(deletions)) - 1);

        assertThatThrownBy(() -> Index.open(path, SCHEMA))
                .isInstanceOf(CorruptIndexException.class)
                .hasMessageContaining(deletions);
    }

    @Test
    void checkFindsAFlippedByteThatOpeningDoesNotRead() throws IOException {
        var path = root.resolve("index");
        try (var index = Index.open(path, SCHEMA);
                var writer = index.writer()) {
            writer.add(document("a", "fox"));
        }
        // Inside the postings, which opening does not read.
        flip(path.resolve(segments(path).first()), 10);

        try (var index = Index.open(path, SCHEMA)) {
            assertThatThrownBy(index::check)
                    .isInstanceOf(CorruptIndexException.class)
                    .hasMessageContaining("Checksum");
        }
    }

    @ParameterizedTest
    @EnumSource(StorageKind.class)
    void checksASoundIndex(StorageKind storage) throws IOException {
        var index = storage.open(SCHEMA, root);
        try (var writer = index.writer()) {
            writer.add(document("a", "fox"));
            writer.refresh();
            writer.add(document("b", "fox"));
        }
        index.check();
        index.close();

        assertThatThrownBy(index::check).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletesTheFilesNothingUsesOnlyOnceTheirSearchersClose() throws IOException {
        var path = root.resolve("index");
        try (var index = Index.open(path, SCHEMA);
                var writer = index.writer()) {
            writer.add(document("a", "fox"));
            writer.add(document("b", "fox"));
            writer.commit();
            var first = segments(path);
            var searcher = index.searcher();

            writer.delete("a");
            writer.delete("b");
            writer.add(document("c", "fox"));
            writer.commit();

            // The searcher still reads the segment that no commit lists any more.
            assertThat(segments(path)).containsAll(first);
            assertThat(keys(searcher, "fox")).containsExactly("a", "b");
            assertThat(keys(index, "fox")).containsExactly("c");
            searcher.close();
            assertThat(segments(path)).doesNotContainAnyElementsOf(first);
        }
    }

    @Test
    void keepsSearchersWorkingWhileMergesReplaceTheirFiles() throws IOException, InterruptedException {
        var path = root.resolve("index");
        try (var index = Index.open(path, SCHEMA);
                var writer = index.writer()) {
            // Nine segments: too few to merge.
            for (var i = 0; i < 9; i++) {
                writer.add(document("k" + i, "fox"));
                writer.refresh();
            }
            var searcher = index.searcher();
            var before = keys(searcher, "fox");
            var held = segments(path);
            assertThat(held).hasSize(9);

            // Ten segments per tier: merges start, and write segments numbered after the flushed ones.
            for (var i = 9; i < 40; i++) {
                writer.add(document("k" + i, "fox"));
                writer.refresh();
            }
            var deadline = System.nanoTime() + TIMEOUT.toNanos();
            // Merged segments replace theirs at the next refresh.
            while (segments(path).size() > 20) {
                writer.refresh();
                assertThat(System.nanoTime()).as("merged").isLessThan(deadline);
                Thread.sleep(10);
            }
            writer.commit();

            assertThat(keys(searcher, "fox")).isEqualTo(before);
            assertThat(keys(index, "fox")).hasSize(40);
            assertThat(segments(path)).containsAll(held);
            searcher.close();
            writer.commit();
            index.check();
        }
        try (var index = Index.open(path, SCHEMA)) {
            assertThat(keys(index, "fox")).hasSize(40);
            index.check();
        }
    }

    // Whether another process can take a lock: "held" or "free".
    private String probeLock(Path lock) throws IOException, InterruptedException {
        var program = root.resolve("LockProbe.java");
        Files.writeString(program, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;

                class LockProbe {
                    public static void main(String[] args) throws Exception {
                        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
                            var lock = channel.tryLock();
                            System.out.println(lock == null ? "held" : "free");
                        }
                    }
                }
                """);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, program.toString(), lock.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        assertThat(process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        return output;
    }

    private static Schema.Builder schema(LanguageAnalyzer... analyzers) {
        return Schema.builder()
                .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
                .keyword("tags", Stored.NO)
                .languages(List.of(analyzers));
    }

    private static Document document(String key, String title) {
        return Document.builder(key).text("title", title).build();
    }

    private static List<String> keys(Index index, String word) {
        try (var searcher = index.searcher()) {
            return keys(searcher, word);
        }
    }

    private static List<String> keys(Searcher searcher, String word) {
        return searcher.search(Query.term("title", word), 100).stream()
                .map(Hit::key)
                .toList();
    }

    private static Set<String> names(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    // The segment files, deletions left out.
    private static SortedSet<String> segments(Path directory) throws IOException {
        return names(directory).stream()
                .filter(name -> name.matches("segment-\\d+"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void flip(Path file, long offset) {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.allocate(1);
            channel.read(buffer, offset);
            buffer.put(0, (byte) (buffer.get(0) ^ 1));
            buffer.rewind();
            channel.write(buffer, offset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
