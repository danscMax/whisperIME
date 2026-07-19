package com.whispertflite.asr;

import android.content.Context;

import java.io.File;

/**
 * Process-wide warm {@link Whisper}. Loading a model is the slow part (~1 s for a 640 MB ONNX model);
 * the recognize dialog, the IME keyboard and the RecognitionService all live in this one process, so
 * a single loaded engine is kept here and shared between them. After the first (cold) load every open
 * reuses the warm engine and is instant.
 *
 * <p>Only one surface transcribes at a time (the recognize dialog is modal, the keyboard is the shown
 * IME, the RecognitionService serves one request), so the shared instance is never driven concurrently.
 * Each surface swaps in its own listener via {@link Whisper#setListener} while active and clears it on
 * close (see callers) so no Activity/Service is leaked through the long-lived instance.
 *
 * <p>Held with the application context (never an Activity/Service context) to avoid leaks. Freed by
 * {@link #evict()} on a model switch or under memory pressure ({@code Application.onTrimMemory}).
 */
public final class WarmWhisper {

    private static Whisper instance;
    private static String loadedModelPath;

    private WarmWhisper() {}

    /** True if the warm instance already holds this exact model file, ready to use without loading. */
    public static synchronized boolean isWarm(File modelFile) {
        return instance != null
                && modelFile.getAbsolutePath().equals(loadedModelPath)
                && instance.isModelLoaded();
    }

    /**
     * The shared warm Whisper for this model, loading it if the warm instance holds a different model
     * (or none). May block on a cold load — call OFF the main thread the first time; when {@link #isWarm}
     * is already true it returns instantly. Returns null if the load failed.
     */
    public static synchronized Whisper get(Context ctx, File modelFile, File vocabFile, boolean multilingual) {
        String path = modelFile.getAbsolutePath();
        if (instance != null && path.equals(loadedModelPath) && instance.isModelLoaded()) {
            return instance;                      // warm hit
        }
        if (instance != null) {                   // different model held: evict it first
            instance.shutdown();
            instance = null;
            loadedModelPath = null;
        }
        Whisper w = new Whisper(ctx.getApplicationContext());
        if (!w.loadModel(modelFile, vocabFile, multilingual)) {
            w.shutdown();
            return null;
        }
        instance = w;
        loadedModelPath = path;
        return w;
    }

    /** Free the warm engine + worker thread (model switch / low memory). Safe to call any time. */
    public static synchronized void evict() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
            loadedModelPath = null;
        }
    }
}
