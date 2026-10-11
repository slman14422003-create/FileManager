package com.fileman.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Wraps one vertically scrolling child (the PDF page list) and gives it live, PDF-reader style zoom:
 * <ul>
 *   <li>two-finger pinch zooms continuously around the fingers and pans with them (no jump on release);</li>
 *   <li>double tap toggles zoom with a short animation;</li>
 *   <li>while zoomed, a one-finger drag pans freely: horizontally here, vertically by the child's own scrolling.</li>
 * </ul>
 * The child is scaled with a view transform (pivot top-left) and its height is divided by the zoom, so it always
 * fills the screen vertically and keeps scrolling normally. The owner re-renders sharper content when the
 * zoom settles ({@link Listener#onZoomSettled}).
 */
public class PinchZoomLayout extends FrameLayout {
    public interface Listener {
        /** Called on every zoom change (live). */
        void onZoomChanged(float zoom);

        /** Called once a pinch / animation finished: time to re-render at the final resolution. */
        void onZoomSettled(float zoom);

        /** Scroll the child's content by dy pixels (child coordinates); dy &gt; 0 reveals content further down. */
        void scrollContentBy(int dy);
    }

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private Listener listener;
    private float minZoom = 1f;
    private float maxZoom = 4f;
    private float doubleTapZoom = 2.5f;

    private float zoom = 1f;
    private float tx = 0f;          // horizontal translation of the child (<= 0)
    private float prevFx, prevFy;   // previous pinch focus
    private float carryY = 0f;      // fractional vertical scroll not yet applied
    private float lastX;
    private boolean multi = false;
    private ValueAnimator anim;

    public PinchZoomLayout(Context c) {
        super(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (target() == null) return false;
                cancelAnim();
                prevFx = d.getFocusX();
                prevFy = d.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX();
                float fy = d.getFocusY();
                zoomAbout(zoom * d.getScaleFactor(), prevFx, prevFy, fx, fy);
                prevFx = fx;
                prevFy = fy;
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                settle();
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
        tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateZoomTo(zoom < 1.5f ? doubleTapZoom : minZoom, e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setZoomRange(float min, float max, float doubleTap) {
        minZoom = min;
        maxZoom = max;
        doubleTapZoom = doubleTap;
    }

    public float getZoom() {
        return zoom;
    }

    private View target() {
        return getChildCount() > 0 ? getChildAt(0) : null;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Sets a new zoom so that the content point that was under (fromX, fromY) ends up under (toX, toY).
     * Horizontal position is a translation; vertical position is done by scrolling the child.
     */
    private void zoomAbout(float newZoom, float fromX, float fromY, float toX, float toY) {
        View t = target();
        if (t == null || getWidth() <= 0) return;
        newZoom = clamp(newZoom, minZoom, maxZoom);
        float xc = (fromX - tx) / zoom;
        float yl = fromY / zoom;
        float dyF = yl - toY / newZoom + carryY;
        zoom = newZoom;
        tx = toX - xc * zoom;
        apply();
        int dy = Math.round(dyF);
        carryY = dyF - dy;
        if (dy != 0 && listener != null) listener.scrollContentBy(dy);
        if (listener != null) listener.onZoomChanged(zoom);
    }

    /** Pushes zoom + translation to the child and sizes it so it fills the layout vertically. */
    private void apply() {
        View t = target();
        int w = getWidth();
        int h = getHeight();
        if (t == null || w <= 0 || h <= 0) return;
        tx = clamp(tx, w - w * zoom, 0f);
        t.setPivotX(0f);
        t.setPivotY(0f);
        t.setScaleX(zoom);
        t.setScaleY(zoom);
        t.setTranslationX(tx);
        t.setTranslationY(0f);
        ViewGroup.LayoutParams lp = t.getLayoutParams();
        int nh = (int) Math.ceil(h / zoom);
        if (lp != null && lp.height != nh) {
            lp.height = nh;
            t.setLayoutParams(lp);
        }
    }

    private void settle() {
        carryY = 0f;
        if (listener != null) listener.onZoomSettled(zoom);
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    /** Animated zoom (double tap, zoom button) keeping the point (fx, fy) fixed on screen. */
    public void animateZoomTo(float target, final float fx, final float fy) {
        cancelAnim();
        final float from = zoom;
        final float to = clamp(target, minZoom, maxZoom);
        if (Math.abs(to - from) < 0.005f) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            zoomAbout(from + (to - from) * f, fx, fy, fx, fy);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled) settle();
            }
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        apply();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        tapDetector.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multi = false;
            cancelAnim();
            lastX = ev.getX();
        }
        if (ev.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            if (!multi) {
                // a second finger arrived: the child must stop its own scroll / fling
                multi = true;
                MotionEvent c = MotionEvent.obtain(ev);
                c.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c);
                c.recycle();
            }
            return true;
        }
        if (multi) {
            // one finger left after a pinch: ignore it until the next gesture so the page does not jump
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) multi = false;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && zoom > 1.001f) {
            float x = ev.getX();
            float dx = x - lastX;
            lastX = x;
            if (dx != 0f) {
                tx += dx;
                apply();
            }
        }
        super.dispatchTouchEvent(ev);
        return true;
    }
}
