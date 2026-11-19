package be.imgn.search.snowball;

import static java.util.Objects.requireNonNull;

import be.imgn.search.TermBuffer;
import be.imgn.search.WordAnalyzer;
import be.imgn.search.snowball.internal.SnowballStemmer;

/// Elision, then a Snowball stemmer that works on the word's own array, so that a word allocates nothing unless
/// stemming makes it longer than the buffer's capacity.
final class SnowballWordAnalyzer implements WordAnalyzer {

    private final SnowballStemmer stemmer;
    private final Elision elision;

    SnowballWordAnalyzer(SnowballStemmer stemmer, Elision elision) {
        this.stemmer = stemmer;
        this.elision = elision;
    }

    @Override
    public void analyze(TermBuffer word) {
        requireNonNull(word, "word");
        var chars = word.chars();
        var length = elision.strip(chars, word.length());
        stemmer.setCurrent(chars, length);
        stemmer.stem();
        // The stemmer rewrites the word's own array, unless the word outgrows it: the copy is then needed, and
        // otherwise moves nothing.
        word.set(stemmer.getCurrentBuffer(), 0, stemmer.getCurrentBufferLength());
    }
}
