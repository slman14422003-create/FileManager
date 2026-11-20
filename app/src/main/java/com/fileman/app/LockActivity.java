package com.fileman.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The screen that stands in front of the whole app while the app lock is on. */
public class LockActivity extends BaseActivity {
    private EditText pass;
    private TextView error;
    private int wrong;

    static void show(android.app.Activity from) {
        Intent i = new Intent(from, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Throwable ignored) {
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 72), Ui.dp(this, 28), Ui.dp(this, 24));
        root.addView(Ui.hero(this, getString(R.string.lk_title), getString(R.string.lk_sub)));

        pass = VaultGate.passwordField(this, R.string.vk_password);
        pass.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pass.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = Ui.dp(this, 32);
        root.addView(pass, pp);
        pass.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) {
                check();
                return true;
            }
            return false;
        });

        error = new TextView(this);
        error.setTextColor(Ui.color(this, R.color.bad));
        error.setTextSize(13);
        error.setGravity(Gravity.CENTER);
        root.addView(error, new LinearLayout.LayoutParams(-1, -2));

        Button go = Ui.button(this, R.string.lk_unlock, true);
        go.setOnClickListener(v -> check());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = Ui.dp(this, 8);
        root.addView(go, gp);

        if (AppLock.bio(this)) {
            Button fp = Ui.button(this, R.string.lk_finger, false);
            fp.setOnClickListener(v -> finger());
            LinearLayout.LayoutParams fpp = new LinearLayout.LayoutParams(-1, -2);
            fpp.topMargin = Ui.dp(this, 10);
            root.addView(fp, fpp);
        }
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AppLock.locked(this)) {
            finish();
            return;
        }
        if (AppLock.bio(this) && wrong == 0) pass.post(this::finger);
    }

    private void finger() {
        Bio.confirm(this, getString(R.string.lk_title), getString(R.string.cancel), new Bio.Result() {
            @Override
            public void ok(javax.crypto.Cipher cipher) {
                done();
            }

            @Override
            public void fail() {
            }
        });
    }

    private void check() {
        long wait = AppLock.waitMs(this);
        if (wait > 0) {
            error.setText(getString(R.string.lk_wait_s, (wait + 999) / 1000));
            pass.getText().clear();
            return;
        }
        char[] s = Pass.take(pass);
        boolean good = s.length > 0 && AppLock.verify(this, s);
        Pass.wipe(s);
        if (good) {
            done();
            return;
        }
        wrong++;
        AppLock.failed(this);
        error.setText(R.string.lk_wrong);
        Fx.tick(pass);
        pass.animate().translationX(Ui.dp(this, 10)).setDuration(60).withEndAction(
                () -> pass.animate().translationX(0).setDuration(120).start()).start();
        long w2 = AppLock.waitMs(this);
        if (w2 > 0) error.setText(getString(R.string.lk_wait_s, (w2 + 999) / 1000));
    }

    private void done() {
        AppLock.markUnlocked(this);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void syncBack() {
    }
}
