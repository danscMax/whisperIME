package com.whispertflite.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Living Signal — a glowing "voice orb" drawn with the 2D Canvas (a {@link RadialGradient} sphere).
 *
 * <p>Deliberately Canvas, NOT a GLES {@code TextureView}: the GLES orb was fragile inside the IME
 * window (quarter-circle from an EGL surface/viewport mismatch, blank after a surface detach, glow
 * clipped at the view edge). A Canvas view owns none of that — the framework owns the buffer, there is
 * no EGL/SurfaceTexture lifecycle, and the glow is drawn with padding so it never clips. State is
 * carried by colour + energy: the orb breathes calmly when idle and flares/grows with the mic level
 * pushed via {@link #pushLevel}. Public API is unchanged so callers need no edits.
 */
public class LivingSignalView extends View {

    public enum SignalState { READY, LISTENING, PROCESSING, RESULT, ERROR }

    private SignalState state = SignalState.READY;
    private float targetLevel;   // 0..1, set from pushLevel (already amplified from raw RMS)
    private float level;         // smoothed toward targetLevel
    private float act;           // smoothed activity that drives size + brightness
    private float phase;         // breathing phase

    // Palette accent as HUE only; the orb keeps its own airy saturation/value so it reads as a bright
    // glowing sphere (blending toward raw palette colours darkened it, exactly what went wrong first).
    private float accentHue = 190f;
    private boolean accentValid;
    private final float[] hsvScratch = new float[3];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastFrameNs;
    private boolean reducedMotion;

    public LivingSignalView(Context context) {
        super(context);
        init();
    }

    public LivingSignalView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        try {
            reducedMotion = Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            reducedMotion = false;
        }
    }

    // ----- public API (unchanged) -----

    public void setSignalState(SignalState next) {
        if (next != null && next != state) {
            state = next;
            postInvalidateOnAnimation();
        }
    }

    public SignalState getSignalState() {
        return state;
    }

    public void pushLevel(float rms) {
        // Speech RMS from the recorder lands in a narrow ~0.02–0.08 band (measured on-device); subtract a
        // silence floor and expand so the voice drives the full activity range and the orb visibly pulses
        // with speech instead of sitting near its idle baseline.
        targetLevel = clamp01(Math.max(0f, rms - 0.015f) * 16f);
        postInvalidateOnAnimation();   // react even if the idle loop is paused (reduced motion)
    }

    public void setIdle() {
        targetLevel = 0f;
        state = SignalState.READY;
        postInvalidateOnAnimation();
    }

    /** Adopt the app palette accent as HUE only so the orb recolours to the palette while staying bright. */
    public void setColors(int bright, int soft) {
        android.graphics.Color.colorToHSV(bright, hsvScratch);
        accentHue = hsvScratch[0];
        accentValid = true;
        postInvalidateOnAnimation();
    }

    /** Kept for API compatibility — a Canvas view has no GL surface to re-arm; just kick a redraw. */
    public void resumeRender() {
        lastFrameNs = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNs = 0;
        postInvalidateOnAnimation();
    }

    // ----- rendering -----

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0.016f : Math.min((now - lastFrameNs) / 1e9f, 0.05f);
        lastFrameNs = now;

        boolean listening = state == SignalState.LISTENING;
        boolean processing = state == SignalState.PROCESSING;
        boolean result = state == SignalState.RESULT;
        boolean error = state == SignalState.ERROR;

        // Mic level: fast attack / slow decay, and only while listening.
        float lvlTarget = listening ? targetLevel : 0f;
        level += (lvlTarget - level) * (lvlTarget > level ? 0.55f : 0.10f);

        // Idle breathing — the gentle "just sitting there" motion; faster while active.
        phase += dt * (listening || result ? 2.6f : 1.0f);
        float breath = 0.5f + 0.5f * (float) Math.sin(phase);
        float breathAmp = reducedMotion ? 0f : (listening ? 0.05f : 0.14f);

        // Activity baseline per state + the voice level; this drives radius and brightness.
        float base = processing ? 0.45f : result ? 0.60f : error ? 0.20f : 0.14f;
        float voice = listening ? level : 0f;
        float actTarget = clamp01(base + voice * 0.9f + breath * breathAmp);
        act += (actTarget - act) * Math.min(1f, dt * 10f);

        int w = getWidth(), h = getHeight();
        float cx = w * 0.5f, cy = h * 0.5f;
        // Padding (0.96) so the glow's soft edge always fades out INSIDE the view — never a hard clip.
        float maxR = Math.min(w, h) * 0.5f * 0.96f;
        float r = maxR * (0.60f + act * 0.32f);   // 0.60 (idle) .. 0.92 (loud) of the padded radius
        if (r < 1f) { postInvalidateOnAnimation(); return; }

        // Hue encodes STATE so the orb is glanceable at a glance: palette-tinted calm when idle, red while
        // recording, amber while transcribing, green on a fresh result. Size/breathing alone read as "same
        // colour, slightly bigger" — users couldn't tell listening from idle from processing.
        float hue = listening ? 4f              // red — recording
                : processing ? 38f              // amber — transcribing
                : result ? 140f                 // green — done
                : accentValid ? accentHue : 200f;   // ready — palette accent (calm)
        // Airy, bright colours (the orb owns its S/V) so it glows instead of muddying.
        int body = error ? 0xFFE0564B : hsv(hue, 0.62f, 0.99f);                        // saturated bright body
        int rim = error ? 0xFFC24A40 : hsv(hue, 0.78f, 0.92f);                         // deeper rim for the glow edge
        int core = error ? 0xFFF7D2CE : hsv(hue, Math.max(0f, 0.16f - act * 0.12f), 1f); // near-white core, whiter on voice

        // white-ish core -> saturated body -> soft rim -> transparent glow tail
        int[] colors = {core, body, rim, setAlpha(rim, 0)};
        float[] stops = {0f, 0.42f, 0.72f, 1f};
        paint.setShader(new RadialGradient(cx, cy, r, colors, stops, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        // Keep animating while there is motion; honour reduced-motion by settling to a static idle frame.
        boolean moving = level > 0.01f || Math.abs(act - actTarget) > 0.003f;
        if (!reducedMotion || moving) postInvalidateOnAnimation();
    }

    // ----- helpers -----

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static int setAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private int hsv(float h, float s, float v) {
        hsvScratch[0] = h;
        hsvScratch[1] = s;
        hsvScratch[2] = v;
        return android.graphics.Color.HSVToColor(hsvScratch);
    }
}
