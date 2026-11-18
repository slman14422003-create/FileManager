package com.fileman.app;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The screen title scrolls away with the list like any other row; nothing is pinned or faded over the content. Only a
 * solid strip the height of the status bar keeps rows from scrolling under the clock and battery icons.
 */
final class CollapseTitle {
    private final Activity a;
    private final View strip;

    CollapseTitle(Activity a, ListView list, TextView large) {
        this.a = a;
        strip = new View(a);
        strip.setClickable(false);
        strip.setFocusable(false);
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        strip.setBackgroundColor(Ui.color(a, R.color.bg));
        ViewGroup root = a.findViewById(android.R.id.content);
        if (root instanceof FrameLayout) {
            ((FrameLayout) root).addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        }
    }

    private int top() {
        try {
            WindowInsetsCompat i = ViewCompat.getRootWindowInsets(a.getWindow().getDecorView());
            if (i != null) return i.getInsets(WindowInsetsCompat.Type.systemBars()).top;
        } catch (Throwable ignored) {
        }
        int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? a.getResources().getDimensionPixelSize(id) : Ui.dp(a, 24);
    }

    /** The window already pads the screen below the status bar on Android 15+, so the strip is only for unpadded screens. */
    private boolean needed() {
        ViewGroup root = a.findViewById(android.R.id.content);
        return root != null && root.getPaddingTop() < top() / 2 && android.os.Build.VERSION.SDK_INT >= 35;
    }

    /** Call from the list's scroll listener. */
    void onScroll(int first) {
        if (strip.getParent() == null) return;
        int h = needed() ? top() : 0;
        ViewGroup.LayoutParams lp = strip.getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            strip.setLayoutParams(lp);
        }
    }
}
