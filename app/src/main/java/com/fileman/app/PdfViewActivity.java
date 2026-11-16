package com.fileman.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF viewer built on the platform PdfRenderer. All layout, scrolling and zooming is done by {@link PdfPagesView}:
 * pinch zoom follows the fingers, double tap zooms around the tap, rotation keeps the reading position and
 * re-renders sharply, and the header and the floating bar slide away while reading.
 */
public class PdfViewActivity extends BaseActivity {
    @Override
    protected java.io.File aiFile() {
        return file;
    }

    @Override
    protected boolean glassScreen() {
        return false;
    }

    @Override
    protected boolean constrainContent() {
        return false;
    }

    private static final float[] ZOOMS = {1f, 1.5f, 2f, 3f, 4f, 5f};
    private static final long IDLE_HIDE_MS = 4000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private File file;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private volatile boolean destroyed = false;
    private int pageCount = 0;
    private float defaultRatio = 1.414f;
    private boolean night = false;
    private boolean rotationLocked = false;
    private String openedPwd = null;

    private View loading;
    private TextView subtitle;
    private TextView pill;
    private PdfPagesView view;
    private View topBar;
    private View bottomBar;
    private boolean barsShown = true;
    private float scrollAcc = 0f;

    private final Runnable hidePill = () -> {
        if (pill != null) pill.animate().alpha(0f).setDuration(250).start();
    };
    private final Runnable idleHide = () -> showBars(false);

