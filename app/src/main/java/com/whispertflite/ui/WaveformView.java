package com.whispertflite.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scrolling live-audio waveform. {@link #push(float)} appends one bar from a normalized RMS value;
 * only the newest bars that fit the width are drawn, so the wave scrolls left while recording.
 * Must be driven from the UI thread (push calls invalidate).
 */
public class WaveformView extends View {

    private static final int MAX_BARS = 96;

    private final Deque<Float> mBars = new ArrayDeque<>(MAX_BARS);
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mBarWidth;
    private final float mGap;

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = Resources.getSystem().getDisplayMetrics().density;
        mBarWidth = 3f * density;
        mGap = 2f * density;
        mPaint.setStyle(Paint.Style.FILL);
        // Resolve colorPrimary from the themed context so the wave follows the active palette.
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, tv, true);
        mPaint.setColor(tv.data);
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
        float maxH = getHeight() * 0.9f;
        // Right-align the newest `count` bars.
        float x = getWidth() - count * step + mGap / 2f;
        for (int i = arr.length - count; i < arr.length; i++) {
            float h = minH + arr[i] * (maxH - minH);
            mRect.set(x, cy - h / 2f, x + mBarWidth, cy + h / 2f);
            canvas.drawRoundRect(mRect, mBarWidth / 2f, mBarWidth / 2f, mPaint);
            x += step;
        }
    }
}
