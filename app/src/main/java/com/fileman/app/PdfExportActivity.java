package com.fileman.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Save / print PDF": its own options screen. The file is rebuilt from the rendered pages, so the options are
 * output format (PDF or page images), page range, quality / size, colour or grey, a password to open the file,
 * a copy / edit restriction and the file name. The original file is never touched.
 */
public class PdfExportActivity extends BaseActivity {
    private interface Pick {
        void on(int index);
    }

    private static final int F_PDF = 0, F_JPG = 1, F_PNG = 2;
    /** dpi and JPEG quality per quality level: original, high, medium, smallest. */
    private static final int[] DPI = {200, 150, 110, 80};
    private static final int[] JPEG_Q = {92, 82, 70, 55};
    private static final long MAX_PIXELS = 24_000_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean destroyed = false;

    private File file;
    private String openPwd;
    private int pageCount = 0;

    private int format = F_PDF;
    private int quality = 1;
    private boolean gray = false;
    private boolean allPages = true;

    private EditText rangeEdit, nameEdit, pwEdit, pw2Edit;
    private Ui.Toggle pwToggle, restrictToggle;
    private View pwCard, qualityBlock, noteView;
    private TextView noteText;
    private LinearLayout form;

    @Override
    protected java.io.File aiFile() {
        return file;
    }

    @Override
    protected boolean glassScreen() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        openPwd = getIntent().getStringExtra("pwd");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ((TextView) findViewById(R.id.title)).setText(R.string.px_title);
        ((TextView) findViewById(R.id.subtitle)).setText(file.getName());
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ViewerBar.headerIcon(this, file);
        findViewById(R.id.loading).setVisibility(View.VISIBLE);
        io.execute(() -> {
            final int n = readPageCount();
            ui.post(() -> {
                if (destroyed) return;
                findViewById(R.id.loading).setVisibility(View.INVISIBLE);
                if (n <= 0) {
                    Toast.makeText(this, R.string.v_pdf_failed, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                pageCount = n;
                ((TextView) findViewById(R.id.subtitle)).setText(
                        getString(R.string.px_sub_pages, n) + " · " + Fmt.size(file.length()));
                buildForm();
            });
        });
    }

    private PdfRenderer open(ParcelFileDescriptor[] fdOut) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        fdOut[0] = fd;
        return PdfViewActivity.newRenderer(fd, openPwd);
    }

