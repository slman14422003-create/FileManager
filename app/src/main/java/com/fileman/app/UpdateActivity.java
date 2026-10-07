package com.fileman.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks the app's GitHub Releases for a newer APK, downloads it and starts the installer. */
public class UpdateActivity extends BaseActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private View loading;
    private Updater.Info info;
    private String errorText;
    private boolean checked = false;
    private boolean autostart = false;
    private boolean busy = false;

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private final boolean[] cancel = {false};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        ((TextView) findViewById(R.id.title)).setText(R.string.upd_title);
        updateSubtitle();
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> check());
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        content = findViewById(R.id.content);
        autostart = getIntent().getBooleanExtra("autostart", false);
        render();
        check();
    }

    private void updateSubtitle() {
        String repo = Store.updateRepo(this);
        ((TextView) findViewById(R.id.subtitle)).setText(repo.isEmpty() ? getString(R.string.upd_no_source_short) : repo);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel[0] = true;
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        hideProgress();
    }

    private void post(Runnable r) {
        ui.post(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }

    private void check() {
        final String[] src = Updater.split(Store.updateRepo(this));
        if (src == null) {
            loading.setVisibility(View.INVISIBLE);
            checked = true;
            info = null;
            errorText = getString(R.string.upd_no_source);
            render();
            return;
        }
        loading.setVisibility(View.VISIBLE);
        errorText = null;
        final boolean pre = Store.updatePre(this);
        io.execute(() -> {
            try {
                final Updater.Info in = Updater.check(this, src[0], src[1], pre);
                Store.setLastUpdateCheck(this, System.currentTimeMillis());
                post(() -> {
                    loading.setVisibility(View.INVISIBLE);
                    info = in;
                    checked = true;
                    render();
                    if (autostart && in != null && in.newer) {
                        autostart = false;
                        download();
                    }
                });
            } catch (Exception e) {
                final boolean notFound = e instanceof Updater.ApiException && ((Updater.ApiException) e).code == 404;
                final String raw = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> {
                    loading.setVisibility(View.INVISIBLE);
                    checked = true;
                    info = null;
                    errorText = notFound ? getString(R.string.upd_repo_404, src[0] + "/" + src[1])
                            : getString(R.string.upd_error, raw);
                    render();
                });
            }
        });
    }

    private void render() {
        content.removeAllViews();
        content.addView(Ui.sectionTitle(this, getString(R.string.upd_version_section)));
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(Ui.rowView(this, content, new Row(R.drawable.ic_package, true, getString(R.string.upd_current),
                Updater.installedName(this) + " (" + Updater.installedCode(this) + ")", false, false), null));

        if (!checked) {
            group.addView(Ui.rowView(this, content, new Row(R.drawable.ic_refresh, true, getString(R.string.upd_latest),
                    getString(R.string.upd_checking), false, false), null));
        } else if (info != null) {
            String title = info.name.isEmpty() ? info.tag : info.name;
            if (!info.name.isEmpty() && !info.name.equals(info.tag)) title = title + "  (" + info.tag + ")";
            StringBuilder sub = new StringBuilder(Fmt.size(info.assetSize));
            String ago = Fmt.ago(info.publishedAt);
            if (!ago.isEmpty()) sub.append(" · ").append(getString(R.string.upd_published, ago));
            if (info.assetName.toLowerCase(Locale.US).contains("debug")) {
                sub.append(" · ").append(getString(R.string.upd_debug_note));
            }
            Row latest = new Row(R.drawable.ic_download, true, title, sub.toString(), false, false);
            if (info.newer) latest.badge(getString(R.string.upd_badge_new), Ui.color(this, R.color.accent_text));
            else latest.badge(getString(R.string.upd_badge_current), Ui.color(this, R.color.ok));
            group.addView(Ui.rowView(this, content, latest, null));
        }
        Ui.group(this, group);
        content.addView(group);

        if (checked && errorText != null) {
            content.addView(Ui.noteCard(this, errorText, R.color.bad));
        } else if (checked && info == null) {
            content.addView(Ui.noteCard(this, getString(R.string.upd_none_found), R.color.text_secondary));
        } else if (checked && info != null && !info.newer) {
            content.addView(Ui.noteCard(this, getString(R.string.upd_up_to_date), R.color.ok));
        }

        if (info != null) {
            Button install = Ui.block(this, Ui.button(this,
                    info.newer ? R.string.upd_download_install : R.string.upd_reinstall, info.newer));
            ((LinearLayout.LayoutParams) install.getLayoutParams()).topMargin = Ui.dp(this, 14);
            install.setOnClickListener(v -> download());
            content.addView(install);

            LinearLayout more = new LinearLayout(this);
            more.setOrientation(LinearLayout.VERTICAL);
            if (!info.htmlUrl.isEmpty()) {
                more.addView(Ui.rowView(this, content, new Row(R.drawable.ic_open, false,
                        getString(R.string.upd_view_release), null, false, true), v -> openUrl(info.htmlUrl)));
            }
            if (info.newer) {
                more.addView(Ui.rowView(this, content, new Row(R.drawable.ic_clock, false,
                        getString(R.string.upd_skip_version), info.tag, false, false), v -> {
                    Store.setSkippedVersion(this, info.tag);
                    Toast.makeText(this, getString(R.string.upd_skipped, info.tag), Toast.LENGTH_SHORT).show();
                }));
            }
            Ui.group(this, more);
            content.addView(more);

            String notes = Updater.cleanNotes(info.body);
            if (!notes.isEmpty()) {
                content.addView(Ui.sectionTitle(this, getString(R.string.upd_notes)));
                content.addView(Ui.noteCard(this, notes, R.color.text_primary));
            }
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.upd_settings)));
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        String repo = Store.updateRepo(this);
        opts.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false, getString(R.string.upd_source),
                repo.isEmpty() ? getString(R.string.upd_no_source_short) : repo, false, true), v -> editSource()));
        final Ui.Toggle auto = Ui.toggle(this, opts, R.string.upd_auto, 0, Store.autoUpdate(this));
        auto.onChange((b, on) -> Store.setAutoUpdate(this, on));
        opts.addView(auto.view);
        final Ui.Toggle pre = Ui.toggle(this, opts, R.string.upd_pre, 0, Store.updatePre(this));
        pre.onChange((b, on) -> {
            Store.setUpdatePre(this, on);
            check();
        });
        opts.addView(pre.view);
        Ui.group(this, opts);
        content.addView(opts);

        content.addView(Ui.noteCard(this, getString(R.string.upd_sign_note), R.color.text_secondary));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void editSource() {
        LinearLayout box = Ui.box(this);
        final EditText src = Ui.edit(this, getString(R.string.upd_source_hint), Store.updateRepo(this));
        box.addView(Ui.label(this, getString(R.string.upd_source_hint)));
        box.addView(src);
        new Dlg(this)
                .setTitle(R.string.upd_source)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = src.getText().toString().trim();
                    if (Updater.split(v) == null) {
                        Toast.makeText(this, R.string.upd_source_bad, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Store.setUpdateRepo(this, v);
                    Store.setSkippedVersion(this, "");
                    updateSubtitle();
                    checked = false;
                    info = null;
                    render();
                    check();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ download + install

    private void showProgress() {
        hideProgress();
        cancel[0] = false;
        LinearLayout box = Ui.box(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 8));
        progressText = new TextView(this);
        progressText.setTextColor(Ui.color(this, R.color.text_secondary));
        progressText.setTextSize(14);
        progressText.setPadding(0, 0, 0, Ui.dp(this, 12));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        Ui.tint(this, progressBar);
        box.addView(progressText);
        box.addView(progressBar);
        progressText.setText(R.string.downloading);
        progressDialog = new Dlg(this).setTitle(R.string.downloading).setView(box).setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> cancel[0] = true)
                .create();
        progressDialog.show();
    }

    private void updateBytes(long done, long total) {
        if (progressBar == null) return;
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(1000);
            progressBar.setProgress((int) (done * 1000 / total));
            progressText.setText(getString(R.string.upd_progress, Fmt.size(done), Fmt.size(total)));
        } else {
            progressText.setText(Fmt.size(done));
        }
    }

    private void hideProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
    }

    private void download() {
        final Updater.Info in = info;
        if (in == null || busy) return;
        busy = true;
        showProgress();
        io.execute(() -> {
            try {
                final File apk = Updater.download(this, in, (done, total) -> post(() -> updateBytes(done, total)), cancel);
                post(() -> {
                    busy = false;
                    hideProgress();
                    Perms.installApk(this, apk);
                });
            } catch (final Exception e) {
                post(() -> {
                    busy = false;
                    hideProgress();
                    if (cancel[0]) {
                        Toast.makeText(this, R.string.fm_cancelled, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String msg = e instanceof Updater.VerifyException
                            ? getString(((Updater.VerifyException) e).resId)
                            : getString(R.string.upd_error, e.getMessage() == null ? e.toString() : e.getMessage());
                    new Dlg(this).setTitle(R.string.error).setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null).show();
                });
            }
        });
    }
}
