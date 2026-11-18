package be.imgn.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// The storages of an index, for the tests that run on both.
enum StorageKind {
    MEMORY {
        @Override
        Index open(Schema schema, Path directory) {
            return Index.inMemory(schema);
        }
    },
    FILES {
        @Override
        Index open(Schema schema, Path directory) throws IOException {
            return Index.open(Files.createTempDirectory(directory, "index"), schema);
        }
    };

    /// A new, empty index.
    ///
    /// @param schema the schema
    /// @param directory a temporary directory, in which a file index takes a directory of its own
    /// @return the index
    abstract Index open(Schema schema, Path directory) throws IOException;
}
