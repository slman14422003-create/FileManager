package com.fileman.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Floating iOS-style tab bar (thumb reach): Home, Files, Search, Settings. Added to the screen's root column. */
final class NavBar {
    static final int HOME = 0, FILES = 1, SEARCH = 2, SETTINGS = 3;

    private NavBar() {
    }

    static View attach(final Activity a, final int selected) {
        ViewGroup content = a.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        if (!(root instanceof LinearLayout)) return null;

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_tabbar_float);   // floating capsule
        bar.setElevation(Ui.dp(a, 10));
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        int[] icons = {R.drawable.ic_home, R.drawable.ic_folder, R.drawable.ic_search, R.drawable.ic_settings};
        int[] labels = {R.string.nav_home, R.string.nav_files, R.string.nav_search, R.string.nav_settings};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean on = i == selected;
            int tint = Ui.color(a, on ? R.color.accent : R.color.text_hint);
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(a.getString(labels[i]));
            item.setPadding(0, Ui.dp(a, 5), 0, Ui.dp(a, 4));
            if (on) item.setBackgroundResource(R.drawable.bg_nav_active);

            ImageView icon = new ImageView(a);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(tint));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28)));

            TextView t = new TextView(a);
            t.setText(labels[i]);
            t.setTextSize(10.5f);
            t.setSingleLine(true);
            t.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            t.setTextColor(tint);
            t.setGravity(Gravity.CENTER);
            item.addView(t);

            Ui.press(a, item);
            item.setOnClickListener(v -> go(a, idx, selected));
            bar.addView(item, new LinearLayout.LayoutParams(0, Ui.dp(a, 52), 1f));
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 12));
        ((LinearLayout) root).addView(bar, blp);
        Layout.markNav(bar);
        Layout.refreshNav(a);
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
