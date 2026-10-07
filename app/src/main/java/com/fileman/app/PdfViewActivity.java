package com.fileman.app;

import android.graphics.Bitmap;
import android.graphics.Color;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF viewer built on the platform PdfRenderer: scrolling pages, 4 zoom levels, page counter. */
public class PdfViewActivity extends BaseActivity {
    private static final float[] ZOOMS = {1f, 1.5f, 2f, 3f};
    private static final int MAX_BITMAP_W = 2600;

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
    private int zoomIdx = 0;
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
                new String[]{getString(R.string.rd_night) + (night ? "  ✓" : ""), getString(R.string.rd_page_jump)},
                new Runnable[]{this::toggleNight, this::askPage}));
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
        int errorRes = 0;
        try {
            synchronized (lock) {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
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
            errorRes = R.string.v_pdf_protected;
        } catch (IOException | RuntimeException e) {
            errorRes = R.string.v_pdf_failed;
        }
        final int err = errorRes;
        ui.post(() -> {
            if (destroyed || isFinishing()) return;
            loading.setVisibility(View.INVISIBLE);
            if (err != 0 || pageCount == 0) {
                Toast.makeText(this, err != 0 ? err : R.string.v_empty_doc, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            ratios = new float[pageCount];
            buildList();
        });
    }

    private void buildList() {
        final FrameLayout holder = findViewById(R.id.holder);
        holder.post(() -> {
            if (destroyed) return;
            viewW = holder.getWidth();
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
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
                }
            });
            hsv.addView(list, new FrameLayout.LayoutParams(listWidth(), ViewGroup.LayoutParams.MATCH_PARENT));
            holder.addView(hsv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
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
            int saved = (int) Store.resume(this, file.getAbsolutePath());
            if (saved > 0 && saved < pageCount) {
                list.setSelection(saved);
                Toast.makeText(this, getString(R.string.rd_resumed_page, saved + 1), Toast.LENGTH_SHORT).show();
            }
            updateSubtitle(saved);
            showPill(saved);
        });
    }

    private int listWidth() {
        return Math.round(viewW * ZOOMS[zoomIdx]);
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
        pill.setText(getString(R.string.v_page_of, Math.min(firstVisible + 1, pageCount), pageCount));
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

    private void cycleZoom() {
        if (list == null) return;
        zoomIdx = (zoomIdx + 1) % ZOOMS.length;
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        lp.width = listWidth();
        list.setLayoutParams(lp);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, Math.round(ZOOMS[zoomIdx] * 100) + "%", Toast.LENGTH_SHORT).show();
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
            ImageView iv;
            if (convertView instanceof FrameLayout) {
                row = (FrameLayout) convertView;
                iv = (ImageView) row.getChildAt(0);
            } else {
                row = new FrameLayout(PdfViewActivity.this);
                row.setPadding(margin, margin / 2, margin, margin / 2);
                iv = new ImageView(PdfViewActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setBackgroundColor(Color.WHITE);
                final float rad = Ui.dp(PdfViewActivity.this, 10);
                iv.setClipToOutline(true);
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                    }
                });
                row.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
                row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            int pageW = Math.max(100, listWidth() - 2 * margin);
            float ratio = ratios[position] > 0 ? ratios[position] : defaultRatio;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) iv.getLayoutParams();
            lp.height = Math.round(pageW * ratio);
            iv.setLayoutParams(lp);
            iv.setColorFilter(night ? INVERT : null);
            bind(position, pageW, iv);
            return row;
        }
    }

    private void bind(final int position, final int pageW, final ImageView iv) {
        final int bw = Math.min(pageW, MAX_BITMAP_W);
        final String key = position + "@" + bw;
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageBitmap(null);
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
