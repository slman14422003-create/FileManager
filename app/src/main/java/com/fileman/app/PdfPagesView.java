package com.fileman.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One view that lays out, scrolls, zooms and draws every page of a PDF itself (no ListView / ScrollView /
 * view scaling involved), so:
 * <ul>
 *   <li>pinch zoom keeps the exact document point under the fingers (and follows them while they move),</li>
 *   <li>rotation keeps the same position in the document and re-fits pages to the new width without stretching,</li>
 *   <li>sharpness comes from a per-page base bitmap plus a high-resolution tile of only the visible region.</li>
 * </ul>
 * Rendering itself is delegated to a {@link Source}; its methods are called on a background thread.
 */
public class PdfPagesView extends View {

    public interface Source {
        /** Background thread. Renders the whole page into a bitmap {@code width} px wide; writes height / width to {@code ratioOut[0]}. */
        Bitmap renderPage(int index, int width, float[] ratioOut);

        /** Background thread. Renders the region [l,t,r,b] (px) of the page laid out {@code pageW} px wide. */
        Bitmap renderTile(int index, int pageW, int l, int t, int r, int b);
    }

    public interface Listener {
        /** The page at the top of the screen changed. */
        void onPage(int index);

        /** The content moved by {@code dy} px (positive = towards the end of the document). */
        void onScrolled(float dy);

        /** A single tap (not part of a double tap). */
        void onTap();

        /** The zoom changed (1 = fit width). */
        void onZoom(float zoom);

        /** A finger touched down or lifted. */
        void onTouch();
    }

    private static final int MAX_BASE_W = 2000;
    private static final int MAX_TILE_DIM = 4096;
    private static final long MAX_TILE_PIXELS = 6_000_000L;
    private static final long WORK_DELAY = 110;
    private static final float MAX_ZOOM_CAP = 6f;
    private static final float MAX_PAGE_PX = 60000f;

