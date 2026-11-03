package com.fileman.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import eightbitlab.com.blurview.BlurView;

/** Floating capsule tab bar: Home, Files, Search, Settings. Added to the screen's root column. */
final class NavBar {
    static final int HOME = 0, FILES = 1, SEARCH = 2, SETTINGS = 3;

    private NavBar() {
    }

    static View attach(final Activity a, final int selected) {
        LinearLayout host = Blur.bottomHost(a);
        if (host == null) return null;

        BlurView bar = new BlurView(a);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5));
        bar.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int[] iconRes = {R.drawable.ic_home, R.drawable.ic_folder, R.drawable.ic_search, R.drawable.ic_settings};
        int[] labelRes = {R.string.nav_home, R.string.nav_files, R.string.nav_search, R.string.nav_settings};
        int on = Ui.color(a, R.color.accent);
        int off = Ui.color(a, R.color.text_secondary);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean sel = i == selected;
            int tint = sel ? on : off;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(a.getString(labelRes[i]));
            item.setBackgroundResource(R.drawable.bg_nav_pill);
            item.setSelected(sel);
            android.graphics.drawable.Drawable pill = item.getBackground().mutate();
            pill.setAlpha(sel ? 255 : 0);

            ImageView icon = new ImageView(a);
            icon.setImageResource(iconRes[i]);
            icon.setImageTintList(ColorStateList.valueOf(tint));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));

            TextView t = new TextView(a);
            t.setText(labelRes[i]);
            t.setTextSize(10.5f);
            t.setSingleLine(true);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(tint);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = Ui.dp(a, 2);
            item.addView(t, tp);

            Ui.press(a, item);
            item.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                go(a, idx, selected);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 54), 1f);
            lp.setMargins(Ui.dp(a, 2), 0, Ui.dp(a, 2), 0);
            row.addView(item, lp);
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(a, 20), Ui.dp(a, 6), Ui.dp(a, 20), Ui.dp(a, 10));
        host.addView(bar, blp);
        Layout.markNav(bar);
        Layout.refreshNav(a);
        Blur.glass(a, bar, 100, Blur.ALPHA_CAPSULE, true);
        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(a, 16));
        bar.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(Motion.OUT).start();
        return bar;
    }

    private static void go(Activity a, int idx, int selected) {
        if (idx == selected) return;
        Intent i;
        switch (idx) {
            case HOME:
                i = new Intent(a, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                break;
            case FILES:
                i = new Intent(a, FileManagerActivity.class);
                break;
            case SEARCH:
                i = new Intent(a, FileManagerActivity.class);
                i.putExtra("search", true);
                break;
            default:
                i = new Intent(a, SettingsActivity.class);
                break;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        a.startActivity(i);
        if (selected != HOME) {
            a.finish();
            a.overridePendingTransition(0, 0);
        }
    }
}
