package com.whispertflite.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.whispertflite.models.ModelInfo.Engine;

/** Static catalog of all selectable models plus the shared vocab files for TFLite. */
public final class ModelRegistry {

    private ModelRegistry() {}

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
        m.add(ModelInfo.of("tflite-base-topworld", "base", Engine.TFLITE,
                TFLITE_BASE + "whisper-base.TOP_WORLD.tflite", 107564368L,
                "whisper-base.TOP_WORLD.tflite", 78, false, 1, 2));
        m.add(ModelInfo.of("tflite-small-topworld", "small", Engine.TFLITE,
                TFLITE_BASE + "whisper-small.TOP_WORLD.tflite", 307408944L,
                "whisper-small.TOP_WORLD.tflite", 78, false, 2, 2));

        // --- whisper.cpp GGUF (stored under gguf/). The Q5-quantized variants were removed (they
        // recognise noticeably worse). tiny/base/small are f16. medium/large-v3-turbo are Q8_0, NOT f16:
        // f16 medium/large (1.4-1.6 GB) exceed a phone's RAM headroom -> swap thrashing (measured 13 s +
        // corrupted output). Q8_0 is documented as no-perceptible-accuracy-loss (unlike the lossy Q5),
        // ~half the size (785/833 MB), and fits in RAM. ---
        m.add(ModelInfo.of("gguf-tiny", "tiny", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-tiny.bin", 75L * MB,
                "gguf/ggml-tiny.bin", 99, false, 1, 1));
        m.add(ModelInfo.of("gguf-base", "base", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-base.bin", 142L * MB,
                "gguf/ggml-base.bin", 99, false, 1, 2));
        m.add(ModelInfo.of("gguf-small", "small", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-small.bin", 487601967L,
                "gguf/ggml-small.bin", 99, false, 2, 2));
        m.add(ModelInfo.of("gguf-medium", "medium", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-medium-q8_0.bin", 823369779L,
                "gguf/ggml-medium-q8_0.bin", 99, false, 3, 3));
        m.add(ModelInfo.of("gguf-large-v3-turbo", "large-v3-turbo", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-large-v3-turbo-q8_0.bin", 874188075L,
                "gguf/ggml-large-v3-turbo-q8_0.bin", 99, false, 2, 3));

        // Fuller-multilingual TFLite small (99 languages vs small.TOP_WORLD's 78) — drop-in with the
        // existing multilingual vocab; wider language coverage for the international audience.
        m.add(ModelInfo.of("tflite-small-full", "small · 99 langs", Engine.TFLITE,
                TFLITE_BASE + "whisper-small.tflite", 387698368L,
                "whisper-small.tflite", 99, false, 2, 2));

        // --- sherpa-onnx (ONNX NeMo transducers): the modern engine. Multi-file (encoder/decoder/
        // joiner/tokens under a directory). ~25-40x faster than whisper.cpp on-device, with punctuation.
        // Parakeet TDT v3 = 25-language lead engine for the international audience; GigaAM RNN-T v3 =
        // Russian specialist (best RU: punctuation, capitals, ё). Both quantized int8. ---
        final String PARAKEET =
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/";
        m.add(ModelInfo.ofSherpa("sherpa-parakeet-v3", "Parakeet · 25 langs", "sherpa/parakeet-tdt-v3",
                java.util.Arrays.asList(
                        new ModelInfo.Asset(PARAKEET + "encoder.int8.onnx", "sherpa/parakeet-tdt-v3/encoder.int8.onnx", 652184281L),
                        new ModelInfo.Asset(PARAKEET + "decoder.int8.onnx", "sherpa/parakeet-tdt-v3/decoder.int8.onnx", 11845275L),
                        new ModelInfo.Asset(PARAKEET + "joiner.int8.onnx", "sherpa/parakeet-tdt-v3/joiner.int8.onnx", 6355277L),
                        new ModelInfo.Asset(PARAKEET + "tokens.txt", "sherpa/parakeet-tdt-v3/tokens.txt", 93939L)),
                25, 1, 3));

        final String GIGAAM =
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16/resolve/main/";
        m.add(ModelInfo.ofSherpa("sherpa-gigaam-ru", "GigaAM · русский", "sherpa/gigaam-rnnt-v3",
                java.util.Arrays.asList(
                        new ModelInfo.Asset(GIGAAM + "encoder.int8.onnx", "sherpa/gigaam-rnnt-v3/encoder.int8.onnx", 224570814L),
                        new ModelInfo.Asset(GIGAAM + "decoder.onnx", "sherpa/gigaam-rnnt-v3/decoder.onnx", 3331651L),
                        new ModelInfo.Asset(GIGAAM + "joiner.onnx", "sherpa/gigaam-rnnt-v3/joiner.onnx", 1440448L),
                        new ModelInfo.Asset(GIGAAM + "tokens.txt", "sherpa/gigaam-rnnt-v3/tokens.txt", 196L)),
                1, 1, 3));

        // GigaAM CTC v3 — a lighter/faster Russian option for weaker devices: a single-file NeMo CTC model
        // (no encoder/decoder/joiner split). Trades punctuation + casing for speed; ~225 MB total.
        final String GIGAAM_CTC =
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/resolve/main/";
        m.add(ModelInfo.ofSherpa("sherpa-gigaam-ctc-ru", "GigaAM CTC · русский", "sherpa/gigaam-ctc-v3",
                java.util.Arrays.asList(
                        new ModelInfo.Asset(GIGAAM_CTC + "model.int8.onnx", "sherpa/gigaam-ctc-v3/model.int8.onnx", 224721476L),
                        new ModelInfo.Asset(GIGAAM_CTC + "tokens.txt", "sherpa/gigaam-ctc-v3/tokens.txt", 196L)),
                1, 2, 2));

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
