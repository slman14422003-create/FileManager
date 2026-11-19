package com.fileman.app;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

/**
 * Reading mode for the viewers: header, floating bar and system bars are hidden so the document fills the screen;
 * one tap on the content brings the header and bar back, another hides them again. System bars stay hidden
 * (a swipe from the edge shows them for a moment).
 */
final class ReaderChrome {
    private final Activity a;
    private final View header;
    private final View[] extras;
    private boolean shown;

    private ReaderChrome(Activity a, View header, View[] extras) {
        this.a = a;
        this.header = header;
        this.extras = extras;
    }

    /** Hides the header (the parent of the back button), the given views and the system bars at once. */
    static ReaderChrome attach(Activity a, View... extras) {
        View back = a.findViewById(R.id.btnBack);
        View header = back == null ? null : (View) back.getParent();
        ReaderChrome c = new ReaderChrome(a, header, extras);
        Immersive.apply(a);
        c.set(false, false);
        return c;
    }

    boolean shown() {
        return shown;
    }

    void toggle() {
        set(!shown, true);
    }

    void show() {
        set(true, true);
    }

    private void set(boolean on, boolean animate) {
        shown = on;
        apply(header, on, animate);
        for (View v : extras) apply(v, on, animate);
        Immersive.hide(a);
    }

    private void apply(final View v, boolean on, boolean animate) {
        if (v == null) return;
        if (on && v instanceof TextView && ((TextView) v).getText().length() == 0) return;
        v.animate().cancel();
        long d = animate ? Fx.dur(a, 180) : 0;
        if (on) {
            v.setVisibility(View.VISIBLE);
            if (d == 0) {
                v.setAlpha(1f);
            } else {
                v.setAlpha(0f);
                v.animate().alpha(1f).setDuration(d).withLayer().start();
            }
        } else if (d == 0) {
            v.setAlpha(0f);
            v.setVisibility(View.GONE);
        } else {
            v.animate().alpha(0f).setDuration(d).withLayer().withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }
}
