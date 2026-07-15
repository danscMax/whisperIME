package com.whispertflite.engine;

/**
 * Thin holder for the native whisper.cpp bridge (see app/src/main/cpp/whisper_jni.cpp).
 * The context pointer returned by {@link #nativeInit} must be passed back to
 * {@link #nativeTranscribe} / {@link #nativeRelease} and is not thread-safe.
 */
public final class WhisperCpp {
    static {
        System.loadLibrary("whisper_jni");
    }

    private WhisperCpp() {}

    /** @return opaque whisper_context pointer, or 0 on failure (also throws RuntimeException). */
    public static native long nativeInit(String modelPath);

    /** @param lang ISO code ("ru"/"en") or "auto" for language detection. */
    public static native String nativeTranscribe(long ctxPtr, float[] pcm16k, String lang, boolean translate);

    public static native void nativeRelease(long ctxPtr);
}
