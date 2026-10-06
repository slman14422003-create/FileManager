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
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_nav_float);
        bar.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        int[] icons = {R.drawable.ic_home, R.drawable.ic_folder, R.drawable.ic_search, R.drawable.ic_settings};
        int[] labels = {R.string.nav_home, R.string.nav_files, R.string.nav_search, R.string.nav_settings};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean on = i == selected;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(a.getString(labels[i]));
            if (on) item.setBackgroundResource(R.drawable.bg_nav_active);
            item.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, on ? 14 : 8), 0);

            ImageView icon = new ImageView(a);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(Ui.color(a, on ? R.color.on_accent : R.color.text_secondary)));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28)));

            if (on) {   // only the current tab shows its name: a calmer, more modern bar
                TextView t = new TextView(a);
                t.setText(labels[i]);
                t.setTextSize(13.5f);
                t.setSingleLine(true);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                t.setTextColor(Ui.color(a, R.color.on_accent));
                t.setPadding(Ui.dp(a, 6), 0, 0, 0);
                item.addView(t);
            }

            Ui.press(a, item);
            item.setOnClickListener(v -> go(a, idx, selected));
            bar.addView(item, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), on ? 2.1f : 1f));
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 12));
        ((LinearLayout) root).addView(bar, blp);
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