    /** Rendering runs on the view's worker thread; the renderer itself allows one open page at a time. */
    private final PdfPagesView.Source source = new PdfPagesView.Source() {
        @Override
        public Bitmap renderPage(int index, int width, float[] ratioOut) {
            synchronized (lock) {
                if (destroyed || renderer == null) return null;
                PdfRenderer.Page p = null;
                try {
                    p = renderer.openPage(index);
                    float ratio = (float) p.getHeight() / Math.max(1, p.getWidth());
                    ratioOut[0] = ratio;
                    int h = Math.max(1, Math.round(width * ratio));
                    Bitmap b = Bitmap.createBitmap(Math.max(1, width), h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return b;
                } catch (Throwable t) {
                    return null;
                } finally {
                    if (p != null) {
                        try {
                            p.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }

        @Override
        public Bitmap renderTile(int index, int pageW, int l, int t, int r, int b) {
            synchronized (lock) {
                if (destroyed || renderer == null) return null;
                PdfRenderer.Page p = null;
                try {
                    p = renderer.openPage(index);
                    Bitmap bmp = Bitmap.createBitmap(Math.max(1, r - l), Math.max(1, b - t), Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    float scale = (float) pageW / Math.max(1, p.getWidth());
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    m.postTranslate(-l, -t);
                    p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return bmp;
                } catch (Throwable e) {
                    return null;
                } finally {
                    if (p != null) {
                        try {
                            p.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideNavigationBar();
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        topBar = findViewById(R.id.pdfTop);
        styleHeader();
        ((TextView) findViewById(R.id.title)).setText(file.getName());
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText("PDF · " + Fmt.size(file.length()));
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ViewerBar.headerIcon(this, file);

        ImageButton more = findViewById(R.id.btnA2);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> {
            scheduleIdleHide();
            showMenu();
        });
        night = Store.intPref(this, "pdf_night", 0) == 1;

        loading.setVisibility(View.VISIBLE);
        io.execute(this::openDocument);
    }

    private void styleHeader() {
        int[] ids = {R.id.btnBack, R.id.btnAi, R.id.btnA2};
        int accent = Ui.color(this, R.color.accent);
        for (int id : ids) {
            ImageButton b = findViewById(id);
            b.setBackground(glass(true));
            b.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
            b.setElevation(Ui.dp(this, 5));
            Ui.press(this, b);
        }
        View cap = findViewById(R.id.pdfCapsule);
        cap.setBackground(glass(false));
        cap.setElevation(Ui.dp(this, 5));
        topBar.setBackgroundColor(0);
        topBar.setElevation(0f);
    }

    private GradientDrawable glass(boolean round) {
        GradientDrawable g = new GradientDrawable();
        if (round) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(Ui.dp(this, 23));
        g.setColor(Blur.surface(this, Ui.color(this, R.color.surface), 0xF0));
        g.setStroke(Math.max(1, Ui.dp(this, 1) / 2), Ui.color(this, R.color.stroke));
        return g;
    }

    private void showMenu() {
        java.util.List<ActionSheet.Item> tiles = new java.util.ArrayList<>();
        tiles.add(new ActionSheet.Item(R.drawable.ic_night, getString(R.string.rd_night), this::toggleNight)
                .toggle(() -> night));
        tiles.add(new ActionSheet.Item(R.drawable.ic_list_play, getString(R.string.rd_page_jump), this::askPage));
        tiles.add(new ActionSheet.Item(R.drawable.ic_rotate, getString(R.string.pdf_lock_rotation), this::toggleRotationLock)
                .toggle(() -> rotationLocked));
        tiles.add(new ActionSheet.Item(R.drawable.ic_zoom, getString(R.string.v_zoom), this::cycleZoom));
        java.util.List<ActionSheet.Item> rows = new java.util.ArrayList<>();
        rows.add(new ActionSheet.Item(R.drawable.ic_file_text, getString(R.string.px_title), this::openExport));
        rows.add(new ActionSheet.Item(R.drawable.ic_open, getString(R.string.fm_open_with), () -> Opener.external(this, file, true)));
        rows.add(new ActionSheet.Item(R.drawable.ic_share, getString(R.string.share), () -> Opener.share(this, file)));
        ActionSheet.show(this, file.getName(),
                "PDF · " + (pageCount > 0 ? pageCount + " · " : "") + Fmt.size(file.length()),
                R.drawable.ic_file_text, tiles, rows);
    }

    private void openDocument() {
        openDocument(null);
    }

    /** Android 15+ can open password protected files: the constructor with LoadParams is reached by reflection. */
    static PdfRenderer newRenderer(ParcelFileDescriptor fd, String pwd) throws IOException {
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
                openedPwd = pwd;
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
            buildViewer();
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

    /** Save / print options screen (format, pages, compression, password...). */
    private void openExport() {
        startActivity(new android.content.Intent(this, PdfExportActivity.class)
                .putExtra("path", file.getAbsolutePath()).putExtra("pwd", openedPwd));
    }

    // ------------------------------------------------------------------ viewer

    private void buildViewer() {
        final FrameLayout holder = findViewById(R.id.holder);
        long budget = Math.max(16L * 1024 * 1024, Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6));
        view = new PdfPagesView(this, (int) budget);
        view.setNight(night);
        view.setListener(new PdfPagesView.Listener() {
            @Override
            public void onPage(int index) {
                showPill(index);
            }

            @Override
            public void onScrolled(float dy) {
                // scrolling forward hides the bars, a scroll back (or reaching the top) brings them back
                if ((dy > 0) == (scrollAcc > 0)) scrollAcc += dy;
                else scrollAcc = dy;
                float step = Ui.dp(PdfViewActivity.this, 28);
                if (scrollAcc > step) {
                    scrollAcc = 0f;
                    showBars(false);
                } else if (scrollAcc < -step || (view != null && view.isAtTop())) {
                    scrollAcc = 0f;
                    showBars(true);
                }
            }

            @Override
            public void onTap() {
                showBars(!barsShown);
            }

            @Override
            public void onZoom(float z) {
                showZoomPill(z);
            }

            @Override
            public void onTouch() {
                if (barsShown) scheduleIdleHide();
            }
        });
        holder.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        view.setSource(source, pageCount, defaultRatio);

        // the header floats over the page: keep the first page below it
        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (view != null) view.setTopInset(b - t);
        });
        view.setTopInset(topBar.getHeight());

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

        bottomBar = ViewerBar.attach(this, holder, false,
                new int[]{R.drawable.ic_zoom, R.drawable.ic_list_play, R.drawable.ic_night},
                new int[]{R.string.v_zoom, R.string.rd_page_jump, R.string.rd_night},
                new View.OnClickListener[]{v -> cycleZoom(), v -> askPage(), v -> toggleNight()});

        int saved = (int) Store.resume(this, file.getAbsolutePath());
        if (saved > 0 && saved < pageCount) {
            view.scrollToPage(saved);
            Toast.makeText(this, getString(R.string.rd_resumed_page, saved + 1), Toast.LENGTH_SHORT).show();
        }
        subtitle.setText("PDF · " + pageCount + " · " + Fmt.size(file.length()));
        showPill(Math.max(0, saved));
        prefetchRatios();
        scheduleIdleHide();
    }

    /** Reads every page's shape in the background so heights are right before they are drawn (no jumps while scrolling). */
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
                if (destroyed || view == null) return;
                view.setRatios(found);
            });
        });
    }

    // ------------------------------------------------------------------ bars

    private void scheduleIdleHide() {
        ui.removeCallbacks(idleHide);
        ui.postDelayed(idleHide, IDLE_HIDE_MS);
    }

    /** Slides the header and the floating bar away (or back); they also hide on their own after a few idle seconds. */
    private void showBars(boolean show) {
        ui.removeCallbacks(idleHide);
        if (show == barsShown || topBar == null) {
            if (show) scheduleIdleHide();
            return;
        }
        barsShown = show;
        float shift = Ui.dp(this, 24);
        for (int k = 0; k < 2; k++) {
            final View v = k == 0 ? topBar : bottomBar;
            if (v == null) continue;
            float away = k == 0 ? -shift : shift;
            v.animate().cancel();
            if (show) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).translationY(0f).setDuration(200).start();
            } else {
                v.animate().alpha(0f).translationY(away).setDuration(200)
                        .withEndAction(() -> {
                            if (!barsShown) v.setVisibility(View.GONE);
                        }).start();
            }
        }
        if (show) scheduleIdleHide();
    }

    // ------------------------------------------------------------------ actions

    private void showPill(int firstVisible) {
        if (pill == null || view == null) return;
        String label = getString(R.string.v_page_of, Math.min(firstVisible + 1, pageCount), pageCount);
        float z = view.getZoom();
        if (z > 1.02f) label += "  ·  " + Math.round(z * 100) + "%";
        pill.setText(label);
        pill.animate().cancel();
        pill.setAlpha(1f);
        ui.removeCallbacks(hidePill);
        ui.postDelayed(hidePill, 1600);
    }

    private void showZoomPill(float z) {
        if (pill == null || view == null) return;
        pill.setText(getString(R.string.v_page_of, Math.min(view.getCurrentPage() + 1, pageCount), pageCount)
                + "  ·  " + Math.round(z * 100) + "%");
        pill.animate().cancel();
        pill.setAlpha(1f);
        ui.removeCallbacks(hidePill);
        ui.postDelayed(hidePill, 1600);
    }

    private void toggleNight() {
        night = !night;
        Store.setIntPref(this, "pdf_night", night ? 1 : 0);
        if (view != null) view.setNight(night);
    }

    /** Keeps the screen in its current orientation (or lets it rotate with the phone again). */
    private void toggleRotationLock() {
        rotationLocked = !rotationLocked;
        setRequestedOrientation(rotationLocked ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void askPage() {
        if (view == null) return;
        final android.widget.EditText e = Ui.edit(this, getString(R.string.v_go_page, pageCount), "");
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        new Dlg(this).setTitle(R.string.v_go_page_title).setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int n = Integer.parseInt(e.getText().toString().trim());
                        view.scrollToPage(Math.max(0, Math.min(pageCount, n) - 1));
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Zoom button: steps through the preset levels around the centre of the screen. */
    private void cycleZoom() {
        if (view == null) return;
        float cur = view.getZoom();
        float next = ZOOMS[0];
        for (float z : ZOOMS) {
            if (z > cur + 0.05f) {
                next = z;
                break;
            }
        }
        view.animateZoom(next, view.getWidth() / 2f, view.getHeight() / 2f);
    }

    /** Hides the status bar and the navigation bar; a swipe from the edge shows them briefly. */
    private void hideNavigationBar() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNavigationBar();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pageCount > 0 && view != null) Store.setResume(this, file.getAbsolutePath(), view.getCurrentPage());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (view != null) view.destroy();
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
