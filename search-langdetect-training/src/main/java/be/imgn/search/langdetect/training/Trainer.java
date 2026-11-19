package be.imgn.search.langdetect.training;

import be.imgn.search.langdetect.internal.Alphabet;
import be.imgn.search.langdetect.internal.Model;
import be.imgn.search.langdetect.internal.NGrams;
import be.imgn.search.langdetect.internal.Script;
import be.imgn.search.langdetect.internal.ScriptModel;
import be.imgn.search.langdetect.internal.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SortedMap;
import java.util.TreeMap;

/// Learns the costs of a model from normalized sentences: counts their n-grams per language, keeps the most
/// frequent of each language, and turns the counts into smoothed, quantized costs.
///
/// Every computation is deterministic, logarithms included ([StrictMath]), so that the same sentences always give
/// the same model.
final class Trainer {

    /// The cost units per nat: costs are bytes, so the highest cost is 25.5 nats.
    static final float UNITS_PER_NAT = 10;

    /// The pseudo-count added to every n-gram of every language (additive smoothing).
    static final double ALPHA = 0.5;

    /// A char is in the alphabet if one language uses it at least this many times.
    static final int MIN_CHAR_COUNT = 10;

    private static final int MAX_COST = 255;

    /// The number of the most frequent n-grams of each order each language keeps, from 1-grams on.
    private final int[] kept;

    /// Creates a trainer keeping the `kept[order - 1]` most frequent n-grams of each order in each language.
    Trainer(int... kept) {
        if (kept.length != NGrams.MAX_ORDER) {
            throw new IllegalArgumentException("A trainer keeps a number of n-grams per order");
        }
        this.kept = kept.clone();
    }

    /// Learns a model, not yet fitted ([ScriptModel.Fit#NONE]), from the normalized sentences of each language,
    /// leaving out the languages without sentences.
    Model train(SequencedMap<Language, List<Text>> sentences) {
        var alphabet = alphabet(sentences);
        var scripts = new ArrayList<ScriptModel>();
        for (var script : Script.values()) {
            var languages = sentences.keySet().stream()
                    // A language without sentences would win every text: its unseen n-grams cost little.
                    .filter(language -> language.script() == script
                            && !sentences.getOrDefault(language, List.of()).isEmpty())
                    .toList();
            if (!languages.isEmpty()) {
                var texts = languages.stream()
                        .map(language -> sentences.getOrDefault(language, List.of()))
                        .toList();
                scripts.add(trainScript(script, languages, texts, alphabet));
            }
        }
        return new Model(alphabet, UNITS_PER_NAT, scripts);
    }

    private static Alphabet alphabet(Map<Language, List<Text>> sentences) {
        var max = new int[Character.MAX_VALUE + 1];
        var total = new long[Character.MAX_VALUE + 1];
        for (var texts : sentences.values()) {
            var counts = new int[Character.MAX_VALUE + 1];
            for (var text : texts) {
                var chars = text.chars();
                for (var i = 0; i < text.length(); i++) {
                    counts[chars[i]]++;
                }
            }
            for (var c = 0; c <= Character.MAX_VALUE; c++) {
                max[c] = Math.max(max[c], counts[c]);
                total[c] += counts[c];
            }
        }
        var chars = new ArrayList<Character>();
        for (var c = 0; c <= Character.MAX_VALUE; c++) {
            if (c != ' ' && max[c] >= MIN_CHAR_COUNT) {
                chars.add((char) c);
            }
        }
        if (chars.size() > Alphabet.MAX_SIZE) {
            chars.sort(Comparator.comparingLong((Character c) -> -total[c]).thenComparing(c -> c));
            chars.subList(Alphabet.MAX_SIZE, chars.size()).clear();
            chars.sort(null);
        }
        var array = new char[chars.size()];
        for (var i = 0; i < array.length; i++) {
            array[i] = chars.get(i);
        }
        return new Alphabet(array);
    }

    private ScriptModel trainScript(
            Script script, List<Language> languages, List<List<Text>> texts, Alphabet alphabet) {
        var size = languages.size();
        var counts = new HashMap<Integer, int[]>();
        var totals = new long[NGrams.MAX_ORDER][size];
        for (var language = 0; language < size; language++) {
            for (var text : texts.get(language)) {
                var keys = new int[NGrams.capacity(text.length())];
                var count = NGrams.keys(text, alphabet, keys);
                for (var i = 0; i < count; i++) {
                    counts.computeIfAbsent(keys[i], key -> new int[size])[language]++;
                    totals[NGrams.order(keys[i]) - 1][language]++;
                }
            }
        }
        var distinct = new long[NGrams.MAX_ORDER];
        for (var key : counts.keySet()) {
            distinct[NGrams.order(key) - 1]++;
        }
        var selected = select(counts, size);

        var defaults = new byte[NGrams.MAX_ORDER * size];
        for (var order = 1; order <= NGrams.MAX_ORDER; order++) {
            for (var language = 0; language < size; language++) {
                defaults[(order - 1) * size + language] = cost(0, totals[order - 1][language], distinct[order - 1]);
            }
        }
        var keys = new int[selected.size()];
        var costs = new byte[selected.size() * size];
        var row = 0;
        for (var entry : selected.entrySet()) {
            keys[row] = entry.getKey();
            var order = NGrams.order(entry.getKey());
            for (var language = 0; language < size; language++) {
                costs[row * size + language] =
                        cost(entry.getValue()[language], totals[order - 1][language], distinct[order - 1]);
            }
            row++;
        }
        var unfitted = languages.stream()
                .map(language -> new ScriptModel.Language(language.tag(), Float.POSITIVE_INFINITY))
                .toList();
        return new ScriptModel(script, unfitted, ScriptModel.Fit.NONE, defaults, keys, costs);
    }

    /// The n-grams among the most frequent of their order in at least one language, with their counts, by key.
    private SortedMap<Integer, int[]> select(Map<Integer, int[]> counts, int size) {
        var selected = new TreeMap<Integer, int[]>();
        for (var language = 0; language < size; language++) {
            for (var order = 1; order <= NGrams.MAX_ORDER; order++) {
                var finalLanguage = language;
                var finalOrder = order;
                counts.entrySet().stream()
                        .filter(entry ->
                                NGrams.order(entry.getKey()) == finalOrder && entry.getValue()[finalLanguage] > 0)
                        .sorted(Comparator.comparingInt(
                                        (Map.Entry<Integer, int[]> entry) -> -entry.getValue()[finalLanguage])
                                .thenComparing(Map.Entry::getKey))
                        .limit(kept[order - 1])
                        .forEach(entry -> selected.put(entry.getKey(), entry.getValue()));
            }
        }
        return selected;
    }

    /// The quantized cost of an n-gram seen `count` times among the `total` n-grams of its order in a language,
    /// with `distinct` n-grams of that order in all the languages of the script.
    private static byte cost(int count, long total, long distinct) {
        var probability = (count + ALPHA) / (total + ALPHA * distinct);
        var cost = Math.round(-StrictMath.log(probability) * UNITS_PER_NAT);
        return (byte) Math.min(MAX_COST, cost);
    }
}
