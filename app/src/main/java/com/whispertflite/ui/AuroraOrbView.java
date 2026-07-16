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

    public AuroraOrbView(Context c) { super(c); init(); }
    public AuroraOrbView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        anim.setDuration(4200);
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

        // Symmetric: everything centred. Only two motions — a slow breathe and the voice swell.
        float breathe = 0.5f + 0.5f * (float) Math.sin(phase);
        float swell = 0.74f + 0.06f * breathe + 0.22f * level;

        // One single sphere (Siri idiom): a single continuous radial gradient with a MONOTONE falloff
        // — a soft light centre eases through the light tint into the deep accent and feathers out.
        // No pure-white dot and no alpha dip mid-way, so there is no visible ring: just one glowing orb.
        float r = baseR * (0.98f + 0.30f * level); // fills the view; the outer band is the glow
        int centre = mix(accentSoft, Color.WHITE, 0.55f + 0.35f * level); // light, brighter with voice
        paint.setShader(new RadialGradient(cx, cy, r,
                new int[]{ centre, accentSoft, accent, withAlpha(accent, 0) },
                new float[]{ 0f, 0.42f, 0.78f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
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
