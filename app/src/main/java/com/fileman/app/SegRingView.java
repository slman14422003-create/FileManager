package com.fileman.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** Donut chart of the storage: one coloured arc per kind of file, the percentage used in the middle. */
public class SegRingView extends View {
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float[] frac = new float[0];
    private int[] cols = new int[0];
    private int track = 0x33888888, textColor = 0xFFFFFFFF, subColor = 0xFF999999;
    private String center = "", sub = "";

    public SegRingView(Context c) {
        this(c, null);
    }

    public SegRingView(Context c, AttributeSet a) {
        super(c, a);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.BUTT);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** frac: share of the whole disk per segment (0..1), in drawing order starting at 12 o'clock. */
    public void setData(float[] frac, int[] cols, int track, String center, String sub, int textColor, int subColor) {
        this.frac = frac;
        this.cols = cols;
        this.track = track;
        this.center = center;
        this.sub = sub;
        this.textColor = textColor;
        this.subColor = subColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = size * 0.115f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = size / 2f - stroke / 2f;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        arc.setStrokeWidth(stroke);
        arc.setColor(track);
        canvas.drawArc(rect, 0, 360, false, arc);
        float angle = -90f;
        for (int i = 0; i < frac.length; i++) {
            float sweep = Math.max(0f, Math.min(1f, frac[i])) * 360f;
            if (sweep < 0.6f) continue;
            float gap = sweep > 6f ? 2.4f : 0f;
            arc.setColor(cols[i]);
            canvas.drawArc(rect, angle + gap / 2f, sweep - gap, false, arc);
            angle += sweep;
        }
        text.setColor(textColor);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(size * 0.24f);
        canvas.drawText(center, cx, cy + size * 0.04f, text);
        text.setColor(subColor);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(size * 0.1f);
        canvas.drawText(sub, cx, cy + size * 0.19f, text);
    }
}
