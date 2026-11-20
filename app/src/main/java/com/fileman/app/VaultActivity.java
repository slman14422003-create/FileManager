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
import android.widget.Button;
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

    private final ExecutorService io = Bg.single("fm-bg");
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;

    // ------------------------------------------------------------------ adding files (called from the file manager)

    static void protect(final Activity a, final List<File> files, final Runnable done) {
        final List<File> only = new ArrayList<>();
        for (File f : files) if (f.isFile()) only.add(f);
        if (only.isEmpty()) {
            Toast.makeText(a, R.string.v_only_files, Toast.LENGTH_SHORT).show();
            return;
        }
        VaultGate.ask(a, () -> new Dlg(a).setTitle(R.string.v_add_title).setMessage(R.string.v_add_msg)
                .setPositiveButton(R.string.v_add_go, (d, w) -> {
                    Toast.makeText(a, R.string.v_busy, Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        int ok = 0;
                        for (File f : only) if (Vault.protect(a, f) != null) ok++;
                        final int n = ok;
                        a.runOnUiThread(() -> {
                            if (!(a instanceof VaultActivity)) VaultGate.close(a);
                            Toast.makeText(a, a.getString(R.string.v_added_n, n), Toast.LENGTH_SHORT).show();
                            if (done != null) done.run();
                        });
                    }, "vault-add").start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show(), null);
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
        lastTouch = System.currentTimeMillis();
        watch.removeCallbacks(watchTick);
        watch.postDelayed(watchTick, 15_000L);
        try {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Throwable ignored) {
        }
        if (VaultKey.isUnlocked()) {
            render();
        } else {
            renderLocked();
            if (!asking) {
                asking = true;
                VaultGate.ask(this, () -> {
                    asking = false;
                    render();
                }, () -> {
                    asking = false;
                    renderLocked();
                });
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) VaultGate.close(this);
        io.shutdown();
    }

    private boolean asking;
    private long lastTouch = System.currentTimeMillis();
    private final android.os.Handler watch = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable watchTick = new Runnable() {
        @Override
        public void run() {
            if (VaultKey.isUnlocked()) {
                boolean idle = System.currentTimeMillis() - lastTouch > 3 * 60_000L;
                if (idle || Hostile.hooking()) {
                    VaultGate.close(VaultActivity.this);
                    renderLocked();
                    Toast.makeText(VaultActivity.this, idle ? R.string.vk_idle : R.string.vk_hooked,
                            Toast.LENGTH_LONG).show();
                } else {
                    io.execute(() -> Vault.clearOldTemps(getApplicationContext(), 10 * 60_000L));
                }
            }
            watch.postDelayed(this, 15_000L);
        }
    };

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        lastTouch = System.currentTimeMillis();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onPause() {
        super.onPause();
        watch.removeCallbacks(watchTick);
    }

    private void renderLocked() {
        content.removeAllViews();
        content.addView(Ui.hero(this, getString(R.string.v_title), getString(R.string.v_hero)));
        content.addView(Ui.footer(this, getString(R.string.vk_locked)));
        Button open = Ui.block(this, Ui.button(this, R.string.vk_open, true));
        open.setOnClickListener(v -> {
            if (asking) return;
            asking = true;
            VaultGate.ask(this, () -> {
                asking = false;
                render();
            }, () -> asking = false);
        });
        content.addView(open);
    }

    private void render() {
        content.removeAllViews();
        content.addView(Ui.hero(this, getString(R.string.v_title), getString(R.string.v_hero)));
        List<Vault.Item> items = Vault.list(this);
        content.addView(Ui.noteCard(this, getString(R.string.vk_status, items.size()), R.color.accent_text));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.addView(Ui.rowView(this, tools, new Row(R.drawable.ic_lock_fill, true, getString(R.string.vk_lock_now),
                getString(R.string.vk_lock_now_sub), false, true), v -> {
            VaultGate.close(this);
            renderLocked();
        }));
        if (Bio.cryptoSupported() && Bio.available(this)) {
            final boolean on = VaultKey.bioReady(this);
            Row r = new Row(R.drawable.ic_shield, true, getString(R.string.vk_bio_row),
                    getString(on ? R.string.vk_bio_row_on : R.string.vk_bio_row_off), false, true);
            r.badge(getString(on ? R.string.lk_on : R.string.lk_off), Ui.color(this, on ? R.color.ok : R.color.text_hint));
            tools.addView(Ui.rowView(this, tools, r, v -> {
                if (on) {
                    VaultKey.bioDisable(this);
                    render();
                } else {
                    VaultKey.bioEnable(this, this::render,
                            () -> Toast.makeText(this, R.string.vk_bio_fail, Toast.LENGTH_SHORT).show());
                }
            }));
        }
        Ui.group(this, tools);
        content.addView(tools);
        security(items);

        if (items.isEmpty()) {
            content.addView(Ui.footer(this, getString(R.string.v_empty)));
            return;
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.v_docs_n, items.size())));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final Vault.Item it : items) {
            String ext = Cats.extOf(it.name);
            Row r = new Row(Cats.iconForExt(ext, Cats.typeOfExt(ext)), false, it.name,
                    Fmt.size(it.size) + "  \u00b7  " + Fmt.date(it.added), false, true);
            r.badge(getString(R.string.vk_badge), Ui.color(this, R.color.accent_text));
            list.addView(Ui.rowView(this, list, r, v -> itemMenu(it)));
        }
        Ui.group(this, list);
        content.addView(list);
        content.addView(Ui.footer(this, getString(R.string.vk_hint)));
    }

    private void security(List<Vault.Item> items) {
        content.addView(Ui.sectionTitle(this, getString(R.string.vk_adv)));
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);

        final boolean duress = VaultKey.panicSet(this);
        Row r1 = new Row(R.drawable.ic_shield, true, getString(R.string.vk_panic), getString(R.string.vk_panic_sub),
                false, true);
        r1.badge(getString(duress ? R.string.lk_on : R.string.lk_off),
                Ui.color(this, duress ? R.color.ok : R.color.text_hint));
        adv.addView(Ui.rowView(this, adv, r1, v -> {
            if (duress) {
                VaultKey.clearPanic(this);
                render();
            } else {
                VaultGate.newPassword(this, R.string.vk_panic, pw -> {
                    if (VaultKey.isReal(this, pw)) {
                        Toast.makeText(this, R.string.vk_panic_same, Toast.LENGTH_LONG).show();
                    } else {
                        VaultKey.setPanic(this, pw);
                    }
                    Pass.wipe(pw);
                    render();
                }, null, false);
            }
        }));

        final boolean sms = Remote.smsOn(this);
        Row r2 = new Row(R.drawable.ic_delete, true, getString(R.string.vk_sms), getString(R.string.vk_sms_sub),
                false, true);
        r2.badge(getString(sms ? R.string.lk_on : R.string.lk_off), Ui.color(this, sms ? R.color.ok : R.color.text_hint));
        adv.addView(Ui.rowView(this, adv, r2, v -> {
            if (sms) {
                new Dlg(this).setTitle(R.string.vk_sms).setMessage(R.string.vk_sms_off_msg)
                        .setPositiveButton(R.string.vk_sms_new, (d, w) -> showSmsCommand())
                        .setNeutralButton(R.string.vk_sms_stop, (d, w) -> {
                            Remote.smsDisable(this);
                            render();
                        })
                        .setNegativeButton(R.string.cancel, null).show();
            } else if (checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showSmsCommand();
            } else {
                requestPermissions(new String[]{android.Manifest.permission.RECEIVE_SMS}, REQ_SMS);
            }
        }));

        final int days = Remote.deadDays(this);
        Row r3 = new Row(R.drawable.ic_timer, true, getString(R.string.vk_dead), getString(R.string.vk_dead_sub),
                false, true);
        r3.badge(days == 0 ? getString(R.string.lk_off) : getString(R.string.vk_days, days),
                Ui.color(this, days == 0 ? R.color.text_hint : R.color.ok));
        adv.addView(Ui.rowView(this, adv, r3, v -> {
            final int[] opts = {0, 7, 14, 30, 60};
            CharSequence[] names = {getString(R.string.lk_off), getString(R.string.vk_days, 7),
                    getString(R.string.vk_days, 14), getString(R.string.vk_days, 30), getString(R.string.vk_days, 60)};
            new Dlg(this).setTitle(R.string.vk_dead).setMessage(R.string.vk_dead_msg).setItems(names, (d, which) -> {
                Remote.setDeadDays(this, opts[which]);
                render();
            }).show();
        }));
        Ui.group(this, adv);
        content.addView(adv);
    }

    private static final int REQ_SMS = 91;

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_SMS && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showSmsCommand();
        }
    }

    private void showSmsCommand() {
        final String cmd = Remote.smsEnable(this);
        new Dlg(this).setTitle(R.string.vk_sms_title).setMessage(getString(R.string.vk_sms_cmd, cmd))
                .setPositiveButton(R.string.ai_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("wipe", cmd));
                    render();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> render()).show();
    }

    private void itemMenu(final Vault.Item it) {
        final String[] labels = {getString(R.string.v_open), getString(R.string.v_restore), getString(R.string.v_destroy)};
        final int[] icons = {R.drawable.ic_open, R.drawable.ic_download, R.drawable.ic_delete};
        new Dlg(this).setTitle(it.name).setItems(labels, icons, (d, which) -> {
            if (which == 0) openItem(it);
            else if (which == 1) restoreItem(it);
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
}
