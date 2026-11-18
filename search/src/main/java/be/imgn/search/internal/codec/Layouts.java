package be.imgn.search.internal.codec;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/// The layouts every segment is read with: unaligned, since a heap segment is aligned on one byte only and a mapped
/// one is read at odd offsets, and little-endian whatever the platform's order.
public final class Layouts {

    /// A little-endian `int` at any offset.
    public static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// A little-endian `long` at any offset.
    public static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// A byte.
    public static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private Layouts() {}
}
