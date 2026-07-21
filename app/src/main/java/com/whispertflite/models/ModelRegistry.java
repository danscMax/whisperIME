package com.whispertflite.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.whispertflite.R;
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
                "whisper-tiny.en.tflite", 1, true, 1, 1)
                .withDisplayNameRes(R.string.model_tflite_tiny_en));
        m.add(ModelInfo.of("tflite-base-topworld", "base", Engine.TFLITE,
                TFLITE_BASE + "whisper-base.TOP_WORLD.tflite", 107564368L,
                "whisper-base.TOP_WORLD.tflite", 78, false, 1, 2)
                .withDisplayNameRes(R.string.model_tflite_base));
        m.add(ModelInfo.of("tflite-small-topworld", "small", Engine.TFLITE,
                TFLITE_BASE + "whisper-small.TOP_WORLD.tflite", 307408944L,
                "whisper-small.TOP_WORLD.tflite", 78, false, 2, 2)
                .withDisplayNameRes(R.string.model_tflite_small));

        // --- whisper.cpp GGUF (stored under gguf/). The Q5-quantized variants were removed (they
        // recognise noticeably worse). tiny/base/small are f16. medium/large-v3-turbo are Q8_0, NOT f16:
        // f16 medium/large (1.4-1.6 GB) exceed a phone's RAM headroom -> swap thrashing (measured 13 s +
        // corrupted output). Q8_0 is documented as no-perceptible-accuracy-loss (unlike the lossy Q5),
        // ~half the size (785/833 MB), and fits in RAM. ---
        m.add(ModelInfo.of("gguf-tiny", "tiny", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-tiny.bin", 75L * MB,
                "gguf/ggml-tiny.bin", 99, false, 1, 1)
                .withDisplayNameRes(R.string.model_gguf_tiny));
        m.add(ModelInfo.of("gguf-base", "base", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-base.bin", 142L * MB,
                "gguf/ggml-base.bin", 99, false, 1, 2)
                .withDisplayNameRes(R.string.model_gguf_base));
        m.add(ModelInfo.of("gguf-small", "small", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-small.bin", 487601967L,
                "gguf/ggml-small.bin", 99, false, 2, 2)
                .withDisplayNameRes(R.string.model_gguf_small));
        m.add(ModelInfo.of("gguf-medium", "medium", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-medium-q8_0.bin", 823369779L,
                "gguf/ggml-medium-q8_0.bin", 99, false, 3, 3)
                .withDisplayNameRes(R.string.model_gguf_medium));
        m.add(ModelInfo.of("gguf-large-v3-turbo", "large-v3-turbo", Engine.WHISPER_CPP,
                GGUF_BASE + "ggml-large-v3-turbo-q8_0.bin", 874188075L,
                "gguf/ggml-large-v3-turbo-q8_0.bin", 99, false, 2, 3)
                .withDisplayNameRes(R.string.model_gguf_large));

        // Fuller-multilingual TFLite small (99 languages vs small.TOP_WORLD's 78) — drop-in with the
        // existing multilingual vocab; wider language coverage for the international audience.
        m.add(ModelInfo.of("tflite-small-full", "small · 99 langs", Engine.TFLITE,
                TFLITE_BASE + "whisper-small.tflite", 387698368L,
                "whisper-small.tflite", 99, false, 2, 2)
                .withDisplayNameRes(R.string.model_tflite_small_full));

        // --- sherpa-onnx (ONNX NeMo transducers): the modern engine. Multi-file (encoder/decoder/
        // joiner/tokens under a directory). ~25-40x faster than whisper.cpp on-device, with punctuation.
        // Parakeet TDT v3 = 25-language lead engine for the international audience; GigaAM RNN-T v3 =
        // Russian specialist (best RU: punctuation, capitals, ё). Both quantized int8. ---
        final String PARAKEET =
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/";
        m.add(ModelInfo.ofSherpa("sherpa-parakeet-v3", "Parakeet · 25 langs", "sherpa/parakeet-tdt-v3",
                java.util.Arrays.asList(
                        new ModelInfo.Asset(PARAKEET + "encoder.int8.onnx", "sherpa/parakeet-tdt-v3/encoder.int8.onnx", 652184281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                        new ModelInfo.Asset(PARAKEET + "decoder.int8.onnx", "sherpa/parakeet-tdt-v3/decoder.int8.onnx", 11845275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                        new ModelInfo.Asset(PARAKEET + "joiner.int8.onnx", "sherpa/parakeet-tdt-v3/joiner.int8.onnx", 6355277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                        new ModelInfo.Asset(PARAKEET + "tokens.txt", "sherpa/parakeet-tdt-v3/tokens.txt", 93939L)),
                25, 1, 3)
                .withDisplayNameRes(R.string.model_parakeet));

        // GigaAM RNN-T v3 (punct) — Russian, highest quality. The "-punct" export emits punctuation +
        // casing natively (plain transducers output raw lowercase); the enlarged tokens.txt is the punct
        // vocab. Fresh dir name so a device holding the old no-punct model re-downloads this one.
        final String GIGAAM =
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16/resolve/main/";
        m.add(ModelInfo.ofSherpa("sherpa-gigaam-ru", "GigaAM · русский", "sherpa/gigaam-rnnt-punct-v3",
                java.util.Arrays.asList(
                        new ModelInfo.Asset(GIGAAM + "encoder.int8.onnx", "sherpa/gigaam-rnnt-punct-v3/encoder.int8.onnx", 224570820L, "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
                        new ModelInfo.Asset(GIGAAM + "decoder.onnx", "sherpa/gigaam-rnnt-punct-v3/decoder.onnx", 4600132L, "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
                        new ModelInfo.Asset(GIGAAM + "joiner.onnx", "sherpa/gigaam-rnnt-punct-v3/joiner.onnx", 2712896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
                        new ModelInfo.Asset(GIGAAM + "tokens.txt", "sherpa/gigaam-rnnt-punct-v3/tokens.txt", 13354L)),
                1, 1, 3)
                .withDisplayNameRes(R.string.model_gigaam_rnnt));

        // GigaAM CTC v3 (punct) — Russian, single-file NeMo CTC (no encoder/decoder/joiner split): faster
        // than the transducer, now ALSO with punctuation + casing via the "-punct" export; ~225 MB total.
        final String GIGAAM_CTC =
                "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/resolve/main/";
        m.add(ModelInfo.ofSherpa("sherpa-gigaam-ctc-ru", "GigaAM CTC · русский", "sherpa/gigaam-ctc-punct-v3",
                java.util.Arrays.asList(
                        new ModelInfo.Asset(GIGAAM_CTC + "model.int8.onnx", "sherpa/gigaam-ctc-punct-v3/model.int8.onnx", 224893661L, "d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35"),
                        new ModelInfo.Asset(GIGAAM_CTC + "tokens.txt", "sherpa/gigaam-ctc-punct-v3/tokens.txt", 2007L)),
                1, 2, 2)
                .withDisplayNameRes(R.string.model_gigaam_ctc));

        // GigaAM RNN-T v3 (e2e, FP32) — full-precision "max quality" A/B option: SAME architecture as the
        // int8 RNN-T above, no quantization (~886 MB encoder, slower inference). Same punctuating tokens, so
        // it isolates the pure FP32-vs-int8 effect. Local files use the engine's expected names; the URLs
        // point at Smirnov75's e2e RNN-T export. SherpaEngine detects the fp32 encoder.onnx.
        final String GIGAAM_FP32 =
                "https://huggingface.co/Smirnov75/GigaAM-v3-sherpa-onnx/resolve/main/";
        m.add(ModelInfo.ofSherpa("sherpa-gigaam-fp32", "GigaAM · русский · FP32", "sherpa/gigaam-rnnt-e2e-fp32",
                java.util.Arrays.asList(
                        new ModelInfo.Asset(GIGAAM_FP32 + "gigaam_v3_e2e_rnnt_encoder.onnx", "sherpa/gigaam-rnnt-e2e-fp32/encoder.onnx", 885084898L, "a1a1bd82caa1507cd9e1e85c7fabf09b96f139640f3f4694de380e3e8a376c6a"),
                        new ModelInfo.Asset(GIGAAM_FP32 + "gigaam_v3_e2e_rnnt_decoder.onnx", "sherpa/gigaam-rnnt-e2e-fp32/decoder.onnx", 4600058L, "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860"),
                        new ModelInfo.Asset(GIGAAM_FP32 + "gigaam_v3_e2e_rnnt_joint.onnx", "sherpa/gigaam-rnnt-e2e-fp32/joiner.onnx", 2712896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
                        new ModelInfo.Asset(GIGAAM_FP32 + "gigaam_v3_e2e_rnnt_tokens.txt", "sherpa/gigaam-rnnt-e2e-fp32/tokens.txt", 13353L)),
                1, 1, 3)
                .withDisplayNameRes(R.string.model_gigaam_fp32));

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
