package com.fileman.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ThemeActivity extends BaseActivity {
    private static final int COLUMNS = 4;

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
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

        content.addView(Ui.sectionTitle(this, getString(R.string.theme_mode)));
        final HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4));
        View selected = null;
        for (String id : Appearance.MODES) {
            View tile = modeTile(id, mode, pal);
            if (id.equals(mode)) selected = tile;
            tiles.addView(tile);
        }
        scroll.addView(tiles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (selected != null) {
            final View target = selected;
            scroll.post(() -> scroll.scrollTo(Math.max(0, target.getLeft() - Ui.dp(this, 40)), 0));
        }
        content.addView(Ui.body(this, getString(Appearance.AUTO.equals(mode) ? R.string.theme_auto_note
                : R.string.theme_mode_note), 13, R.color.text_hint));

        content.addView(Ui.sectionTitle(this, getString(R.string.theme_colors)));
        content.addView(paletteGrid(mode, pal));
        content.addView(Ui.body(this, getString(R.string.theme_colors_note), 13, R.color.text_hint));
    }

    private View modeTile(final String id, String current, int pal) {
        boolean selected = id.equals(current);
        ThemePreview.Colors first;
        ThemePreview.Colors second = null;
        if (Appearance.AUTO.equals(id)) {
            first = ThemePreview.Colors.of(Appearance.themed(this, Appearance.LIGHT, pal));
            second = ThemePreview.Colors.of(Appearance.themed(this, Appearance.DARK, pal));
        } else {
            first = ThemePreview.Colors.of(Appearance.themed(this, id, pal));
        }

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(this, 96),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginEnd(Ui.dp(this, 10));
        tile.setLayoutParams(tlp);

        FrameLayout frame = new FrameLayout(this);
        int pad = Ui.dp(this, 3);
        frame.setPadding(pad, pad, pad, pad);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(Ui.dp(this, 19));
        ring.setColor(0);
        ring.setStroke(Ui.dp(this, selected ? 2 : 1), Ui.color(this, selected ? R.color.accent : R.color.stroke));
        frame.setBackground(ring);
        frame.addView(new ThemePreview(this, first, second),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 132)));
        tile.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout label = new LinearLayout(this);
        label.setGravity(Gravity.CENTER);
        label.setOrientation(LinearLayout.HORIZONTAL);
        label.setPadding(0, Ui.dp(this, 8), 0, 0);
        if (selected) {
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check_circle);
            ck.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
            label.addView(ck, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        }
        TextView t = new TextView(this);
        t.setText(Appearance.modeName(id));
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setTextColor(Ui.color(this, selected ? R.color.accent_text : R.color.text_primary));
        if (selected) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(this, selected ? 4 : 0), 0, 0, 0);
        label.addView(t);
        tile.addView(label);

        Ui.press(this, tile);
        tile.setOnClickListener(v -> {
            if (id.equals(Store.themeMode(this))) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            boolean before = Appearance.nightFor(Store.themeMode(this));
            Store.setThemeMode(this, id);
            Appearance.applyMode(this);
            if (before == Appearance.nightFor(id)) Appearance.recreateAll();
            else render();
        });
        return tile;
    }

    private View paletteGrid(String mode, int selectedPal) {
        String swatchMode = Appearance.AUTO.equals(mode)
                ? (Appearance.isNight(this) ? Appearance.DARK : Appearance.LIGHT) : mode;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        LinearLayout row = null;
        int n = Appearance.PALETTES.length;
        for (int i = 0; i < n; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(swatch(i, i == selectedPal, swatchMode));
        }
        int rest = (COLUMNS - n % COLUMNS) % COLUMNS;
        for (int i = 0; i < rest && row != null; i++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        return grid;
    }

    private View swatch(final int index, boolean selected, String swatchMode) {
        ThemePreview.Colors k = ThemePreview.Colors.of(Appearance.themed(this, swatchMode, index));
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout ringBox = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0);
        ring.setStroke(Ui.dp(this, selected ? 2 : 1), selected ? k.accent : Ui.color(this, R.color.stroke_soft));
        ringBox.setBackground(ring);

        View dot = new View(this);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{k.b1, k.b2, k.b3});
        fill.setShape(GradientDrawable.OVAL);
        dot.setBackground(fill);
        int gap = Ui.dp(this, 5);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        dotLp.setMargins(gap, gap, gap, gap);
        ringBox.addView(dot, dotLp);
        if (selected) {
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check);
            ck.setImageTintList(ColorStateList.valueOf(k.onAccent));
            int ip = Ui.dp(this, 15);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            cp.setMargins(ip, ip, ip, ip);
            ringBox.addView(ck, cp);
        }
        cell.addView(ringBox, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        TextView name = new TextView(this);
        name.setText(Appearance.PALETTES[index].name);
        name.setTextSize(12);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        name.setTextColor(Ui.color(this, selected ? R.color.text_primary : R.color.text_secondary));
        if (selected) name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = Ui.dp(this, 6);
        cell.addView(name, np);

        Ui.press(this, cell);
        cell.setOnClickListener(v -> {
            if (Appearance.PALETTES[index].id.equals(Store.palette(this))) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            Store.setPalette(this, Appearance.PALETTES[index].id);
            Appearance.recreateAll();
        });
        return cell;
    }
}
