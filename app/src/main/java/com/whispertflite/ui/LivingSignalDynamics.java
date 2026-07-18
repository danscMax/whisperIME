package com.whispertflite.ui;

/** Pure signal-level math shared by the animated renderer and JVM tests. */
public final class LivingSignalDynamics {
    static final float ATTACK = 0.70f;
    static final float DECAY = 0.06f;

    private LivingSignalDynamics() {
    }

    public static float normalize(float value) {
        if (!Float.isFinite(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    public static float step(float current, float target) {
        current = normalize(current);
        target = normalize(target);
        float rate = target > current ? ATTACK : DECAY;
        return current + (target - current) * rate;
    }
}
