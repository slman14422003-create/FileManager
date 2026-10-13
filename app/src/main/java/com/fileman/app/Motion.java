package com.fileman.app;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** The app's single motion language (iOS curves): every code-driven animation takes its curve from here. */
final class Motion {
    private Motion() {
    }

    static final long DUR = 320, DUR_SHORT = 200;
    /** Things arriving / settling (iOS push and sheet curve). */
    static final Interpolator OUT = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    /** Things leaving or fading. */
    static final Interpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
}