    /** Inverts the page colours (white paper becomes dark) for comfortable night reading. */
    private static final ColorMatrixColorFilter INVERT = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            -0.9f, 0, 0, 0, 255 * 0.93f,
            0, -0.9f, 0, 0, 255 * 0.93f,
            0, 0, -0.9f, 0, 255 * 0.93f,
            0, 0, 0, 1, 0}));

    private static final class Tile {
        Bitmap bmp;
        int pageW, l, t;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final float density;
    private final int margin, gap, padBottom;
    private int padTop;

    private Source source;
    private Listener listener;
    private int count = 0;
    private float[] ratios = new float[0];
    private float[] tops = new float[0];
    private float totalBase = 0f;
    private float defaultRatio = 1.414f;
    private int baseW = 1;

    private float zoom = 1f;
    private float maxZoom = 5f;
    private float scrollX = 0f, scrollY = 0f;
    private boolean night = false;
    private volatile int curPage = -1;
    private int pendingPage = -1;
    private volatile boolean destroyed = false;

    private final LruCache<String, Bitmap> cache;
    private final SparseArray<WeakReference<Bitmap>> lastBmp = new SparseArray<>();
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final SparseArray<Tile> tiles = new SparseArray<>();
    private final HashSet<Integer> tilePending = new HashSet<>();
    private volatile int gen = 0;

    private final Paint pagePaint = new Paint();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final RectF tdst = new RectF();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
    private ValueAnimator zoomAnim;
    private boolean pinching = false;
    private boolean dragging = false;
    private boolean thumbVisible = false;
    private float lastFx, lastFy;
    private long lastScaleEnd = 0;

    private final Runnable workRunner = this::doWork;
    private final Runnable hideThumb = () -> {
        if (dragging) return;
        thumbVisible = false;
        invalidate();
    };

    public PdfPagesView(Context c, int cacheBytes) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        margin = Math.round(8 * density);
        gap = Math.round(8 * density);
        padBottom = Math.round(100 * density);
        padTop = Math.round(6 * density);
        cache = new LruCache<String, Bitmap>(Math.max(1 << 20, cacheBytes)) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
        pagePaint.setColor(Color.WHITE);
        thumbPaint.setColor(0xB38E8E93);
        scroller = new OverScroller(c);
        setWillNotDraw(false);
        setOverScrollMode(OVER_SCROLL_NEVER);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (count == 0) return false;
                scroller.forceFinished(true);
                if (zoomAnim != null) zoomAnim.cancel();
                pinching = true;
                lastFx = d.getFocusX();
                lastFy = d.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX();
                float fy = d.getFocusY();
                setZoomAround(zoom * d.getScaleFactor(), lastFx, lastFy, fx, fy);
                lastFx = fx;
                lastFy = fy;
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                pinching = false;
                lastScaleEnd = SystemClock.uptimeMillis();
                scheduleWork();
            }
        });
        scaleDetector.setQuickScaleEnabled(false);

        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (pinching || scaleDetector.isInProgress()) return false;
                scrollByPx(dx, dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (pinching || SystemClock.uptimeMillis() - lastScaleEnd < 150 || count == 0) return false;
                scroller.fling(Math.round(scrollX), Math.round(scrollY), Math.round(-vx), Math.round(-vy),
                        0, Math.round(maxScrollX()), 0, Math.round(maxScrollY()));
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateZoom(zoom > 1.3f ? 1f : Math.min(2.5f, maxZoom), e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);
    }

    // ------------------------------------------------------------------ public API

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSource(Source s, int pages, float firstPageRatio) {
        source = s;
        count = Math.max(0, pages);
        defaultRatio = firstPageRatio > 0 ? firstPageRatio : 1.414f;
        ratios = new float[count];
        tops = new float[0];
        zoom = 1f;
        scrollX = 0f;
        scrollY = 0f;
        cache.evictAll();
        tiles.clear();
        pending.clear();
        failed.clear();
        tilePending.clear();
        gen++;
        if (getWidth() > 0) relayoutGeometry();
        invalidate();
        scheduleWork();
    }

    /** Real page shapes (height / width, 0 = unknown), read in the background; keeps the reading position. */
    public void setRatios(float[] found) {
        if (count == 0 || found == null) return;
        boolean changed = false;
        for (int i = 0; i < count && i < found.length; i++) {
            if (found[i] > 0 && Math.abs(ratios[i] - found[i]) > 0.001f) {
                ratios[i] = found[i];
                changed = true;
            }
        }
        if (changed) relayoutKeepingAnchor();
    }

    public void setNight(boolean on) {
        night = on;
        pagePaint.setColor(on ? 0xFF080808 : Color.WHITE);
        bmpPaint.setColorFilter(on ? INVERT : null);
        invalidate();
    }

    /** Height of the header drawn over the top of the view, so the first page starts below it. */
    public void setTopInset(int px) {
        int v = Math.max(0, px) + Math.round(4 * density);
        if (v == padTop) return;
        padTop = v;
        if (count > 0 && getWidth() > 0) {
            clampScroll();
            invalidate();
        }
    }

    public float getZoom() {
        return zoom;
    }

    public int getCurrentPage() {
        return Math.max(0, curPage);
    }

    public boolean isAtTop() {
        return scrollY <= 1f;
    }

    public void scrollToPage(int page) {
        if (count == 0) return;
        int p = Math.max(0, Math.min(count - 1, page));
        if (tops.length != count || getWidth() == 0) {
            pendingPage = p;
            return;
        }
        scroller.forceFinished(true);
        scrollY = tops[p] * zoom;
        clampScroll();
        notifyPage();
        showThumb();
        invalidate();
        scheduleWork();
    }

    public void animateZoom(float target, final float fx, final float fy) {
        if (count == 0) return;
        final float to = clamp(target, 1f, maxZoom);
        if (zoomAnim != null) zoomAnim.cancel();
        if (Math.abs(to - zoom) < 0.005f) return;
        scroller.forceFinished(true);
        final ValueAnimator va = ValueAnimator.ofFloat(zoom, to);
        va.setDuration(240);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> setZoomAround((float) a.getAnimatedValue(), fx, fy, fx, fy));
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                if (zoomAnim == an) zoomAnim = null;
                scheduleWork();
            }
        });
        zoomAnim = va;
        va.start();
    }

    public void destroy() {
        destroyed = true;
        if (zoomAnim != null) zoomAnim.cancel();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ geometry

    private float ratioOf(int i) {
        return ratios[i] > 0 ? ratios[i] : defaultRatio;
    }

    private float hBase(int i) {
        return baseW * ratioOf(i);
    }

    private float contentH() {
        return padTop + totalBase * zoom + padBottom;
    }

    private float maxScrollX() {
        return Math.max(0f, getWidth() * zoom - getWidth());
    }

    private float maxScrollY() {
        return Math.max(0f, contentH() - getHeight());
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void clampScroll() {
        scrollX = clamp(scrollX, 0f, maxScrollX());
        scrollY = clamp(scrollY, 0f, maxScrollY());
    }

    private void relayoutGeometry() {
        baseW = Math.max(1, getWidth() - 2 * margin);
        if (tops.length != count) tops = new float[count];
        float y = 0f;
        for (int i = 0; i < count; i++) {
            tops[i] = y;
            y += hBase(i) + gap;
        }
        totalBase = Math.max(0f, y - gap);
        maxZoom = Math.max(2f, Math.min(MAX_ZOOM_CAP, MAX_PAGE_PX / Math.max(1f, baseW * Math.max(1f, defaultRatio))));
        if (zoom > maxZoom) zoom = maxZoom;
    }

    /** Index of the page whose slot contains the content y (px), or the nearest one. */
    private int pageAtY(float y) {
        if (count == 0 || tops.length != count) return 0;
        float by = (y - padTop) / zoom;
        int lo = 0, hi = count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (tops[mid] <= by) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private float fracIn(int page, float y) {
        return ((y - padTop) / zoom - tops[page]) / Math.max(1f, hBase(page));
    }

    /** Page shapes changed: rebuild the layout but keep the same spot of the document at the top of the screen. */
    private void relayoutKeepingAnchor() {
        if (count == 0 || getWidth() == 0 || tops.length != count) {
            return;
        }
        int ap = pageAtY(scrollY);
        float af = fracIn(ap, scrollY);
        relayoutGeometry();
        scrollY = padTop + (tops[ap] + af * hBase(ap)) * zoom;
        clampScroll();
        invalidate();
        scheduleWork();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (count == 0 || w <= 0) return;
        boolean widthChanged = ow > 0 && w != ow && tops.length == count;
        int ap = 0;
        float af = 0f, xf = 0.5f;
        if (widthChanged) {   // remember what is at the top of the screen and which part of the width is centred
            ap = pageAtY(scrollY);
            af = fracIn(ap, scrollY);
            xf = (scrollX + ow / 2f) / Math.max(1f, ow * zoom);
        }
        relayoutGeometry();
        if (widthChanged) {
            scrollY = padTop + (tops[ap] + af * hBase(ap)) * zoom;
            scrollX = xf * w * zoom - w / 2f;
            gen++;
            tiles.clear();
            tilePending.clear();
        }
        if (pendingPage >= 0) {
            int p = pendingPage;
            pendingPage = -1;
            scrollY = tops[Math.min(count - 1, p)] * zoom;
        }
        clampScroll();
        notifyPage();
        invalidate();
        scheduleWork();
    }

    // ------------------------------------------------------------------ zoom & scroll

    /**
     * Applies a new zoom keeping the document point that was under (oldFx, oldFy) under (newFx, newFy):
     * the focus point moving while the fingers move pans the page, exactly like a map.
     */
    private void setZoomAround(float newZoom, float oldFx, float oldFy, float newFx, float newFy) {
        if (count == 0 || getWidth() == 0) return;
        float nz = clamp(newZoom, 1f, maxZoom);
        float bx = (scrollX + oldFx) / zoom;
        float by = (scrollY + oldFy - padTop) / zoom;
        zoom = nz;
        scrollX = bx * zoom - newFx;
        scrollY = by * zoom + padTop - newFy;
        clampScroll();
        notifyPage();
        showThumb();
        if (listener != null) listener.onZoom(zoom);
        invalidate();
        scheduleWork();
    }

    private void scrollByPx(float dx, float dy) {
        float oy = scrollY;
        scrollX += dx;
        scrollY += dy;
        clampScroll();
        float applied = scrollY - oy;
        if (listener != null && applied != 0f) listener.onScrolled(applied);
        notifyPage();
        showThumb();
        invalidate();
        scheduleWork();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            float oy = scrollY;
            scrollX = scroller.getCurrX();
            scrollY = scroller.getCurrY();
            clampScroll();
            float applied = scrollY - oy;
            if (listener != null && applied != 0f) listener.onScrolled(applied);
            notifyPage();
            showThumb();
            postInvalidateOnAnimation();
            if (scroller.isFinished()) scheduleWork();
        }
    }

    private void notifyPage() {
        if (count == 0 || tops.length != count) return;
        int p = pageAtY(scrollY + padTop + 8 * density);
        if (p != curPage) {
            curPage = p;
            if (listener != null) listener.onPage(p);
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (listener != null && (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP)) listener.onTouch();
        if (handleThumb(ev)) return true;
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        return true;
    }

    private float thumbTop() {
        return 76 * density;
    }

    private float thumbBottom() {
        return getHeight() - 96 * density;
    }

    private float thumbH() {
        return 52 * density;
    }

    private float thumbY() {
        float maxY = maxScrollY();
        float track = thumbBottom() - thumbTop() - thumbH();
        if (maxY <= 0f || track <= 0f) return thumbTop();
        return thumbTop() + clamp(scrollY / maxY, 0f, 1f) * track;
    }

    private boolean handleThumb(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!thumbVisible || count == 0 || ev.getX() < getWidth() - 40 * density) return false;
                float ty = thumbY();
                if (ev.getY() < ty - 16 * density || ev.getY() > ty + thumbH() + 16 * density) return false;
                dragging = true;
                scroller.forceFinished(true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float track = thumbBottom() - thumbTop() - thumbH();
                if (track > 0f) {
                    float f = clamp((ev.getY() - thumbTop() - thumbH() / 2f) / track, 0f, 1f);
                    float oy = scrollY;
                    scrollY = f * maxScrollY();
                    if (listener != null && scrollY != oy) listener.onScrolled(scrollY - oy);
                    notifyPage();
                    showThumb();
                    invalidate();
                    scheduleWork();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                showThumb();
                scheduleWork();
                return true;
            }
            default:
                return dragging;
        }
    }

    private void showThumb() {
        thumbVisible = true;
        ui.removeCallbacks(hideThumb);
        ui.postDelayed(hideThumb, 1100);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (count == 0 || tops.length != count || getWidth() == 0) return;
        int first = pageAtY(scrollY);
        int last = pageAtY(scrollY + getHeight());
        float pageWpx = baseW * zoom;
        float left = margin * zoom - scrollX;
        int bw = Math.min(baseW, MAX_BASE_W);
        for (int i = first; i <= last && i < count; i++) {
            float top = padTop + tops[i] * zoom - scrollY;
            float h = hBase(i) * zoom;
            dst.set(left, top, left + pageWpx, top + h);
            c.drawRect(dst, pagePaint);
            String key = i + "@" + bw;
            Bitmap b = cache.get(key);
            if (b == null) {
                WeakReference<Bitmap> w = lastBmp.get(i);
                b = w == null ? null : w.get();
                requestBase(i);
            }
            if (b != null && !b.isRecycled()) c.drawBitmap(b, null, dst, bmpPaint);
            Tile t = tiles.get(i);
            if (t != null && t.bmp != null && !t.bmp.isRecycled() && t.pageW > 0) {
                float k = pageWpx / t.pageW;
                tdst.set(left + t.l * k, top + t.t * k,
                        left + (t.l + t.bmp.getWidth()) * k, top + (t.t + t.bmp.getHeight()) * k);
                c.drawBitmap(t.bmp, null, tdst, bmpPaint);
            }
        }
        if (thumbVisible && maxScrollY() > 0f) {
            float w = (dragging ? 8 : 5) * density;
            float x = getWidth() - 4 * density;
            float y = thumbY();
            c.drawRoundRect(x - w, y, x, y + thumbH(), w, w, thumbPaint);
        }
    }

    // ------------------------------------------------------------------ rendering

    private void scheduleWork() {
        if (destroyed) return;
        ui.removeCallbacks(workRunner);
        ui.postDelayed(workRunner, WORK_DELAY);
    }

    private boolean busy() {
        return pinching || dragging || zoomAnim != null || !scroller.isFinished();
    }

    private void doWork() {
        if (destroyed || count == 0 || tops.length != count || getWidth() == 0 || source == null) return;
        int first = pageAtY(scrollY);
        int last = Math.min(count - 1, pageAtY(scrollY + getHeight()));
        for (int i = Math.max(0, first - 1); i <= Math.min(count - 1, last + 1); i++) requestBase(i);
        if (busy()) {
            ui.postDelayed(workRunner, WORK_DELAY);
            return;
        }
        requestTiles(first, last);
    }

    private void requestBase(final int index) {
        if (destroyed || source == null || index < 0 || index >= count || baseW <= 1) return;
        final int bw = Math.min(baseW, MAX_BASE_W);
        final String key = index + "@" + bw;
        if (failed.contains(key) || cache.get(key) != null || !pending.add(key)) return;
        io.execute(() -> {
            Bitmap b = null;
            float[] ratio = new float[1];
            boolean skipped = Math.abs(index - curPage) > 5;   // scrolled far away meanwhile: do not waste time
            if (!destroyed && !skipped) {
                try {
                    b = source.renderPage(index, bw, ratio);
                } catch (Throwable ignored) {
                    b = null;
                }
            }
            final Bitmap fb = b;
            final float fr = ratio[0];
            final boolean sk = skipped;
            ui.post(() -> {
                pending.remove(key);
                if (destroyed) return;
                if (fb != null) {
                    cache.put(key, fb);
                    lastBmp.put(index, new WeakReference<>(fb));
                    if (fr > 0 && index < ratios.length && Math.abs(ratios[index] - fr) > 0.001f) {
                        ratios[index] = fr;
                        relayoutKeepingAnchor();
                    }
                } else if (!sk) {
                    failed.add(key);
                }
                invalidate();
            });
        });
    }

    private void requestTiles(int first, int last) {
        for (int i = tiles.size() - 1; i >= 0; i--) {
            int k = tiles.keyAt(i);
            if (k < first - 1 || k > last + 1) tiles.removeAt(i);
        }
        final int bw = Math.min(baseW, MAX_BASE_W);
        final float pageWpx = baseW * zoom;
        if (pageWpx <= bw * 1.1f) {   // the base bitmap is already sharp enough for this zoom
            if (tiles.size() > 0) {
                tiles.clear();
                invalidate();
            }
            return;
        }
        final int pw = Math.round(pageWpx);
        for (int i = first; i <= last && i < count; i++) {
            if (tilePending.contains(i)) continue;
            float left = margin * zoom - scrollX;
            float top = padTop + tops[i] * zoom - scrollY;
            float h = hBase(i) * zoom;
            float vl = Math.max(0f, -left);
            float vt = Math.max(0f, -top);
            float vr = Math.min(pageWpx, getWidth() - left);
            float vb = Math.min(h, getHeight() - top);
            if (vr <= vl || vb <= vt) continue;
            Tile have = tiles.get(i);
            if (have != null && have.bmp != null && Math.abs(have.pageW - pw) <= 1
                    && have.l <= vl + 1 && have.t <= vt + 1
                    && have.l + have.bmp.getWidth() >= vr - 1 && have.t + have.bmp.getHeight() >= vb - 1) {
                continue;
            }
            float padX = (vr - vl) * 0.15f;
            float padY = (vb - vt) * 0.15f;
            int ph = Math.round(h);
            int l = 0, t = 0, r = 0, b = 0;
            for (int k = 0; k < 7; k++) {
                l = Math.max(0, Math.round(vl - padX));
                t = Math.max(0, Math.round(vt - padY));
                r = Math.min(pw, Math.round(vr + padX));
                b = Math.min(ph, Math.round(vb + padY));
                if ((long) (r - l) * (b - t) <= MAX_TILE_PIXELS && r - l <= MAX_TILE_DIM && b - t <= MAX_TILE_DIM) break;
                padX *= 0.5f;
                padY *= 0.5f;
            }
            if ((long) (r - l) * (b - t) > MAX_TILE_PIXELS || r - l > MAX_TILE_DIM || b - t > MAX_TILE_DIM) {
                int cx = Math.round((vl + vr) / 2f);
                int cy = Math.round((vt + vb) / 2f);
                int tw = Math.max(1, Math.min(r - l, Math.min(MAX_TILE_DIM, 2400)));
                int th = (int) Math.max(1, Math.min((long) (b - t), Math.min((long) MAX_TILE_DIM, MAX_TILE_PIXELS / tw)));
                l = Math.max(0, Math.min(Math.max(0, pw - tw), cx - tw / 2));
                t = Math.max(0, Math.min(Math.max(0, ph - th), cy - th / 2));
                r = Math.min(pw, l + tw);
                b = Math.min(ph, t + th);
            }
            if (r <= l || b <= t) continue;
            if (have != null && have.bmp != null && have.pageW == pw && have.l == l && have.t == t
                    && have.bmp.getWidth() == r - l && have.bmp.getHeight() == b - t) {
                continue;   // already the best tile this size allows
            }
            final int fi = i, fl = l, ft = t, fr = r, fb = b;
            final int g = gen;
            tilePending.add(fi);
            io.execute(() -> {
                Bitmap bm = null;
                if (!destroyed && g == gen) {
                    try {
                        bm = source.renderTile(fi, pw, fl, ft, fr, fb);
                    } catch (Throwable ignored) {
                        bm = null;
                    }
                }
                final Bitmap res = bm;
                ui.post(() -> {
                    tilePending.remove(fi);
                    if (destroyed || g != gen || res == null) return;
                    Tile tl = new Tile();
                    tl.bmp = res;
                    tl.pageW = pw;
                    tl.l = fl;
                    tl.t = ft;
                    tiles.put(fi, tl);
                    invalidate();
                    scheduleWork();
                });
            });
        }
    }
}
