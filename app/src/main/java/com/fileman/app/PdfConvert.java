package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PdfPrinter;
import android.print.PrintAttributes;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Word, Excel, PowerPoint and OpenDocument files to PDF, written next to the original. */
final class PdfConvert {
    private PdfConvert() {
    }

    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            "doc", "dot", "docx", "docm", "dotx", "dotm",
            "xls", "xlt", "xlsx", "xlsm", "xltx", "xltm", "csv", "tsv",
            "ppt", "pps", "pot", "pptx", "pptm", "ppsx", "potx",
            "odt", "ods", "odp", "rtf"));

    private static final Set<String> WIDE = new HashSet<>(Arrays.asList(
            "xls", "xlt", "xlsx", "xlsm", "xltx", "xltm", "csv", "tsv", "ods",
            "ppt", "pps", "pot", "pptx", "pptm", "ppsx", "potx", "odp"));

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final String BASE = "https://doc.local/pdf";

    private static final String PRINT_CSS = "<style>"
            + "html,body{background:#fff !important;margin:0 !important;padding:0 !important}"
            + ".paper{box-shadow:none !important;border-radius:0 !important;max-width:none !important;"
            + "padding:0 !important;overflow:visible !important}"
            + ".sheet{overflow:visible !important;max-height:none !important;border:0 !important;border-radius:0 !important}"
            + ".sheet table{white-space:normal !important;font-size:10px !important}"
            + ".sheet td{max-width:none !important;overflow:visible !important;text-overflow:clip !important}"
            + ".nav{display:none !important}"
            + ".slide{break-inside:avoid;page-break-inside:avoid}"
            + "h1,h2,h3,h4,h5,h6{break-after:avoid;page-break-after:avoid}"
            + "tr,img{break-inside:avoid;page-break-inside:avoid}img{max-width:100% !important}"
            + "*{-webkit-print-color-adjust:exact;print-color-adjust:exact}"
            + "</style>";

    static boolean canConvert(String ext) {
        return ext != null && SUPPORTED.contains(ext.toLowerCase(Locale.ROOT));
    }

    private static final long LOAD_TIMEOUT_MS = 20000;
    private static final long WRITE_TIMEOUT_MS = 90000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** One conversion: owns the wait dialog, the hidden WebView and the watchdogs, and finishes exactly once. */
    private static final class Job {
        final Activity a;
        final File src;
        final AtomicBoolean over = new AtomicBoolean();
        AlertDialog wait;
        WebView web;
        File target;
        PrintAttributes attrs;
        final Runnable loadDog = () -> render(this);
        final Runnable writeDog = () -> end(this, false, false);

        Job(Activity a, File src) {
            this.a = a;
            this.src = src;
        }
    }

    // ------------------------------------------------------------------ from a file (file manager)

    static void file(final Activity a, final File src, final String password) {
        final String ext = Cats.extOf(src.getName());
        final Job job = new Job(a, src);
        job.wait = waitDialog(job);
        IO.execute(() -> {
            final OfficeLoad.Result r = OfficeLoad.load(a, src, ext, password);
            a.runOnUiThread(() -> {
                if (job.over.get()) return;
                if (a.isFinishing() || a.isDestroyed()) {
                    end(job, false, true);
                    return;
                }
                if (r.lock != 0) {
                    end(job, false, true);
                    LockPrompt.ask(a, R.string.lock_title_doc, r.lock == 2, new LockPrompt.Result() {
                        @Override
                        public void password(String p) {
                            file(a, src, p);
                        }

                        @Override
                        public void cancelled() {
                        }
                    });
                    return;
                }
                if (r.html == null) {
                    end(job, false, true);
                    Toast.makeText(a, r.err != 0 ? r.err : R.string.pdf_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                export(job, r.html);
            });
        });
    }

    // ------------------------------------------------------------------ from a page the viewer already built

    static void html(Activity a, String html, File src) {
        Job job = new Job(a, src);
        job.wait = waitDialog(job);
        export(job, html);
    }

    private static AlertDialog waitDialog(final Job job) {
        AlertDialog d = new Dlg(job.a).setTitle(R.string.pdf_converting).setMessage(R.string.pdf_wait)
                .setNegativeButton(R.string.cancel, (x, w) -> end(job, false, true)).create();
        d.setCancelable(false);
        d.show();
        return d;
    }

    private static void dismiss(AlertDialog d) {
        try {
            if (d != null && d.isShowing()) d.dismiss();
        } catch (Exception ignored) {
        }
    }

    /** Stops the watchdogs, removes the hidden WebView and closes the dialog; shows the failure when asked. */
    private static void end(Job job, boolean ok, boolean silent) {
        if (!job.over.compareAndSet(false, true)) return;
        MAIN.removeCallbacks(job.loadDog);
        MAIN.removeCallbacks(job.writeDog);
        dismiss(job.wait);
        WebView w = job.web;
        job.web = null;
        if (w != null) {
            try {
                w.stopLoading();
                if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
                w.destroy();
            } catch (Throwable ignored) {
            }
        }
        if (!ok && job.target != null) //noinspection ResultOfMethodCallIgnored
            job.target.delete();
        if (!ok && !silent && !job.a.isFinishing() && !job.a.isDestroyed())
            Toast.makeText(job.a, R.string.pdf_failed, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ rendering

    private static void export(final Job job, String html) {
        final Activity a = job.a;
        final String ext = Cats.extOf(job.src.getName());
        final boolean wide = WIDE.contains(ext);
        job.target = target(a, job.src);
        PrintAttributes.MediaSize size = PrintAttributes.MediaSize.ISO_A4;
        job.attrs = new PrintAttributes.Builder()
                .setMediaSize(wide ? size.asLandscape() : size.asPortrait())
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(new PrintAttributes.Margins(400, 400, 400, 400))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        String page = html;
        int head = page.indexOf("</head>");
        page = head >= 0 ? page.substring(0, head) + PRINT_CSS + page.substring(head) : PRINT_CSS + page;
        try {
            final WebView web = new WebView(a);
            job.web = web;
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setBlockNetworkLoads(true);
            // a WebView that never joined a window can not lay out for printing on recent WebView builds:
            // keep it in the window at page size, practically invisible and out of reach of touches
            final int w = wide ? 1123 : 794;
            final int h = wide ? 794 : 1123;
            web.setAlpha(0.01f);
            web.setClickable(false);
            web.setFocusable(false);
            web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ((ViewGroup) a.getWindow().getDecorView()).addView(web, 0, new FrameLayout.LayoutParams(w, h));
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    v.postDelayed(() -> render(job), 400);
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView v, int progress) {
                    if (progress >= 100) v.postDelayed(() -> render(job), 600);
                }
            });
            MAIN.postDelayed(job.loadDog, LOAD_TIMEOUT_MS);
            web.loadDataWithBaseURL(BASE, page, "text/html", "utf-8", null);
        } catch (Throwable t) {
            end(job, false, false);
        }
    }

    private static void render(final Job job) {
        final WebView web = job.web;
        if (job.over.get() || web == null || job.target == null) return;
        if (job.attrs == null) return;
        MAIN.removeCallbacks(job.loadDog);
        // started once: later triggers (page finished and progress both fire) are ignored
        if (web.getTag() != null) return;
        web.setTag(Boolean.TRUE);
        MAIN.postDelayed(job.writeDog, WRITE_TIMEOUT_MS);
        final Activity a = job.a;
        final PdfPrinter.Done done = ok -> a.runOnUiThread(() -> {
            if (job.over.get()) return;
            if (a.isFinishing() || a.isDestroyed()) {
                end(job, false, true);
                return;
            }
            if (!ok) {
                // direct writing is not available on this device: hand the page to the system print service instead
                final PrintAttributes pa = job.attrs;
                final String name = job.src.getName();
                try {
                    android.print.PrintManager pm = (android.print.PrintManager) a.getSystemService(Context.PRINT_SERVICE);
                    pm.print(name, web.createPrintDocumentAdapter(name), pa);
                    // the print job keeps the adapter alive: leave the WebView in place until the activity closes
                    MAIN.removeCallbacks(job.writeDog);
                    job.over.set(true);
                    dismiss(job.wait);
                    if (job.target != null) //noinspection ResultOfMethodCallIgnored
                        job.target.delete();
                } catch (Throwable t) {
                    end(job, false, false);
                }
                return;
            }
            File pdf = job.target;
            end(job, true, true);
            finished(a, pdf);
        });
        try {
            PdfPrinter.print(web.createPrintDocumentAdapter(job.src.getName()), job.attrs, job.target, done);
        } catch (Throwable t) {
            done.finished(false);
        }
    }

    private static void finished(final Activity a, final File pdf) {
        new Dlg(a).setTitle(R.string.pdf_done)
                .setMessage(a.getString(R.string.pdf_done_msg, pdf.getName()))
                .setPositiveButton(R.string.pdf_open, (d, w) -> Opener.open(a, pdf))
                .setNeutralButton(R.string.share, (d, w) -> Opener.share(a, pdf))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** name.pdf next to the document (name (1).pdf when taken); Downloads or the cache when that folder is read-only. */
    private static File target(Activity a, File src) {
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File[] dirs = {src.getParentFile(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), a.getCacheDir()};
        for (File dir : dirs) {
            if (dir == null) continue;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            if (!dir.isDirectory() || !dir.canWrite()) continue;
            File f = new File(dir, base + ".pdf");
            for (int i = 1; f.exists() && i < 1000; i++) f = new File(dir, base + " (" + i + ").pdf");
            return f;
        }
        return new File(a.getCacheDir(), base + ".pdf");
    }
}
