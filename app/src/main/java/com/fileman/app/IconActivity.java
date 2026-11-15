package com.fileman.app;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Lets the user pick the launcher icon: each choice is an activity-alias with its own icon. */
public class IconActivity extends BaseActivity {
    private static final String[] KEYS = {"Blue", "Violet", "Green", "Orange", "Red", "Pink", "Gold", "Night", "Graphite"};
    private static final int[] NAMES = {R.string.ic_blue, R.string.ic_violet, R.string.ic_green, R.string.ic_orange,
            R.string.ic_red, R.string.ic_pink, R.string.ic_gold, R.string.ic_night, R.string.ic_graphite};
    private static final int[] ICONS = {R.mipmap.ic_launcher_blue, R.mipmap.ic_launcher_violet,
            R.mipmap.ic_launcher_green, R.mipmap.ic_launcher_orange, R.mipmap.ic_launcher_red,
            R.mipmap.ic_launcher_pink, R.mipmap.ic_launcher_gold, R.mipmap.ic_launcher_night,
            R.mipmap.ic_launcher_graphite};
    private static final int COLUMNS = 3;

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.ic_title);
        findViewById(R.id.subtitle).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        content = findViewById(R.id.content);
        render();
    }

    private ComponentName alias(int i) {
        return new ComponentName(getPackageName(), "com.fileman.app.Icon" + KEYS[i]);
    }

    private int current() {
        PackageManager pm = getPackageManager();
        for (int i = 0; i < KEYS.length; i++) {
            int st = pm.getComponentEnabledSetting(alias(i));
            if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
        }
        return 0;
    }

    private void render() {
        content.removeAllViews();
        final int sel = current();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout row = null;
        for (int i = 0; i < KEYS.length; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(tile(i, i == sel), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(Layout.gutter(this), Ui.dp(this, 8), Layout.gutter(this), 0);
        content.addView(card, cp);
        TextView note = Ui.body(this, getString(R.string.ic_note), 13, R.color.text_secondary);
        content.addView(note);
    }

    private View tile(final int i, boolean selected) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        FrameLayout ring = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 32));
        g.setColor(0);
        if (selected) g.setStroke(Ui.dp(this, 3), Ui.color(this, R.color.accent));
        ring.setBackground(g);
        ImageView iv = new ImageView(this);
        iv.setImageResource(ICONS[i]);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int radius = Ui.dp(this, 26);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        iv.setClipToOutline(true);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int pad = Ui.dp(this, 6);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76));
        ip.setMargins(pad, pad, pad, pad);
        ring.addView(iv, ip);
        t.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)));

        TextView label = new TextView(this);
        label.setText(NAMES[i]);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        label.setTextColor(Ui.color(this, selected ? R.color.accent_text : R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        t.addView(label, lp);

        Ui.press(this, t);
        t.setContentDescription(getString(NAMES[i]));
        t.setOnClickListener(v -> choose(i));
        return t;
    }

    private void choose(int i) {
        if (i == current()) return;
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(alias(i), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        for (int k = 0; k < KEYS.length; k++) {
            if (k != i) {
                pm.setComponentEnabledSetting(alias(k), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
        }
        render();
    }
}
