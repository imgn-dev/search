package be.imgn.search.langdetect.internal;

import org.jspecify.annotations.Nullable;

/// The scripts the detector reads. Each has its own languages and n-gram table, since an n-gram never mixes two
/// scripts.
public enum Script {
    LATIN(1),
    GREEK(2),
    CYRILLIC(3);

    /// The index of the chars that are no letters, or letters common to several scripts, such as the modifier
    /// letter apostrophe.
    static final int NONE = 0;

    /// The index of the letters of the scripts this enum leaves out.
    static final int OTHER = 4;

    // The index of each UTF-16 unit. A table, since Character.UnicodeScript.of is a binary search.
    private static final byte[] INDEXES = indexes();

    private final int index;

    Script(int index) {
        this.index = index;
    }

    /// The index of this script, as [#indexOf(char)] gives it, and as the model format stores it.
    int index() {
        return index;
    }

    /// The script of `c` as an index: [#NONE], the [#index()] of a script of this enum, or [#OTHER].
    static int indexOf(char c) {
        return INDEXES[c];
    }

    /// The script of an index [#indexOf(char)] gives.
    ///
    /// @return the script, or `null` for [#NONE], [#OTHER] and any other index
    static @Nullable Script ofIndex(int index) {
        for (var script : values()) {
            if (script.index == index) {
                return script;
            }
        }
        return null;
    }

    private static byte[] indexes() {
        var indexes = new byte[Character.MAX_VALUE + 1];
        for (var c = 0; c <= Character.MAX_VALUE; c++) {
            if (Character.isLetter(c)) {
                indexes[c] = (byte)
                        switch (Character.UnicodeScript.of(c)) {
                            case LATIN -> LATIN.index;
                            case GREEK -> GREEK.index;
                            case CYRILLIC -> CYRILLIC.index;
                            case COMMON, INHERITED, UNKNOWN -> NONE;
                            default -> OTHER;
                        };
            }
        }
        return indexes;
    }
}
