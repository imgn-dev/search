package be.imgn.search.langdetect.training;

import be.imgn.search.langdetect.internal.Script;
import java.util.List;
import java.util.Set;

/// A language the detector learns in one script, and its codes in the corpus.
///
/// @param tag the language tag the detector returns
/// @param script the script of its sentences; a language written in two scripts is learnt as two
/// @param codes its ISO 639-3 codes in the corpus
record Language(String tag, Script script, Set<String> codes) {

    /// The languages the detector learns, Serbian in both its scripts.
    static final List<Language> ALL = List.of(
            latin("eu", "eus"),
            latin("ca", "cat"),
            latin("cs", "ces"),
            latin("da", "dan"),
            latin("nl", "nld"),
            latin("en", "eng"),
            latin("eo", "epo"),
            latin("et", "est"),
            latin("fi", "fin"),
            latin("fr", "fra"),
            latin("de", "deu"),
            new Language("el", Script.GREEK, Set.of("ell")),
            latin("hu", "hun"),
            latin("ga", "gle"),
            latin("it", "ita"),
            latin("lt", "lit"),
            new Language("no", Script.LATIN, Set.of("nob", "nno")),
            latin("pl", "pol"),
            latin("pt", "por"),
            latin("ro", "ron"),
            cyrillic("ru", "rus"),
            latin("sr", "srp"),
            cyrillic("sr", "srp"),
            latin("es", "spa"),
            latin("sv", "swe"),
            cyrillic("uk", "ukr"));

    /// The codes of the languages the detector does not learn, whose samples test that it rejects them: languages
    /// unrelated to the European ones, in the Latin script but for Kazakh, in Cyrillic.
    static final List<String> UNKNOWN = List.of("tur", "ind", "vie", "tgl", "swh", "kaz");

    Language {
        codes = Set.copyOf(codes);
    }

    /// The name of this language in the samples: its codes, with the script for a language learnt in two.
    String name() {
        var code = String.join("+", codes.stream().sorted().toList());
        return "sr".equals(tag) ? code + "-" + (script == Script.LATIN ? "Latn" : "Cyrl") : code;
    }

    private static Language latin(String tag, String code) {
        return new Language(tag, Script.LATIN, Set.of(code));
    }

    private static Language cyrillic(String tag, String code) {
        return new Language(tag, Script.CYRILLIC, Set.of(code));
    }
}