    private int readPageCount() {
        ParcelFileDescriptor[] fd = new ParcelFileDescriptor[1];
        PdfRenderer r = null;
        try {
            r = open(fd);
            return r.getPageCount();
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
            try {
                if (fd[0] != null) fd[0].close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ form

    private HorizontalScrollView chips(String[] labels, int selected, final Pick pick) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        final List<TextView> all = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final TextView c = Ui.chip(this, labels[i], i == selected);
            all.add(c);
            c.setOnClickListener(v -> {
                for (int k = 0; k < all.size(); k++) Ui.setChip(this, all.get(k), k == idx);
                pick.on(idx);
            });
            row.addView(c);
        }
        sv.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private void buildForm() {
        FrameLayout holder = findViewById(R.id.holder);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, 0, 0, Ui.dp(this, 32));
        scroll.addView(form, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // format
        form.addView(Ui.sectionTitle(this, getString(R.string.px_sec_format)));
        form.addView(chips(new String[]{getString(R.string.px_fmt_pdf), getString(R.string.px_fmt_jpg), getString(R.string.px_fmt_png)},
                format, i -> {
                    format = i;
                    refresh();
                }));

        // pages
        form.addView(Ui.sectionTitle(this, getString(R.string.px_sec_pages)));
        form.addView(chips(new String[]{getString(R.string.px_pages_all), getString(R.string.px_pages_range)}, 0, i -> {
            allPages = i == 0;
            refresh();
        }));
        rangeEdit = Ui.edit(this, getString(R.string.px_pages_hint), "");
        rangeEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams rl = (LinearLayout.LayoutParams) rangeEdit.getLayoutParams();
        rl.setMargins(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        rangeEdit.setLayoutParams(rl);
        form.addView(rangeEdit);

        // quality and colour (hidden for PNG, which is lossless)
        LinearLayout qb = new LinearLayout(this);
        qb.setOrientation(LinearLayout.VERTICAL);
        qb.addView(Ui.sectionTitle(this, getString(R.string.px_sec_quality)));
        qb.addView(chips(new String[]{getString(R.string.px_q_orig), getString(R.string.px_q_high),
                getString(R.string.px_q_mid), getString(R.string.px_q_small)}, quality, i -> quality = i));
        qb.addView(Ui.sectionTitle(this, getString(R.string.px_sec_color)));
        qb.addView(chips(new String[]{getString(R.string.px_color_full), getString(R.string.px_color_gray)}, 0, i -> gray = i == 1));
        qualityBlock = qb;
        form.addView(qb);

        // protection (PDF only)
        LinearLayout pc = new LinearLayout(this);
        pc.setOrientation(LinearLayout.VERTICAL);
        pc.addView(Ui.sectionTitle(this, getString(R.string.px_sec_protect)));
        LinearLayout card = Ui.formCard(this);
        pwToggle = Ui.toggle(this, card, R.string.px_pw_title, R.string.px_pw_sub, false);
        card.addView(pwToggle.view);
        pwEdit = Ui.edit(this, getString(R.string.px_pw_hint), "");
        pwEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw2Edit = Ui.edit(this, getString(R.string.px_pw_confirm), "");
        pw2Edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(pwEdit);
        card.addView(pw2Edit);
        card.addView(Ui.label(this, getString(R.string.px_pw_note)));
        restrictToggle = Ui.toggle(this, card, R.string.px_restrict_title, R.string.px_restrict_sub, false);
        card.addView(restrictToggle.view);
        pwToggle.onChange((b, on) -> refresh());
        pc.addView(card);
        pwCard = pc;
        form.addView(pc);

        // file name
        form.addView(Ui.sectionTitle(this, getString(R.string.px_sec_name)));
        nameEdit = Ui.edit(this, "", baseName());
        LinearLayout.LayoutParams nl = (LinearLayout.LayoutParams) nameEdit.getLayoutParams();
        nl.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        nameEdit.setLayoutParams(nl);
        form.addView(nameEdit);

        noteText = Ui.noteCard(this, getString(R.string.px_note_pdf), R.color.text_secondary);
        noteView = noteText;
        form.addView(noteText);

        // actions
        android.widget.Button save = Ui.button(this, R.string.px_save, true);
        save.setOnClickListener(v -> startExport());
        form.addView(Ui.block(this, save));
        android.widget.Button print = Ui.button(this, R.string.px_print, false);
        print.setOnClickListener(v -> printOriginal());
        form.addView(Ui.block(this, print));
        refresh();
    }

    private String baseName() {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return base + " (2)";
    }

    private void refresh() {
        rangeEdit.setVisibility(allPages ? View.GONE : View.VISIBLE);
        qualityBlock.setVisibility(format == F_PNG ? View.GONE : View.VISIBLE);
        pwCard.setVisibility(format == F_PDF ? View.VISIBLE : View.GONE);
        boolean pw = pwToggle != null && pwToggle.isChecked();
        pwEdit.setVisibility(pw ? View.VISIBLE : View.GONE);
        pw2Edit.setVisibility(pw ? View.VISIBLE : View.GONE);
        noteText.setText(format == F_PDF ? R.string.px_note_pdf : R.string.px_note_img);
    }

    // ------------------------------------------------------------------ export

    /** "1-3, 5, 8-10" to zero-based page numbers; null when the text is not a valid range. */
    private int[] parsePages(String text) {
        TreeSet<Integer> set = new TreeSet<>();
        try {
            for (String part : text.replace('،', ',').replace(';', ',').split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int dash = p.indexOf('-');
                if (dash < 0) {
                    int n = Integer.parseInt(p);
                    if (n < 1 || n > pageCount) return null;
                    set.add(n - 1);
                } else {
                    int a = Integer.parseInt(p.substring(0, dash).trim());
                    int b = Integer.parseInt(p.substring(dash + 1).trim());
                    if (a < 1 || b < a || b > pageCount) return null;
                    for (int i = a; i <= b; i++) set.add(i - 1);
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (set.isEmpty()) return null;
        int[] out = new int[set.size()];
        int k = 0;
        for (int v : set) out[k++] = v;
        return out;
    }

    private void startExport() {
        final int[] pages;
        if (allPages) {
            pages = new int[pageCount];
            for (int i = 0; i < pageCount; i++) pages[i] = i;
        } else {
            pages = parsePages(rangeEdit.getText().toString());
            if (pages == null) {
                Toast.makeText(this, R.string.px_err_pages, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String pw = null;
        if (format == F_PDF && pwToggle.isChecked()) {
            pw = pwEdit.getText().toString();
            if (pw.isEmpty()) {
                Toast.makeText(this, R.string.px_err_pw_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!pw.equals(pw2Edit.getText().toString())) {
                Toast.makeText(this, R.string.px_err_pw, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final boolean restrict = format == F_PDF && restrictToggle.isChecked();
        String name = nameEdit.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) name = baseName();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        final String fname = name;
        final String fpw = pw;

        // progress dialog
        LinearLayout box = Ui.box(this);
        final TextView msg = Ui.body(this, getString(R.string.px_progress, 0, pages.length), 15, R.color.text_primary);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        msg.setPaddingRelative(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        Ui.tintRounded(this, bar);
        box.addView(msg);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)));
        cancelled.set(false);
        final AlertDialog dlg = new Dlg(this).setTitle(R.string.px_saving).setView(box)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelled.set(true)).create();
        dlg.setCancelable(false);
        dlg.show();

        final int fmt = format, q = quality;
        final boolean g = gray;
        io.execute(() -> {
            File result = null;
            int saved = 0;
            boolean ok = false;
            ParcelFileDescriptor[] fd = new ParcelFileDescriptor[1];
            PdfRenderer renderer = null;
            PdfBuilder builder = null;
            try {
                renderer = open(fd);
                File dir = outputDir();
                File folder = null;
                if (fmt == F_PDF) {
                    result = uniqueFile(dir, fname, ".pdf");
                    builder = new PdfBuilder(new BufferedOutputStream(new FileOutputStream(result), 1 << 16), fpw, restrict);
                } else {
                    folder = uniqueFile(dir, fname, "");
                    if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("folder");
                    result = folder;
                }
                int dpi = fmt == F_PNG ? DPI[1] : DPI[q];
                int jq = fmt == F_PNG ? 100 : JPEG_Q[q];
                for (int n = 0; n < pages.length; n++) {
                    if (cancelled.get() || destroyed) break;
                    PdfRenderer.Page p = renderer.openPage(pages[n]);
                    float wPt = p.getWidth(), hPt = p.getHeight();
                    float scale = dpi / 72f;
                    long px = (long) (wPt * scale) * (long) (hPt * scale);
                    if (px > MAX_PIXELS) scale *= (float) Math.sqrt((double) MAX_PIXELS / px);
                    int w = Math.max(1, Math.round(wPt * scale)), h = Math.max(1, Math.round(hPt * scale));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    try {
                        bmp.eraseColor(Color.WHITE);
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    } finally {
                        p.close();
                    }
                    if (g) {
                        Bitmap grey = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        Paint paint = new Paint();
                        ColorMatrix cm = new ColorMatrix();
                        cm.setSaturation(0f);
                        paint.setColorFilter(new ColorMatrixColorFilter(cm));
                        new Canvas(grey).drawBitmap(bmp, 0, 0, paint);
                        bmp.recycle();
                        bmp = grey;
                    }
                    if (fmt == F_PDF) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1 << 16, w * h / 8));
                        bmp.compress(Bitmap.CompressFormat.JPEG, jq, bo);
                        builder.addJpegPage(wPt, hPt, bo.toByteArray(), w, h);
                    } else {
                        boolean png = fmt == F_PNG;
                        File img = new File(folder, String.format(java.util.Locale.ROOT, "%s-%03d.%s", fname, pages[n] + 1, png ? "png" : "jpg"));
                        try (FileOutputStream os = new FileOutputStream(img)) {
                            bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, jq, os);
                        }
                    }
                    bmp.recycle();
                    saved++;
                    final int done = n + 1;
                    ui.post(() -> {
                        msg.setText(getString(R.string.px_progress, done, pages.length));
                        bar.setProgress(done * 100 / pages.length);
                    });
                }
                if (builder != null) {
                    if (!cancelled.get() && !destroyed) builder.finish();
                    builder.close();
                    builder = null;
                }
                ok = !cancelled.get() && saved > 0;
            } catch (Throwable t) {
                ok = false;
            } finally {
                try {
                    if (builder != null) builder.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (renderer != null) renderer.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (fd[0] != null) fd[0].close();
                } catch (Throwable ignored) {
                }
            }
            final boolean fok = ok;
            final File fres = result;
            final int fsaved = saved;
            if (!fok && fres != null) deleteAll(fres);
            ui.post(() -> {
                try {
                    dlg.dismiss();
                } catch (Exception ignored) {
                }
                if (destroyed || isFinishing()) return;
                if (cancelled.get()) return;
                if (!fok || fres == null) {
                    Toast.makeText(this, R.string.px_err_save, Toast.LENGTH_LONG).show();
                    return;
                }
                showDone(fres, fsaved);
            });
        });
    }

    private void showDone(final File result, int count) {
        if (result.isDirectory()) {
            new Dlg(this).setTitle(R.string.px_done)
                    .setMessage(getString(R.string.px_done_img, count, result.getAbsolutePath()))
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new Dlg(this).setTitle(R.string.px_done)
                .setMessage(getString(R.string.px_done_pdf, result.getName(), Fmt.size(result.length()), Fmt.size(file.length())))
                .setPositiveButton(R.string.pdf_open, (d, w) -> Opener.open(this, result))
                .setNeutralButton(R.string.share, (d, w) -> Opener.share(this, result))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Next to the original when that folder is writable, else Downloads, else the app cache. */
    private File outputDir() {
        File[] dirs = {file.getParentFile(), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), getCacheDir()};
        for (File d : dirs) {
            if (d == null) continue;
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            if (d.isDirectory() && d.canWrite()) return d;
        }
        return getCacheDir();
    }

    private static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        for (int i = 1; f.exists() && i < 1000; i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------------ print

    /** Hands the original file to the system print service (also "save as PDF"). */
    private void printOriginal() {
        try {
            android.print.PrintManager pm = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
            final String name = file.getName();
            final int pages = pageCount;
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
                            .setPageCount(pages > 0 ? pages : android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true);
                }

                @Override
                public void onWrite(android.print.PageRange[] ranges, ParcelFileDescriptor dest,
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        cancelled.set(true);
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }
}
