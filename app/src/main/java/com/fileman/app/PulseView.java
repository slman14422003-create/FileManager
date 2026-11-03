package com.fileman.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Lightweight "radar" animation: 3 rings expand outward from a solid centre dot and fade as
 * they grow, looping continuously. Used behind the QR code while waiting to connect (send) and
 * behind the transfer icon while a download is active (receive) — gives otherwise static
 * "waiting" screens a sense of motion, similar to AirDrop's searching animation.
 */
public final class PulseView extends View {
    private static final int RINGS = 3;
    private static final long PERIOD = 1800L;

    private ValueAnimator animator;
    private float phase = 0f;
    private int ringColor = 0xFF007AFF;
    private int dotColor = 0xFF007AFF;

    public PulseView(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public PulseView(Context c) {
        this(c, null);
    }

    /** Tints the rings/dot to the app's current accent colour. */
    public void useAccent(Context c) {
        int accent = Ui.color(c, R.color.accent);
        setColors(accent, accent);
    }

    public void setColors(int ring, int dot) {
        ringColor = ring;
        dotColor = dot;
        invalidate();
    }

    public void start() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(PERIOD);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        phase = 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy);
        if (maxR <= 0) return;

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(getContext(), 1.6f));

        for (int i = 0; i < RINGS; i++) {
            float t = phase + i / (float) RINGS;
            t -= Math.floor(t);
            float r = maxR * (0.28f + 0.72f * t);
            int alpha = (int) (160 * (1f - t));
            ring.setColor(ringColor);
            ring.setAlpha(Math.max(0, alpha));
            canvas.drawCircle(cx, cy, r, ring);
        }

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(dotColor);
        canvas.drawCircle(cx, cy, maxR * 0.16f, dot);
    }
}
