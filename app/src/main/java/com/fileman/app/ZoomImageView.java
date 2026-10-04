package com.fileman.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/** Image view with pinch-to-zoom, drag, double-tap zoom and a horizontal swipe callback. */
public class ZoomImageView extends AppCompatImageView {
    public interface SwipeListener {
        /** dir = +1 for next, -1 for previous. */
        void onSwipe(int dir);
    }

    private static final float MAX_ZOOM = 6f;

    private final Matrix matrix = new Matrix();
    private final float[] vals = new float[9];
    private float baseScale = 1f;
    private SwipeListener swipe;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public ZoomImageView(Context c) {
        this(c, null);
    }

    public ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float cur = currentScale();
                float target = Math.max(baseScale, Math.min(baseScale * MAX_ZOOM, cur * d.getScaleFactor()));
                float f = target / cur;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (isZoomed()) {
                    matrix.postTranslate(-dx, -dy);
                    fixTranslation();
                    setImageMatrix(matrix);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isZoomed()) {
                    fit();
                } else {
                    float f = 2.5f;
                    matrix.postScale(f, f, e.getX(), e.getY());
                    fixTranslation();
                    setImageMatrix(matrix);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!isZoomed() && swipe != null && Math.abs(vx) > 800 && Math.abs(vx) > Math.abs(vy)) {
                    swipe.onSwipe(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setSwipeListener(SwipeListener l) {
        swipe = l;
    }

    /** Shows a bitmap fitted to the view. */
    public void show(android.graphics.Bitmap b) {
        setImageBitmap(b);
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    private float currentScale() {
        matrix.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    private boolean isZoomed() {
        return currentScale() > baseScale * 1.02f;
    }

    private void fit() {
        Drawable d = getDrawable();
        int vw = getWidth();
        int vh = getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        baseScale = Math.min((float) vw / dw, (float) vh / dh);
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((vw - dw * baseScale) / 2f, (vh - dh * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    /** Keeps the image inside the view (or centered when it is smaller than the view). */
    private void fixTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;
        matrix.getValues(vals);
        float s = vals[Matrix.MSCALE_X];
        float w = d.getIntrinsicWidth() * s;
        float h = d.getIntrinsicHeight() * s;
        float tx = vals[Matrix.MTRANS_X];
        float ty = vals[Matrix.MTRANS_Y];
        float vw = getWidth();
        float vh = getHeight();
        float nx = w <= vw ? (vw - w) / 2f : Math.max(vw - w, Math.min(0f, tx));
        float ny = h <= vh ? (vh - h) / 2f : Math.max(vh - h, Math.min(0f, ty));
        matrix.postTranslate(nx - tx, ny - ty);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        if (!scaleDetector.isInProgress()) gestureDetector.onTouchEvent(ev);
        return true;
    }
}
