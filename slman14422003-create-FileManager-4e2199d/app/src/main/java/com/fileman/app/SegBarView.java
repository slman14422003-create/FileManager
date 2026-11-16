package com.fileman.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Horizontal storage bar split into coloured segments (one per kind of file) on a rounded track. */
public class SegBarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private float[] frac = new float[0];
    private int[] cols = new int[0];
    private int track = 0x33888888;

    public SegBarView(Context c) {
        this(c, null);
    }

    public SegBarView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** frac: share of the whole disk per segment (0..1), drawn in order from the start edge. */
    public void setData(float[] frac, int[] cols, int track) {
        this.frac = frac;
        this.cols = cols;
        this.track = track;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float r = h / 2f;
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(track);
        canvas.drawRect(0, 0, w, h, paint);
        float gap = getResources().getDisplayMetrics().density * 1.5f;
        float x = 0f;
        for (int i = 0; i < frac.length && i < cols.length; i++) {
            float seg = Math.max(0f, Math.min(1f, frac[i])) * w;
            if (seg < 0.5f) continue;
            float from = x;
            float to = Math.min(w, x + seg);
            x = to;
            float a = from;
            float b = Math.max(from, to - (to < w ? gap : 0f));
            if (rtl) {
                float na = w - b;
                float nb = w - a;
                a = na;
                b = nb;
            }
            paint.setColor(cols[i]);
            canvas.drawRect(a, 0, b, h, paint);
        }
        canvas.restore();
    }
}
