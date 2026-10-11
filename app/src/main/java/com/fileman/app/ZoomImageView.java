package com.fileman.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * Image view with pinch-to-zoom, drag, animated double-tap zoom, a finger-following horizontal swipe
 * (next / previous image), a fling-down to close and a single-tap callback.
 */
public class ZoomImageView extends AppCompatImageView {
    public interface SwipeListener {
        /** dir = +1 for next, -1 for previous. The listener must slide to the new image or call {@link #snapBack()}. */
        void onSwipe(int dir);
    }

    public interface TapListener {
        void onTap();
    }

    public interface DismissListener {
        void onDismiss();
    }

    private static final float MAX_ZOOM = 6f;

    private final Matrix matrix = new Matrix();
    private final float[] vals = new float[9];
    private float baseScale = 1f;
    private float dragX = 0f;
    private boolean flinged = false;
    private boolean hasPrev = true, hasNext = true;
    private ValueAnimator zoomAnim;
    private SwipeListener swipe;
    private TapListener tap;
    private DismissListener dismiss;
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
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tap != null) tap.onTap();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (isZoomed()) {
                    matrix.postTranslate(-dx, -dy);
                    fixTranslation();
                    setImageMatrix(matrix);
                } else if (swipe != null && (Math.abs(dx) > Math.abs(dy) || dragX != 0f)) {
                    float next = dragX - dx;
                    boolean blocked = (next < 0 && !hasNext) || (next > 0 && !hasPrev);
                    dragX = blocked ? dragX - dx * 0.3f : next;   // rubber band where there is no image
                    setTranslationX(dragX);
                    setAlpha(1f - Math.min(0.3f, Math.abs(dragX) / Math.max(1f, getWidth()) * 0.5f));
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isZoomed()) animateScale(baseScale, getWidth() / 2f, getHeight() / 2f);
                else animateScale(baseScale * 2.5f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (isZoomed()) return false;
                if (swipe != null && Math.abs(vx) > 900 && Math.abs(vx) > Math.abs(vy)) {
                    flinged = true;
                    swipe.onSwipe(vx < 0 ? 1 : -1);
                    return true;
                }
                if (dismiss != null && vy > 2200 && Math.abs(vy) > Math.abs(vx) * 1.5f) {
                    flinged = true;
                    dismiss.onDismiss();
                    return true;
                }
                return false;
            }
        });
    }

    public void setSwipeListener(SwipeListener l) {
        swipe = l;
    }

    public void setTapListener(TapListener l) {
        tap = l;
    }

    public void setDismissListener(DismissListener l) {
        dismiss = l;
    }

    /** Shows a bitmap fitted to the view. */
    public void show(android.graphics.Bitmap b) {
        dragX = 0f;
        flinged = false;
        setImageBitmap(b);
        fit();
    }

    /** Tells the view whether there is an image before / after the current one (rubber-band at the ends). */
    public void setEdges(boolean prev, boolean next) {
        hasPrev = prev;
        hasNext = next;
    }

    /** Slides the picture back to the middle (a swipe that had nowhere to go, or one that was not far enough). */
    public void snapBack() {
        dragX = 0f;
        animate().cancel();
        animate().translationX(0f).alpha(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.8f)).withLayer().start();
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

    private void animateScale(final float target, final float fx, final float fy) {
        if (zoomAnim != null) zoomAnim.cancel();
        final float start = currentScale();
        zoomAnim = ValueAnimator.ofFloat(0f, 1f);
        zoomAnim.setDuration(260);
        zoomAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        zoomAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float want = start + (target - start) * t;
            float f = want / currentScale();
            matrix.postScale(f, f, fx, fy);
            fixTranslation();
            setImageMatrix(matrix);
        });
        zoomAnim.start();
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

    private void settleDrag() {
        if (dragX == 0f) return;
        float w = Math.max(1, getWidth());
        if (!flinged) {
            if (Math.abs(dragX) > w * 0.25f && swipe != null) {
                flinged = true;
                swipe.onSwipe(dragX < 0 ? 1 : -1);
            } else {
                snapBack();
            }
        }
        dragX = 0f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            flinged = false;
            if (zoomAnim != null) zoomAnim.cancel();
            animate().cancel();
            dragX = getTranslationX();
        }
        scaleDetector.onTouchEvent(ev);
        if (!scaleDetector.isInProgress()) gestureDetector.onTouchEvent(ev);
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) settleDrag();
        return true;
    }
}
