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

    /**
     * Organic-blob edge radius at one angular sample: a mean radius modulated by layered angular
     * harmonics — a slow breathing wobble, audio-driven "iris" petals, and a decaying result burst.
     * The modulation is clamped so the silhouette stays a well-formed liquid blob (never spikes to a
     * point or folds through itself). Pure + deterministic so the geometry is unit-testable off-device.
     *
     * @param baseR    mean radius, px
     * @param angle    sample angle, radians
     * @param t        breathing phase, seconds
     * @param spin     accumulated petal rotation, radians
     * @param wobAmp   breathing wobble amplitude (0 under reduced-motion)
     * @param petalAmp iris-petal amplitude (grows with voice level)
     * @param lobes    iris petal count
     * @param burstAmp radiant star-burst amplitude (decays to 0 after a result)
     */
    public static float blobRadius(float baseR, float angle, float t, float spin,
                                   float wobAmp, float petalAmp, int lobes, float burstAmp) {
        // Higher-order harmonics (3/4/5) only: a low 2-lobe term pinches one side into a "deflated ball",
        // so we skip it. These sum to gentle undulations spread all around the rim — organic, never dented.
        float wob = wobAmp * (float) (Math.sin(3 * angle + t * 0.7)
                + 0.7 * Math.sin(4 * angle - t * 0.9 + 1.3)
                + 0.5 * Math.sin(5 * angle + t * 0.5 + 2.1));
        float petals = petalAmp * (float) Math.sin(lobes * angle + spin);
        float burst = burstAmp * (float) Math.sin(12 * angle - spin);
        float k = wob + petals + burst;
        // Keep the edge in [0.45, 1.7] of baseR — a liquid blob, never a spike or a kink.
        k = Math.max(-0.55f, Math.min(0.7f, k));
        return baseR * (1f + k);
    }
}
