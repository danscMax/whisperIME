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

    public static final Action ACTION_TRANSCRIBE = Action.TRANSCRIBE;
    public static final Action ACTION_TRANSLATE = Action.TRANSLATE;
    private String currentModelPath = "";

    public enum Action {
        TRANSLATE, TRANSCRIBE
    }

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private final Context mContext;
    private AsrEngine mEngine;
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
    public void loadModel(File modelPath, File vocabPath, boolean isMultilingual) {
        ModelInfo hint = modelInfoForFile(modelPath); // registry match by filename, may be null
        boolean cpp = modelPath.getName().endsWith(".bin"); // GGUF whisper.cpp model files are .bin
        try {
            AsrEngine engine = cpp ? new WhisperCppEngine() : new TfliteEngine(mContext);
            engine.load(hint, modelPath, cpp ? null : vocabPath);
            mEngine = engine;
            currentModelPath = modelPath.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model...", e);
            sendUpdate("Model initialization failed");
        }
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

            if (chunkMode) drainChunks();
            else processRecordBuffer();
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
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
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
