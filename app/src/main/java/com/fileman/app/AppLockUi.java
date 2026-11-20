package com.fileman.app;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/** The "App lock" sheet of the settings screen: turn it on or off, change the password, fingerprint, delay. */
final class AppLockUi {
    private AppLockUi() {
    }

    static String status(Activity a) {
        return a.getString(AppLock.enabled(a) ? R.string.lk_on : R.string.lk_off);
    }

    static void open(final Activity a, final Runnable changed) {
        if (!AppLock.enabled(a)) {
            setPassword(a, () -> {
                if (Bio.available(a)) {
                    new Dlg(a).setTitle(R.string.lk_finger_q).setMessage(R.string.lk_finger_msg)
                            .setPositiveButton(R.string.lk_yes, (d, w) -> {
                                AppLock.setBio(a, true);
                                changed.run();
                            })
                            .setNegativeButton(R.string.lk_no, (d, w) -> changed.run()).show();
                } else {
                    changed.run();
                }
            });
            return;
        }
        final boolean bioOn = AppLock.bio(a);
        final boolean canBio = Bio.available(a);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> icons = new java.util.ArrayList<>();
        labels.add(a.getString(R.string.lk_change));
        icons.add(R.drawable.ic_lock);
        if (canBio) {
            labels.add(a.getString(bioOn ? R.string.lk_bio_off : R.string.lk_bio_on));
            icons.add(R.drawable.ic_shield);
        }
        labels.add(a.getString(R.string.lk_delay));
        icons.add(R.drawable.ic_timer);
        labels.add(a.getString(R.string.lk_disable));
        icons.add(R.drawable.ic_delete);
        final int base = canBio ? 1 : 0;
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        new Dlg(a).setTitle(R.string.lk_row).setItems(labels.toArray(new CharSequence[0]), ic, (d, which) -> {
            if (which == 0) {
                verifyThen(a, () -> setPassword(a, changed));
            } else if (canBio && which == 1) {
                AppLock.setBio(a, !bioOn);
                changed.run();
            } else if (which == 1 + base) {
                delay(a, changed);
            } else {
                verifyThen(a, () -> {
                    AppLock.disable(a);
                    changed.run();
                });
            }
        }).show();
    }

    private static void verifyThen(final Activity a, final Runnable ok) {
        LinearLayout box = Ui.box(a);
        final EditText p = VaultGate.passwordField(a, R.string.lk_current);
        box.addView(p);
        new Dlg(a).setTitle(R.string.lk_row).setView(box)
                .setPositiveButton(R.string.vk_open, (d, w) -> {
                    if (AppLock.verify(a, p.getText().toString().toCharArray())) ok.run();
                    else Toast.makeText(a, R.string.lk_wrong, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private static void setPassword(final Activity a, final Runnable done) {
        LinearLayout box = Ui.box(a);
        final EditText p1 = VaultGate.passwordField(a, R.string.vk_new);
        final EditText p2 = VaultGate.passwordField(a, R.string.vk_repeat);
        box.addView(p1);
        box.addView(p2);
        new Dlg(a).setTitle(R.string.lk_set).setView(box)
                .setPositiveButton(R.string.vk_create, (d, w) -> {
                    String x = p1.getText().toString();
                    if (x.length() < 4) {
                        Toast.makeText(a, R.string.vk_short, Toast.LENGTH_SHORT).show();
                        setPassword(a, done);
                    } else if (!x.equals(p2.getText().toString())) {
                        Toast.makeText(a, R.string.vk_mismatch, Toast.LENGTH_SHORT).show();
                        setPassword(a, done);
                    } else {
                        AppLock.setPassword(a, x.toCharArray());
                        done.run();
                    }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private static void delay(final Activity a, final Runnable changed) {
        final int[] secs = {0, 30, 60, 300};
        CharSequence[] names = {a.getString(R.string.lk_d0), a.getString(R.string.lk_d30), a.getString(R.string.lk_d60),
                a.getString(R.string.lk_d300)};
        new Dlg(a).setTitle(R.string.lk_delay).setItems(names, (d, which) -> {
            AppLock.setTimeoutSec(a, secs[which]);
            changed.run();
        }).show();
    }
}
