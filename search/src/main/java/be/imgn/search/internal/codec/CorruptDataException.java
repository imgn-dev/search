package be.imgn.search.internal.codec;

import java.io.Serial;

/// Thrown when encoded data does not read as the format says: a wrong magic number or version, a bad checksum, or
/// a compressed block that does not inflate.
public final class CorruptDataException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// An exception with a message.
    ///
    /// @param message what is wrong
    public CorruptDataException(String message) {
        super(message);
    }

    /// An exception with a message and a cause.
    ///
    /// @param message what is wrong
    /// @param cause the cause
    public CorruptDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
