package com.whispertflite.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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

    // Organic "liquid signal" blob: the silhouette is a smooth closed spline through N angular
    // samples, each radius shaped by LivingSignalDynamics.blobRadius (breathing + iris petals + burst).
    private static final int SAMPLES = 64;
    private final float[] px = new float[SAMPLES];
    private final float[] py = new float[SAMPLES];
    private final Path blobPath = new Path();

    private SignalState state = SignalState.READY;
    private float targetLevel;   // 0..1, set from pushLevel (already amplified from raw RMS)
    private float level;         // smoothed toward targetLevel
    private float act;           // smoothed activity that drives size + brightness
    private float phase;         // breathing phase
    private float spin;          // accumulated petal rotation (Focused-Iris spins faster)
    private float burst;         // 0..1 Signal-Burst envelope, kicked on a fresh RESULT, decays to a calm glow

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
            if (next == SignalState.RESULT) burst = 1f;   // radiant Signal-Burst on a fresh result
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

        // Petal rotation + burst envelope advance with the frame (frozen under reduced-motion so the frame
        // is static). Focused-Iris (processing) spins fastest; the burst decays into a calm result glow.
        if (!reducedMotion) {
            spin += dt * (processing ? 3.2f : listening ? 1.4f : 0.5f);
            burst -= burst * Math.min(1f, dt * 4f);
        } else {
            burst = 0f;
        }

        int w = getWidth(), h = getHeight();
        float cx = w * 0.5f, cy = h * 0.5f;
        // Padding (0.96) so the outer glow always fades INSIDE the view — never a hard clip.
        float maxR = Math.min(w, h) * 0.5f * 0.96f;
        // Mean radius. It grows with the smoothed activity AND, while listening, directly with the live voice
        // level, so the orb visibly swells in rhythm with loudness (fast attack / slow decay via `level`).
        // Capped so the petal peaks never clip the view edge (peak ~= meanR * 1.21 at full voice; 0.78*1.21
        // stays inside the 0.96 padding).
        float voiceBoost = listening ? level * 0.18f : 0f;
        float meanR = maxR * Math.min(0.78f, 0.52f + act * 0.16f + voiceBoost);
        if (meanR < 1f) { postInvalidateOnAnimation(); return; }

        // Hue encodes STATE so the orb is glanceable: palette-tinted calm when idle, red while recording,
        // amber while transcribing, green on a fresh result. Colour, not just size, tells the states apart.
        float hue = listening ? 4f              // red — recording
                : processing ? 38f              // amber — transcribing
                : result ? 140f                 // green — done
                : accentValid ? accentHue : 200f;   // ready — palette accent (calm)
        // Luminous, not merely saturated: a bright near-white core reads as a glowing light (harmonises with
        // any background) while a moderate body keeps the hue. Over-saturating turned it into a colour block
        // that clashed with the warm glass background.
        int body = error ? 0xFFE0564B : hsv(hue, 0.72f, 1f);                                 // luminous coloured body
        int rim = error ? 0xFFC24A40 : hsv(hue, 0.90f, 0.90f);                               // deeper rim -> soft glow edge
        int core = error ? 0xFFF7D2CE : hsv(hue, Math.max(0.14f, 0.24f - act * 0.10f), 1f);  // bright near-white core

        // Blob edge amplitudes — kept GENTLE so the silhouette is a softly-living orb, not a spiky inkblot.
        // Idle breathes lightly; listening opens iris petals with the voice; processing keeps a tight
        // rotating aperture; a fresh result flares a subtle burst.
        float wobAmp = reducedMotion ? 0f : (listening ? 0.03f : processing ? 0.03f : 0.045f);
        float petalAmp = reducedMotion ? 0f : (listening ? 0.04f + 0.12f * level : processing ? 0.04f : 0.015f);
        int lobes = listening ? 8 : 6;
        float burstAmp = reducedMotion ? 0f : burst * 0.22f;

        // Sample the organic edge, tracking the outermost tip so the gradient reaches it (tips fade to
        // transparent -> a soft liquid silhouette, no facets).
        float peakR = meanR;
        for (int i = 0; i < SAMPLES; i++) {
            float a = (float) (2 * Math.PI * i / SAMPLES);
            float rr = LivingSignalDynamics.blobRadius(meanR, a, phase, spin, wobAmp, petalAmp, lobes, burstAmp);
            if (rr > peakR) peakR = rr;
            px[i] = cx + rr * (float) Math.cos(a);
            py[i] = cy + rr * (float) Math.sin(a);
        }

        // Smooth closed spline (Catmull-Rom -> cubic Bézier) through the samples.
        blobPath.reset();
        blobPath.moveTo(px[0], py[0]);
        for (int i = 0; i < SAMPLES; i++) {
            int i0 = (i - 1 + SAMPLES) % SAMPLES, i1 = i, i2 = (i + 1) % SAMPLES, i3 = (i + 2) % SAMPLES;
            float c1x = px[i1] + (px[i2] - px[i0]) / 6f, c1y = py[i1] + (py[i2] - py[i0]) / 6f;
            float c2x = px[i2] - (px[i3] - px[i1]) / 6f, c2y = py[i2] - (py[i3] - py[i1]) / 6f;
            blobPath.cubicTo(c1x, c1y, c2x, c2y, px[i2], py[i2]);
        }
        blobPath.close();

        // 1) Soft outer glow halo — a wide radial gradient fading into the background so the orb reads as a
        // glowing light, not a hard-edged sticker. Drawn as a circle (NOT clipped to the blob) so the glow
        // extends past the silhouette; capped at maxR so it never hits the view edge.
        float haloR = Math.min(peakR * 1.7f, maxR);
        paint.setShader(new RadialGradient(cx, cy, haloR,
                new int[]{setAlpha(body, 90), setAlpha(body, 0)}, new float[]{0.22f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, haloR, paint);

        // 2) The organic body: bright core -> body -> rim -> a soft transparent edge. The core stays solid
        // (never breathes toward transparent) while the edge falls off gently, so the silhouette is soft.
        int[] colors = {core, body, rim, setAlpha(rim, 0)};
        float[] stops = {0f, 0.42f, 0.78f, 1f};
        paint.setShader(new RadialGradient(cx, cy, peakR, colors, stops, Shader.TileMode.CLAMP));
        canvas.drawPath(blobPath, paint);
        paint.setShader(null);

        // Keep animating while there is motion; honour reduced-motion by settling to a static idle frame.
        boolean moving = level > 0.01f || burst > 0.01f || Math.abs(act - actTarget) > 0.003f;
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
