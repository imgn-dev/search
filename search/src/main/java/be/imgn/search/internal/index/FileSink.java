package be.imgn.search.internal.index;

import be.imgn.search.internal.codec.ByteOutput;
import be.imgn.search.internal.codec.Output;
import be.imgn.search.internal.segment.SegmentSink;
import be.imgn.search.internal.store.Directory;
import be.imgn.search.internal.store.FileOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A sink that writes a segment into its file, without forcing it. The sections of a large segment go to
/// temporary files, `<segment>.<n>.tmp`, so that a merge holds little on the heap whatever its size; those of a
/// small one, as a flush makes, stay on the heap. Not thread-safe.
final class FileSink implements SegmentSink {

    private final Directory directory;
    private final String name;
    private final boolean large;
    private final List<FileOutput> sections = new ArrayList<>();
    private @Nullable FileOutput segment;

    /// A sink of the segment file `name`.
    ///
    /// @param directory the directory
    /// @param name the segment file's name
    /// @param large whether the sections go to temporary files
    FileSink(Directory directory, String name, boolean large) {
        this.directory = directory;
        this.name = name;
        this.large = large;
    }

    @Override
    public Output newSection() {
        if (!large) {
            return new ByteOutput();
        }
        try {
            var section = new FileOutput(directory, name + "." + sections.size() + FileStorage.TEMPORARY);
            sections.add(section);
            return section;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Output newSegment(long sizeHint) {
        try {
            var created = new FileOutput(directory, name);
            segment = created;
            return created;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Closes the segment's file and deletes the temporary ones.
    ///
    /// @param keep whether to keep the segment's file, rather than delete it: the segment was written in full
    /// @throws IOException if a file cannot be closed or deleted
    void close(boolean keep) throws IOException {
        IOException failure = null;
        var outputs = new ArrayList<FileOutput>(sections);
        var written = segment;
        if (written != null) {
            outputs.add(written);
        }
        for (var output : outputs) {
            try {
                output.close();
                if (output != written || !keep) {
                    directory.delete(output.name());
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
