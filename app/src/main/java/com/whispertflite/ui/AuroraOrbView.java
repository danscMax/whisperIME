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
 * hard sphere. Nothing is drawn with a crisp edge — every layer is a radial gradient that fades to
 * transparent inside the view, so the bloom feathers into the dark. A few offset lobes drift on
 * different sine phases so the shape breathes and morphs organically; the whole thing swells and
 * brightens with the voice level while recording.
 *
 * Feed live audio with {@link #pushLevel(float)} (0..1 RMS); call {@link #setIdle()} to return to
 * calm breathing. Draw on a SOFTWARE layer (set in XML or by the parent) so the soft blends are smooth.
 */
public class AuroraOrbView extends View {

    private int accent = 0xFF2FB8A4;
    private int accentDeep = 0xFF0A6E62;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float phase = 0f;
    private float level = 0f;       // smoothed voice level
    private float targetLevel = 0f;
    private boolean recording = false;

    private ValueAnimator anim;

    // three drifting lobes, each on its own angular speed and phase offset
    private static final float[] LOBE_SPEED = { 1.0f, 0.63f, 0.41f };
    private static final float[] LOBE_PHASE = { 0f, 2.1f, 4.2f };

    public AuroraOrbView(Context c) { super(c); init(); }
    public AuroraOrbView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null); // soft additive blends
        anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        anim.setDuration(7000);
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

    public void setColors(int brightAccent, int deepAccent) {
        this.accent = brightAccent;
        this.accentDeep = deepAccent;
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
        float base = Math.min(cx, cy);

        float breathe = 0.5f + 0.5f * (float) Math.sin(phase);
        float swell = 0.72f + 0.06f * breathe + 0.24f * level;

        // 1) Wide soft halo that feathers fully to transparent (the "glow" in the dark).
        float haloR = base * (1.02f + 0.30f * level);
        paint.setShader(new RadialGradient(cx, cy, haloR,
                new int[]{ withAlpha(accent, (int) (70 + 110 * level)),
                           withAlpha(accentDeep, 34), Color.TRANSPARENT },
                new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, haloR, paint);

        // 2) Drifting colour lobes give the bloom an organic, morphing silhouette.
        float lobeR = base * swell * 0.86f;
        float drift = base * (0.12f + 0.05f * level);
        for (int i = 0; i < LOBE_SPEED.length; i++) {
            float ang = phase * LOBE_SPEED[i] + LOBE_PHASE[i];
            float lx = cx + (float) Math.cos(ang) * drift;
            float ly = cy + (float) Math.sin(ang * 1.3f) * drift;
            paint.setShader(new RadialGradient(lx, ly, lobeR,
                    new int[]{ withAlpha(accent, 150), withAlpha(accentDeep, 70), Color.TRANSPARENT },
                    new float[]{ 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawCircle(lx, ly, lobeR, paint);
        }

        // 3) Soft white core, offset gently by the loudest lobe so the highlight lives inside the bloom.
        float coreR = base * swell * (0.42f + 0.10f * level);
        float coreAng = phase * LOBE_SPEED[0];
        float coreX = cx + (float) Math.cos(coreAng) * drift * 0.5f;
        float coreY = cy + (float) Math.sin(coreAng * 1.3f) * drift * 0.5f;
        paint.setShader(new RadialGradient(coreX, coreY, coreR,
                new int[]{ withAlpha(Color.WHITE, (int) (200 + 55 * level)),
                           withAlpha(mix(Color.WHITE, accent, 0.5f), 120), Color.TRANSPARENT },
                new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(coreX, coreY, coreR, paint);
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
