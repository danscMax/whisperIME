package com.whispertflite.util;

/**
 * Pure helpers for 16-bit little-endian PCM. No Android dependencies so they can be unit-tested
 * on the plain JVM. Single source of truth for the recorder's peak-normalization.
 */
public final class AudioMath {

    private AudioMath() {}

    /** Max absolute 16-bit sample value in the buffer (0 for empty/silence). */
    public static int peak(byte[] pcm16le) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm16le.length; i += 2) {
            int s = Math.abs((pcm16le[i] & 0xff) | (pcm16le[i + 1] << 8));
            if (s > peak) peak = s;
        }
        return peak;
    }

    /**
     * Peak-normalize in place. Gain is capped at 8x so noise-only audio is not blown up; buffers
     * that are already loud (gain < 1.2) or effectively silent (peak < 100) are left untouched.
     */
    public static void normalizeInPlace(byte[] pcm16le) {
        int peak = peak(pcm16le);
        if (peak < 100) return; // silence: nothing to normalize
        double gain = Math.min(8.0, 0.95 * 32767.0 / peak);
        if (gain < 1.2) return; // already loud enough
        for (int i = 0; i + 1 < pcm16le.length; i += 2) {
            int s = (short) ((pcm16le[i] & 0xff) | (pcm16le[i + 1] << 8));
            int v = (int) Math.round(s * gain);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            pcm16le[i] = (byte) (v & 0xff);
            pcm16le[i + 1] = (byte) ((v >> 8) & 0xff);
        }
    }
}
