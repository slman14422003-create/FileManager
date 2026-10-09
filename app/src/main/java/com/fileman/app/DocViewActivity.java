package com.fileman.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only viewer for Word (docx), Excel (xlsx), PowerPoint (pptx), OpenDocument, CSV / TSV, RTF,
 * HTML and SVG files. The document is converted to HTML ({@link DocHtml}) and shown in a locked-down
 * WebView: no JavaScript, no file or content access, no navigation to other pages.
 */
public class DocViewActivity extends BaseActivity {
    private static final String BASE = "https://doc.local/view";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File file;
    private View loading;
    private WebView web;
    private TextView subtitle;
    private ImageButton findBtn;
    private boolean finding = false;
    private boolean night = false;
    private int zoom = 100;
    private String baseHtml;
    private int restoreY = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DocHtml.canvas = String.format("#%06X", 0xFFFFFF & Ui.color(this, R.color.bg));
        DocHtml.ring = String.format("#%06X", 0xFFFFFF & Ui.color(this, R.color.stroke));
        
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final String ext = Cats.extOf(file.getName());
        ((TextView) findViewById(R.id.title)).setText(file.getName());
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(ext.toUpperCase(java.util.Locale.ROOT) + " · " + Fmt.size(file.length()));
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ViewerBar.headerIcon(this, file);
        ImageButton more = findViewById(R.id.btnA1);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        night = Store.intPref(this, "doc_night", 0) == 1;
        zoom = Store.intPref(this, "doc_zoom", 100);
        more.setOnClickListener(v -> {
            final boolean editable = EDITABLE.contains(ext);
            String[] names = new String[editable ? 5 : 4];
            Runnable[] acts = new Runnable[names.length];
            names[0] = getString(R.string.rd_night) + (night ? "  ✓" : "");
            acts[0] = this::toggleNight;
            names[1] = getString(R.string.rd_text_size) + " +";
            acts[1] = () -> changeZoom(20);
            names[2] = getString(R.string.rd_text_size) + " −";
            acts[2] = () -> changeZoom(-20);
            names[3] = getString(R.string.v_print);
            acts[3] = this::printPage;
            if (editable) {
                names[4] = getString(R.string.v_edit_text);
                acts[4] = () -> startActivity(new android.content.Intent(this, FileEditActivity.class)
                        .putExtra("path", file.getAbsolutePath()));
            }
            Opener.moreMenu(this, file, names, acts);
        });
        // find / text size / night mode live in a floating bar over the page (set up after the WebView exists)
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (finding) {
                    stopFind();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        web = new WebView(this);
        web.setFindListener((active, count, done) -> {
            if (!done) return;
            if (count == 0) {
                Toast.makeText(this, R.string.v_find_none, Toast.LENGTH_SHORT).show();
                stopFind();
            } else {
                subtitle.setText(getString(R.string.v_find_count, active + 1, count));
            }
        });
        web.setBackgroundColor(Ui.color(this, R.color.bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        s.setBlockNetworkLoads(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(zoom);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // only in-page anchors (sheet tabs); every other link is ignored
                String u = r.getUrl().toString();
                return !(u.startsWith(BASE) && u.contains("#"));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                loading.setVisibility(View.INVISIBLE);
                if (restoreY > 0) {
                    final int y = restoreY;
                    restoreY = 0;
                    v.postDelayed(() -> v.scrollTo(0, y), 120);
                }
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                loading.setVisibility(View.VISIBLE);
            }
        });
        FrameLayout holder = findViewById(R.id.holder);
        holder.addView(web,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout bar = ViewerBar.attach(this, holder, false,
                new int[]{R.drawable.ic_search, R.drawable.ic_zoom, R.drawable.ic_night},
                new int[]{R.string.v_find, R.string.rd_text_size, R.string.rd_night},
                new View.OnClickListener[]{
                        v -> {
                            if (finding) web.findNext(true);
                            else askFind();
                        },
                        v -> cycleZoom(),
                        v -> toggleNight()});
        findBtn = (ImageButton) bar.getChildAt(0);
        findBtn.setOnLongClickListener(v -> {
            askFind();
            return true;
        });

        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            String html = null;
            int err = 0;
            try {
                html = DocHtml.convert(file, ext);
            } catch (OutOfMemoryError e) {
                err = R.string.v_too_large;
            } catch (Throwable e) {
                err = R.string.v_doc_failed;
            }
            final String h = html;
            final int er = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (h == null) {
                    loading.setVisibility(View.INVISIBLE);
                    Toast.makeText(this, er != 0 ? er : R.string.v_doc_failed, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                baseHtml = h;
                restoreY = (int) Store.resume(this, file.getAbsolutePath());
                render();
            });
        });
    }

    /** Room at the end of the page so the floating bar never hides the last lines. */
    private static final String EXTRA_CSS = "<style>body{padding-bottom:96px !important}</style>";

    private static final String NIGHT_CSS = "<style>html{filter:invert(1) hue-rotate(180deg);background:#fff}"
            + "img,svg{filter:invert(1) hue-rotate(180deg)}</style>";

    /** Loads the converted document, with the night-mode stylesheet in front when it is on. */
    private void render() {
        if (baseHtml == null) return;
        String h = EXTRA_CSS + (night ? NIGHT_CSS : "") + baseHtml;
        web.setBackgroundColor(night ? 0xFF121212 : Ui.color(this, R.color.bg));
        web.loadDataWithBaseURL(BASE, h, "text/html", "utf-8", null);
    }

    /** Plain-text based formats that can also be opened in the text editor from the viewer. */
    private static final java.util.Set<String> EDITABLE = new java.util.HashSet<>(java.util.Arrays.asList(
            "csv", "tsv", "html", "htm", "xhtml", "svg", "md", "markdown"));

    /** Prints the document or saves it as PDF through the system print service. */
    private void printPage() {
        try {
            android.print.PrintManager pm = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
            String name = file.getName();
            pm.print(name, web.createPrintDocumentAdapter(name), new android.print.PrintAttributes.Builder().build());
        } catch (Exception e) {
            Toast.makeText(this, R.string.v_doc_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleNight() {
        night = !night;
        Store.setIntPref(this, "doc_night", night ? 1 : 0);
        restoreY = web.getScrollY();
        render();
    }

    private void cycleZoom() {
        changeZoom((zoom >= 200 ? 80 : zoom + 20) - zoom);
    }

    private void changeZoom(int delta) {
        zoom = Math.max(60, Math.min(260, zoom + delta));
        Store.setIntPref(this, "doc_zoom", zoom);
        web.getSettings().setTextZoom(zoom);
        Toast.makeText(this, zoom + "%", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null && file != null) Store.setResume(this, file.getAbsolutePath(), web.getScrollY());
    }

    private void askFind() {
        final android.widget.EditText q = Ui.edit(this, getString(R.string.v_find), "");
        new Dlg(this).setTitle(R.string.v_find).setView(q)
                .setPositiveButton(R.string.v_find_go, (d, w) -> {
                    String t = q.getText().toString().trim();
                    if (t.isEmpty()) return;
                    finding = true;
                    findBtn.setImageResource(R.drawable.ic_arrow_down);
                    web.findAllAsync(t);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void stopFind() {
        finding = false;
        web.clearMatches();
        findBtn.setImageResource(R.drawable.ic_search);
        subtitle.setText(Cats.extOf(file.getName()).toUpperCase(java.util.Locale.ROOT) + " · " + Fmt.size(file.length()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
    }
}
