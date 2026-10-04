package com.fileman.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bottom navigation (thumb reach): Home, Files, Search, Settings. Added to the screen's root column. */
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
        bar.setBackgroundResource(R.drawable.bg_nav);
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
        int[] icons = {R.drawable.ic_home, R.drawable.ic_folder, R.drawable.ic_search, R.drawable.ic_settings};
        int[] labels = {R.string.nav_home, R.string.nav_files, R.string.nav_search, R.string.nav_settings};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean on = i == selected;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setContentDescription(a.getString(labels[i]));

            ImageView icon = new ImageView(a);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(Ui.color(a, on ? R.color.accent_text : R.color.text_secondary)));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(Ui.dp(a, 100));
            pill.setColor(on ? Ui.color(a, R.color.accent_soft) : 0);
            icon.setBackground(pill);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 60), Ui.dp(a, 32)));

            TextView t = new TextView(a);
            t.setText(labels[i]);
            t.setTextSize(11.5f);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(a, 3), 0, 0);
            t.setTextColor(Ui.color(a, on ? R.color.text_primary : R.color.text_secondary));
            item.addView(t);

            Ui.press(a, item);
            item.setOnClickListener(v -> go(a, idx, selected));
            bar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        ((LinearLayout) root).addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
