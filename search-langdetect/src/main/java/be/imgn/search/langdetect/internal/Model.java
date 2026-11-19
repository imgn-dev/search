package be.imgn.search.langdetect.internal;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// A language model: the alphabet and the model of each script.
public final class Model {

    private final Alphabet alphabet;
    private final float unitsPerNat;
    private final Map<Script, ScriptModel> scripts = new EnumMap<>(Script.class);

    /// Creates a model.
    ///
    /// @param unitsPerNat the number of cost units in a nat
    /// @param scripts the models of the scripts, at most one per script
    public Model(Alphabet alphabet, float unitsPerNat, List<ScriptModel> scripts) {
        if (!(unitsPerNat > 0)) {
            throw new IllegalArgumentException("A model has a positive number of units per nat");
        }
        this.alphabet = alphabet;
        this.unitsPerNat = unitsPerNat;
        for (var model : scripts) {
            if (this.scripts.put(model.script(), model) != null) {
                throw new IllegalArgumentException("A model has one model per script");
            }
        }
    }

    /// The alphabet.
    public Alphabet alphabet() {
        return alphabet;
    }

    /// The number of cost units in a nat.
    public float unitsPerNat() {
        return unitsPerNat;
    }

    /// The models of the scripts, in the order of [Script].
    public List<ScriptModel> scripts() {
        return List.copyOf(scripts.values());
    }

    /// The model of `script`.
    ///
    /// @return the model, or `null` if this model knows no language in `script`
    public @Nullable ScriptModel script(Script script) {
        return scripts.get(script);
    }
}
