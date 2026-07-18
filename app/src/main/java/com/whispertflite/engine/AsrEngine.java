package com.whispertflite.engine;

import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.models.ModelInfo;

import java.io.File;
import java.io.IOException;

/**
 * Speech-to-text engine behind {@link Whisper}. Two implementations exist: {@link TfliteEngine}
 * (the legacy TensorFlow-Lite path) and {@link WhisperCppEngine} (native whisper.cpp).
 *
 * <p>Signatures are adapted to how {@code Whisper} actually feeds audio: a completed 16-bit PCM
 * @16 kHz chunk plus the session's {@link Whisper.Action} and integer language token (the values
 * {@code Whisper} already holds). Each engine converts these to its own native form internally,
 * avoiding a lossy round-trip through a language-code string at the routing point.
 *
 * <p>All methods are called on {@code Whisper}'s single worker thread; implementations may assume
 * thread confinement and need no internal locking.
 */
public interface AsrEngine {
    /** @return true if the model loaded and the engine is ready. Throws on unrecoverable load errors. */
    boolean load(ModelInfo model, File modelFile, File vocabFile) throws IOException;

    void unload();

    boolean isLoaded();

    /** Best-effort abort of an in-flight transcribe() (slow native runs). Safe to call from any thread; no-op if idle or unsupported. */
    default void cancel() {}

    /** Optional vocabulary/prompt to bias recognition toward the user's names/terms (A3). No-op where unsupported. */
    default void setInitialPrompt(String prompt) {}

    /** Transcribe one 16-bit little-endian PCM @16 kHz chunk. Returns empty text on failure, never null. */
    WhisperResult transcribe(byte[] pcm16k, Whisper.Action action, int langToken);
}
