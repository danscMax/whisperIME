package com.whispertflite.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * A frosted-glass bar container that blurs the sibling content scrolling behind it (same-window
 * backdrop blur) while keeping its own children (toolbar, search) crisp on top.
 *
 * <p>Each pre-draw it captures the {@link #attach(View) target} into a small downscaled bitmap offset
 * to its own position; in {@link #onDraw} (which paints behind the children) it draws that bitmap back
 * magnified — refined with a {@link RenderEffect} via a {@link RenderNode} on API&nbsp;31+. Below
 * API&nbsp;31 the downscale itself provides a soft blur. A translucent tint + hairline complete the glass.
 */
public class FrostedBlurView extends FrameLayout {

    private static final float DOWNSCALE = 0.13f;    // capture at ~1/8 res; magnify-back is a cheap blur
    private static final float BLUR_RADIUS = 22f;    // extra RenderEffect smoothing (API 31+)

    private View target;
    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private RenderNode node;                          // API 31+ blur of the captured bitmap only
    private final int[] here = new int[2];
    private final int[] there = new int[2];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int tint = 0x1FFFFFFF;
    private int hairline = 0x24FFFFFF;
    private boolean dirty = true;                     // recapture only when the content behind changed

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        if (dirty) { dirty = false; capture(); }
        return true;
    };

    public FrostedBlurView(Context context) { super(context); init(); }
    public FrostedBlurView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setWillNotDraw(false);   // FrameLayout skips onDraw by default; we paint the blur there
    }

    /** The scrolling content view to blur (e.g. the RecyclerView). */
    public void attach(View target) { this.target = target; markDirty(); }

    /** Request a recapture of the content behind (call from the content's scroll listener). */
    public void markDirty() { dirty = true; invalidate(); }

    /** Glass tint (ARGB) painted over the blur, and the bottom hairline colour. */
    public void setGlass(int tintArgb, int hairlineArgb) {
        this.tint = tintArgb;
        this.hairline = hairlineArgb;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    private void capture() {
        if (target == null || getWidth() == 0 || getHeight() == 0) return;
        int w = Math.max(1, (int) (getWidth() * DOWNSCALE));
        int h = Math.max(1, (int) (getHeight() * DOWNSCALE));
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmapCanvas = new Canvas(bitmap);
        }
        bitmap.eraseColor(Color.TRANSPARENT);
        getLocationInWindow(here);
        target.getLocationInWindow(there);
        bitmapCanvas.save();
        bitmapCanvas.scale(DOWNSCALE, DOWNSCALE);
        bitmapCanvas.translate(there[0] - here[0], there[1] - here[1]);
        try {
            target.draw(bitmapCanvas);   // current scroll state of the content behind the bar
        } catch (Exception ignored) {
            // Defensive: a mid-layout draw can throw; skip this frame rather than crash.
        }
        bitmapCanvas.restore();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        markDirty();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap != null) {
            float sx = (float) getWidth() / bitmap.getWidth();
            float sy = (float) getHeight() / bitmap.getHeight();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated()) {
                if (node == null) node = new RenderNode("frost");
                node.setPosition(0, 0, getWidth(), getHeight());
                node.setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP));
                RecordingCanvas rc = node.beginRecording();
                rc.scale(sx, sy);
                rc.drawBitmap(bitmap, 0, 0, paint);
                node.endRecording();
                canvas.drawRenderNode(node);
            } else {
                canvas.save();
                canvas.scale(sx, sy);
                canvas.drawBitmap(bitmap, 0, 0, paint);
                canvas.restore();
            }
        }
        canvas.drawColor(tint);
        paint.setColor(hairline);
        canvas.drawRect(0, getHeight() - 1f, getWidth(), getHeight(), paint);
    }
}
