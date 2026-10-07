package com.fileman.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/**
 * Small drawing used by the home tiles: a progress ring around an icon (storage volumes) or a filled pie
 * (storage analysis). Colours come from the app palette so every colour theme works.
 */
public class UsageView extends View {
    public static final int RING = 0, PIE = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int mode = RING;
    private float percent = 0f;
    private int fillColor = 0xFF2F6FEB;
    private int trackColor = 0x33888888;
    private Drawable icon;

    public UsageView(Context c) {
        this(c, null);
    }

    public UsageView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setUsage(int mode, double percent, int fillColor, int trackColor) {
        this.mode = mode;
        this.percent = (float) Math.max(0, Math.min(100, percent));
        this.fillColor = fillColor;
        this.trackColor = trackColor;
        invalidate();
    }

    /** Icon drawn in the middle of the ring (ignored for the pie). */
    public void setCenterIcon(int res, int tint) {
        Drawable d = getResources().getDrawable(res, getContext().getTheme());
        if (d != null) {
            d = d.mutate();
            d.setTintList(ColorStateList.valueOf(tint));
        }
        icon = d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        if (mode == PIE) {
            float r = size * 0.36f;
            rect.set(cx - r, cy - r, cx + r, cy + r);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(trackColor);
            canvas.drawOval(rect, paint);
            paint.setColor(fillColor);
            canvas.drawArc(rect, -90f, percent * 3.6f, true, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.02f);
            paint.setColor(0x33FFFFFF);
            canvas.drawOval(rect, paint);
            return;
        }
        float stroke = size * 0.07f;
        float r = size * 0.5f - stroke * 1.3f;
        rect.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        paint.setColor(trackColor);
        canvas.drawArc(rect, 0f, 360f, false, paint);
        paint.setColor(fillColor);
        if (percent > 0.5f) canvas.drawArc(rect, -90f, percent * 3.6f, false, paint);
        if (icon != null) {
            int half = Math.round(size * 0.17f);
            icon.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
            icon.draw(canvas);
        }
    }
}
