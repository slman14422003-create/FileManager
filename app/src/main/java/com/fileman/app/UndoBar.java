package com.fileman.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;

/** A floating capsule with an Undo action that lives 5 seconds; it springs in and out. */
final class UndoBar {
    static final long LIFE_MS = 5000L;

    private final Activity a;
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout view;
    private Runnable onExpire;
    private boolean live;

    UndoBar(Activity a) {
        this.a = a;
    }

    private final Runnable timeout = () -> finish(true);

    /** Shows the bar. A bar that is already up is committed first (its change becomes final). */
    void show(CharSequence msg, final Runnable onUndo, Runnable onExpire) {
        if (live) finish(true);
        ViewGroup root = a.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout)) {
            if (onExpire != null) onExpire.run();
            return;
        }
        this.onExpire = onExpire;
        live = true;
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_tabbar_float);
        bar.setElevation(Ui.dp(a, 12));
        bar.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4));
        TextView t = new TextView(a);
        t.setText(msg);
        t.setTextColor(Ui.color(a, R.color.text_primary));
        t.setTextSize(14);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView b = new TextView(a);
        b.setText(R.string.undo);
        b.setTextColor(Ui.color(a, R.color.accent));
        b.setTextSize(14);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(Ui.dp(a, 44));
        b.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), 0);
        b.setOnClickListener(v -> {
            if (!live) return;
            Runnable u = onUndo;
            finish(false);
            Fx.light(v);
            if (u != null) u.run();
        });
        Ui.press(a, b);
        bar.addView(b);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(a, 96);
        lp.setMarginStart(Ui.dp(a, 16));
        lp.setMarginEnd(Ui.dp(a, 16));
        ((FrameLayout) root).addView(bar, lp);
        view = bar;
        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(a, 80));
        bar.animate().alpha(1f).setDuration(Fx.dur(a, 160)).start();
        Fx.settle(bar, DynamicAnimation.TRANSLATION_Y, 0f, 0f);
        if (!Fx.motion(a)) {
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
        }
        h.postDelayed(timeout, LIFE_MS);
    }

    /** Ends the bar now. {@code commit}: the change stays (run the expire action); otherwise it was undone. */
    void finish(boolean commit) {
        if (!live) return;
        live = false;
        h.removeCallbacks(timeout);
        final View v = view;
        view = null;
        Runnable ex = onExpire;
        onExpire = null;
        if (v != null) {
            v.animate().alpha(0f).setDuration(Fx.dur(a, 140)).withEndAction(() -> {
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            }).start();
            Fx.settle(v, DynamicAnimation.TRANSLATION_Y, Ui.dp(a, 80), 0f);
            if (!Fx.motion(a) && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        }
        if (commit && ex != null) ex.run();
    }
}
