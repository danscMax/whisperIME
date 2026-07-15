package com.whispertflite.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scrolling live-audio waveform. {@link #push(float)} appends one bar from a normalized RMS value;
 * only the newest bars that fit the width are drawn, so the wave scrolls left while recording.
 * Bars are rounded, gradient-filled and softly glowing so the wave reads as premium, not utilitarian.
 * Must be driven from the UI thread (push calls invalidate).
 */
public class WaveformView extends View {

    private static final int MAX_BARS = 96;

    private final Deque<Float> mBars = new ArrayDeque<>(MAX_BARS);
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mBarWidth;
    private final float mGap;
    private final int mAccent;

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = Resources.getSystem().getDisplayMetrics().density;
        mBarWidth = 3.5f * density;
        mGap = 3f * density;
        mPaint.setStyle(Paint.Style.FILL);
        // Resolve colorPrimary from the themed context so the wave follows the active palette.
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, tv, true);
        mAccent = tv.data;
        mPaint.setColor(mAccent);
        // Soft glow around each bar.
        mPaint.setShadowLayer(6f * density, 0f, 0f, withAlpha(mAccent, 140));
        setLayerType(LAYER_TYPE_SOFTWARE, null); // shadowLayer needs software rendering
    }

    private static int withAlpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Append one bar from a normalized RMS value (0..1). */
    public void push(float rms) {
        if (rms < 0f) rms = 0f;
        if (rms > 1f) rms = 1f;
        if (mBars.size() >= MAX_BARS) mBars.pollFirst();
        mBars.addLast(rms);
        invalidate();
    }

    public void clear() {
        mBars.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mBars.isEmpty()) return;
        float step = mBarWidth + mGap;
        int maxVisible = Math.max(1, (int) (getWidth() / step));
        Float[] arr = mBars.toArray(new Float[0]);
        int count = Math.min(arr.length, maxVisible);
        float cy = getHeight() / 2f;
        float minH = mBarWidth;                 // a dot when silent
        float maxH = getHeight() * 0.92f;
        // Vertical gradient: bright at the crest, deep toward the centre line.
        mPaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                new int[]{ mAccent, withAlpha(mAccent, 200), mAccent },
                new float[]{ 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        // Right-align the newest `count` bars; fade the oldest for a trailing look.
        float x = getWidth() - count * step + mGap / 2f;
        int drawn = 0;
        for (int i = arr.length - count; i < arr.length; i++, drawn++) {
            float h = minH + arr[i] * (maxH - minH);
            int alpha = (int) (255 * (0.35f + 0.65f * drawn / (float) count)); // older = fainter
            mPaint.setAlpha(alpha);
            mRect.set(x, cy - h / 2f, x + mBarWidth, cy + h / 2f);
            canvas.drawRoundRect(mRect, mBarWidth / 2f, mBarWidth / 2f, mPaint);
            x += step;
        }
        mPaint.setShader(null);
        mPaint.setAlpha(255);
    }
}
