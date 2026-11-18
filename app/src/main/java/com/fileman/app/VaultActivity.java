package com.fileman.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Protected documents: encrypted copies kept in the app's private storage. Opening one needs the screen lock or a
 * fingerprint; too many wrong attempts, or any other process touching the vault files, wipes everything for good.
 */
public class VaultActivity extends BaseActivity {
    private static final int REQ_CRED = 77;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private Runnable pendingOk;

    // ------------------------------------------------------------------ adding files (called from the file manager)

    static void protect(final Activity a, final List<File> files, final Runnable done) {
        if (!AuthGate.isSecure(a)) {
            Toast.makeText(a, R.string.v_no_lock, Toast.LENGTH_LONG).show();
            return;
        }
        final List<File> only = new ArrayList<>();
        for (File f : files) if (f.isFile()) only.add(f);
        if (only.isEmpty()) {
            Toast.makeText(a, R.string.v_only_files, Toast.LENGTH_SHORT).show();
            return;
        }
        new Dlg(a).setTitle(R.string.v_add_title).setMessage(R.string.v_add_msg)
                .setPositiveButton(R.string.v_add_go, (d, w) -> {
                    Toast.makeText(a, R.string.v_busy, Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int ok = 0;
                        for (File f : only) if (Vault.protect(a, f) != null) ok++;
                        final int n = ok;
                        a.runOnUiThread(() -> {
                            Toast.makeText(a, a.getString(R.string.v_added_n, n), Toast.LENGTH_SHORT).show();
                            if (done != null) done.run();
                        });
                    }, "vault-add").start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Throwable ignored) {
        }
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.v_title);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.v_sub);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(android.view.View.GONE);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        Vault.watch(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        io.execute(() -> Vault.clearTemps(getApplicationContext()));
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void render() {
        content.removeAllViews();
        content.addView(Ui.hero(this, getString(R.string.v_title), getString(R.string.v_hero)));
        List<Vault.Item> items = Vault.list(this);
        if (items.isEmpty()) {
            content.addView(Ui.footer(this, getString(R.string.v_empty)));
            return;
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.v_docs_n, items.size())));
        for (final Vault.Item it : items) {
            content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_lock, true, it.name,
                    Fmt.size(it.size) + "  ·  " + Fmt.date(it.added), false, true), v -> itemMenu(it)));
        }
    }

    private void itemMenu(final Vault.Item it) {
        final String[] labels = {getString(R.string.v_open), getString(R.string.v_restore), getString(R.string.v_destroy)};
        final int[] icons = {R.drawable.ic_open, R.drawable.ic_download, R.drawable.ic_delete};
        new Dlg(this).setTitle(it.name).setItems(labels, icons, (d, which) -> {
            if (which == 0) unlock(() -> openItem(it));
            else if (which == 1) unlock(() -> restoreItem(it));
            else confirmDestroy(it);
        }).show();
    }

    private void openItem(final Vault.Item it) {
        io.execute(() -> {
            try {
                final File f = Vault.openTemp(getApplicationContext(), it);
                ui.post(() -> {
                    if (!isDestroyed()) Opener.open(this, f);
                });
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(this, R.string.v_open_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void restoreItem(final Vault.Item it) {
        io.execute(() -> {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                File dest = unique(dir, it.name);
                Vault.decryptTo(getApplicationContext(), it.id, dest);
                Vault.destroy(getApplicationContext(), it.id);
                ui.post(() -> {
                    Toast.makeText(this, getString(R.string.v_restored, dest.getName()), Toast.LENGTH_LONG).show();
                    render();
                });
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(this, R.string.v_open_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int i = 1;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ext);
        return f;
    }

    private void confirmDestroy(final Vault.Item it) {
        new Dlg(this).setTitle(R.string.v_destroy).setMessage(getString(R.string.v_destroy_msg, it.name))
                .setPositiveButton(R.string.v_destroy, (d, w) -> io.execute(() -> {
                    Vault.destroy(getApplicationContext(), it.id);
                    ui.post(() -> {
                        Toast.makeText(this, R.string.v_destroyed, Toast.LENGTH_SHORT).show();
                        render();
                    });
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ unlock

    private void unlock(final Runnable ok) {
        if (!AuthGate.isSecure(this)) {
            Toast.makeText(this, R.string.v_no_lock, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b =
                        new android.hardware.biometrics.BiometricPrompt.Builder(this)
                                .setTitle(getString(R.string.v_unlock_title))
                                .setSubtitle(getString(R.string.v_unlock_sub));
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                    | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else {
                    b.setDeviceCredentialAllowed(true);
                }
                final Context app = getApplicationContext();
                b.build().authenticate(new CancellationSignal(), getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(
                                    android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                                Vault.resetFailures(app);
                                ok.run();
                            }

                            @Override
                            public void onAuthenticationFailed() {
                                failed();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                if (code == 7 || code == 9) failed();   // too many tries: counts as an attempt too
                            }
                        });
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            Intent i = km.createConfirmDeviceCredentialIntent(getString(R.string.v_unlock_title),
                    getString(R.string.v_unlock_sub));
            if (i == null) return;
            pendingOk = ok;
            startActivityForResult(i, REQ_CRED);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.v_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void failed() {
        int left = Vault.recordFailure(getApplicationContext());
        if (left <= 0) {
            Toast.makeText(this, R.string.v_wiped_toast, Toast.LENGTH_LONG).show();
            render();
        } else {
            Toast.makeText(this, getString(R.string.v_attempts_left, left), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CRED) return;
        Runnable r = pendingOk;
        pendingOk = null;
        if (res == RESULT_OK && r != null) {
            Vault.resetFailures(getApplicationContext());
            r.run();
        }
    }
}
