package com.fileman.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.Build;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

final class Motion {
    static final long DUR = 380L;
    static final long DUR_SHORT = 220L;
    static final Interpolator OUT = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    static final Interpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    private Motion() {
    }

    static void install(Activity a) {
        if (Build.VERSION.SDK_INT < 34) return;
        if ((a.getIntent().getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) return;
        TypedArray t = a.getTheme().obtainStyledAttributes(new int[]{android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent});
        boolean skip = t.getBoolean(0, false) || t.getBoolean(1, false);
        t.recycle();
        if (skip) return;
        a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.act_open_enter, R.anim.act_open_exit);
        a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.act_close_enter, R.anim.act_close_exit);
    }
}
