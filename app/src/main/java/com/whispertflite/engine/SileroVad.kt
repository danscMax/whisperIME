package com.whispertflite.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Silero neural VAD via sherpa-onnx. sherpa-onnx is already a dependency (the ASR engine), so this
 * reuses the loaded onnxruntime — no new library, only the ~0.6 MB silero_vad.onnx bundled in assets/.
 *
 * Why it replaced the webrtc energy VAD: webrtc missed quiet speech from the VOICE_RECOGNITION source,
 * so frames were peak-normalized before it — which also blew pause noise up to speech level, jamming
 * the VAD permanently "on". A whole adaptive-RMS auto-stop existed only to undo that. Silero is a
 * trained DNN, level-robust: it takes the RAW samples and detects end-of-speech itself via
 * minSilenceDuration, so both the normalization and the RMS workaround are gone.
 *
 * Thread-confined to Recorder's worker thread (like [SherpaEngine]) — no internal locking.
 */
class SileroVad @JvmOverloads constructor(
    context: Context,
    minSilenceSec: Float,          // trailing silence that ends an utterance/chunk — the pause-tolerance knob
    minSpeechSec: Float = 0.20f,   // speech must last this long to count (rejects clicks/coughs)
    maxSpeechSec: Float = 30.0f,   // high on purpose: we cap length ourselves; keeps isSpeechDetected from
                                   // flipping false mid-utterance on a forced segment cut
) {
    private val vad: Vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = minSilenceSec,
                minSpeechDuration = minSpeechSec,
                windowSize = 512,   // silero uses 512-sample (32 ms @16k) windows; sherpa buffers frames to fit
                maxSpeechDuration = maxSpeechSec,
            ),
            sampleRate = 16000,
            numThreads = 1,
        ),
    )

    /**
     * Feed one 16-bit little-endian PCM frame (any length; sherpa accumulates to 512-sample windows).
     * Completed speech segments are drained and discarded — the recorder keeps its own full-audio
     * buffer, and an undrained queue would grow unbounded over a long chunked session.
     */
    fun accept(pcm16le: ByteArray, bytes: Int) {
        val n = bytes / 2
        val bb = ByteBuffer.wrap(pcm16le, 0, bytes).order(ByteOrder.LITTLE_ENDIAN)
        val f = FloatArray(n)
        for (i in 0 until n) f[i] = bb.short / 32768.0f
        vad.acceptWaveform(f)
        while (!vad.empty()) vad.pop()
    }

    /** True while inside detected speech; flips false after minSilenceDuration of trailing silence. */
    fun isSpeech(): Boolean = vad.isSpeechDetected()

    fun reset() = vad.reset()

    fun release() = vad.release()
}
