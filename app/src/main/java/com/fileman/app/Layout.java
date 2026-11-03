package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * The app's layout language: one place that decides how a screen adapts to its window.
 * <ul>
 *   <li><b>Width classes</b> compact / medium / expanded, read from the live window width (so split screen,
 *   rotation and foldables are handled), with matching sizes in the dimens.xml files of each width class.</li>
 *   <li><b>Reading column</b>: on wide windows the content is centred in a column of {@code content_max_width}
 *   instead of stretching edge to edge.</li>
 *   <li><b>Grids</b> derive their column count from the width, never from a fixed number.</li>
 * </ul>
 * See LAYOUT.md for the rules screens should follow.
 */
public final class Layout {
    private Layout() {
    }

    public static final int COMPACT = 0, MEDIUM = 1, EXPANDED = 2;
    private static final String NAV_TAG = "fm_nav";

    /** Width class of the window this context is drawn in. */
    public static int sizeClass(Context c) {
        int w = c.getResources().getConfiguration().screenWidthDp;
        return w < 600 ? COMPACT : w < 840 ? MEDIUM : EXPANDED;
    }

    public static boolean isWide(Context c) {
        return sizeClass(c) != COMPACT;
    }

    /** Space between the screen edge and cards (px). */
    public static int gutter(Context c) {
        return c.getResources().getDimensionPixelSize(R.dimen.gutter);
    }

    /** Width of the page column in px: the window width, capped at the class's reading width. */
    public static int contentWidth(Context c) {
        int win = Math.round(c.getResources().getConfiguration().screenWidthDp
                * c.getResources().getDisplayMetrics().density);
        int max = c.getResources().getDimensionPixelSize(R.dimen.content_max_width);
        return max > 0 ? Math.min(win, max) : win;
    }

    /** Columns for a grid of cells that should not get narrower than {@code tile_min_cell}. */
    public static int columns(Context c, int min, int max) {
        int cell = Math.max(1, c.getResources().getDimensionPixelSize(R.dimen.tile_min_cell));
        int n = (contentWidth(c) - 2 * Ui.dp(c, 10)) / cell;
        return Math.max(min, Math.min(max, n));
    }

    /**
     * Centres the screen's root view in the reading column (or lets it fill the window again when the window
     * is narrow). Safe to call repeatedly, e.g. after a rotation.
     */
    public static void constrain(Activity a, boolean enabled) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        View root = content.getChildAt(0);
        ViewGroup.LayoutParams p = root.getLayoutParams();
        if (!(p instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p;
        int max = a.getResources().getDimensionPixelSize(R.dimen.content_max_width);
        int win = Math.round(a.getResources().getConfiguration().screenWidthDp * a.getResources().getDisplayMetrics().density);
        int w = enabled && max > 0 && win > max ? max : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp.width != w || lp.gravity != Gravity.CENTER_HORIZONTAL) {
            lp.width = w;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            root.setLayoutParams(lp);
        }
        refreshNav(a);
    }

    /** Marks the floating bottom bar so it can be re-sized when the window changes. */
    static void markNav(View bar) {
        bar.setTag(NAV_TAG);
    }

    /** Keeps the bottom bar a comfortable width on big windows. */
    static void refreshNav(Activity a) {
        View bar = a.findViewById(android.R.id.content).findViewWithTag(NAV_TAG);
        if (bar == null || !(bar.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
        int max = a.getResources().getDimensionPixelSize(R.dimen.nav_max_width);
        int avail = Math.round(a.getResources().getConfiguration().screenWidthDp * a.getResources().getDisplayMetrics().density);
        int w = max > 0 && avail - 2 * lp.leftMargin > max ? max : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp.width != w || lp.gravity != Gravity.CENTER_HORIZONTAL) {
            lp.width = w;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            bar.setLayoutParams(lp);
        }
    }
}
