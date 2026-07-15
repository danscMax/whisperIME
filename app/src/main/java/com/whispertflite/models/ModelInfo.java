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
    }

    /** Convenience factory to keep registry entries compact. */
    public static ModelInfo of(String id, String displayName, Engine engine, String url,
                               long sizeBytes, String filename, int languages, boolean englishOnly,
                               int speedClass, int qualityClass) {
        return new ModelInfo(id, displayName, engine, url, sizeBytes, filename, languages,
                englishOnly, speedClass, qualityClass);
    }
}
