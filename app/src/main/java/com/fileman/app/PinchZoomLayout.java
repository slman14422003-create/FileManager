package com.fileman.app;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

public class PinchZoomLayout extends FrameLayout {
    public interface Listener {
        float zoom();

        void onZoomCommit(float newZoom, float startX, float startY, float endX, float endY);

        default void onZoomPreview(float zoom) {
        }

        default void onZoomBegin() {
        }

        default void onZoomEnd() {
        }
    }

    public interface Panner {
        boolean active();

        void pan(float dx, float dy);

        void fling(float vx, float vy);
    }

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private final int slop;
    private final int minFling;
    private final int maxFling;
    private Listener listener;
    private Panner panner;
    private float minZoom = 1f;
    private float maxZoom = 4f;
    private float[] tapStops = {2.5f};

    private boolean live = false;
    private float lastFX, lastFY;
    private float preview = 1f;
    private float startX, startY, curX, curY;

    private boolean panning;
    private float lastX, lastY, downX, downY;
    private VelocityTracker tracker;

    public PinchZoomLayout(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                View t = target();
                if (t == null || listener == null) return false;
                endPan(false);
                preview = 1f;
                lastFX = d.getFocusX();
                lastFY = d.getFocusY();
                if (live) listener.onZoomBegin();
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
                if (live) {
                    float nz = clamp(z * d.getScaleFactor(), minZoom, maxZoom);
                    float fx = d.getFocusX();
                    float fy = d.getFocusY();
                    listener.onZoomCommit(nz, lastFX, lastFY, fx, fy);
                    lastFX = fx;
                    lastFY = fy;
                    listener.onZoomPreview(listener.zoom());
                    return true;
                }
                preview = clamp(preview * d.getScaleFactor(), minZoom / z, maxZoom / z);
                curX = d.getFocusX();
                curY = d.getFocusY();
                t.setScaleX(preview);
                t.setScaleY(preview);
                t.setTranslationX(curX - startX);
                t.setTranslationY(curY - startY);
                listener.onZoomPreview(z * preview);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                View t = target();
                if (t == null || listener == null) return;
                if (live) {
                    listener.onZoomEnd();
                    return;
                }
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
                float cur = listener.zoom();
                float to = minZoom;
                for (float s : tapStops) {
                    if (s > cur * 1.12f && s <= maxZoom) {
                        to = s;
                        break;
                    }
                }
                listener.onZoomCommit(to, e.getX(), e.getY(), e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setLive(boolean on) {
        live = on;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setPanner(Panner p) {
        panner = p;
    }

    public void setZoomRange(float min, float max, float... doubleTapStops) {
        minZoom = min;
        maxZoom = max;
        if (doubleTapStops != null && doubleTapStops.length > 0) tapStops = doubleTapStops;
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

    private void endPan(boolean fling) {
        if (panning && fling && tracker != null && panner != null) {
            tracker.computeCurrentVelocity(1000, maxFling);
            float vx = tracker.getXVelocity();
            float vy = tracker.getYVelocity();
            if (Math.abs(vx) > minFling || Math.abs(vy) > minFling) panner.fling(vx, vy);
        }
        panning = false;
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        tapDetector.onTouchEvent(ev);
        if (scaleDetector.isInProgress() || ev.getPointerCount() > 1) {
            endPan(false);
            return true;
        }
        if (panner == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                endPan(false);
                downX = lastX = ev.getX();
                downY = lastY = ev.getY();
                if (panner.active()) {
                    tracker = VelocityTracker.obtain();
                    tracker.addMovement(ev);
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (tracker == null || !panner.active()) return false;
                tracker.addMovement(ev);
                if (Math.hypot(ev.getX() - downX, ev.getY() - downY) > slop) {
                    panning = true;
                    lastX = ev.getX();
                    lastY = ev.getY();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endPan(false);
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        tapDetector.onTouchEvent(ev);
        if (ev.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            endPan(false);
            return true;
        }
        if (panning && panner != null) {
            if (tracker != null) tracker.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    panner.pan(ev.getX() - lastX, ev.getY() - lastY);
                    lastX = ev.getX();
                    lastY = ev.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    endPan(true);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    endPan(false);
                    break;
                default:
                    break;
            }
        }
        return true;
    }
}
