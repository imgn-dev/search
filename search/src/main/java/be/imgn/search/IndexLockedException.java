package be.imgn.search;

import java.io.IOException;
import java.io.Serial;

/// Thrown by [Index#open] when the index is open already: by another process, or by this one. One process opens
/// a file index at a time, through a lock on its `write.lock` file held until the index is closed.
///
/// @since 0.1.0
public final class IndexLockedException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// An exception with a message.
    ///
    /// @param message which index is locked, and by whom
    public IndexLockedException(String message) {
        super(message);
    }

    /// An exception with a message and a cause.
    ///
    /// @param message which index is locked, and by whom
    /// @param cause the cause
    public IndexLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
