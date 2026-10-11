package com.fileman.app;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Wraps one scrollable child and adds two-finger pinch zoom and double-tap zoom.
 * <p>
 * While the fingers move the child is only scaled and moved visually (cheap, 60 fps); when they lift the
 * new zoom is handed to the {@link Listener}, which re-lays-out and re-renders the content sharply.
 * One-finger touches pass straight through to the child, so normal scrolling is untouched.
 */
public class PinchZoomLayout extends FrameLayout {
    public interface Listener {
        /** The zoom the content is currently laid out at (1 = fit to width). */
        float zoom();

        /**
         * Commit a new zoom. The content point that was under (startX, startY) before the gesture must end up
         * under (endX, endY); both are in this layout's coordinates.
         */
        void onZoomCommit(float newZoom, float startX, float startY, float endX, float endY);
    }

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private Listener listener;
    private float minZoom = 1f;
    private float maxZoom = 4f;
    private float doubleTapZoom = 2.5f;

    private float preview = 1f;
    private float startX, startY, curX, curY;

    public PinchZoomLayout(Context c) {
        super(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                View t = target();
                if (t == null || listener == null) return false;
                preview = 1f;
                startX = curX = d.getFocusX();
                startY = curY = d.getFocusY();
                t.setPivotX(startX);
                t.setPivotY(startY);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                View t = target();
                if (t == null || listener == null) return false;
                float z = Math.max(0.01f, listener.zoom());
                preview = clamp(preview * d.getScaleFactor(), minZoom / z, maxZoom / z);
                curX = d.getFocusX();
                curY = d.getFocusY();
                t.setScaleX(preview);
                t.setScaleY(preview);
                t.setTranslationX(curX - startX);   // fingers dragging while pinching also pan
                t.setTranslationY(curY - startY);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                View t = target();
                if (t == null || listener == null) return;
                float ratio = preview;
                preview = 1f;
                t.setScaleX(1f);
                t.setScaleY(1f);
                t.setTranslationX(0f);
                t.setTranslationY(0f);
                if (Math.abs(ratio - 1f) > 0.01f) {
                    listener.onZoomCommit(clamp(listener.zoom() * ratio, minZoom, maxZoom), startX, startY, curX, curY);
                }
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
        tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (listener == null) return false;
                float to = listener.zoom() < 1.5f ? doubleTapZoom : minZoom;
                listener.onZoomCommit(to, e.getX(), e.getY(), e.getX(), e.getY());
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

    public float minZoom() {
        return minZoom;
    }

    public float maxZoom() {
        return maxZoom;
    }

    private View target() {
        return getChildCount() > 0 ? getChildAt(0) : null;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        tapDetector.onTouchEvent(ev);
        return scaleDetector.isInProgress() || ev.getPointerCount() > 1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        tapDetector.onTouchEvent(ev);
        return true;
    }
}
