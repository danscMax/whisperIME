package com.whispertflite.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable description of a downloadable ASR model. */
public final class ModelInfo {
    public enum Engine { TFLITE, WHISPER_CPP, SHERPA }

    /**
     * One downloadable file of a model. Single-file models (TFLite / whisper.cpp) have exactly one;
     * sherpa-onnx models have several (encoder / decoder / joiner / tokens). {@code relPath} is the
     * on-disk path relative to {@code getExternalFilesDir(null)}.
     */
    public static final class Asset {
        public final String url;
        public final String relPath;
        public final long size;
        /** Expected SHA-256 as lowercase hex, or null to skip the integrity check (backward compatible). */
        public final String sha256;

        public Asset(String url, String relPath, long size) {
            this(url, relPath, size, null);
        }

        public Asset(String url, String relPath, long size, String sha256) {
            this.url = url;
            this.relPath = relPath;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public final String id;           // "tflite-base-topworld", "sherpa-parakeet-v3"
    public final String displayName;  // "base · TOP_WORLD"
    public final Engine engine;
    public final String url;          // primary file URL (single-file: the file; sherpa: files.get(0))
    public final long sizeBytes;      // total across all files
    public final String filename;     // on-disk path relative to getExternalFilesDir(null): a FILE (single) or a DIRECTORY (sherpa)
    public final List<Asset> files;   // all downloadable files (>= 1) — source of truth for download + presence
    public final int languages;       // 78, 99, 25, 1
    public final boolean englishOnly;
    public final int speedClass;      // 1 fast .. 3 slow
    public final int qualityClass;    // 1 basic .. 3 best
    public final boolean heavy;       // slow on a phone CPU (gguf medium/large-class)

    /** Single-file model (TFLite / whisper.cpp). */
    public ModelInfo(String id, String displayName, Engine engine, String url, long sizeBytes,
                     String filename, int languages, boolean englishOnly,
                     int speedClass, int qualityClass) {
        this.id = id;
        this.displayName = displayName;
        this.engine = engine;
        this.url = url;
        this.sizeBytes = sizeBytes;
        this.filename = filename;
        this.files = Collections.singletonList(new Asset(url, filename, sizeBytes));
        this.languages = languages;
        this.englishOnly = englishOnly;
        this.speedClass = speedClass;
        this.qualityClass = qualityClass;
        // Derived, single source of truth: too heavy for a phone CPU. Rule = medium/large-class
        // gguf (>= ~500 MB or id names medium/large). Kept in the constructor so no call site can
        // set it inconsistently with the size/id it already passes.
        this.heavy = sizeBytes >= 500L * 1024 * 1024 || id.contains("medium") || id.contains("large");
    }

    /** Multi-file model (sherpa-onnx: encoder/decoder/joiner/tokens under {@code dirName}). */
    private ModelInfo(String id, String displayName, Engine engine, String dirName,
                      List<Asset> files, int languages, boolean englishOnly,
                      int speedClass, int qualityClass) {
        long sum = 0;
        for (Asset a : files) sum += a.size;
        this.id = id;
        this.displayName = displayName;
        this.engine = engine;
        this.url = files.isEmpty() ? "" : files.get(0).url;
        this.sizeBytes = sum;
        this.filename = dirName;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        this.languages = languages;
        this.englishOnly = englishOnly;
        this.speedClass = speedClass;
        this.qualityClass = qualityClass;
        // sherpa-onnx transducers run at RTF ~0.03-0.04 on a phone regardless of file size (Parakeet is
        // 672 MB yet fast), so the gguf ">= 500 MB = slow" rule does not apply — never flag them heavy.
        this.heavy = false;
    }

    /** True when the model is slow on a phone CPU (catalog UI shows a "slow on phone" chip). */
    public boolean isHeavy() {
        return heavy;
    }

    /** Convenience factory to keep single-file registry entries compact. */
    public static ModelInfo of(String id, String displayName, Engine engine, String url,
                               long sizeBytes, String filename, int languages, boolean englishOnly,
                               int speedClass, int qualityClass) {
        return new ModelInfo(id, displayName, engine, url, sizeBytes, filename, languages,
                englishOnly, speedClass, qualityClass);
    }

    /** Factory for a multi-file sherpa-onnx model: {@code dirName} is the on-disk directory, {@code files} its contents. */
    public static ModelInfo ofSherpa(String id, String displayName, String dirName,
                                     List<Asset> files, int languages,
                                     int speedClass, int qualityClass) {
        return new ModelInfo(id, displayName, Engine.SHERPA, dirName, files, languages,
                false, speedClass, qualityClass);
    }
}
