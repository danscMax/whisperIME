package com.whispertflite.models;

/** Immutable description of a downloadable ASR model. */
public final class ModelInfo {
    public enum Engine { TFLITE, WHISPER_CPP }

    public final String id;           // "tflite-base-topworld", "gguf-large-v3-turbo-q5"
    public final String displayName;  // "base · TOP_WORLD"
    public final Engine engine;
    public final String url;          // direct HF resolve URL
    public final long sizeBytes;
    public final String filename;     // on-disk name relative to getExternalFilesDir(null)
    public final int languages;       // 78, 99, 1
    public final boolean englishOnly;
    public final int speedClass;      // 1 fast .. 3 slow
    public final int qualityClass;    // 1 basic .. 3 best
    public final boolean heavy;       // slow on a phone CPU (gguf medium/large-class)

    public ModelInfo(String id, String displayName, Engine engine, String url, long sizeBytes,
                     String filename, int languages, boolean englishOnly,
                     int speedClass, int qualityClass) {
        this.id = id;
        this.displayName = displayName;
        this.engine = engine;
        this.url = url;
        this.sizeBytes = sizeBytes;
        this.filename = filename;
        this.languages = languages;
        this.englishOnly = englishOnly;
        this.speedClass = speedClass;
        this.qualityClass = qualityClass;
        // Derived, single source of truth: too heavy for a phone CPU. Rule = medium/large-class
        // gguf (>= ~500 MB or id names medium/large). Kept in the constructor so no call site can
        // set it inconsistently with the size/id it already passes.
        this.heavy = sizeBytes >= 500L * 1024 * 1024 || id.contains("medium") || id.contains("large");
    }

    /** True when the model is slow on a phone CPU (catalog UI shows a "slow on phone" chip). */
    public boolean isHeavy() {
        return heavy;
    }

    /** Convenience factory to keep registry entries compact. */
    public static ModelInfo of(String id, String displayName, Engine engine, String url,
                               long sizeBytes, String filename, int languages, boolean englishOnly,
                               int speedClass, int qualityClass) {
        return new ModelInfo(id, displayName, engine, url, sizeBytes, filename, languages,
                englishOnly, speedClass, qualityClass);
    }
}
