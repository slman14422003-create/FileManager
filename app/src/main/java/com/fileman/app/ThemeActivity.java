package com.fileman.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings > Appearance: day / night / auto with live previews, and the colour themes. */
public class ThemeActivity extends BaseActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        ((TextView) findViewById(R.id.title)).setText(R.string.theme_title);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.theme_sub);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        content = findViewById(R.id.content);
        render();
    }

    private void render() {
        content.removeAllViews();
        final String mode = Store.themeMode(this);
        final int pal = Appearance.paletteIndex(Store.palette(this));

        // ---- mode tiles (each one drawn with its own real colours)
        content.addView(Ui.sectionTitle(this, getString(R.string.theme_mode)));
        ThemePreview.Colors day = ThemePreview.Colors.of(Appearance.themed(this, false, pal));
        ThemePreview.Colors night = ThemePreview.Colors.of(Appearance.themed(this, true, pal));
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        tiles.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        tiles.addView(modeTile(Appearance.LIGHT, ThemePreview.MODE_LIGHT, day, night, mode, R.string.theme_light));
        tiles.addView(modeTile(Appearance.DARK, ThemePreview.MODE_DARK, day, night, mode, R.string.theme_dark));
        tiles.addView(modeTile(Appearance.AUTO, ThemePreview.MODE_AUTO, day, night, mode, R.string.theme_auto));
        content.addView(tiles);
        content.addView(Ui.body(this, getString(Appearance.AUTO.equals(mode) ? R.string.theme_auto_note
                : R.string.theme_mode_note), 13, R.color.text_hint));

        // ---- colour themes
        content.addView(Ui.sectionTitle(this, getString(R.string.theme_colors)));
        final boolean nightNow = Appearance.isNight(this);
        LinearLayout row = null;
        for (int i = 0; i < Appearance.PALETTES.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
                content.addView(row);
            }
            row.addView(swatch(i, i == pal, nightNow));
        }
        // keep the last row's cards the same width as the others
        for (int k = Appearance.PALETTES.length % 3; row != null && k != 0 && k < 3; k++) {
            View sp = new View(this);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);
            slp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            row.addView(sp, slp);
        }
        content.addView(Ui.body(this, getString(R.string.theme_colors_note), 13, R.color.text_hint));
    }

    private View modeTile(final String id, int previewMode, ThemePreview.Colors day, ThemePreview.Colors night,
                          String current, int nameRes) {
        boolean selected = id.equals(current);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        tile.setLayoutParams(tlp);

        FrameLayout frame = new FrameLayout(this);
        int pad = Ui.dp(this, 3);
        frame.setPadding(pad, pad, pad, pad);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(Ui.dp(this, 20));
        ring.setColor(0);
        ring.setStroke(Ui.dp(this, selected ? 2 : 1), Ui.color(this, selected ? R.color.accent : R.color.stroke));
        frame.setBackground(ring);
        frame.addView(new ThemePreview(this, day, night, previewMode),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150)));
        tile.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout label = new LinearLayout(this);
        label.setGravity(Gravity.CENTER);
        label.setOrientation(LinearLayout.HORIZONTAL);
        label.setPadding(0, Ui.dp(this, 8), 0, 0);
        if (selected) {
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check_circle);
            ck.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
            label.addView(ck, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        }
        TextView t = new TextView(this);
        t.setText(nameRes);
        t.setTextSize(14);
        t.setTextColor(Ui.color(this, selected ? R.color.accent_text : R.color.text_primary));
        if (selected) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(this, selected ? 5 : 0), 0, 0, 0);
        label.addView(t);
        tile.addView(label);

        Ui.press(this, tile);
        tile.setOnClickListener(v -> {
            if (id.equals(Store.themeMode(this))) return;
            Store.setThemeMode(this, id);
            Appearance.applyMode(this);   // AppCompat recreates the open screens when the look really changes
            render();
        });
        return tile;
    }

    private View swatch(final int index, boolean selected, boolean nightNow) {
        Appearance.Palette p = Appearance.PALETTES[index];
        Context pc = Appearance.themed(this, nightNow, index);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        card.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 20));
        bg.setColor(Ui.color(this, R.color.surface));
        bg.setStroke(Ui.dp(this, selected ? 2 : 1), Ui.color(this, selected ? R.color.accent : R.color.stroke_soft));
        card.setBackground(bg);

        FrameLayout dot = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                Ui.color(pc, R.color.brand_start), Ui.color(pc, R.color.brand_mid), Ui.color(pc, R.color.brand_end)});
        g.setShape(GradientDrawable.OVAL);
        dot.setBackground(g);
        if (selected) {
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check);
            ck.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            ck.setScaleType(ImageView.ScaleType.CENTER);
            dot.addView(ck, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        card.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        TextView t = new TextView(this);
        t.setText(p.name);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        t.setTextColor(Ui.color(this, selected ? R.color.accent_text : R.color.text_primary));
        if (selected) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(t);

        Ui.press(this, card);
        final String id = p.id;
        card.setOnClickListener(v -> {
            if (id.equals(Store.palette(this))) return;
            Store.setPalette(this, id);
            // AMOLED black only exists in night mode, so picking it from day mode switches to night
            if ("amoled".equals(id) && Appearance.LIGHT.equals(Store.themeMode(this))) {
                Store.setThemeMode(this, Appearance.DARK);
                Appearance.applyMode(this);
            }
            Appearance.recreateAll();
        });
        return card;
    }
}
