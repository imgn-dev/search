package be.imgn.search.benchmarks;

import be.imgn.search.Index;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/// The storages the benchmarks compare: `memory`, the heap, and `files`, a temporary directory.
final class Storages {

    /// The values of a storage parameter.
    static final String MEMORY = "memory";

    static final String FILES = "files";

    private Storages() {}

    /// A new temporary directory for a file index, under `java.io.tmpdir`.
    static Path newDirectory() throws IOException {
        return Files.createTempDirectory("search-benchmark");
    }

    /// A new, empty index.
    ///
    /// @param storage [#MEMORY] or [#FILES]
    /// @param directory the directory of a file index
    static Index open(String storage, Path directory) throws IOException {
        return switch (storage) {
            case MEMORY -> Index.inMemory(GeneratedText.SCHEMA);
            case FILES -> Index.open(directory, GeneratedText.SCHEMA);
            default -> throw new IllegalArgumentException("Unknown storage " + storage);
        };
    }

    /// Deletes a directory and its files.
    static void delete(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
