package com.whispertflite.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.MessageDigest;

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

    // --- SHA-256 integrity gate (W1-MD1) ---

    @Test
    public void toHexEncodesBytesAsLowercaseHex() {
        assertEquals("000fff10", ModelDownloadManager.toHex(new byte[]{0x00, 0x0f, (byte) 0xff, 0x10}));
    }

    @Test
    public void hashMatchesSkipsWhenNoExpectedHash() {
        // A null expected hash means the asset opted out of the check — always "matches".
        assertTrue(ModelDownloadManager.hashMatches(null, "anything"));
    }

    @Test
    public void hashMatchesIsCaseInsensitive() {
        assertTrue(ModelDownloadManager.hashMatches(
                "ACFC2B4456377E15D04F0243AF540B7FE7C992F8D898D751CF134C3A55FD2247",
                "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"));
    }

    @Test
    public void corruptContentIsRejectedByHash() throws Exception {
        // Real red-green: the gate compares the registry's expected SHA-256 against the freshly hashed
        // bytes. A single flipped byte yields a different hash, so a corrupt-but-plausible file is rejected.
        byte[] good = "sherpa encoder bytes".getBytes("UTF-8");
        String expected = sha256Hex(good);

        byte[] corrupt = good.clone();
        corrupt[0] ^= 0x01;

        assertTrue(ModelDownloadManager.hashMatches(expected, sha256Hex(good)));
        assertFalse(ModelDownloadManager.hashMatches(expected, sha256Hex(corrupt)));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return ModelDownloadManager.toHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
