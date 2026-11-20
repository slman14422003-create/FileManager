package com.fileman.app;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;

/** The door of the vault: creates the vault password the first time, and asks for it (or the fingerprint) every time after. */
final class VaultGate {
    private VaultGate() {
    }

    static EditText passwordField(Activity a, int hint) {
        EditText e = Ui.edit(a, a.getString(hint), null);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }

    /** Opens the vault: warns on a rooted phone, refuses while something is hooking into the app. */
    static void ask(final Activity a, final Runnable ok, final Runnable cancel) {
        if (VaultKey.isUnlocked()) {
            ok.run();
            return;
        }
        if (Hostile.hooking()) {
            Toast.makeText(a, R.string.vk_hooked, Toast.LENGTH_LONG).show();
            if (cancel != null) cancel.run();
            return;
        }
        if (Hostile.rooted(a) && !a.getSharedPreferences("vault_ui", 0).getBoolean("root_ok", false)) {
            new Dlg(a).setTitle(R.string.vk_root_title).setMessage(R.string.vk_root_msg)
                    .setPositiveButton(R.string.vk_continue, (d, w) -> {
                        a.getSharedPreferences("vault_ui", 0).edit().putBoolean("root_ok", true).apply();
                        next(a, ok, cancel);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> {
                        if (cancel != null) cancel.run();
                    }).show();
            return;
        }
        next(a, ok, cancel);
    }

    private static void next(final Activity a, final Runnable ok, final Runnable cancel) {
        if (!VaultKey.isSetup(a)) setup(a, ok, cancel);
        else if (VaultKey.bioReady(a)) {
            VaultKey.bioUnlock(a, () -> {
                Vault.resetFailures(a);
                ok.run();
            }, () -> unlock(a, ok, cancel));
        } else unlock(a, ok, cancel);
    }

    /** Asks twice for a new password and applies the strength rules; delivers the chars (wipe them after use) or null. */
    static void newPassword(final Activity a, int title, final java.util.function.Consumer<char[]> done,
                            final Runnable cancel, boolean showNote) {
        LinearLayout box = Ui.box(a);
        final EditText p1 = passwordField(a, R.string.vk_new);
        final EditText p2 = passwordField(a, R.string.vk_repeat);
        box.addView(p1);
        box.addView(p2);
        TextView note = new TextView(a);
        note.setText(showNote ? R.string.vk_setup_note : R.string.vk_rules);
        note.setTextSize(12.5f);
        note.setTextColor(Ui.color(a, R.color.text_secondary));
        box.addView(note);
        final Runnable retry = () -> newPassword(a, title, done, cancel, showNote);
        new Dlg(a).setTitle(title).setView(box)
                .setPositiveButton(R.string.vk_create, (d, w) -> {
                    char[] x = Pass.take(p1);
                    char[] y = Pass.take(p2);
                    int why = Pass.weak(x);
                    if (why != 0) {
                        Toast.makeText(a, why, Toast.LENGTH_SHORT).show();
                        Pass.wipe(x);
                        Pass.wipe(y);
                        retry.run();
                    } else if (!Arrays.equals(x, y)) {
                        Toast.makeText(a, R.string.vk_mismatch, Toast.LENGTH_SHORT).show();
                        Pass.wipe(x);
                        Pass.wipe(y);
                        retry.run();
                    } else {
                        Pass.wipe(y);
                        done.accept(x);
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    if (cancel != null) cancel.run();
                })
                .show();
    }

    private static void setup(final Activity a, final Runnable ok, final Runnable cancel) {
        newPassword(a, R.string.vk_setup_title, pw -> {
            boolean made = VaultKey.setup(a, pw);
            Pass.wipe(pw);
            if (made) {
                ok.run();
            } else {
                Toast.makeText(a, R.string.v_open_failed, Toast.LENGTH_SHORT).show();
                if (cancel != null) cancel.run();
            }
        }, cancel, true);
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
                    char[] pw = Pass.take(p);
                    try {
                        if (VaultKey.panicSet(a) && VaultKey.isPanic(a, pw)) {
                            // duress password: erase everything quietly and open an empty, normal-looking vault
                            Vault.destroyAll(a, "duress password");
                            VaultKey.setup(a, pw);
                            ok.run();
                            return;
                        }
                        if (VaultKey.unlock(a, pw)) {
                            Vault.resetFailures(a);
                            ok.run();
                            return;
                        }
                    } finally {
                        Pass.wipe(pw);
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
        forgot.setOnClickListener(v -> new Dlg(a).setTitle(R.string.vk_forgot).setMessage(R.string.vk_forgot_msg)
                .setPositiveButton(R.string.v_destroy, (d, w) -> {
                    Vault.destroyAll(a, "forgot");
                    VaultKey.reset(a);
                    Toast.makeText(a, R.string.v_destroyed, Toast.LENGTH_SHORT).show();
                    if (cancel != null) cancel.run();
                })
                .setNegativeButton(R.string.cancel, null).show());
        dlg.show();
    }

    /** Ends the open session: the master key is wiped and every decrypted copy is shredded. */
    static void close(final android.content.Context c) {
        VaultKey.lock();
        final android.content.Context app = c.getApplicationContext();
        Bg.single("fm-vault-close").execute(() -> Vault.clearTemps(app));
    }
}
