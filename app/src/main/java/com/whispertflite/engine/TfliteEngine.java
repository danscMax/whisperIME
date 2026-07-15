package com.whispertflite.engine;

import android.content.Context;

import com.whispertflite.asr.RecordBuffer;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.models.ModelInfo;

import java.io.File;
import java.io.IOException;

/**
 * TFLite path: wraps the existing {@link WhisperEngineJava} unchanged. Behaviour is identical to
 * the legacy pipeline — the chunk is published on the shared {@link RecordBuffer} and inference
 * reads it back, exactly as before.
 */
public final class TfliteEngine implements AsrEngine {
    private static final String ENGLISH_ONLY_EXT = ".en.tflite";

    private final WhisperEngineJava engine;

    public TfliteEngine(Context context) {
        this.engine = new WhisperEngineJava(context);
    }

    @Override
    public boolean load(ModelInfo model, File modelFile, File vocabFile) throws IOException {
        // Legacy call sites pass model == null; fall back to the filename rule MainActivity used.
        boolean multilingual = model != null ? !model.englishOnly
                : !modelFile.getName().endsWith(ENGLISH_ONLY_EXT);
        engine.initialize(modelFile.getAbsolutePath(), vocabFile.getAbsolutePath(), multilingual);
        return engine.isInitialized();
    }

    @Override
    public void unload() {
        engine.deinitialize();
    }

    @Override
    public boolean isLoaded() {
        return engine.isInitialized();
    }

    @Override
    public WhisperResult transcribe(byte[] pcm16k, Whisper.Action action, int langToken) {
        RecordBuffer.setOutputBuffer(pcm16k);
        return engine.processRecordBuffer(action, langToken);
    }
}
