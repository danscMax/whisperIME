package com.whispertflite.asr;

import android.content.Context;
import android.util.Log;


import com.whispertflite.engine.AsrEngine;
import com.whispertflite.engine.TfliteEngine;
import com.whispertflite.engine.WhisperCppEngine;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    // Failure updates listeners can recognise (via startsWith) to surface an error to the user (C4).
    public static final String MSG_LOAD_FAILED = "Model initialization failed";
    public static final String MSG_TRANSCRIBE_FAILED = "Transcription failed";
    public static final String MSG_ENGINE_NOT_INIT = "Engine not initialized or file path not set";

    public static final Action ACTION_TRANSCRIBE = Action.TRANSCRIBE;
    public static final Action ACTION_TRANSLATE = Action.TRANSLATE;
    private String currentModelPath = "";

    public enum Action {
        TRANSLATE, TRANSCRIBE
    }

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private final Context mContext;
    private volatile AsrEngine mEngine;   // read/written across worker, stop() and unloadModel() threads
    private Action mAction;
    private int mLangToken = -1;
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;

    // Chunked mode: chunks (16-bit PCM @16k) are queued and transcribed sequentially on the worker.
    private final BlockingQueue<byte[]> chunkQueue = new LinkedBlockingQueue<>();
    private volatile boolean chunkMode = false;

    private final Thread mWorker;

    public Whisper(Context context) {
        this.mContext = context.getApplicationContext();

        // Start thread for RecordBuffer transcription
        mWorker = new Thread(this::processRecordBufferLoop);
        mWorker.start();
    }

    /** Stop the worker thread and free the engine. Call when discarding this instance (model switch). */
    public void shutdown() {
        stop();
        unloadModel();
        mWorker.interrupt();
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    /**
     * Load the engine for the model FILE the caller passed. The engine is chosen from the file
     * itself (whisper.cpp GGUF models end in {@code .bin}, TFLite models in {@code .tflite}), NOT
     * from the global {@code selectedModelId} pref — different entry points (the standalone app, the
     * RecognitionService, the recognize dialog) each resolve their own file, and routing by a shared
     * pref would load the wrong model for the non-app entry points. {@code isMultilingual} is kept
     * for source compatibility; the engine derives it from the matched model/filename.
     */
    public boolean loadModel(File modelPath, File vocabPath, boolean isMultilingual) {
        ModelInfo hint = modelInfoForFile(modelPath); // registry match by filename, may be null
        boolean cpp = modelPath.getName().endsWith(".bin"); // GGUF whisper.cpp model files are .bin
        try {
            AsrEngine engine = cpp ? new WhisperCppEngine() : new TfliteEngine(mContext);
            engine.load(hint, modelPath, cpp ? null : vocabPath);
            if (!engine.isLoaded()) {
                // load() returned without an exception but the engine isn't ready (e.g. TFLite init
                // failed) — do NOT publish it, or callers would mark the model "loaded" and no-op (C2).
                sendUpdate(MSG_LOAD_FAILED);
                return false;
            }
            mEngine = engine;
            currentModelPath = modelPath.getAbsolutePath();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model...", e);
            // Surface the reason (out-of-memory on a too-large GGUF, missing file, …) so the UI can
            // tell the user why instead of silently returning empty transcriptions (C13).
            sendUpdate(MSG_LOAD_FAILED + ": " + e.getMessage());
            return false;
        }
    }

    /** True only when an engine is published AND reports itself loaded — the gate callers must use
     *  before recording a model as "warm" (C2). */
    public boolean isModelLoaded() {
        AsrEngine engine = mEngine;
        return engine != null && engine.isLoaded();
    }

    /** Registry entry whose filename matches this file (handles the gguf/ subdir), or null. */
    private ModelInfo modelInfoForFile(File file) {
        String name = file.getName();
        for (ModelInfo m : ModelRegistry.all()) {
            if (m.filename.equals(name) || m.filename.endsWith("/" + name)) return m;
        }
        return null;
    }

    public String getCurrentModelPath(){
        return currentModelPath;
    }

    public void unloadModel() {
        AsrEngine engine = mEngine;
        if (engine != null) {
            engine.cancel();            // ask an in-flight transcribe() to abort so we don't block long
            synchronized (engine) {     // wait until the worker has left transcribe(), THEN free the
                engine.unload();        // native context — prevents a use-after-free of whisper_context
            }
        }
        mEngine = null;
        currentModelPath = "";
    }

    public void setAction(Action action) {
        this.mAction = action;
    }

    public void setLanguage(int language){
        this.mLangToken = language;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        chunkMode = false; // single-buffer path; enqueueChunk() flips this on for chunked recording
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
        chunkQueue.clear();
        // Abort a slow in-flight native run (large whisper.cpp models take minutes on mobile CPUs).
        if (mEngine != null) mEngine.cancel();
    }

    public boolean isInProgress() {
        return mInProgress.get() || !chunkQueue.isEmpty();
    }

    /**
     * Chunked mode: enqueue a completed audio chunk for sequential transcription. Each chunk's text
     * is delivered via onResultReceived; MSG_PROCESSING_DONE is sent once the queue drains so the UI
     * can leave the PROCESSING state. Action/language must be set before recording starts.
     */
    public void enqueueChunk(byte[] pcm16k) {
        chunkMode = true;
        mInProgress.set(true);
        chunkQueue.offer(pcm16k);
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            } finally {
                taskLock.unlock();
            }

            // Catch-all so a throw from a listener callback (drainChunks' MSG_PROCESSING_DONE runs
            // outside processRecordBuffer's own try) can never kill the worker thread permanently —
            // there is no restart path, so a dead worker would silently stop all future transcription (C11).
            try {
                if (chunkMode) drainChunks();
                else processRecordBuffer();
            } catch (Throwable t) {
                Log.e(TAG, "Worker loop iteration failed", t);
                mInProgress.set(false);
            }
        }
    }

    private void drainChunks() {
        byte[] chunk;
        while ((chunk = chunkQueue.poll()) != null) {
            // ponytail: reuse the existing single-buffer transcription path by pointing the shared
            // RecordBuffer at this chunk. Safe because draining is sequential on this one worker.
            RecordBuffer.setOutputBuffer(chunk);
            processRecordBuffer();
        }
        mInProgress.set(false);
        sendUpdate(MSG_PROCESSING_DONE);
    }

    private void processRecordBuffer() {
        try {
            AsrEngine engine = mEngine; // capture once: unloadModel() may null the field concurrently
            byte[] pcm = RecordBuffer.getOutputBuffer();
            if (engine != null && engine.isLoaded() && pcm != null) {
                long startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);

                WhisperResult whisperResult;
                synchronized (engine) {
                    whisperResult = engine.transcribe(pcm, mAction, mLangToken);
                }
                sendResult(whisperResult);

                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                sendUpdate(MSG_PROCESSING_DONE);
            } else {
                sendUpdate(MSG_ENGINE_NOT_INIT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate(MSG_TRANSCRIBE_FAILED + ": " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }

}
