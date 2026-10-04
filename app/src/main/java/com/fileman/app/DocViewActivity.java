package com.fileman.app;

import android.annotation.SuppressLint;
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
public class DocViewActivity extends AppCompatActivity {
    private static final String BASE = "https://doc.local/view";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File file;
    private View loading;
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
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
        final String ext = Cats.extOf(file.getName());
        ((TextView) findViewById(R.id.title)).setText(file.getName());
        ((TextView) findViewById(R.id.subtitle)).setText(Fmt.size(file.length()));
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ImageButton more = findViewById(R.id.btnA1);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> Opener.moreMenu(this, file));

        web = new WebView(this);
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
        s.setTextZoom(100);
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
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                loading.setVisibility(View.VISIBLE);
            }
        });
        ((FrameLayout) findViewById(R.id.holder)).addView(web,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
                web.loadDataWithBaseURL(BASE, h, "text/html", "utf-8", null);
            });
        });
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
