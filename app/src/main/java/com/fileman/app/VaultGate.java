package com.fileman.app;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The door of the vault: creates the vault password the first time, and asks for it (or the fingerprint) every time after. */
final class VaultGate {
    private VaultGate() {
    }

    static EditText passwordField(Activity a, int hint) {
        EditText e = Ui.edit(a, a.getString(hint), null);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    static void ask(final Activity a, final Runnable ok, final Runnable cancel) {
        if (VaultKey.isUnlocked()) {
            ok.run();
            return;
        }
        if (!VaultKey.isSetup(a)) setup(a, ok, cancel);
        else unlock(a, ok, cancel);
    }

    private static void setup(final Activity a, final Runnable ok, final Runnable cancel) {
        LinearLayout box = Ui.box(a);
        final EditText p1 = passwordField(a, R.string.vk_new);
        final EditText p2 = passwordField(a, R.string.vk_repeat);
        box.addView(p1);
        box.addView(p2);
        TextView note = new TextView(a);
        note.setText(R.string.vk_setup_note);
        note.setTextSize(12.5f);
        note.setTextColor(Ui.color(a, R.color.text_secondary));
        box.addView(note);
        new Dlg(a).setTitle(R.string.vk_setup_title).setView(box)
                .setPositiveButton(R.string.vk_create, (d, w) -> {
                    String x = p1.getText().toString();
                    if (x.length() < 4) {
                        Toast.makeText(a, R.string.vk_short, Toast.LENGTH_SHORT).show();
                        setup(a, ok, cancel);
                    } else if (!x.equals(p2.getText().toString())) {
                        Toast.makeText(a, R.string.vk_mismatch, Toast.LENGTH_SHORT).show();
                        setup(a, ok, cancel);
                    } else if (VaultKey.setup(a, x.toCharArray())) {
                        ok.run();
                    } else {
                        Toast.makeText(a, R.string.v_open_failed, Toast.LENGTH_SHORT).show();
                        if (cancel != null) cancel.run();
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    if (cancel != null) cancel.run();
                })
                .show();
    }

    private static void unlock(final Activity a, final Runnable ok, final Runnable cancel) {
        LinearLayout box = Ui.box(a);
        final EditText p = passwordField(a, R.string.vk_password);
        box.addView(p);
        TextView forgot = new TextView(a);
        forgot.setText(R.string.vk_forgot);
        forgot.setTextSize(12.5f);
        forgot.setTextColor(Ui.color(a, R.color.accent_text));
        forgot.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        box.addView(forgot);
        Dlg dlg = new Dlg(a).setTitle(R.string.v_unlock_title).setView(box)
                .setPositiveButton(R.string.vk_open, (d, w) -> {
                    if (VaultKey.unlock(a, p.getText().toString().toCharArray())) {
                        Vault.resetFailures(a);
                        ok.run();
                        return;
                    }
                    if (Vault.isEmpty(a)) {
                        Toast.makeText(a, R.string.vk_wrong, Toast.LENGTH_SHORT).show();
                        unlock(a, ok, cancel);
                        return;
                    }
                    int left = Vault.recordFailure(a);
                    if (left <= 0) {
                        Toast.makeText(a, R.string.v_wiped_toast, Toast.LENGTH_LONG).show();
                        if (cancel != null) cancel.run();
                    } else {
                        Toast.makeText(a, a.getString(R.string.v_attempts_left, left), Toast.LENGTH_SHORT).show();
                        unlock(a, ok, cancel);
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    if (cancel != null) cancel.run();
                });
        if (VaultKey.bioReady(a)) {
            dlg.setNeutralButton(R.string.vk_finger, (d, w) -> VaultKey.bioUnlock(a, () -> {
                Vault.resetFailures(a);
                ok.run();
            }, () -> unlock(a, ok, cancel)));
        }
        final Dlg shown = dlg;
        forgot.setOnClickListener(v -> new Dlg(a).setTitle(R.string.vk_forgot).setMessage(R.string.vk_forgot_msg)
                .setPositiveButton(R.string.v_destroy, (d, w) -> {
                    Vault.destroyAll(a, "forgot");
                    VaultKey.reset(a);
                    Toast.makeText(a, R.string.v_destroyed, Toast.LENGTH_SHORT).show();
                    if (cancel != null) cancel.run();
                })
                .setNegativeButton(R.string.cancel, null).show());
        shown.show();
    }

    /** Ends the open session: the master key is wiped and every decrypted copy is shredded. */
    static void close(final android.content.Context c) {
        VaultKey.lock();
        final android.content.Context app = c.getApplicationContext();
        Bg.single("fm-vault-close").execute(() -> Vault.clearTemps(app));
    }
}
