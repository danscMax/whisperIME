package com.whispertflite.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Unit tests for the VPS-mirror URL derivation (host+prefix swap of HuggingFace resolve URLs). */
public class ModelDownloadManagerTest {

    @Test
    public void mirrorSwapsHostAndPreservesResolveTail() {
        assertEquals(
                "https://sweetwhisper.app/models/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
                ModelDownloadManager.mirrorUrl(
                        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"));
    }

    @Test
    public void mirrorPreservesNestedSubpaths() {
        assertEquals(
                "https://sweetwhisper.app/models/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/encoder.int8.onnx",
                ModelDownloadManager.mirrorUrl(
                        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/encoder.int8.onnx"));
    }

    @Test
    public void mirrorRejectsNonHuggingFaceUrls() {
        assertNull(ModelDownloadManager.mirrorUrl("https://example.com/foo/bar"));
        assertNull(ModelDownloadManager.mirrorUrl(null));
    }

    @Test
    public void mirrorRejectsNonResolveHuggingFaceUrls() {
        // API / search URLs are not files on the static mirror; never rewrite them (they'd 404).
        assertNull(ModelDownloadManager.mirrorUrl("https://huggingface.co/api/models/ggerganov/whisper.cpp"));
    }
}
