package com.fileman.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.view.Gravity;
import android.view.ViewOutlineProvider;
import android.text.InputType;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.ViewTreeObserver;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF viewer built on the platform PdfRenderer: scrolling pages, pinch / double-tap / button zoom, page counter. */
public class PdfViewActivity extends BaseActivity {
    @Override
    protected boolean constrainContent() {
        return false;
    }

    private static final float[] ZOOMS = {1f, 1.25f, 1.5f, 2f, 3f, 4f, 6f, 8f};
    private static final float ZOOM_CAP = 8f;
    private static final float MAX_PAGE_PX = 60000f;
    private float maxZoom = ZOOM_CAP;
    private static final int MAX_BITMAP_W = 1800;
    private static final int MAX_TILE_DIM = 4096;
    private static final int MAX_TILE_PIXELS = 6_000_000;
    private static final long TILE_DELAY = 140;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private File file;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private volatile boolean destroyed = false;
    private int pageCount = 0;
    private float[] ratios;          // height / width per page (0 = unknown yet)
    private float defaultRatio = 1.414f;
    private float zoom = 1f;
    private boolean liveZoom = false;
    private HorizontalScrollView hsv;
    private FixedWidthFrame content;
    private PinchZoomLayout pinch;
    /** Last bitmap drawn for each page: shown (stretched) while a sharper one renders after a zoom. */
    private final SparseArray<WeakReference<Bitmap>> lastBmp = new SparseArray<>();
    private final SparseArray<Tile> tiles = new SparseArray<>();
    private int tileGen = 0;
    private final Runnable tileRunner = this::startTiles;

    /** Forces its width: a HorizontalScrollView measures children unbounded and would ignore the zoomed width. */
    private static final class FixedWidthFrame extends FrameLayout {
        private int fixedW = 1;

        FixedWidthFrame(Context c) {
            super(c);
        }

