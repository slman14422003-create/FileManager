package com.fileman.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

final class Motion {
    /** Set on the intent of a tab switch: the screens cross-fade instead of sliding. */
    static final String EXTRA_FADE = "fm_fade";
    static final long DUR = 300L;
    static final long DUR_SHORT = 220L;
    static final Interpolator OUT = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    static final Interpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    private Motion() {
    }

    /** Slides and fades a list in when the folder changes: from the side you came from, mirrored in right-to-left layouts. */
    static void pageIn(View v, boolean forward) {
        if (v == null) return;
        v.animate().cancel();
        if (!Fx.motion(v.getContext())) {
            v.setAlpha(1f);
            v.setTranslationX(0f);
            return;
        }
        boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float dx = v.getResources().getDisplayMetrics().density * 28f * (forward ? 1f : -1f) * (rtl ? -1f : 1f);
        v.setAlpha(0f);
        v.setTranslationX(dx);
        v.animate().alpha(1f).translationX(0f).setDuration(DUR_SHORT).setInterpolator(OUT).withLayer().start();
    }

    /** Staggered entrance for the first rows of a freshly opened screen (the container with id content). */
    static void enterScreen(Activity a) {
        try {
            View c = a.findViewById(R.id.content);
            if (!(c instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) c;
            int n = Math.min(g.getChildCount(), 8);
            for (int i = 0; i < n; i++) Ui.enter(g.getChildAt(i), i);
        } catch (Throwable ignored) {
        }
    }

    static void install(Activity a) {
        if (Build.VERSION.SDK_INT < 34) return;
        if ((a.getIntent().getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) return;
        if (a.getIntent().getBooleanExtra(EXTRA_FADE, false)) return;
        TypedArray t = a.getTheme().obtainStyledAttributes(new int[]{android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent});
        boolean skip = t.getBoolean(0, false) || t.getBoolean(1, false);
        t.recycle();
        if (skip) return;
        a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.act_open_enter, R.anim.act_open_exit);
        a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.act_close_enter, R.anim.act_close_exit);
    }
}
