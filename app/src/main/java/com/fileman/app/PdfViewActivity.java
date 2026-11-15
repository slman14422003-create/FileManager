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
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        topBar = findViewById(R.id.pdfTop);
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
            Opener.moreMenu(this, file,
                    new String[]{getString(R.string.rd_night) + (night ? "  ✓" : ""),
                            getString(R.string.rd_page_jump),
                            getString(R.string.pdf_lock_rotation) + (rotationLocked ? "  ✓" : ""),
                            getString(R.string.v_print)},
                    new int[]{R.drawable.ic_night, R.drawable.ic_list_play, R.drawable.ic_rotate, R.drawable.ic_file_text},
                    new Runnable[]{this::toggleNight, this::askPage, this::toggleRotationLock, this::printPdf});
        });
        night = Store.intPref(this, "pdf_night", 0) == 1;

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