        void setFixedWidth(int w) {
            fixedW = Math.max(1, w);
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(fixedW, MeasureSpec.EXACTLY), heightSpec);
        }
    }

    private static final class Tile {
        Bitmap bmp;
        int pageW, left, top;
    }

    private static final class TileJob {
        int position, pageW, left, top, right, bottom;
    }
    private int viewW = 0;
    private boolean night = false;
    private int firstVisible = 0;

    private View loading;
    private TextView subtitle;
    private ListView list;
    private PageAdapter adapter;
    private LruCache<String, Bitmap> cache;
    private TextView pill;
    private final Runnable hidePill = () -> {
        if (pill != null) pill.animate().alpha(0f).setDuration(250).start();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ((TextView) findViewById(R.id.title)).setText(file.getName());
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText("PDF · " + Fmt.size(file.length()));
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ViewerBar.headerIcon(this, file);

        ImageButton zoomBtn = findViewById(R.id.btnA1);
        zoomBtn.setImageResource(R.drawable.ic_zoom);
        zoomBtn.setContentDescription(getString(R.string.v_zoom));
        zoomBtn.setVisibility(View.GONE);   // zoom now lives in the floating bar
        zoomBtn.setOnClickListener(v -> cycleZoom());
        ImageButton more = findViewById(R.id.btnA2);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> Opener.moreMenu(this, file,
                new String[]{getString(R.string.rd_night) + (night ? "  ✓" : ""), getString(R.string.rd_page_jump),
                        getString(R.string.v_print)},
                new int[]{R.drawable.ic_night, R.drawable.ic_list_play, R.drawable.ic_file_text},
                new Runnable[]{this::toggleNight, this::askPage, this::printPdf}));
        night = Store.intPref(this, "pdf_night", 0) == 1;

        long budget = Math.max(16L * 1024 * 1024, Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6));
        cache = new LruCache<String, Bitmap>((int) budget) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };

        loading.setVisibility(View.VISIBLE);
        io.execute(this::openDocument);
    }

    private void openDocument() {
        openDocument(null);
    }

    /** Android 15+ can open password protected files: the constructor with LoadParams is reached by reflection. */
    private PdfRenderer newRenderer(ParcelFileDescriptor fd, String pwd) throws IOException {
        if (pwd == null) return new PdfRenderer(fd);
        try {
            Class<?> bc = Class.forName("android.graphics.pdf.LoadParams$Builder");
            Object builder = bc.getConstructor().newInstance();
            bc.getMethod("setPassword", String.class).invoke(builder, pwd);
            Object params = bc.getMethod("build").invoke(builder);
            return PdfRenderer.class.getConstructor(ParcelFileDescriptor.class, Class.forName("android.graphics.pdf.LoadParams"))
                    .newInstance(fd, params);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof SecurityException) throw (SecurityException) t;
            throw new IOException(String.valueOf(t));
        } catch (ReflectiveOperationException e) {
            throw new IOException("password support missing");
        }
    }

    private void openDocument(final String pwd) {
        int errorRes = 0;
        boolean needPassword = false;
        try {
            synchronized (lock) {
                try {
                    if (renderer != null) renderer.close();
                    if (pfd != null) pfd.close();
                } catch (Exception ignored) {
                }
                renderer = null;
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = newRenderer(pfd, pwd);
                pageCount = renderer.getPageCount();
                if (pageCount > 0) {
                    PdfRenderer.Page p = renderer.openPage(0);
                    try {
                        if (p.getWidth() > 0) defaultRatio = (float) p.getHeight() / p.getWidth();
                    } finally {
                        p.close();
                    }
                }
            }
        } catch (SecurityException e) {
            if (android.os.Build.VERSION.SDK_INT >= 35) needPassword = true;
            else errorRes = R.string.v_pdf_need_new_android;
        } catch (IOException | RuntimeException e) {
            errorRes = R.string.v_pdf_failed;
        }
        final int err = errorRes;
        final boolean ask = needPassword;
        ui.post(() -> {
            if (destroyed || isFinishing()) return;
            loading.setVisibility(View.INVISIBLE);
            if (ask) {
                askPassword(pwd != null);
                return;
            }
            if (err != 0 || pageCount == 0) {
                Toast.makeText(this, err != 0 ? err : R.string.v_empty_doc, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            ratios = new float[pageCount];
            buildList();
        });
    }

    private void askPassword(boolean wrong) {
        final android.widget.EditText e = Ui.edit(this, getString(R.string.v_pdf_password), "");
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Dlg d = new Dlg(this);
        d.setTitle(wrong ? getString(R.string.v_pdf_wrong_password) : getString(R.string.v_pdf_password));
        d.setView(e);
        d.setPositiveButton(android.R.string.ok, (dd, w) -> {
            final String pw = e.getText().toString();
            loading.setVisibility(View.VISIBLE);
            io.execute(() -> openDocument(pw));
        });
        d.setNegativeButton(R.string.cancel, (dd, w) -> finish());
        d.show();
    }

    /** Hands the original file to the system print service (also "save as PDF"). */
    private void printPdf() {
        try {
            android.print.PrintManager pm = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
            final String name = file.getName();
            pm.print(name, new android.print.PrintDocumentAdapter() {
                @Override
                public void onLayout(android.print.PrintAttributes o, android.print.PrintAttributes n,
                                     android.os.CancellationSignal c, LayoutResultCallback cb, Bundle extras) {
                    if (c.isCanceled()) {
                        cb.onLayoutCancelled();
                        return;
                    }
                    cb.onLayoutFinished(new android.print.PrintDocumentInfo.Builder(name)
                            .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(pageCount > 0 ? pageCount : android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true);
                }

                @Override
                public void onWrite(android.print.PageRange[] pages, ParcelFileDescriptor dest,
                                    android.os.CancellationSignal c, WriteResultCallback cb) {
                    try (java.io.InputStream in = new java.io.FileInputStream(file);
                         java.io.OutputStream out = new java.io.FileOutputStream(dest.getFileDescriptor())) {
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            if (c.isCanceled()) {
                                cb.onWriteCancelled();
                                return;
                            }
                            out.write(buf, 0, r);
                        }
                        cb.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
                    } catch (IOException e) {
                        cb.onWriteFailed(String.valueOf(e.getMessage()));
                    }
                }
            }, null);
        } catch (Exception e) {
            Toast.makeText(this, R.string.v_pdf_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void buildList() {
        final FrameLayout holder = findViewById(R.id.holder);
        holder.post(() -> {
            if (destroyed) return;
            viewW = holder.getWidth();
            hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setOnScrollChangeListener((v, x, y, ox, oy) -> scheduleTiles());
            list = new ListView(this);
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setVerticalScrollBarEnabled(false);
            list.setSelector(android.R.color.transparent);
            list.setClipToPadding(false);
            list.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 100));
            list.setFastScrollEnabled(true);
            adapter = new PageAdapter();
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView v, int state) {
                }

                @Override
                public void onScroll(AbsListView v, int first, int visible, int total) {
                    updateSubtitle(first);
                    scheduleTiles();
                }
            });
            content = new FixedWidthFrame(this);
            content.setFixedWidth(listWidth());
            content.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            hsv.addView(content, new FrameLayout.LayoutParams(listWidth(), ViewGroup.LayoutParams.MATCH_PARENT));
            maxZoom = Math.min(ZOOM_CAP, MAX_PAGE_PX / Math.max(1f, viewW * Math.max(1f, defaultRatio)));
            pinch = new PinchZoomLayout(this);
            pinch.setZoomRange(1f, maxZoom, 2f, 4f, 8f);
            pinch.setLive(true);
            pinch.setPanner(new PinchZoomLayout.Panner() {
                @Override
                public boolean active() {
                    return zoom > 1.05f;
                }

                @Override
                public void pan(float dx, float dy) {
                    if (hsv != null) hsv.scrollBy(Math.round(-dx), 0);
                    if (list != null) list.scrollListBy(Math.round(-dy));
                }

                @Override
                public void fling(float vx, float vy) {
                    if (hsv != null) hsv.fling(Math.round(-vx));
                    if (list != null) list.fling(Math.round(-vy));
                }
            });
            pinch.setListener(new PinchZoomLayout.Listener() {
                @Override
                public float zoom() {
                    return zoom;
                }

                @Override
                public void onZoomPreview(float z) {
                    showZoomPill(z);
                }

                @Override
                public void onZoomBegin() {
                    if (zoomAnim != null) zoomAnim.cancel();
                    liveZoom = true;
                }

                @Override
                public void onZoomEnd() {
                    liveZoom = false;
                    if (adapter != null) adapter.notifyDataSetChanged();
                    scheduleTiles();
                }

                @Override
                public void onZoomCommit(float z, float sx, float sy, float ex, float ey) {
                    if (liveZoom) commitZoom(z, sx, sy, ex, ey);
                    else animateZoom(z, ex, ey);
                }
            });
            pinch.addView(hsv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            holder.addView(pinch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // floating page counter: shows while scrolling, tap to jump to a page
            pill = new TextView(this);
            pill.setTextColor(Ui.color(this, R.color.text_primary));
            pill.setTextSize(14);
            pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            pill.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
            GradientDrawable pg = new GradientDrawable();
            pg.setCornerRadius(Ui.dp(this, 22));
            pg.setColor(Ui.color(this, R.color.surface));
            pg.setStroke(Ui.dp(this, 1), Ui.color(this, R.color.stroke));
            pill.setBackground(pg);
            pill.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            pill.setAlpha(0f);
            pill.setOnClickListener(v -> askPage());
            Ui.press(this, pill);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pl.bottomMargin = Ui.dp(this, 92);
            holder.addView(pill, pl);
            ViewerBar.attach(this, holder, false,
                    new int[]{R.drawable.ic_zoom, R.drawable.ic_list_play, R.drawable.ic_night},
                    new int[]{R.string.v_zoom, R.string.rd_page_jump, R.string.rd_night},
                    new View.OnClickListener[]{v -> cycleZoom(), v -> askPage(), v -> toggleNight()});
            prefetchRatios();
            int saved = (int) Store.resume(this, file.getAbsolutePath());
            if (saved > 0 && saved < pageCount) {
                list.setSelection(saved);
                Toast.makeText(this, getString(R.string.rd_resumed_page, saved + 1), Toast.LENGTH_SHORT).show();
            }
            updateSubtitle(saved);
            showPill(saved);
        });
    }

    /** Reads every page's shape in the background so heights are right before they are drawn (no jumps while zooming). */
    private void prefetchRatios() {
        final int count = Math.min(pageCount, 3000);
        io.execute(() -> {
            final float[] found = new float[count];
            for (int i = 0; i < count; i++) {
                if (destroyed) return;
                synchronized (lock) {
                    if (destroyed || renderer == null) return;
                    try {
                        PdfRenderer.Page p = renderer.openPage(i);
                        try {
                            if (p.getWidth() > 0) found[i] = (float) p.getHeight() / p.getWidth();
                        } finally {
                            p.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            ui.post(() -> {
                if (destroyed || list == null || ratios == null) return;
                boolean changed = false;
                for (int i = 0; i < count && i < ratios.length; i++) {
                    if (found[i] > 0 && Math.abs(ratios[i] - found[i]) > 0.001f) {
                        ratios[i] = found[i];
                        changed = true;
                    }
                }
                if (!changed) return;
                int pos = list.getFirstVisiblePosition();
                View c = list.getChildAt(0);
                int top = c == null ? 0 : c.getTop();
                adapter.notifyDataSetChanged();
                list.setSelectionFromTop(pos, top - list.getPaddingTop());
                scheduleTiles();
            });
        });
    }

    private int listWidth() {
        return Math.round(viewW * zoom);
    }

    private void updateSubtitle(int first) {
        firstVisible = first;
        subtitle.setText("PDF · " + pageCount + " · " + Fmt.size(file.length()));
        showPill(first);
    }

    private void toggleNight() {
        night = !night;
        Store.setIntPref(this, "pdf_night", night ? 1 : 0);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** Inverts the page colours (white paper becomes dark) for comfortable night reading. */
    private static final ColorMatrixColorFilter INVERT = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            -0.9f, 0, 0, 0, 255 * 0.93f,
            0, -0.9f, 0, 0, 255 * 0.93f,
            0, 0, -0.9f, 0, 255 * 0.93f,
            0, 0, 0, 1, 0}));

    private void showPill(int firstVisible) {
        if (pill == null) return;
        String label = getString(R.string.v_page_of, Math.min(firstVisible + 1, pageCount), pageCount);
        if (zoom > 1.02f) label += "  ·  " + Math.round(zoom * 100) + "%";
        pill.setText(label);
        pill.animate().cancel();
        pill.setAlpha(1f);
        ui.removeCallbacks(hidePill);
        ui.postDelayed(hidePill, 1600);
    }

    private void showZoomPill(float z) {
        if (pill == null) return;
        pill.setText(Math.round(z * 100) + "%");
        pill.animate().cancel();
        pill.setAlpha(1f);
        ui.removeCallbacks(hidePill);
        ui.postDelayed(hidePill, 1600);
    }

    private void askPage() {
        if (list == null) return;
        final android.widget.EditText e = Ui.edit(this, getString(R.string.v_go_page, pageCount), "");
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        new Dlg(this).setTitle(R.string.v_go_page_title).setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int n = Integer.parseInt(e.getText().toString().trim());
                        list.setSelection(Math.max(0, Math.min(pageCount, n) - 1));
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Zoom button: steps through the preset levels around the centre of the screen. */
    private void cycleZoom() {
        if (list == null || pinch == null) return;
        float next = ZOOMS[0];
        for (float z : ZOOMS) {
            if (z > zoom + 0.05f && z <= maxZoom + 0.01f) {
                next = z;
                break;
            }
        }
        float cx = pinch.getWidth() / 2f;
        float cy = pinch.getHeight() / 2f;
        animateZoom(next, cx, cy);
    }

    /**
     * Applies a new zoom and keeps the page point that was under (sx, sy) under (ex, ey): vertically by
     * re-anchoring the list on the page it sits on, horizontally by rescrolling the wrapper.
     */
    private void commitZoom(float newZoom, float sx, float sy, float ex, float ey) {
        if (list == null || hsv == null || adapter == null || content == null || pinch == null) return;
        newZoom = Math.max(1f, Math.min(maxZoom, newZoom));
        if (Math.abs(newZoom - zoom) < 0.0005f) return;
        final int margin = Ui.dp(this, 8);
        final int padTop = list.getPaddingTop();
        final int oldPageW = Math.max(100, listWidth() - 2 * margin);

        // vertical anchor: the page under the point (the nearest one when the point sits in a gap)
        int anchorPos = firstVisible;
        float anchorOff = 0f;
        int first = list.getFirstVisiblePosition();
        int n = list.getChildCount();
        if (n > 0) {
            int best = 0;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                View c = list.getChildAt(i);
                float d = sy < c.getTop() ? c.getTop() - sy : sy > c.getBottom() ? sy - c.getBottom() : 0f;
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            View c = list.getChildAt(best);
            anchorPos = first + best;
            anchorOff = sy - c.getTop() - margin / 2f;   // distance inside the page itself
        }
        final float contentX = hsv.getScrollX() + sx;

        zoom = newZoom;
        invalidateTiles();
        final int newW = listWidth();
        final float pageRatio = Math.max(100, newW - 2 * margin) / (float) oldPageW;
        content.setFixedWidth(newW);
        adapter.notifyDataSetChanged();
        float newOff = margin / 2f + anchorOff * pageRatio;
        list.setSelectionFromTop(anchorPos, Math.round(ey - newOff) - padTop);

        // lay everything out right now so the horizontal position is exact in this very frame
        pinch.measure(View.MeasureSpec.makeMeasureSpec(pinch.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pinch.getHeight(), View.MeasureSpec.EXACTLY));
        pinch.layout(pinch.getLeft(), pinch.getTop(), pinch.getRight(), pinch.getBottom());
        float target = (contentX - margin) * pageRatio + margin - ex;
        int max = Math.max(0, newW - hsv.getWidth());
        hsv.scrollTo(Math.max(0, Math.min(max, Math.round(target))), 0);

        showPill(firstVisible);
        scheduleTiles();
    }

    private android.animation.ValueAnimator zoomAnim;

    /** Smooth zoom to a level around a fixed point (double tap and the zoom button). */
    private void animateZoom(final float target, final float fx, final float fy) {
        if (list == null || pinch == null) return;
        final float to = Math.max(1f, Math.min(maxZoom, target));
        if (zoomAnim != null) zoomAnim.cancel();
        if (Math.abs(to - zoom) < 0.005f) return;
        final android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(zoom, to);
        va.setDuration(220);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            liveZoom = true;
            commitZoom((float) a.getAnimatedValue(), fx, fy, fx, fy);
            showZoomPill(zoom);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                liveZoom = false;
                if (zoomAnim == animation) zoomAnim = null;
                if (adapter != null && !destroyed) adapter.notifyDataSetChanged();
                scheduleTiles();
            }
        });
        zoomAnim = va;
        va.start();
    }

    /** Rotation, split screen or a foldable opening: refit the pages to the new width. */
    @Override
    protected void onLayoutChanged() {
        if (list == null) return;
        final FrameLayout holder = findViewById(R.id.holder);
        holder.post(() -> {
            if (destroyed || list == null) return;
            if (holder.getWidth() <= 0 || holder.getWidth() == viewW) return;
            viewW = holder.getWidth();
            invalidateTiles();
            content.setFixedWidth(listWidth());
            adapter.notifyDataSetChanged();
            scheduleTiles();
        });
    }

    // ------------------------------------------------------------------ pages

    private final class PageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pageCount;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int margin = Ui.dp(PdfViewActivity.this, 8);
            FrameLayout row;
            FrameLayout page;
            ImageView iv;
            ImageView detail;
            if (convertView instanceof FrameLayout && ((FrameLayout) convertView).getChildCount() == 1) {
                row = (FrameLayout) convertView;
                page = (FrameLayout) row.getChildAt(0);
                iv = (ImageView) page.getChildAt(0);
                detail = (ImageView) page.getChildAt(1);
            } else {
                row = new FrameLayout(PdfViewActivity.this);
                row.setPadding(margin, margin / 2, margin, margin / 2);
                page = new FrameLayout(PdfViewActivity.this);
                page.setBackgroundColor(Color.WHITE);
                final float rad = Ui.dp(PdfViewActivity.this, 10);
                page.setClipToOutline(true);
                page.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                    }
                });
                iv = new ImageView(PdfViewActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                page.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
                detail = new ImageView(PdfViewActivity.this);
                detail.setScaleType(ImageView.ScaleType.FIT_XY);
                detail.setVisibility(View.GONE);
                page.addView(detail, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
                row.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            int pageW = Math.max(100, listWidth() - 2 * margin);
            float ratio = ratios[position] > 0 ? ratios[position] : defaultRatio;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) iv.getLayoutParams();
            lp.height = Math.round(pageW * ratio);
            iv.setLayoutParams(lp);
            iv.setColorFilter(night ? INVERT : null);
            detail.setColorFilter(night ? INVERT : null);
            bind(position, pageW, iv);
            showTile(position, pageW, detail);
            return row;
        }
    }

    private void showTile(int position, int pageW, ImageView detail) {
        Tile t = tiles.get(position);
        if (t == null || t.bmp == null || t.pageW != pageW) {
            detail.setImageDrawable(null);
            detail.setVisibility(View.GONE);
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) detail.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = t.left;
        lp.topMargin = t.top;
        lp.width = t.bmp.getWidth();
        lp.height = t.bmp.getHeight();
        detail.setLayoutParams(lp);
        detail.setImageBitmap(t.bmp);
        detail.setVisibility(View.VISIBLE);
    }

    private void invalidateTiles() {
        tileGen++;
        tiles.clear();
        ui.removeCallbacks(tileRunner);
    }

    private void scheduleTiles() {
        if (list == null || destroyed) return;
        ui.removeCallbacks(tileRunner);
        ui.postDelayed(tileRunner, TILE_DELAY);
    }

    private ImageView pageViewAt(int childIndex) {
        View row = list.getChildAt(childIndex);
        if (!(row instanceof FrameLayout) || ((FrameLayout) row).getChildCount() == 0) return null;
        View page = ((FrameLayout) row).getChildAt(0);
        if (!(page instanceof FrameLayout) || ((FrameLayout) page).getChildCount() < 2) return null;
        View iv = ((FrameLayout) page).getChildAt(0);
        return iv instanceof ImageView ? (ImageView) iv : null;
    }

    private void startTiles() {
        if (list == null || destroyed || adapter == null) return;
        final int first = list.getFirstVisiblePosition();
        final int count = list.getChildCount();
        final List<TileJob> jobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ImageView iv = pageViewAt(i);
            if (iv == null) continue;
            int pos = first + i;
            int pageW = iv.getWidth();
            int pageH = iv.getHeight();
            if (pageW <= MAX_BITMAP_W || pageH <= 0) continue;
            Rect vis = new Rect();
            if (!iv.getLocalVisibleRect(vis) || vis.width() <= 0 || vis.height() <= 0) continue;
            int padX = vis.width() / 5;
            int padY = vis.height() / 5;
            int l = Math.max(0, vis.left - padX);
            int t = Math.max(0, vis.top - padY);
            int r = Math.min(pageW, vis.right + padX);
            int b = Math.min(pageH, vis.bottom + padY);
            for (int k = 0; k < 6 && (r - l > MAX_TILE_DIM || b - t > MAX_TILE_DIM
                    || (long) (r - l) * (b - t) > MAX_TILE_PIXELS); k++) {
                l = Math.max(0, l + (vis.left - l) / 2);
                t = Math.max(0, t + (vis.top - t) / 2);
                r = Math.min(pageW, r - (r - vis.right) / 2);
                b = Math.min(pageH, b - (b - vis.bottom) / 2);
            }
            if (r - l > MAX_TILE_DIM || b - t > MAX_TILE_DIM || (long) (r - l) * (b - t) > MAX_TILE_PIXELS) {
                int cx = (vis.left + vis.right) / 2;
                int cy = (vis.top + vis.bottom) / 2;
                int w = Math.min(MAX_TILE_DIM, 2400);
                int h = Math.min(MAX_TILE_DIM, MAX_TILE_PIXELS / w);
                l = Math.max(0, Math.min(pageW - w, cx - w / 2));
                t = Math.max(0, Math.min(pageH - h, cy - h / 2));
                r = Math.min(pageW, l + w);
                b = Math.min(pageH, t + h);
            }
            Tile have = tiles.get(pos);
            if (have != null && have.pageW == pageW && have.left <= vis.left && have.top <= vis.top
                    && have.left + have.bmp.getWidth() >= vis.right && have.top + have.bmp.getHeight() >= vis.bottom) {
                continue;
            }
            TileJob job = new TileJob();
            job.position = pos;
            job.pageW = pageW;
            job.left = l;
            job.top = t;
            job.right = r;
            job.bottom = b;
            jobs.add(job);
        }
        for (int i = tiles.size() - 1; i >= 0; i--) {
            int pos = tiles.keyAt(i);
            if (pos < first - 1 || pos > first + count) tiles.removeAt(i);
        }
        if (jobs.isEmpty()) return;
        final int gen = ++tileGen;
        io.execute(() -> {
            for (TileJob job : jobs) {
                if (destroyed || gen != tileGen) return;
                Bitmap b = null;
                synchronized (lock) {
                    if (destroyed || renderer == null) return;
                    try {
                        PdfRenderer.Page p = renderer.openPage(job.position);
                        try {
                            int w = job.right - job.left;
                            int h = job.bottom - job.top;
                            float scale = (float) job.pageW / Math.max(1, p.getWidth());
                            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            b.eraseColor(Color.WHITE);
                            Matrix m = new Matrix();
                            m.setScale(scale, scale);
                            m.postTranslate(-job.left, -job.top);
                            p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            p.close();
                        }
                    } catch (Throwable t) {
                        b = null;
                    }
                }
                if (b == null) continue;
                final Tile tile = new Tile();
                tile.bmp = b;
                tile.pageW = job.pageW;
                tile.left = job.left;
                tile.top = job.top;
                final int position = job.position;
                ui.post(() -> {
                    if (destroyed || gen != tileGen || list == null) return;
                    tiles.put(position, tile);
                    int firstNow = list.getFirstVisiblePosition();
                    int idx = position - firstNow;
                    if (idx < 0 || idx >= list.getChildCount()) return;
                    ImageView iv = pageViewAt(idx);
                    if (iv == null) return;
                    ViewGroup pageGroup = (ViewGroup) iv.getParent();
                    showTile(position, iv.getWidth(), (ImageView) pageGroup.getChildAt(1));
                });
            }
        });
    }

    private void bind(final int position, final int pageW, final ImageView iv) {
        final int bw = Math.min(pageW, MAX_BITMAP_W);
        final String key = position + "@" + bw;
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            lastBmp.put(position, new WeakReference<>(cached));
            iv.setImageBitmap(cached);
            return;
        }
        WeakReference<Bitmap> prev = lastBmp.get(position);
        Bitmap stale = prev == null ? null : prev.get();
        iv.setImageBitmap(stale != null && !stale.isRecycled() ? stale : null);   // blurry for a moment, never blank
        if (liveZoom && stale != null && !stale.isRecycled()) return;
        io.execute(() -> {
            Bitmap b = null;
            float ratio = 0;
            synchronized (lock) {
                if (destroyed || renderer == null) return;
                try {
                    PdfRenderer.Page p = renderer.openPage(position);
                    try {
                        ratio = (float) p.getHeight() / Math.max(1, p.getWidth());
                        int h = Math.max(1, Math.round(bw * ratio));
                        b = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
                        b.eraseColor(Color.WHITE);
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        p.close();
                    }
                } catch (Throwable t) {
                    b = null;
                }
            }
            if (b == null) return;
            cache.put(key, b);
            final Bitmap fb = b;
            final float fr = ratio;
            ui.post(() -> {
                if (destroyed) return;
                if (ratios != null && position < ratios.length && Math.abs(ratios[position] - fr) > 0.001f) {
                    ratios[position] = fr;
                    if (key.equals(iv.getTag())) {
                        ViewGroup.LayoutParams lp = iv.getLayoutParams();
                        lp.height = Math.round(pageW * fr);
                        iv.setLayoutParams(lp);
                    }
                }
                lastBmp.put(position, new WeakReference<>(fb));   // UI thread only: SparseArray is not thread-safe
                if (key.equals(iv.getTag())) iv.setImageBitmap(fb);
            });
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pageCount > 0) Store.setResume(this, file.getAbsolutePath(), firstVisible);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (zoomAnim != null) zoomAnim.cancel();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        synchronized (lock) {
            try {
                if (renderer != null) renderer.close();
            } catch (Exception ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
            renderer = null;
            pfd = null;
        }
    }
}
