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
}
