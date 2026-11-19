package be.imgn.search.snowball;

import java.util.Arrays;

/// The articles and pronouns a language elides before a vowel, such as French `l'` in `l'arbre`, which are dropped
/// from the start of a word before stemming.
final class Elision {

    static final Elision NONE = new Elision();

    private final char[][] articles;
    private final int maxLength;

    /// @param articles the elided words, without their apostrophe
    Elision(String... articles) {
        this.articles = Arrays.stream(articles).map(String::toCharArray).toArray(char[][]::new);
        this.maxLength = Arrays.stream(articles).mapToInt(String::length).max().orElse(0);
    }

    /// Drops the elided article that starts the word, if any, moving the rest of the word to index 0.
    ///
    /// @param chars the word's characters, rewritten in place
    /// @param length the word's length
    /// @return the new length
    int strip(char[] chars, int length) {
        var end = Math.min(length, maxLength + 1);
        for (var i = 1; i < end; i++) {
            if (chars[i] == '\'') {
                // Only the first apostrophe can end an article; something must follow it.
                if (i + 1 < length && isArticle(chars, i)) {
                    var rest = length - i - 1;
                    System.arraycopy(chars, i + 1, chars, 0, rest);
                    return rest;
                }
                return length;
            }
        }
        return length;
    }

    private boolean isArticle(char[] chars, int length) {
        for (var article : articles) {
            if (Arrays.equals(article, 0, article.length, chars, 0, length)) {
                return true;
            }
        }
        return false;
    }
}
