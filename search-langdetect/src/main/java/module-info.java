/// A language detector for the search library: [be.imgn.search.langdetect.NaiveBayesDetector], for
/// [be.imgn.search.Schema.Builder#languageDetector(be.imgn.search.LanguageDetector, double)].
///
/// Its model is trained on sentences from Tatoeba (https://tatoeba.org), released under CC BY 2.0 FR.
module be.imgn.search.langdetect {
    requires transitive be.imgn.search;

    exports be.imgn.search.langdetect;
}
