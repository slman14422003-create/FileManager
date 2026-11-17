package com.fileman.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The big screen title shrinks and fades while the list scrolls, and a compact title appears in a top bar whose glass
 * background fades in gradually. Only alpha, scale and translation change.
 */
final class CollapseTitle {
    private final Activity a;
    private final ListView list;
    private final TextView large;
    private final FrameLayout bar;
    private final View glass;
    private final View line;
    private final TextView compact;
    private final int[] loc = new int[2];
    private final float barH;
    private float last = -1f;

    CollapseTitle(Activity a, ListView list, TextView large) {
        this.a = a;
        this.list = list;
        this.large = large;
        barH = Ui.dp(a, 48);
        bar = new FrameLayout(a);
        bar.setClickable(false);
        bar.setFocusable(false);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        glass = new View(a);
        glass.setBackgroundColor(Ui.color(a, R.color.bg));
        glass.setAlpha(0f);
        bar.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        line = new View(a);
        line.setBackgroundColor(Ui.color(a, R.color.stroke_soft));
        line.setAlpha(0f);
        bar.addView(line, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM));
        compact = new TextView(a);
        compact.setTextSize(17);
        compact.setTypeface(Typeface.DEFAULT_BOLD);
        compact.setSingleLine(true);
        compact.setEllipsize(android.text.TextUtils.TruncateAt.END);
        compact.setGravity(Gravity.CENTER);
        compact.setTextColor(Ui.color(a, R.color.text_primary));
        compact.setAlpha(0f);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) barH, Gravity.BOTTOM);
        tp.setMarginStart(Ui.dp(a, 72));
        tp.setMarginEnd(Ui.dp(a, 72));
        bar.addView(compact, tp);
        ViewGroup root = a.findViewById(android.R.id.content);
        if (root instanceof FrameLayout) {
            ((FrameLayout) root).addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) barH + top()));
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

    /** Call from the list's scroll listener. */
    void onScroll(int first) {
        if (bar.getParent() == null) return;
        float p;
        if (first > 0 || list.getChildCount() == 0) {
            p = 1f;
        } else {
            large.getLocationOnScreen(loc);
            float edge = top() + barH;
            float mid = loc[1] + large.getHeight() * 0.5f;
            p = Math.max(0f, Math.min(1f, (edge + Ui.dp(a, 18) - mid) / Ui.dp(a, 30)));
        }
        if (Math.abs(p - last) < 0.01f && p != 0f && p != 1f) return;
        last = p;
        int h = (int) barH + top();
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            bar.setLayoutParams(lp);
        }
        if (!android.text.TextUtils.equals(compact.getText(), large.getText())) compact.setText(large.getText());
        glass.setAlpha(p * 0.94f);
        line.setAlpha(p);
        compact.setAlpha(p);
        compact.setTranslationY((1f - p) * Ui.dp(a, 8));
        float s = 1f - 0.3f * p;
        large.setPivotX(large.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? large.getWidth() : 0f);
        large.setPivotY(large.getHeight() * 0.5f);
        large.setScaleX(s);
        large.setScaleY(s);
        large.setAlpha(1f - p);
    }
}
