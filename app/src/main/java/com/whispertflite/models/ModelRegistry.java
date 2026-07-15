package com.whispertflite.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.whispertflite.models.ModelInfo.Engine;

/** Static catalog of all selectable models plus the shared vocab files for TFLite. */
public final class ModelRegistry {

    private ModelRegistry() {}

    /** whisper.cpp engine is not wired yet (Task 3.3 flips this to true). */
    public static final boolean WHISPER_CPP_READY = false;

    // Base URLs.
    private static final String TFLITE_BASE =
            "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/";
    private static final String GGUF_BASE =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    // Vocab files shipped as assets; TFLite models need them present in the external dir.
    // They have no download URL (bundled in the APK, copied from assets when missing).
    public static final String ENGLISH_VOCAB = "filters_vocab_en.bin";
    public static final String MULTILINGUAL_VOCAB = "filters_vocab_multilingual.bin";

    private static final long MB = 1024L * 1024L;

    private static final List<ModelInfo> ALL = Collections.unmodifiableList(build());

    private static List<ModelInfo> build() {
        List<ModelInfo> m = new ArrayList<>();

        // --- TFLite (byte sizes are the real file sizes from the legacy Downloader) ---
        m.add(ModelInfo.of("tflite-tiny-en", "tiny · English", Engine.TFLITE,
                TFLITE_BASE + "whisper-tiny.en.tflite", 41486616L,
                "whisper-tiny.en.tflite", 1, true, 1, 1));
        m.add(ModelInfo.of("tflite-base-topworld", "base · TOP_WORLD", Engine.TFLITE,
                TFLITE_BASE + "whisper-base.TOP_WORLD.tflite", 107564368L,
                "whisper-base.TOP_WORLD.tflite", 78, false, 1, 2));
        m.add(ModelInfo.of("tflite-small-topworld", "small · TOP_WORLD", Engine.TFLITE,
                TFLITE_BASE + "whisper-small.TOP_WORLD.tflite", 307408944L,
                "whisper-small.TOP_WORLD.tflite", 78, false, 2, 2));

        // --- whisper.cpp GGUF (stored under gguf/ subdir; sizes approximate for display) ---
        m.add(ModelInfo.of("gguf-tiny", "tiny", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-tiny.bin", 75L * MB,
                "gguf/ggml-tiny.bin", 99, false, 1, 1));
        m.add(ModelInfo.of("gguf-base", "base", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-base.bin", 142L * MB,
                "gguf/ggml-base.bin", 99, false, 1, 2));
        m.add(ModelInfo.of("gguf-small", "small", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-small.bin", 466L * MB,
                "gguf/ggml-small.bin", 99, false, 2, 2));
        m.add(ModelInfo.of("gguf-medium-q5", "medium · Q5", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-medium-q5_0.bin", 514L * MB,
                "gguf/ggml-medium-q5_0.bin", 99, false, 3, 3));
        m.add(ModelInfo.of("gguf-large-v3-turbo-q5", "large-v3-turbo · Q5", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-large-v3-turbo-q5_0.bin", 547L * MB,
                "gguf/ggml-large-v3-turbo-q5_0.bin", 99, false, 2, 3));

        return m;
    }

    public static List<ModelInfo> all() {
        return ALL;
    }

    public static ModelInfo byId(String id) {
        for (ModelInfo m : ALL) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    /** Vocab file a TFLite model relies on (english-only vs multilingual). */
    public static String vocabFor(ModelInfo model) {
        return model.englishOnly ? ENGLISH_VOCAB : MULTILINGUAL_VOCAB;
    }
}
