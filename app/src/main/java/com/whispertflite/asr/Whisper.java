package com.whispertflite.asr;

import android.content.Context;
import android.util.Log;

import com.whispertflite.engine.WhisperEngine;
import com.whispertflite.engine.WhisperEngineJava;

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

    private final WhisperEngine mWhisperEngine;
    private Action mAction;
    private int mLangToken = -1;
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;

    // Chunked mode: chunks (16-bit PCM @16k) are queued and transcribed sequentially on the worker.
    private final BlockingQueue<byte[]> chunkQueue = new LinkedBlockingQueue<>();
    private volatile boolean chunkMode = false;

    public Whisper(Context context) {
        this.mWhisperEngine = new WhisperEngineJava(context);

        // Start thread for RecordBuffer transcription
        Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop);
        threadProcessRecordBuffer.start();

    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel(File modelPath, File vocabPath, boolean isMultilingual) {
        loadModel(modelPath.getAbsolutePath(), vocabPath.getAbsolutePath(), isMultilingual);
        currentModelPath = modelPath.getAbsolutePath();
    }

    public void loadModel(String modelPath, String vocabPath, boolean isMultilingual) {
        try {
            mWhisperEngine.initialize(modelPath, vocabPath, isMultilingual);
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model...", e);
            sendUpdate("Model initialization failed");
        }
    }

    public String getCurrentModelPath(){
        return currentModelPath;
    }

    public void unloadModel() {
        mWhisperEngine.deinitialize();
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
            if (mWhisperEngine.isInitialized() && RecordBuffer.getOutputBuffer() != null) {
                long startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);

                WhisperResult whisperResult = null;
                synchronized (mWhisperEngine) {
                    whisperResult = mWhisperEngine.processRecordBuffer(mAction, mLangToken);
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
