package be.imgn.search;

import java.io.IOException;
import java.io.Serial;

/// Thrown when the files of an index do not read as they were written: a wrong magic number or format version, a
/// truncated file, or a checksum that does not match. [Index#open] checks the manifest and the deletion files in
/// full, and the header and footer of the segment files; [Index#check()] checks the segments in full.
///
/// @since 0.1.0
public final class CorruptIndexException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// An exception with a message.
    ///
    /// @param message which file is corrupt, and how
    public CorruptIndexException(String message) {
        super(message);
    }

    /// An exception with a message and a cause.
    ///
    /// @param message which file is corrupt, and how
    /// @param cause the cause
    public CorruptIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
