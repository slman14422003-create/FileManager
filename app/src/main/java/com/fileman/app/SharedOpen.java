package com.fileman.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.os.Bundle;
import android.view.View;

import java.lang.ref.WeakReference;

/** Shared-element hop: the tapped icon or thumbnail grows into the viewer's header icon. */
final class SharedOpen {
    static final String NAME = "fm_icon";
    private static WeakReference<View> armed;
    private static WeakReference<View> named;

    private SharedOpen() {
    }

    /** Remember the view whose picture should expand into the next viewer that opens. */
    static void arm(View v) {
        armed = v == null ? null : new WeakReference<>(v);
    }

    /** Options for the next startActivity, or null (no motion, no view, or anything unusual). */
    static Bundle take(Activity a) {
        View v = armed == null ? null : armed.get();
        armed = null;
        try {
            if (v == null || !Fx.motion(a) || !v.isShown() || v.getWidth() == 0) return null;
            clear();
            v.setTransitionName(NAME);
            named = new WeakReference<>(v);
            return ActivityOptions.makeSceneTransitionAnimation(a, v, NAME).toBundle();
        } catch (Throwable t) {
            return null;
        }
    }

    static void clear() {
        View v = named == null ? null : named.get();
        named = null;
        if (v != null) v.setTransitionName(null);
    }
}
