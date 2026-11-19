package be.imgn.search.langdetect.training;

import be.imgn.search.langdetect.internal.Script;
import org.jspecify.annotations.Nullable;

/// A labelled sample of text.
///
/// @param name the name of its language: [Language#name()], or the code of a language the detector does not learn
/// @param language the language the detector should return for it, or `null` if it should return none
/// @param script the script of that language, or `null` for a language the detector does not learn
/// @param length the length it was built for
/// @param text the text
record Sample(
        String name, @Nullable String language, @Nullable Script script, int length, String text) {}
