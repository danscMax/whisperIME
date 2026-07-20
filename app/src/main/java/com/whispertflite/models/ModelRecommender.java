package com.whispertflite.models;

import com.whispertflite.R;

/**
 * Picks the best-fitting model for a {@link DeviceProfile} + the user's main language, with a
 * human-readable reason for the onboarding UI.
 *
 * <p>Heuristic (adapted from SweetWhisper's desktop recommender to on-device Android): strong 64-bit
 * devices get the fast, accurate sherpa engine (Parakeet for the world, GigaAM for Russian); weak /
 * 32-bit / low-RAM devices get whisper.cpp <b>base</b> — small, 99 languages, fast on the accelerated
 * CPU backend, and silent on silence. The one rule we never break is "no fast lane → smallest capable
 * model, never the 640 MB flagship" so a weak phone doesn't freeze for a minute on its first dictation.
 */
public final class ModelRecommender {

    public final ModelInfo model;
    public final int reasonResId;   // R.string explaining WHY, shown under the recommendation

    private ModelRecommender(ModelInfo model, int reasonResId) {
        this.model = model;
        this.reasonResId = reasonResId;
    }

    /** @param preferRussian the user dictates mainly in Russian (from locale / app language). */
    public static ModelRecommender recommend(DeviceProfile d, boolean preferRussian) {
        // Weak / 32-bit / low-RAM: the safe multilingual baseline, whatever the language.
        if (d.tier() == 0) return of("gguf-base", R.string.reco_base_safe);

        if (preferRussian) {
            if (fits(d, "sherpa-gigaam-ru")) return of("sherpa-gigaam-ru", R.string.reco_gigaam_ru);
            return of("gguf-base", R.string.reco_base_safe);
        }

        // International / multilingual on a capable device: the Parakeet flagship if it fits in RAM.
        if (fits(d, "sherpa-parakeet-v3")) {
            return of("sherpa-parakeet-v3",
                    d.tier() == 2 ? R.string.reco_parakeet : R.string.reco_parakeet_mid);
        }
        return of("gguf-base", R.string.reco_base_safe);
    }

    private static ModelRecommender of(String id, int reason) {
        ModelInfo m = ModelRegistry.byId(id);
        // Registry ids are compile-time constants here; if one were ever renamed, fail loud in tests
        // rather than hand back a null model to the UI.
        if (m == null) throw new IllegalStateException("recommended model id not in registry: " + id);
        return new ModelRecommender(m, reason);
    }

    /** RAM-budget guard: a model needs ~1.5x its on-disk size resident (weights + activations). */
    static boolean fits(DeviceProfile d, String id) {
        ModelInfo m = ModelRegistry.byId(id);
        if (m == null) return false;
        if (d.ramMb == 0) return true;   // unknown RAM -> don't block (detect() should always fill it)
        long budgetMb = (long) (m.sizeBytes / (1024.0 * 1024.0) * 1.5);
        return budgetMb <= d.ramMb;
    }
}
