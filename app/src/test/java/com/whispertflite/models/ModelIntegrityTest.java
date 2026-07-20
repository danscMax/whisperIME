package com.whispertflite.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.MessageDigest;

/**
 * Verifies the SHA-256 hex-compare used by the download integrity gate (pure logic; no Android runtime).
 * Covers the exact promote/reject decision {@code ModelDownloadManager.downloadAsset} makes before rename.
 */
public class ModelIntegrityTest {

    /** Known-answer: SHA-256("") = e3b0c442...b855. Validates toHex against a canonical digest. */
    @Test public void toHexMatchesKnownDigest() throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ModelDownloadManager.toHex(d));
    }

    @Test public void matchingHashPromotes() throws Exception {
        byte[] payload = "the model bytes".getBytes("UTF-8");
        String expected = ModelDownloadManager.toHex(MessageDigest.getInstance("SHA-256").digest(payload));
        // Same bytes hashed again -> promote.
        String actual = ModelDownloadManager.toHex(MessageDigest.getInstance("SHA-256").digest(payload));
        assertTrue(ModelDownloadManager.hashMatches(expected, actual));
    }

    @Test public void corruptByteIsRejected() throws Exception {
        byte[] payload = "the model bytes".getBytes("UTF-8");
        String expected = ModelDownloadManager.toHex(MessageDigest.getInstance("SHA-256").digest(payload));
        payload[0] ^= 0x01; // flip a single bit -> different digest
        String corrupt = ModelDownloadManager.toHex(MessageDigest.getInstance("SHA-256").digest(payload));
        assertFalse(ModelDownloadManager.hashMatches(expected, corrupt));
    }

    @Test public void nullExpectedSkipsCheck() {
        // Un-hashed assets (every current registry entry) must always pass -> backward compatible.
        assertTrue(ModelDownloadManager.hashMatches(null, "whatever"));
    }

    @Test public void compareIsCaseInsensitive() {
        String lower = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertTrue(ModelDownloadManager.hashMatches(lower.toUpperCase(), lower));
        assertFalse(ModelDownloadManager.hashMatches(lower, lower.replace('a', 'b')));
    }
}
