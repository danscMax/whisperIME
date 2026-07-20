package com.whispertflite.engine

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.whispertflite.asr.Transcript
import com.whispertflite.asr.Whisper
import com.whispertflite.asr.WhisperResult
import com.whispertflite.models.ModelInfo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * sherpa-onnx path: NeMo transducer ONNX models (Parakeet TDT, GigaAM RNN-T). One recognizer serves
 * both — Parakeet ships int8 decoder/joiner, GigaAM fp32 — the file names are auto-detected. The model
 * is a DIRECTORY ([ModelInfo.filename]) holding encoder / decoder / joiner / tokens.
 *
 * Whole-utterance and offline: one createStream() -> decode() per [transcribe] call, matching Whisper's
 * one-chunk-per-call contract. The recognizer is kept warm across calls. All methods run on Whisper's
 * single worker thread (thread-confined; no internal locking needed). `cancel()` / `setInitialPrompt()`
 * keep the interface no-op defaults — decode is <500 ms (RTF ~0.03-0.04) so the 120 s watchdog never
 * fires, and a transducer takes no prompt.
 */
class SherpaEngine : AsrEngine {

    private var recognizer: OfflineRecognizer? = null

    @Throws(IOException::class)
    override fun load(model: ModelInfo?, modelFile: File?, vocabFile: File?): Boolean {
        val dir = modelFile ?: throw IOException("sherpa model directory missing")
        if (!dir.isDirectory) throw IOException("sherpa model directory not found: $dir")

        val tokens = File(dir, "tokens.txt")
        if (!tokens.exists()) throw IOException("sherpa tokens.txt missing in $dir")

        // sherpa/onnxruntime has no spin-wait barrier (unlike ggml), so a modest thread count is safe.
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = minOf(4, maxOf(2, cores - 1))

        // Two shapes: a 3-file transducer (Parakeet TDT / GigaAM RNN-T -> encoder + decoder + joiner) or a
        // single-file NeMo CTC (GigaAM CTC -> model.int8.onnx). Detect by which files are present.
        val encoder = File(dir, "encoder.int8.onnx")
        val modelConfig: OfflineModelConfig = if (encoder.exists()) {
            // Parakeet ships decoder.int8.onnx / joiner.int8.onnx; GigaAM RNN-T ships fp32 decoder.onnx / joiner.onnx.
            val decoder = firstExisting(dir, "decoder.int8.onnx", "decoder.onnx")
            val joiner = firstExisting(dir, "joiner.int8.onnx", "joiner.onnx")
            if (decoder == null || joiner == null) throw IOException("sherpa transducer incomplete in $dir")
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                modelType = "nemo_transducer",
                numThreads = threads,
            )
        } else {
            // NeMo CTC (GigaAM CTC): a single model file. modelType is left empty — sherpa infers CTC from
            // the populated nemo config (unlike NeMo transducers, which need the explicit "nemo_transducer").
            val ctc = firstExisting(dir, "model.int8.onnx", "model.onnx")
                ?: throw IOException("sherpa model incomplete in $dir")
            OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = ctc.absolutePath),
                tokens = tokens.absolutePath,
                numThreads = threads,
            )
        }

        val config = OfflineRecognizerConfig(modelConfig = modelConfig)
        try {
            recognizer = OfflineRecognizer(config = config)
        } catch (e: Throwable) {
            throw IOException("sherpa init failed: ${e.message}", e)
        }
        return true
    }

    override fun unload() {
        recognizer?.release()
        recognizer = null
    }

    override fun isLoaded(): Boolean = recognizer != null

    override fun transcribe(pcm16k: ByteArray?, action: Whisper.Action, langToken: Int): WhisperResult {
        val rec = recognizer ?: return WhisperResult.error(action)   // not loaded — surface, don't treat as silence
        if (pcm16k == null) return WhisperResult("", "", action)

        // langToken is ignored: Parakeet auto-detects 25 languages, GigaAM is Russian-only. A transducer
        // cannot translate, so a TRANSLATE action still transcribes (verbatim source language).
        val samples = pcm16ToFloat(pcm16k)
        val text: String = try {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                rec.decode(stream)
                rec.getResult(stream).text
            } finally {
                stream.release()   // free the native stream on every path, incl. a decode throw (F26)
            }
        } catch (e: Throwable) {
            return WhisperResult.error(action)   // native run crashed mid-phrase — surface, not silence
        }
        // No detected-language read-back: a NeMo transducer does not expose one. Leave it empty (the
        // downstream zh simplified/traditional pass only fires on an explicit zh code anyway).
        return WhisperResult(Transcript.clean(text), "", action)
    }

    private fun firstExisting(dir: File, vararg names: String): File? {
        for (n in names) {
            val f = File(dir, n)
            if (f.exists()) return f
        }
        return null
    }

    /** 16-bit little-endian PCM to normalized float [-1, 1] — sherpa's expected input. */
    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = bb.short / 32768.0f
        return out
    }
}
