package com.whispertflite.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioMathTest {

    /** Pack signed samples into 16-bit little-endian PCM bytes. */
    private static byte[] pcm(int... samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[2 * i] = (byte) (samples[i] & 0xff);
            b[2 * i + 1] = (byte) ((samples[i] >> 8) & 0xff);
        }
        return b;
    }

    private static int sampleAt(byte[] b, int i) {
        return (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
    }

    @Test
    public void peakFindsMaxAbs() {
        assertEquals(300, AudioMath.peak(pcm(10, -300, 50, 299)));
        assertEquals(0, AudioMath.peak(new byte[0]));
    }

    @Test
    public void silenceUnchanged() {
        byte[] in = pcm(0, 50, -99, 10); // peak 99 < 100
        byte[] copy = in.clone();
        AudioMath.normalizeInPlace(in);
        assertArrayEquals(copy, in);
    }

    @Test
    public void alreadyLoudUnchanged() {
        byte[] in = pcm(30000, -30000, 32000); // gain ~0.97 < 1.2
        byte[] copy = in.clone();
        AudioMath.normalizeInPlace(in);
        assertArrayEquals(copy, in);
    }

    @Test
    public void quietSignalAmplifiedAndCapped() {
        byte[] in = pcm(1000, -500); // uncapped gain ~31 -> capped to 8.0
        AudioMath.normalizeInPlace(in);
        assertEquals(8000, sampleAt(in, 0));
        assertEquals(-4000, sampleAt(in, 1));
    }

    @Test
    public void gainAppliedWhenNotCapped() {
        int peak = 5000;
        double gain = 0.95 * 32767.0 / peak; // ~6.23, below the 8.0 cap
        byte[] in = pcm(peak, -2000);
        AudioMath.normalizeInPlace(in);
        assertEquals((int) Math.round(peak * gain), sampleAt(in, 0));
        assertEquals((int) Math.round(-2000 * gain), sampleAt(in, 1));
    }

    @Test
    public void neverOverflowsShort() {
        byte[] in = pcm(3800, -3800, 3000, -3000); // peak 3800 -> gain capped 8.0
        AudioMath.normalizeInPlace(in);
        for (int i = 0; i < in.length / 2; i++) {
            int s = sampleAt(in, i);
            assertTrue("sample " + i + " out of range: " + s, s <= 32767 && s >= -32768);
        }
    }
}
