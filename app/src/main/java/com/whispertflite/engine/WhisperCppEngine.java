package com.whispertflite.engine;

import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.utils.InputLang;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Native whisper.cpp path. Owns a single whisper_context pointer.
 *
 * <p>Thread confinement: {@link WhisperCpp}'s context is not thread-safe. All methods here run on
 * {@link Whisper}'s single worker thread (load/transcribe/unload are serialized there), so the
 * pointer is never touched concurrently.
 */
public final class WhisperCppEngine implements AsrEngine {
    private long ctxPtr = 0;

    @Override
    public boolean load(ModelInfo model, File modelFile, File vocabFile) throws IOException {
        if (modelFile == null || !modelFile.exists()) {
            throw new IOException("whisper.cpp model missing: " + modelFile);
        }
        try {
            ctxPtr = WhisperCpp.nativeInit(modelFile.getAbsolutePath());
        } catch (RuntimeException e) {
            // Surface as IOException so Whisper.loadModel's error path reports it.
            throw new IOException("whisper.cpp init failed: " + e.getMessage(), e);
        }
        if (ctxPtr == 0) {
            throw new IOException("whisper.cpp init returned null context");
        }
        return true;
    }

    @Override
    public void unload() {
        if (ctxPtr != 0) {
            WhisperCpp.nativeRelease(ctxPtr);
            ctxPtr = 0;
        }
    }

    @Override
    public boolean isLoaded() {
        return ctxPtr != 0;
    }

    @Override
    public void cancel() {
        WhisperCpp.nativeCancel();
    }

    @Override
    public WhisperResult transcribe(byte[] pcm16k, Whisper.Action action, int langToken) {
        if (ctxPtr == 0 || pcm16k == null) {
            return new WhisperResult("", "", action);
        }
        float[] samples = pcm16ToFloat(pcm16k);
        String lang = langToken != -1
                ? InputLang.getLanguageCodeById(InputLang.getLangList(), langToken) : "auto";
        if (lang == null || lang.isEmpty()) {
            lang = "auto";
        }
        boolean translate = action == Whisper.Action.TRANSLATE;

        String text;
        try {
            text = WhisperCpp.nativeTranscribe(ctxPtr, samples, lang, translate);
        } catch (RuntimeException e) {
            return new WhisperResult("", "", action);
        }
        String detected = "auto".equals(lang) ? "" : lang;
        return new WhisperResult(com.whispertflite.asr.Transcript.clean(text), detected, action);
    }

    /** 16-bit little-endian PCM to normalized float [-1, 1] — whisper.cpp's expected input. */
    private static float[] pcm16ToFloat(byte[] pcm) {
        int n = pcm.length / 2;
        ByteBuffer bb = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder());
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = bb.getShort() / 32768.0f;
        }
        return out;
    }
}
