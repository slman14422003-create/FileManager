package com.fileman.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * One place that puts a screen into true full screen: status bar and navigation bar hidden, content under the
 * camera cut-out, and a swipe from the edge shows the bars for a moment only. Works from API 24 to the newest
 * release: the compat controller maps to the system UI flags below API 30 and to WindowInsetsController above.
 */
final class Immersive {
    private Immersive() {
    }

    static void apply(Activity a) {
        if (a == null) return;
        try {
            Window w = a.getWindow();
            if (w == null) return;
            WindowCompat.setDecorFitsSystemWindows(w, false);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setStatusBarContrastEnforced(false);
                w.setNavigationBarContrastEnforced(false);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                int mode = Build.VERSION.SDK_INT >= 30
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                if (lp.layoutInDisplayCutoutMode != mode) {
                    lp.layoutInDisplayCutoutMode = mode;
                    w.setAttributes(lp);
                }
            }
            hide(a);
        } catch (Throwable ignored) {
        }
    }

    /** Hides both bars again; safe to call as often as needed (focus change, resume, after a dialog). */
    static void hide(Activity a) {
        try {
            Window w = a.getWindow();
            View decor = w.getDecorView();
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, decor);
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } catch (Throwable ignored) {
        }
    }

    /** Re-applies the hidden state after the window gets focus or the bars were brought back by the system. */
    static void keep(final Activity a) {
        if (a == null) return;
        final View decor = a.getWindow().getDecorView();
        decor.post(() -> {
            if (!a.isFinishing() && !a.isDestroyed()) hide(a);
        });
        if (Build.VERSION.SDK_INT < 30) {
            decor.setOnSystemUiVisibilityChangeListener(v -> {
                if ((v & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0 || (v & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decor.postDelayed(() -> {
                        if (!a.isFinishing() && !a.isDestroyed()) hide(a);
                    }, 1800);
                }
            });
        }
    }
}
