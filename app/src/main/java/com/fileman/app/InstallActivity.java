package com.fileman.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The install screen: opens for any APK / APKS / XAPK / APKM handed over by another app (or by the file
 * manager), shows the app's icon, name, version and size, and installs it through a PackageInstaller
 * session. Deliberately light: no permission or certificate details, one clear action.
 */
public class InstallActivity extends AppCompatActivity {
    private static final int S_LOADING = 0, S_READY = 1, S_INSTALLING = 2, S_DONE = 3, S_FAILED = 4, S_INVALID = 5;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    private ImageView icon;
    private TextView name, version, chip, message;
    private ProgressBar spin, progress;
    private Button primary, secondary;

    private int state = S_LOADING;
    private PkgInstaller.Info info;
    private boolean returnResult;
    private boolean resultOk;
    private boolean installStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);
        icon = findViewById(R.id.appIcon);
        name = findViewById(R.id.appName);
        version = findViewById(R.id.appVersion);
        chip = findViewById(R.id.chip);
        message = findViewById(R.id.message);
        spin = findViewById(R.id.spin);
        progress = findViewById(R.id.progress);
        primary = findViewById(R.id.primary);
        secondary = findViewById(R.id.secondary);
        Ui.tint(this, spin);
        Ui.tint(this, progress);
        Ui.press(this, primary);
        Ui.press(this, secondary);
        findViewById(R.id.btnClose).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        final float r = Ui.dp(this, 24);
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
        Uri data = in == null ? null : in.getData();
        if (data == null) {
            showInvalid();
            return;
        }
        load(data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // came back from the "install unknown apps" settings page
        if (state == S_READY && info != null) render();
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
        setState(S_LOADING);
        io.execute(() -> {
            PkgInstaller.Info in = null;
            try {
                File f = PkgInstaller.fromUri(this, data);
                in = PkgInstaller.inspect(this, f);
            } catch (Throwable ignored) {
            }
            final PkgInstaller.Info fin = in;
            ui.post(() -> {
                if (destroyed) return;
                if (fin == null) {
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

    // ------------------------------------------------------------------ states

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

    private void showInfo() {
        PkgInstaller.Info in = info;
        if (in == null) return;
        Drawable d = in.icon;
        icon.setImageTintList(null);
        if (d != null) icon.setImageDrawable(d);
        else icon.setImageResource(R.drawable.ic_package);
        name.setText(in.label.isEmpty() ? in.pkg : in.label);
        String v = in.versionName.isEmpty() ? String.valueOf(in.versionCode) : in.versionName;
        version.setText(v + " · " + Fmt.size(in.size));
    }

    private void render() {
        spin.setVisibility(state == S_LOADING ? View.VISIBLE : View.GONE);
        progress.setVisibility(state == S_INSTALLING ? View.VISIBLE : View.GONE);
        switch (state) {
            case S_LOADING:
                icon.setImageDrawable(null);
                name.setText(R.string.pk_inspecting);
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
                buttons(null, null, null, null);
                break;
            case S_DONE:
                showInfo();
                pill(getString(R.string.in_installed), R.color.ok);
                note(getString(R.string.pk_ok_msg, info != null ? name.getText().toString() : ""));
                boolean canOpen = info != null && getPackageManager().getLaunchIntentForPackage(info.pkg) != null;
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
                buttons(getString(R.string.in_retry), v -> startInstall(), getString(R.string.in_close), v -> finishWithResult());
                break;
            default:   // S_INVALID
                version.setText("");
                pill(null, 0);
                note(getString(R.string.pk_invalid));
                buttons(getString(R.string.in_close), v -> finishWithResult(), null, null);
                break;
        }
    }

    private void renderReady() {
        final PkgInstaller.Info in = info;
        showInfo();
        String chipText;
        int color;
        String line = null;
        if (!in.installed) {
            chipText = getString(R.string.in_chip_new);
            color = R.color.info;
        } else if (!in.sameCert) {
            chipText = getString(R.string.in_chip_cert);
            color = R.color.bad;
            line = getString(R.string.in_cert_line);
        } else if (in.versionCode > in.installedCode) {
            chipText = getString(R.string.in_chip_update);
            color = R.color.ok;
            line = in.installedName + " → " + in.versionName;
        } else if (in.versionCode == in.installedCode) {
            chipText = getString(R.string.in_chip_same);
            color = R.color.warn;
        } else {
            chipText = getString(R.string.in_chip_older);
            color = R.color.bad;
            line = getString(R.string.in_older_line, in.installedName);
        }
        pill(chipText, color);
        String extra = in.bundle && in.hasObb ? getString(R.string.pk_obb_note) : null;
        if (line != null && extra != null) line = line + "\n" + extra;
        else if (line == null) line = extra;
        note(line);

        if (!Perms.canInstall(this)) {
            note(getString(R.string.in_need_allow));
            buttons(getString(R.string.perm_open_settings), v -> Perms.requestInstall(this),
                    getString(R.string.cancel), v -> finishWithResult());
            return;
        }
        View.OnClickListener un = null;
        String unText = null;
        if (in.installed && !in.sameCert) {
            unText = getString(R.string.pk_uninstall);
            un = v -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + in.pkg)));
        }
        buttons(getString(in.installed ? R.string.in_update : R.string.pk_install), v -> startInstall(),
                unText != null ? unText : getString(R.string.cancel), un != null ? un : v -> finishWithResult());
    }

    // ------------------------------------------------------------------ installing

    private void startInstall() {
        final PkgInstaller.Info in = info;
        if (in == null) return;
        installStarted = true;
        progress.setProgress(0);
        message.setText(R.string.pk_installing_plain);
        message.setVisibility(View.VISIBLE);
        setState(S_INSTALLING);
        message.setText(R.string.pk_installing_plain);
        message.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                PkgInstaller.install(this, in, (done, total) -> ui.post(() -> {
                    if (!destroyed) progress.setProgress((int) Math.min(100, done * 100 / Math.max(1, total)));
                }));
                ui.post(() -> {
                    if (!destroyed && state == S_INSTALLING) message.setText(R.string.pk_waiting);
                });
            } catch (IOException | RuntimeException e) {
                ui.post(() -> fail(getString(R.string.pk_fail_invalid)));
            }
        });
    }

    private void fail(String msg) {
        if (destroyed) return;
        installStarted = false;
        setState(S_FAILED);
        note(msg);
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
                        fail(getString(R.string.pk_fail_blocked));
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                installStarted = false;
                resultOk = true;
                if (!destroyed) setState(S_DONE);
            } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                // the person said no in the system dialog: go back to the ready screen, nothing to report
                installStarted = false;
                if (!destroyed) setState(S_READY);
            } else {
                fail(friendly(st, msg));
            }
        }
    };

    private String friendly(int st, String msg) {
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
