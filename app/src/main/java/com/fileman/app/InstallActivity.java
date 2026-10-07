package com.fileman.app;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The install screen (beta 2): opens for any APK / APKS / XAPK / APKM handed over by another app (or by the file
 * manager). Shows the app, what the install will do (new / update / reinstall), checks it against this device
 * before starting (Android version, CPU, space, downgrade, signature), lists size / packages / permissions /
 * signature / file fingerprint, installs through a PackageInstaller session, copies OBB data and reports the
 * result in plain words.
 */
public class InstallActivity extends BaseActivity {
    private static final int S_LOADING = 0, S_READY = 1, S_INSTALLING = 2, S_DONE = 3, S_FAILED = 4, S_INVALID = 5;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;
    private boolean resumed = false;

    private ImageView icon;
    private TextView name, pkgView, version, chip, message, progressText;
    private ProgressBar spin, progress;
    private Button primary, secondary;
    private LinearLayout details;

    private int state = S_LOADING;
    private PkgInstaller.Info info;
    private Uri dataUri;
    private boolean returnResult;
    private boolean resultOk;
    private boolean installStarted;
    private boolean uninstallRequested;
    private int stageRes = R.string.pk_installing_plain;
    private String loadError;
    private String lastTech = "";
    private String obbNote;
    private boolean obbWarn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);
        icon = findViewById(R.id.appIcon);
        name = findViewById(R.id.appName);
        pkgView = findViewById(R.id.appPkg);
        version = findViewById(R.id.appVersion);
        chip = findViewById(R.id.chip);
        message = findViewById(R.id.message);
        progressText = findViewById(R.id.progressText);
        spin = findViewById(R.id.spin);
        progress = findViewById(R.id.progress);
        primary = findViewById(R.id.primary);
        secondary = findViewById(R.id.secondary);
        details = findViewById(R.id.details);
        Ui.tint(this, spin);
        Ui.tint(this, progress);
        Ui.press(this, primary);
        Ui.press(this, secondary);
        Ui.autoGroup(this, details);
        findViewById(R.id.btnClose).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        TextView beta = findViewById(R.id.betaPill);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 20));
        bg.setColor(Ui.color(this, R.color.accent_soft));
        beta.setBackground(bg);
        beta.setTextColor(Ui.color(this, R.color.accent_text));

        final float r = Ui.dp(this, 22);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (state == S_INSTALLING && installStarted) return;   // the system is working: do not drop the result
                finishWithResult();
            }
        });

        ContextCompat.registerReceiver(this, rx, new IntentFilter(PkgInstaller.action(this)),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        Intent in = getIntent();
        returnResult = in != null && in.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false);
        dataUri = in == null ? null : in.getData();
        if (dataUri == null) {
            showInvalid();
            return;
        }
        load(dataUri);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (uninstallRequested && dataUri != null) {
            // came back from the uninstall dialog: look at the package again, the installed copy may be gone
            uninstallRequested = false;
            load(dataUri);
        } else if (state == S_READY && info != null) {
            renderReady();   // the "install unknown apps" permission may have just been granted
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        try {
            unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (isFinishing()) cleanCache();
    }

    private void finishWithResult() {
        if (returnResult) setResult(resultOk ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    private void cleanCache() {
        File dir = new File(getCacheDir(), "inst");
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            //noinspection ResultOfMethodCallIgnored
            k.delete();
        }
    }

    // ------------------------------------------------------------------ loading

    private void load(final Uri data) {
        loadError = null;
        setState(S_LOADING);
        io.execute(() -> {
            PkgInstaller.Info in = null;
            String err = null;
            try {
                File f = PkgInstaller.fromUri(this, data);
                in = PkgInstaller.inspect(this, f);
                if ("file".equals(data.getScheme())) in.original = f;
            } catch (PkgInstaller.NoSpace e) {
                err = getString(R.string.pk_fail_storage);
            } catch (Throwable ignored) {
            }
            final PkgInstaller.Info fin = in;
            final String ferr = err;
            ui.post(() -> {
                if (destroyed) return;
                if (fin == null) {
                    loadError = ferr;
                    showInvalid();
                } else {
                    info = fin;
                    setState(S_READY);
                }
            });
        });
    }

    private void showInvalid() {
        icon.setImageResource(R.drawable.ic_package);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.bad)));
        name.setText(R.string.in_invalid_title);
        setState(S_INVALID);
    }

    // ------------------------------------------------------------------ small view helpers

    private void setState(int s) {
        state = s;
        render();
    }

    private void pill(String text, int colorRes) {
        if (text == null) {
            chip.setVisibility(View.GONE);
            return;
        }
        int c = Ui.color(this, colorRes);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 20));
        g.setColor((c & 0x00FFFFFF) | 0x26000000);
        chip.setBackground(g);
        chip.setTextColor(c);
        chip.setText(text);
        chip.setVisibility(View.VISIBLE);
    }

    private void note(String text) {
        message.setText(text);
        message.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void buttons(String p, View.OnClickListener pc, String s, View.OnClickListener sc) {
        primary.setVisibility(p == null ? View.GONE : View.VISIBLE);
        primary.setText(p);
        primary.setOnClickListener(pc);
        primary.setEnabled(true);
        secondary.setVisibility(s == null ? View.GONE : View.VISIBLE);
        secondary.setText(s);
        secondary.setOnClickListener(sc);
    }

    private View row(int iconRes, String title, String sub, View.OnClickListener click) {
        return Ui.rowView(this, details, new Row(iconRes, false, title, sub, false, click != null), click);
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("fileman", text));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void showInfo() {
        PkgInstaller.Info in = info;
        if (in == null) return;
        Drawable d = in.icon;
        icon.setImageTintList(null);
        if (d != null) icon.setImageDrawable(d);
        else icon.setImageResource(R.drawable.ic_package);
        name.setText(in.label.isEmpty() ? in.pkg : in.label);
        pkgView.setText(in.pkg);
        pkgView.setVisibility(in.pkg.isEmpty() ? View.GONE : View.VISIBLE);
        String v = in.versionName.isEmpty() ? String.valueOf(in.versionCode) : in.versionName;
        if (in.installed && in.sameCert && in.versionCode > in.installedCode) {
            String from = in.installedName.isEmpty() ? String.valueOf(in.installedCode) : in.installedName;
            version.setText(from + " → " + v + " · " + Fmt.size(in.size));
        } else {
            version.setText(v + " · " + Fmt.size(in.size));
        }
    }

    // ------------------------------------------------------------------ states

    private void render() {
        spin.setVisibility(state == S_LOADING ? View.VISIBLE : View.GONE);
        progress.setVisibility(state == S_INSTALLING ? View.VISIBLE : View.GONE);
        progressText.setVisibility(state == S_INSTALLING ? View.VISIBLE : View.GONE);
        if (state != S_READY) details.removeAllViews();
        switch (state) {
            case S_LOADING:
                icon.setImageDrawable(null);
                name.setText(R.string.pk_inspecting);
                pkgView.setVisibility(View.GONE);
                version.setText("");
                pill(null, 0);
                note(null);
                buttons(null, null, null, null);
                break;
            case S_READY:
                renderReady();
                break;
            case S_INSTALLING:
                showInfo();
                pill(null, 0);
                note(getString(stageRes));
                buttons(null, null, null, null);
                break;
            case S_DONE:
                showInfo();
                pill(getString(R.string.in_installed), R.color.ok);
                note(getString(R.string.pk_ok_msg, info != null ? name.getText().toString() : ""));
                if (obbNote != null) {
                    details.addView(Ui.noteCard(this, obbNote, obbWarn ? R.color.warn : R.color.info));
                }
                final boolean canOpen = info != null && getPackageManager().getLaunchIntentForPackage(info.pkg) != null;
                buttons(canOpen ? getString(R.string.pk_open_app) : getString(R.string.in_done),
                        v -> {
                            if (canOpen) {
                                Intent li = getPackageManager().getLaunchIntentForPackage(info.pkg);
                                if (li != null) startActivity(li);
                            }
                            finishWithResult();
                        },
                        canOpen ? getString(R.string.in_done) : null, v -> finishWithResult());
                break;
            case S_FAILED:
                showInfo();
                pill(getString(R.string.pk_fail_title), R.color.bad);
                if (!lastTech.isEmpty()) {
                    details.addView(Ui.body(this, getString(R.string.pk_fail_code, lastTech), 12, R.color.text_hint));
                }
                final boolean offerAll = info != null && info.bundle && info.smart;
                View.OnClickListener second;
                if (offerAll) {
                    second = v -> {
                        PkgInstaller.applySelection(this, info, false);
                        startInstall();
                    };
                } else {
                    second = v -> finishWithResult();
                }
                buttons(getString(R.string.in_retry), v -> startInstall(),
                        offerAll ? getString(R.string.pk_retry_all) : getString(R.string.in_close), second);
                break;
            default:   // S_INVALID
                pkgView.setVisibility(View.GONE);
                version.setText("");
                pill(null, 0);
                note(loadError != null ? loadError : getString(R.string.pk_invalid));
                buttons(getString(R.string.in_close), v -> finishWithResult(), null, null);
                break;
        }
    }

    private void renderReady() {
        final PkgInstaller.Info in = info;
        if (in == null) return;
        showInfo();
        details.removeAllViews();

        final List<PkgInstaller.Issue> issues = PkgInstaller.preflight(this, in);
        boolean blocked = false;
        for (PkgInstaller.Issue is : issues) if (is.blocking) blocked = true;
        final boolean mustUninstall = in.installed && (!in.sameCert || in.versionCode < in.installedCode);

        String chipText;
        int color;
        if (blocked && !mustUninstall) {
            chipText = getString(R.string.pk_chip_blocked);
            color = R.color.bad;
        } else if (!in.installed) {
            chipText = getString(R.string.in_chip_new);
            color = R.color.info;
        } else if (!in.sameCert) {
            chipText = getString(R.string.in_chip_cert);
            color = R.color.bad;
        } else if (in.versionCode > in.installedCode) {
            chipText = getString(R.string.in_chip_update);
            color = R.color.ok;
        } else if (in.versionCode == in.installedCode) {
            chipText = getString(R.string.in_chip_same);
            color = R.color.warn;
        } else {
            chipText = getString(R.string.in_chip_older);
            color = R.color.bad;
        }
        pill(chipText, color);
        note(null);

        buildDetails(in, issues);

        if (!Perms.canInstall(this)) {
            note(getString(R.string.in_need_allow));
            buttons(getString(R.string.perm_open_settings), v -> Perms.requestInstall(this),
                    getString(R.string.cancel), v -> finishWithResult());
            return;
        }
        if (mustUninstall) {
            buttons(getString(R.string.pk_uninstall_first), v -> {
                uninstallRequested = true;
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + in.pkg)));
                } catch (Exception e) {
                    uninstallRequested = false;
                }
            }, getString(R.string.in_close), v -> finishWithResult());
        } else if (blocked) {
            buttons(getString(R.string.in_close), v -> finishWithResult(), null, null);
        } else {
            buttons(getString(in.installed ? R.string.in_update : R.string.pk_install), v -> startInstall(),
                    getString(R.string.cancel), v -> finishWithResult());
        }
    }

    private void buildDetails(final PkgInstaller.Info in, List<PkgInstaller.Issue> issues) {
        for (PkgInstaller.Issue is : issues) {
            int c = is.level == PkgInstaller.LV_ERROR ? R.color.bad
                    : is.level == PkgInstaller.LV_WARN ? R.color.warn : R.color.info;
            details.addView(Ui.noteCard(this, is.text, c));
        }

        // ---- options
        boolean canSmart = in.bundle && in.allEntries.size() >= 3;
        boolean canDelete = in.original != null;
        if (canSmart || canDelete) {
            details.addView(Ui.sectionTitle(this, getString(R.string.pk_opt_section)));
            if (canSmart) {
                Ui.Toggle t = Ui.toggle(this, details, R.string.pk_opt_smart, R.string.pk_opt_smart_sub,
                        Store.instBool(this, "smart", true));
                t.onChange((b, on) -> {
                    Store.setInstBool(this, "smart", on);
                    PkgInstaller.applySelection(this, in, on);
                    ui.post(this::renderReady);
                });
                details.addView(t.view);
            }
            if (canDelete) {
                Ui.Toggle t = Ui.toggle(this, details, R.string.pk_opt_delete, R.string.pk_opt_delete_sub,
                        Store.instBool(this, "delete_after", false));
                t.onChange((b, on) -> Store.setInstBool(this, "delete_after", on));
                details.addView(t.view);
            }
        }

        // ---- facts
        details.addView(Ui.sectionTitle(this, getString(R.string.pk_details)));
        if (in.installed) {
            String iv = (in.installedName.isEmpty() ? "" : in.installedName + " ") + "(" + in.installedCode + ")";
            details.addView(row(R.drawable.ic_clock, getString(R.string.pk_d_installed), iv, null));
        }
        String sizeSub = Fmt.size(in.size);
        if (in.bundle) sizeSub += " · " + getString(R.string.pk_d_splits_sub2, in.entries.size(), in.allEntries.size());
        details.addView(row(R.drawable.ic_drive, getString(R.string.pk_d_size), sizeSub, null));
        details.addView(row(R.drawable.ic_info, getString(R.string.pk_d_sdk),
                getString(R.string.pk_d_sdk_sub, in.minSdk, in.targetSdk), null));
        String abi;
        if (in.abis.isEmpty()) {
            abi = getString(R.string.pk_d_abi_none);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String a : in.abis) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(a);
            }
            abi = sb.toString();
        }
        details.addView(row(R.drawable.ic_code, getString(R.string.pk_d_abi), abi, null));

        int sens = PkgInstaller.sensitiveCount(this, in);
        String permSub = in.perms.isEmpty() ? getString(R.string.pk_perms_none)
                : sens > 0 ? getString(R.string.pk_perms_sens, sens) : getString(R.string.pk_perms_plain);
        details.addView(row(R.drawable.ic_shield, getString(R.string.pk_perms, in.perms.size()), permSub,
                in.perms.isEmpty() ? null : v -> showPerms(in)));

        final String cert = in.certs.isEmpty() ? "" : in.certs.iterator().next();
        details.addView(row(R.drawable.ic_lock, getString(R.string.pk_d_cert), in.certShort(),
                cert.isEmpty() ? null : v -> copy(cert)));

        String hashSub = in.fileSha.isEmpty() ? getString(R.string.pk_d_filehash_tap) : in.fileSha.substring(0, 32);
        details.addView(row(R.drawable.ic_copy, getString(R.string.pk_d_filehash), hashSub, v -> {
            if (!in.fileSha.isEmpty()) {
                copy(in.fileSha);
                return;
            }
            Toast.makeText(this, R.string.pk_d_calc, Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                try {
                    final String h = PkgInstaller.fileSha256(in.source, null);
                    ui.post(() -> {
                        if (destroyed) return;
                        in.fileSha = h;
                        if (state == S_READY) renderReady();
                    });
                } catch (Throwable ignored) {
                }
            });
        }));
        Ui.group(this, details);
    }

    private void showPerms(PkgInstaller.Info in) {
        int n = Math.min(in.perms.size(), 120);
        String[] items = new String[n];
        for (int i = 0; i < n; i++) {
            String p = in.perms.get(i);
            String shortName = p.startsWith("android.permission.") ? p.substring("android.permission.".length()) : p;
            items[i] = (PkgInstaller.sensitive(this, p) ? "⚠  " : "•  ") + shortName;
        }
        new Dlg(this).setTitle(getString(R.string.pk_perms, in.perms.size()))
                .setItems(items, (d, w) -> { })
                .show();
    }

    // ------------------------------------------------------------------ installing

    private void startInstall() {
        final PkgInstaller.Info in = info;
        if (in == null) return;
        installStarted = true;
        stageRes = R.string.pk_installing_plain;
        progress.setProgress(0);
        progressText.setText("");
        setState(S_INSTALLING);
        io.execute(() -> {
            try {
                PkgInstaller.install(this, in, (done, total) -> ui.post(() -> {
                    if (destroyed || state != S_INSTALLING) return;
                    progress.setProgress((int) Math.min(100, done * 100 / Math.max(1, total)));
                    progressText.setText(getString(R.string.pk_installing_size, Fmt.size(done), Fmt.size(total)));
                }));
                ui.post(() -> {
                    if (!destroyed && state == S_INSTALLING) {
                        progress.setProgress(100);
                        message.setText(R.string.pk_waiting);
                        message.setVisibility(View.VISIBLE);
                    }
                });
            } catch (PkgInstaller.NoSpace e) {
                ui.post(() -> fail(getString(R.string.pk_fail_storage), "no space"));
            } catch (IOException | RuntimeException e) {
                final String tech = String.valueOf(e.getMessage());
                ui.post(() -> fail(getString(R.string.pk_fail_invalid), tech));
            }
        });
    }

    private void fail(String msg, String tech) {
        if (destroyed) return;
        installStarted = false;
        lastTech = tech == null ? "" : tech;
        if (info != null) PkgInstaller.log(this, info, false, msg);
        setState(S_FAILED);
        note(msg);
        if (!resumed) Notifs.installResult(this, getString(R.string.pk_fail_title), msg, false);
    }

    private void onSuccess() {
        installStarted = false;
        resultOk = true;
        if (info != null) PkgInstaller.log(this, info, true, "");
        if (destroyed) return;
        final PkgInstaller.Info in = info;
        if (in != null && in.bundle && !in.obbEntries.isEmpty()) {
            // the app is installed; now put its OBB data where it looks for it
            stageRes = R.string.pk_obb_copying;
            progress.setProgress(0);
            progressText.setText("");
            installStarted = true;
            setState(S_INSTALLING);
            io.execute(() -> {
                String result;
                boolean warn = false;
                try {
                    int n = PkgInstaller.copyObb(this, in, (done, total) -> ui.post(() -> {
                        if (destroyed || state != S_INSTALLING) return;
                        progress.setProgress((int) Math.min(100, done * 100 / Math.max(1, total)));
                        progressText.setText(getString(R.string.pk_installing_size, Fmt.size(done), Fmt.size(total)));
                    }));
                    result = getString(R.string.pk_obb_done, n);
                } catch (Throwable e) {
                    result = getString(R.string.pk_obb_fail);
                    warn = true;
                }
                final String fres = result;
                final boolean fwarn = warn;
                ui.post(() -> {
                    if (destroyed) return;
                    installStarted = false;
                    obbNote = fres;
                    obbWarn = fwarn;
                    finishDone();
                });
            });
        } else {
            finishDone();
        }
    }

    private void finishDone() {
        final PkgInstaller.Info in = info;
        if (in != null && in.original != null && Store.instBool(this, "delete_after", false)) {
            try {
                File cache = getCacheDir();
                if (!in.original.getAbsolutePath().startsWith(cache.getAbsolutePath())) {
                    //noinspection ResultOfMethodCallIgnored
                    in.original.delete();
                }
            } catch (Throwable ignored) {
            }
        }
        setState(S_DONE);
        if (!resumed) {
            Notifs.installResult(this, getString(R.string.pk_ok_title),
                    getString(R.string.pk_ok_msg, in != null ? (in.label.isEmpty() ? in.pkg : in.label) : ""), true);
        }
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    try {
                        startActivity(confirm);
                    } catch (Exception e) {
                        fail(getString(R.string.pk_fail_blocked), "blocked");
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                onSuccess();
            } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                // the person said no in the system dialog: go back to the ready screen, nothing to report
                installStarted = false;
                if (!destroyed) setState(S_READY);
            } else {
                fail(friendly(st, msg), st + (msg == null || msg.isEmpty() ? "" : ": " + msg));
            }
        }
    };

    private String friendly(int st, String msg) {
        int r = PkgInstaller.friendlyMessage(msg);
        if (r != 0) return getString(r);
        if (Build.VERSION.SDK_INT >= 34 && st == PackageInstaller.STATUS_FAILURE_TIMEOUT) {
            return getString(R.string.pk_fail_timeout);
        }
        switch (st) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return getString(R.string.pk_fail_blocked);
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return getString(R.string.pk_fail_conflict);
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return getString(R.string.pk_fail_incompat);
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return getString(R.string.pk_fail_storage);
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return getString(R.string.pk_fail_invalid);
            default:
                return getString(R.string.pk_fail_other, msg == null || msg.isEmpty() ? String.valueOf(st) : msg);
        }
    }
}
