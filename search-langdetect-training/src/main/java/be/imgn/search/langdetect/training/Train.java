package be.imgn.search.langdetect.training;

import be.imgn.search.langdetect.internal.ModelFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Trains the language model on Tatoeba's sentences, and writes it with the held-out samples of the tests.
///
/// Usage: `Train <sentences.csv> <model> <samples>`, where `sentences.csv` is the path of Tatoeba's unpacked
/// export, `model` the model to write and `samples` the held-out samples to write. The sentences are split by a
/// hash of their normalized text into training, validation and test sentences ([Split]); the model learns from the
/// training sentences ([Pools], [Trainer]), is fitted on samples of the validation sentences and on the register
/// texts ([Evaluation], [Register]), and the samples written come from the test sentences. The same corpus always
/// gives the same files. The tool prints the model's size and fit, its accuracy on the register texts and on the
/// validation and test samples, and the validation counts the tests derive their minimums from.
public final class Train {

    /// The number of the most frequent 1-, 2- and 3-grams of each language the model keeps.
    static final int[] KEPT = {Integer.MAX_VALUE, 1_500, 5_000};

    /// The number of validation samples per language for each sample length.
    static final List<Integer> VALIDATION_SAMPLES = List.of(300, 200, 100);

    /// The number of test samples per language for each sample length.
    static final List<Integer> TEST_SAMPLES = List.of(100, 50, 20);

    private static final int ARGUMENTS = 3;
    private static final int USAGE = 2;

    private Train() {}

    /// Runs the tool.
    ///
    /// @param args the path of `sentences.csv`, of the model to write and of the samples to write
    public static void main(String[] args) throws IOException {
        if (args.length != ARGUMENTS) {
            System.err.println("Usage: Train <sentences.csv> <model> <samples>");
            System.exit(USAGE);
        }
        run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Register.read());
    }

    /// Trains a model on the corpus at `corpus`, fitting it to keep the `register` texts, and writes it to `model`
    /// with the held-out samples to `samples`.
    static void run(Path corpus, Path model, Path samples, List<Sample> register) throws IOException {
        var pools = Pools.read(corpus);
        var trained = new Trainer(KEPT).train(pools.training());
        var validation = Samples.of(pools.validation(), VALIDATION_SAMPLES);
        var fitted = Evaluation.fit(trained, validation, register);
        var bytes = ModelFormat.write(fitted.model());
        var written = ModelFormat.read(bytes);
        var test = Samples.of(pools.test(), TEST_SAMPLES);

        var report = new Report(System.out);
        report.model(written, bytes.length, pools.training());
        report.tradeOffs(fitted.tradeOffs());
        report.register(written, register);
        var validated = report.accuracy("Validation", written, validation);
        report.accuracy("Test", written, test);

        Files.write(model, bytes);
        Samples.write(samples, test, validated);
    }
}
