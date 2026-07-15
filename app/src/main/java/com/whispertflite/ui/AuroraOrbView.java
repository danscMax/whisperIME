package com.whispertflite.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The signature element, in the ChatGPT-voice / Siri idiom: a soft, fluid light bloom rather than a
 * hard sphere. Nothing has a crisp edge — every layer is a radial gradient that fades to transparent
 * inside the view, so the bloom feathers into the dark. Several lobes on different sine phases drift
 * around a slowly-rotating frame, so the shape breathes and morphs organically; a cool rim shadow and
 * a warm inner shift give it depth. The whole thing swells and brightens with the voice level.
 *
 * Feed live audio with {@link #pushLevel(float)} (0..1 RMS); call {@link #setIdle()} to return to
 * calm breathing. Rendered on a SOFTWARE layer so the additive gradient blends stay smooth.
 */
public class AuroraOrbView extends View {

    private int accent = 0xFF2FB8A4;      // primary hue
    private int accentSoft = 0xFF9DF2E3;  // lighter tint (primaryContainer) for the bloom rim
    private int cool = 0xFF16324F;        // cool shadow that gives volume

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float phase = 0f;
    private float level = 0f;
    private float targetLevel = 0f;
    private boolean recording = false;

    private ValueAnimator anim;

    // four drifting lobes, each on its own angular speed and phase offset
    private static final float[] LOBE_SPEED = { 1.00f, 0.63f, 0.41f, 0.27f };
    private static final float[] LOBE_PHASE = { 0f, 1.7f, 3.3f, 5.0f };
    private static final int[]   LOBE_HUE   = { 0, 1, 0, 1 }; // 0 = accent, 1 = accentSoft

    public AuroraOrbView(Context c) { super(c); init(); }
    public AuroraOrbView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        anim.setDuration(9000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            level += (targetLevel - level) * 0.16f;
            if (!recording && level < 0.01f) level = 0f;
            if (!recording) targetLevel = 0f;
            invalidate();
        });
    }

    /** @param bright primary hue (colorPrimary); @param soft lighter tint (colorPrimaryContainer). */
    public void setColors(int bright, int soft) {
        this.accent = bright;
        this.accentSoft = soft;
        // Palette-coherent cool shadow: the accent pulled deep toward a blue-black for volume.
        this.cool = mix(mix(bright, 0xFF0A1830, 0.6f), Color.BLACK, 0.25f);
        invalidate();
    }

    public void pushLevel(float rms) {
        recording = true;
        targetLevel = Math.max(0f, Math.min(1f, rms));
    }

    public void setIdle() {
        recording = false;
        targetLevel = 0f;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!anim.isStarted()) anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float baseR = Math.min(cx, cy);

        float breathe = 0.5f + 0.5f * (float) Math.sin(phase);
        float swell = 0.70f + 0.06f * breathe + 0.24f * level;

        // 1) Wide halo that feathers fully to transparent — the glow in the dark.
        float haloR = baseR * (1.00f + 0.32f * level);
        paint.setShader(new RadialGradient(cx, cy, haloR,
                new int[]{ withAlpha(accent, (int) (66 + 120 * level)),
                           withAlpha(cool, 30), Color.TRANSPARENT },
                new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, haloR, paint);

        // 2) Cool shadow lobe, offset low/right, gives the bloom volume (a lit-from-upper-left feel).
        float shadowR = baseR * swell * 0.9f;
        paint.setShader(new RadialGradient(cx + baseR * 0.14f, cy + baseR * 0.16f, shadowR,
                new int[]{ withAlpha(cool, 150), withAlpha(cool, 40), Color.TRANSPARENT },
                new float[]{ 0f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx + baseR * 0.14f, cy + baseR * 0.16f, shadowR, paint);

        // 3) Drifting colour lobes — the organic, morphing silhouette.
        float lobeR = baseR * swell * 0.82f;
        float drift = baseR * (0.13f + 0.05f * level);
        for (int i = 0; i < LOBE_SPEED.length; i++) {
            float ang = phase * LOBE_SPEED[i] + LOBE_PHASE[i];
            float lx = cx + (float) Math.cos(ang) * drift;
            float ly = cy + (float) Math.sin(ang * 1.28f) * drift;
            int hue = LOBE_HUE[i] == 0 ? accent : accentSoft;
            paint.setShader(new RadialGradient(lx, ly, lobeR,
                    new int[]{ withAlpha(hue, 135), withAlpha(hue, 55), Color.TRANSPARENT },
                    new float[]{ 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawCircle(lx, ly, lobeR, paint);
        }

        // 4) Soft white-hot core, offset toward the upper-left light.
        float coreR = baseR * swell * (0.40f + 0.10f * level);
        float coreX = cx - baseR * 0.10f;
        float coreY = cy - baseR * 0.12f;
        paint.setShader(new RadialGradient(coreX, coreY, coreR,
                new int[]{ withAlpha(Color.WHITE, (int) (205 + 50 * level)),
                           withAlpha(mix(Color.WHITE, accentSoft, 0.55f), 130), Color.TRANSPARENT },
                new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(coreX, coreY, coreR, paint);

        // 5) Thin bright rim on the lit edge for a crisp, jewel-like highlight.
        float rimR = baseR * swell * 0.9f;
        paint.setShader(new RadialGradient(cx - baseR * 0.16f, cy - baseR * 0.18f, rimR,
                new int[]{ Color.TRANSPARENT, Color.TRANSPARENT,
                           withAlpha(accentSoft, (int) (70 + 60 * level)), Color.TRANSPARENT },
                new float[]{ 0f, 0.78f, 0.9f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx - baseR * 0.16f, cy - baseR * 0.18f, rimR, paint);
    }

    private static int withAlpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0x00FFFFFF);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = (int) (Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }
}
