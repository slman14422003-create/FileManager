package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.print.PdfPrinter;
import android.print.PrintAttributes;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // ------------------------------------------------------------------ from a file (file manager)

    static void file(final Activity a, final File src, final String password) {
        final String ext = Cats.extOf(src.getName());
        final AlertDialog wait = waitDialog(a);
        IO.execute(() -> {
            final OfficeLoad.Result r = OfficeLoad.load(a, src, ext, password);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    dismiss(wait);
                    return;
                }
                if (r.lock != 0) {
                    dismiss(wait);
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
                    dismiss(wait);
                    Toast.makeText(a, r.err != 0 ? r.err : R.string.pdf_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                export(a, r.html, src, wait);
            });
        });
    }

    // ------------------------------------------------------------------ from a page the viewer already built

    static void html(Activity a, String html, File src) {
        export(a, html, src, waitDialog(a));
    }

    private static AlertDialog waitDialog(Activity a) {
        AlertDialog d = new Dlg(a).setTitle(R.string.pdf_converting).setMessage(R.string.pdf_wait).create();
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

    // ------------------------------------------------------------------ rendering

    private static void export(final Activity a, String html, final File src, final AlertDialog wait) {
        final String ext = Cats.extOf(src.getName());
        final boolean wide = WIDE.contains(ext);
        final File target = target(a, src);
        String page = html;
        int head = page.indexOf("</head>");
        page = head >= 0 ? page.substring(0, head) + PRINT_CSS + page.substring(head) : PRINT_CSS + page;
        final WebView web = new WebView(a);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        web.setWebViewClient(new WebViewClient() {
            private boolean started;

            @Override
            public void onPageFinished(final WebView v, String url) {
                if (started) return;
                started = true;
                v.postDelayed(() -> render(a, v, target, src, wide, wait), 400);
            }
        });
        web.loadDataWithBaseURL(BASE, page, "text/html", "utf-8", null);
    }

    private static void render(final Activity a, final WebView web, final File target, final File src, boolean wide,
                               final AlertDialog wait) {
        PrintAttributes.MediaSize size = PrintAttributes.MediaSize.ISO_A4;
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(wide ? size.asLandscape() : size.asPortrait())
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(new PrintAttributes.Margins(400, 400, 400, 400))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        final PrintAttributes pa = attrs;
        final PdfPrinter.Done done = ok -> a.runOnUiThread(() -> {
            dismiss(wait);
            if (a.isFinishing() || a.isDestroyed()) {
                web.destroy();
                return;
            }
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                // direct writing is not available on this device: hand the page to the system print service instead
                try {
                    android.print.PrintManager pm = (android.print.PrintManager) a.getSystemService(Context.PRINT_SERVICE);
                    pm.print(src.getName(), web.createPrintDocumentAdapter(src.getName()), pa);
                } catch (Throwable t) {
                    web.destroy();
                    Toast.makeText(a, R.string.pdf_failed, Toast.LENGTH_LONG).show();
                }
                return;
            }
            web.destroy();
            finished(a, target);
        });
        try {
            PdfPrinter.print(web.createPrintDocumentAdapter(src.getName()), attrs, target, done);
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
