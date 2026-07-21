package com.whispertflite.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LivingSignalDynamicsTest {

    @Test
    public void normalizeClampsAndRejectsNonFiniteInput() {
        assertEquals(0f, LivingSignalDynamics.normalize(-1f), 0f);
        assertEquals(0.42f, LivingSignalDynamics.normalize(0.42f), 0f);
        assertEquals(1f, LivingSignalDynamics.normalize(2f), 0f);
        assertEquals(0f, LivingSignalDynamics.normalize(Float.NaN), 0f);
        assertEquals(0f, LivingSignalDynamics.normalize(Float.POSITIVE_INFINITY), 0f);
    }

    @Test
    public void stepUsesFastAttackAndSlowDecay() {
        assertEquals(0.70f, LivingSignalDynamics.step(0f, 1f), 0.0001f);
        assertEquals(0.94f, LivingSignalDynamics.step(1f, 0f), 0.0001f);
    }

    @Test
    public void repeatedStepsConvergeWithoutOvershoot() {
        float level = 0f;
        for (int i = 0; i < 8; i++) level = LivingSignalDynamics.step(level, 1f);
        assertTrue(level > 0.999f);
        assertTrue(level <= 1f);

        for (int i = 0; i < 120; i++) level = LivingSignalDynamics.step(level, 0f);
        assertTrue(level < 0.001f);
        assertTrue(level >= 0f);
    }

    @Test
    public void blobRadiusStaysBoundedAndFinite() {
        float baseR = 100f;
        for (int i = 0; i < 360; i++) {
            float a = (float) Math.toRadians(i);
            // Worst-case amplitudes (full voice + burst) must not spike or produce NaN/Inf.
            float r = LivingSignalDynamics.blobRadius(baseR, a, 3.1f, 2.0f, 0.08f, 0.24f, 8, 0.28f);
            assertTrue(Float.isFinite(r));
            assertTrue(r >= 0.45f * baseR);   // clamp floor (1 - 0.55)
            assertTrue(r <= 1.70f * baseR);   // clamp ceiling (1 + 0.7)
        }
    }

    @Test
    public void blobRadiusIsAPlainCircleWhenUnmodulated() {
        // Reduced-motion / idle-silence path: no wobble, petals, or burst -> exactly baseR at every angle.
        for (int i = 0; i < 8; i++) {
            float a = i * 0.7f;
            assertEquals(50f, LivingSignalDynamics.blobRadius(50f, a, 9f, 4f, 0f, 0f, 8, 0f), 1e-4f);
        }
    }
}
