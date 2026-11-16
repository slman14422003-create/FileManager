package com.fileman.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fileman.app.docedit.DocSpeaker;
import com.fileman.app.docedit.DocxHtmlRenderer;
import com.fileman.app.docedit.DocxWriter;
import com.fileman.app.docedit.XlsxModel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Word (.docx) and Excel (.xlsx) editor: an Office-style toolbar inside a WebView (assets/editor/word.html and
 * excel.html), saving back to the file or to a copy, and a read-aloud player. The screen frame, colours and
 * dialogs are the file manager's own; the page colours are taken from the current app theme.
 */
public class DocEditActivity extends BaseActivity {
    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private File file;
    private boolean excel;
    private boolean dirty;
    private boolean overwriteConfirmed;
    private String initialContent = "";
    private Runnable afterSave;

    private WebView web;
    private View loading;
    private TextView subtitle;
    private LinearLayout ttsBar;
    private TextView ttsStatus;
    private TextView ttsSpeed;
    private ImageButton ttsPlay;
    private DocSpeaker speaker;
    private int speedIdx = 1;
    private boolean speechPrepared;

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImageChosen);

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

    public static void open(Context c, File f) {
        c.startActivity(new Intent(c, DocEditActivity.class).putExtra("path", f.getAbsolutePath()));
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
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
        String ext = Cats.extOf(file.getName());
        excel = ext.equals("xlsx");
        ((TextView) findViewById(R.id.title)).setText(file.getName());
        subtitle = findViewById(R.id.subtitle);
        updateSubtitle();
        loading = findViewById(R.id.loading);
        if (loading instanceof android.widget.ProgressBar) Ui.tint(this, (android.widget.ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ViewerBar.headerIcon(this, file);

        ImageButton read = findViewById(R.id.btnA1);
        read.setImageResource(R.drawable.ic_volume);
        read.setContentDescription(getString(R.string.de_read_aloud));
        read.setVisibility(View.VISIBLE);
        read.setOnClickListener(v -> toggleSpeech());
        ImageButton more = findViewById(R.id.btnA2);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> showMore());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!dirty) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                new Dlg(DocEditActivity.this).setTitle(R.string.de_unsaved_title)
                        .setMessage(R.string.de_unsaved_msg)
                        .setPositiveButton(R.string.save, (d, w) -> requestSave("save", () -> {
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        }))
                        .setNegativeButton(R.string.discard, (d, w) -> {
                            dirty = false;
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                        })
                        .setNeutralButton(R.string.cancel, null)
                        .show();
            }
        });

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(Ui.color(this, R.color.bg));
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                applyAppTheme();
            }
        });
        FrameLayout holder = findViewById(R.id.holder);
        holder.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        buildTtsBar(holder);

        loading.setVisibility(View.VISIBLE);
        loadContent();
    }

    private void updateSubtitle() {
        String kind = getString(excel ? R.string.de_kind_excel : R.string.de_kind_word);
        String t = kind + " · " + Fmt.size(file.length());
        subtitle.setText(dirty ? t + " · ● " + getString(R.string.de_unsaved_short) : t);
    }

    // ---------------------------------------------------------------- page

    private void loadContent() {
        io.execute(() -> {
            try {
                String content = excel ? XlsxModel.read(file) : DocxHtmlRenderer.render(file);
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    initialContent = content;
                    web.loadUrl("file:///android_asset/editor/" + (excel ? "excel.html" : "word.html"));
                    loading.setVisibility(View.INVISIBLE);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.v_doc_failed, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        return 0xFF808080;
    }

    private static String css(int c) {
        int a = (c >>> 24) & 0xFF;
        if (a == 0xFF) return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, a / 255f);
    }

    /** Re-colours the editor page with the file manager's current theme (day / night and the chosen accent). */
    private void applyAppTheme() {
        String bg = css(themeColor(R.attr.cBg)), surf = css(themeColor(R.attr.cSurface));
        String hi = css(themeColor(R.attr.cSurfaceHigh)), stroke = css(themeColor(R.attr.cStroke));
        String soft = css(themeColor(R.attr.cStrokeSoft)), ink = css(themeColor(R.attr.cTextPrimary));
        String mut = css(themeColor(R.attr.cTextSecondary)), acc = css(themeColor(R.attr.cAccent));
        String accSoft = css(themeColor(R.attr.cAccentSoft)), neutral = css(themeColor(R.attr.cNeutralSoft));
        String vars = "--surf:" + surf + ";--bgx:" + bg + ";--bg:" + bg + ";--card:" + surf + ";--line:" + soft
                + ";--line2:" + stroke + ";--chip:" + neutral + ";--chip2:" + hi + ";--ink:" + ink + ";--mut:" + mut
                + ";--acc:" + acc + ";--accsoft:" + accSoft + ";--act:" + accSoft + ";--rib:" + surf
                + ";--ribline:" + soft + ";--btn:" + neutral + ";--btnh:" + hi + ";--sheet:" + surf + ";";
        String sel = "html,body,body.night,body.xl,body.xl.night";
        String style = sel + "{" + vars + "}"
                + "html,body{background:" + bg + ";color:" + ink + "}"
                + "#tabs{background:" + surf + "}#tabs button{color:" + mut + "}"
                + "#tabs button.on{background:" + accSoft + ";color:" + acc + "}"
                + "body.night #tabs button{color:" + mut + "}body.night #tabs button.on{color:" + acc + "}";
        String js = "(function(){var s=document.getElementById('fm-theme');if(!s){s=document.createElement('style');"
                + "s.id='fm-theme';document.head.appendChild(s);}s.textContent=" + JSONObject.quote(style) + ";})()";
        web.evaluateJavascript(js, null);
    }

    private final class Bridge {
        @JavascriptInterface
        public String getInitialContent() {
            return initialContent;
        }

        @JavascriptInterface
        public boolean isNight() {
            return Appearance.isNight(DocEditActivity.this);
        }

        @JavascriptInterface
        public void onDirty(boolean d) {
            ui.post(() -> {
                dirty = d;
                updateSubtitle();
            });
        }

        @JavascriptInterface
        public void onExported(String mode, String json) {
            handleExported(mode, json);
        }

        @JavascriptInterface
        public void copyText(String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", text));
            });
        }

        @JavascriptInterface
        public String pasteText() {
            final String[] out = {""};
            final Object lock = new Object();
            final boolean[] done = {false};
            ui.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(DocEditActivity.this);
                        if (t != null) out[0] = t.toString();
                    }
                } catch (Throwable ignored) {
                }
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            });
            synchronized (lock) {
                long end = System.currentTimeMillis() + 1500;
                while (!done[0] && System.currentTimeMillis() < end) {
                    try {
                        lock.wait(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            return out[0];
        }

        @JavascriptInterface
        public void toast(String s) {
            ui.post(() -> Toast.makeText(DocEditActivity.this, s, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void pickImage() {
            ui.post(() -> pickImage.launch("image/*"));
        }

        @JavascriptInterface
        public void toggleSpeech() {
            ui.post(DocEditActivity.this::toggleSpeech);
        }
    }

    private void js(String script) {
        web.evaluateJavascript(script, null);
    }

    // ---------------------------------------------------------------- images

    private void onImageChosen(Uri img) {
        if (img == null) return;
        io.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(img)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (o.outWidth / sample > 2000 || o.outHeight / sample > 2000) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(img)) {
                    bmp = BitmapFactory.decodeStream(in, null, o2);
                }
                if (bmp == null) throw new IllegalStateException("decode");
                boolean png = bmp.hasAlpha();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 88, bos);
                String data = "data:" + (png ? "image/png" : "image/jpeg") + ";base64,"
                        + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                ui.post(() -> js("window.onImagePicked && window.onImagePicked('" + data + "')"));
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(this, R.string.v_doc_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ---------------------------------------------------------------- saving

    private String ext() {
        return excel ? ".xlsx" : ".docx";
    }

    private void showMore() {
        final String[] items = {getString(R.string.save), getString(R.string.rd_save_copy), getString(R.string.share)};
        new Dlg(this).setTitle(file.getName()).setItems(items, new int[]{R.drawable.ic_check, R.drawable.ic_copy,
                R.drawable.ic_share}, (d, which) -> requestSave(which == 0 ? "save" : which == 1 ? "saveas" : "share", null))
                .show();
    }

    private void requestSave(String mode, Runnable then) {
        afterSave = then;
        js("window.exportAndSave && window.exportAndSave('" + mode + "')");
    }

    private void markClean() {
        dirty = false;
        updateSubtitle();
        js("window.markSaved && window.markSaved()");
    }

    /** Called from the page once it has turned the document into JSON. */
    private void handleExported(String mode, String json) {
        final Runnable then = afterSave;
        io.execute(() -> {
            File out = new File(getCacheDir(), "edit_out_" + System.nanoTime() + ext());
            try (OutputStream os = new FileOutputStream(out)) {
                if (excel) XlsxModel.write(json, os);
                else DocxWriter.write(json, os);
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                ui.post(() -> Toast.makeText(this, R.string.de_save_failed, Toast.LENGTH_SHORT).show());
                return;
            }
            ui.post(() -> dispatchSave(mode, out, then));
        });
    }

    private void dispatchSave(String mode, File out, Runnable then) {
        if ("share".equals(mode)) {
            File named = new File(getCacheDir(), file.getName());
            //noinspection ResultOfMethodCallIgnored
            named.delete();
            Opener.share(this, out.renameTo(named) ? named : out);
            return;
        }
        if ("saveas".equals(mode)) {
            saveCopy(out, then);
            return;
        }
        if (!overwriteConfirmed) {
            new Dlg(this).setTitle(R.string.de_overwrite_title).setMessage(R.string.de_overwrite_msg)
                    .setPositiveButton(R.string.de_overwrite, (d, w) -> {
                        overwriteConfirmed = true;
                        overwriteOriginal(out, then);
                    })
                    .setNegativeButton(R.string.de_save_as_copy, (d, w) -> saveCopy(out, then))
                    .setNeutralButton(R.string.cancel, null)
                    .show();
        } else {
            overwriteOriginal(out, then);
        }
    }

    private void overwriteOriginal(File out, Runnable then) {
        io.execute(() -> {
            try {
                copy(out, file);
                ui.post(() -> {
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                    markClean();
                    updateSubtitle();
                    if (then != null) then.run();
                });
            } catch (Throwable t) {
                ui.post(() -> saveCopy(out, then));
            }
        });
    }

    /** Writes "name (edited).ext" next to the original (or in Downloads when that folder is not writable). */
    private void saveCopy(File out, Runnable then) {
        io.execute(() -> {
            try {
                String base = file.getName().replaceAll("\\.[^.]*$", "");
                File dir = file.getParentFile();
                if (dir == null || !dir.canWrite()) {
                    dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                }
                File target = new File(dir, base + " (edited)" + ext());
                for (int i = 2; target.exists(); i++) target = new File(dir, base + " (edited " + i + ")" + ext());
                copy(out, target);
                final File saved = target;
                ui.post(() -> {
                    Toast.makeText(this, getString(R.string.de_saved_as, saved.getName()), Toast.LENGTH_LONG).show();
                    markClean();
                    if (then != null) then.run();
                });
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(this, R.string.de_save_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        }
    }

    // ---------------------------------------------------------------- read aloud

    private void buildTtsBar(FrameLayout holder) {
        ttsBar = new LinearLayout(this);
        ttsBar.setOrientation(LinearLayout.VERTICAL);
        ttsBar.setBackgroundResource(R.drawable.bg_tabbar_float);
        ttsBar.setElevation(Ui.dp(this, 10));
        ttsBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 8));
        ttsBar.setVisibility(View.GONE);
        ttsBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        ttsStatus = new TextView(this);
        ttsStatus.setTextColor(Ui.color(this, R.color.text_secondary));
        ttsStatus.setTextSize(12);
        ttsStatus.setGravity(Gravity.CENTER);
        ttsBar.addView(ttsStatus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setOrientation(LinearLayout.HORIZONTAL);
        ImageButton prev = barButton(R.drawable.ic_skip_prev, R.string.de_prev);
        ttsPlay = barButton(R.drawable.ic_pause, R.string.de_read_aloud);
        ImageButton next = barButton(R.drawable.ic_skip_next, R.string.de_next);
        ttsSpeed = new TextView(this);
        ttsSpeed.setTextColor(Ui.color(this, R.color.accent));
        ttsSpeed.setTextSize(14);
        ttsSpeed.setTypeface(null, android.graphics.Typeface.BOLD);
        ttsSpeed.setGravity(Gravity.CENTER);
        ImageButton close = barButton(R.drawable.ic_close, R.string.de_close);
        row.addView(prev);
        row.addView(ttsPlay);
        row.addView(next);
        row.addView(ttsSpeed, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 46)));
        row.addView(close);
        ttsBar.addView(row);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(this, 16);
        holder.addView(ttsBar, lp);

        speaker = new DocSpeaker(this, new DocSpeaker.Listener() {
            @Override
            public void onItem(int index, int total) {
                ttsStatus.setText(getString(excel ? R.string.de_row_of : R.string.de_para_of, index + 1, total));
                js("window.highlightItem && window.highlightItem(" + index + ")");
            }

            @Override
            public void onPlaying(boolean playing) {
                ttsPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            }

            @Override
            public void onFinished() {
                hideTts();
            }

            @Override
            public void onStatus(String message) {
                Toast.makeText(DocEditActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
        float r = speaker.getRate();
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - r) < 0.01f) speedIdx = i;
        ttsSpeed.setText(SPEEDS[speedIdx] + "x");
        ttsPlay.setOnClickListener(v -> speaker.toggle());
        next.setOnClickListener(v -> speaker.next());
        prev.setOnClickListener(v -> speaker.previous());
        close.setOnClickListener(v -> hideTts());
        ttsSpeed.setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % SPEEDS.length;
            speaker.setRate(SPEEDS[speedIdx]);
            ttsSpeed.setText(SPEEDS[speedIdx] + "x");
        });
    }

    private ImageButton barButton(int icon, int desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.bg_tile_round);
        b.setContentDescription(getString(desc));
        Ui.press(this, b);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 46)));
        return b;
    }

    private void toggleSpeech() {
        if (speaker == null) return;
        if (ttsBar.getVisibility() == View.VISIBLE && speechPrepared) {
            speaker.toggle();
            return;
        }
        web.evaluateJavascript("(function(){try{return JSON.stringify({items:window.collectSpeechItems(),start:"
                + "(window.getSpeechStart?window.getSpeechStart():0)});}catch(e){return '';}})()", value -> {
            try {
                if (value == null || value.equals("null") || value.equals("\"\"")) {
                    Toast.makeText(this, R.string.de_nothing_to_read, Toast.LENGTH_SHORT).show();
                    return;
                }
                String inner = (String) new JSONTokener(value).nextValue();
                JSONObject o = new JSONObject(inner);
                JSONArray arr = new JSONArray(o.getString("items"));
                List<String> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) items.add(arr.getString(i));
                if (items.isEmpty()) {
                    Toast.makeText(this, R.string.de_nothing_to_read, Toast.LENGTH_SHORT).show();
                    return;
                }
                speaker.setItems(items);
                speaker.setRate(SPEEDS[speedIdx]);
                ttsBar.setVisibility(View.VISIBLE);
                speechPrepared = true;
                speaker.playFrom(o.optInt("start", 0));
            } catch (Throwable t) {
                Toast.makeText(this, R.string.de_nothing_to_read, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void hideTts() {
        if (speaker != null) speaker.stop();
        ttsBar.setVisibility(View.GONE);
        speechPrepared = false;
        js("window.clearHighlight && window.clearHighlight()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speaker != null && speaker.isPlaying()) speaker.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (speaker != null) speaker.release();
        io.shutdown();
        if (web != null) {
            web.removeJavascriptInterface("Android");
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
    }
}
