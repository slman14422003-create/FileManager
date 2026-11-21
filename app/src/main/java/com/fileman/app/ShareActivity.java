package com.fileman.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class ShareActivity extends BaseActivity {
    private LinearLayout content;
    private boolean tapBanner;
    private boolean built;

    static String deviceName(Context c) {
        String d = Store.strPref(c, "share_name", "");
        if (d.trim().isEmpty()) {
            String m = Build.MODEL == null ? "" : Build.MODEL;
            d = m.isEmpty() ? "Android" : m;
        }
        return d;
    }

    static File receiveDir(Context c) {
        String d = Store.strPref(c, "share_dir", "");
        if (!d.isEmpty()) return new File(d);
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileManager-Share");
    }

    static int streams(Context c) {
        return Math.max(1, Math.min(8, Store.intPref(c, "share_streams", 4)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.sh_title);
        TextView sub = findViewById(R.id.subtitle);
        sub.setText(R.string.sh_sub);
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        findViewById(R.id.loading).setVisibility(View.INVISIBLE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        tapBanner = getIntent() != null && getIntent().getBooleanExtra("tap", false);
        build(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (built) build(false);
    }

    @Override
    protected int tapMode() {
        return TapShare.AUTO;
    }

    @Override
    protected void onTapPeer() {
        TapShare.ripple(this);
        Fx.done(content);
        tapBanner = true;
        build(false);
    }

    private int side() {
        return Math.max(Ui.dp(this, 16), Layout.gutter(this));
    }

    private View bigCard(int mode, int icon, int title, int sub, int c1, int c2, View.OnClickListener click) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        bg.setCornerRadius(Ui.dp(this, 28));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(Ui.dpf(this, 3f));
        AirView av = new AirView(this);
        av.palette(0xFFFFFFFF, 0x40FFFFFF, 0xFFFFFFFF);
        av.disc(0.36f);
        av.icon(icon);
        av.setMode(mode);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(Ui.dp(this, 176), Ui.dp(this, 176), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ap.topMargin = Ui.dp(this, 4);
        card.addView(av, ap);
        av.start();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(21);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0xE6FFFFFF);
        s.setTextSize(12);
        s.setGravity(Gravity.CENTER);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(this, 2);
        col.addView(s, sp);
        card.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        Ui.press(this, card);
        card.setOnClickListener(v -> {
            Fx.light(v);
            click.onClick(v);
        });
        return card;
    }

    private void go(Intent i) {
        startActivity(i);
        if (!Store.reduceMotion(this)) overridePendingTransition(R.anim.share_zoom_in, R.anim.share_hold);
    }

    private void build(boolean animate) {
        built = true;
        content.removeAllViews();
        if (tapBanner) {
            TextView banner = Ui.noteCard(this, getString(R.string.tap_detected), R.color.ok);
            content.addView(banner);
            Ui.successPop(banner);
        }

        LinearLayout big = new LinearLayout(this);
        big.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 244));
        bl.setMargins(side(), Ui.dp(this, 8), side(), Ui.dp(this, 8));
        View send = bigCard(AirView.SEND, R.drawable.ic_send_up, R.string.sh_send_title, R.string.sh_send_row_sub,
                0xFF0A84FF, 0xFF5E5CE6, v -> go(new Intent(this, ShareSendActivity.class)));
        View recv = bigCard(AirView.RECEIVE, R.drawable.ic_receive_down, R.string.sh_recv_title, R.string.sh_recv_row_sub,
                0xFF30D158, 0xFF00A5A8, v -> go(new Intent(this, ShareReceiveActivity.class)));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        l1.setMarginEnd(Ui.dp(this, 6));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        l2.setMarginStart(Ui.dp(this, 6));
        big.addView(send, l1);
        big.addView(recv, l2);
        content.addView(big, bl);
        if (animate) {
            Ui.enter(send, 0);
            Ui.enter(recv, 1);
        }

        content.addView(Ui.footer(this, getString(R.string.sh_hub_note)));

        content.addView(Ui.sectionTitle(this, getString(R.string.sh_more_ways)));
        LinearLayout ways = new LinearLayout(this);
        ways.setOrientation(LinearLayout.VERTICAL);
        ways.addView(Ui.rowView(this, ways, new Row(R.drawable.ic_quick_share, true, getString(R.string.sx_m_quick),
                getString(R.string.sh_quick_row_sub), false, true),
                v -> go(new Intent(this, ShareSendActivity.class).putExtra("quick", true))));
        final Ui.Toggle tap = Ui.toggle(this, ways, R.string.tap_title, R.string.tap_sub, TapShare.enabled(this));
        tap.withIcon(R.drawable.ic_nfc);
        tap.onChange((b, on) -> tapToggled(tap, on));
        ways.addView(tap.view);
        Ui.group(this, ways);
        content.addView(ways);

        content.addView(Ui.sectionTitle(this, getString(R.string.sh_settings)));
        LinearLayout set = new LinearLayout(this);
        set.setOrientation(LinearLayout.VERTICAL);
        set.addView(Ui.rowView(this, set, new Row(R.drawable.ic_phone, false, getString(R.string.sh_dev_name),
                deviceName(this), false, true), v -> editName()));
        set.addView(Ui.rowView(this, set, new Row(R.drawable.ic_folder, false, getString(R.string.sh_save_to),
                receiveDir(this).getAbsolutePath(), false, true), v -> editDir()));
        set.addView(Ui.rowView(this, set, new Row(R.drawable.ic_timer, false, getString(R.string.sh_streams),
                getString(R.string.sh_streams_sub, streams(this)), false, true), v -> pickStreams()));
        Ui.group(this, set);
        content.addView(set);
        content.addView(Ui.footer(this, getString(R.string.sh_streams_hint)));

        if (animate) {
            for (int i = 2; i < Math.min(content.getChildCount(), 9); i++) Ui.enter(content.getChildAt(i), i);
        }
        tapRefresh();
    }

    static void tapToggled(final Ui.Toggle tap, boolean on) {
        Context c = tap.view.getContext();
        if (!on) {
            TapShare.setEnabled(c, false);
            return;
        }
        if (!TapShare.supported(c)) {
            Toast.makeText(c, R.string.tap_unsupported, Toast.LENGTH_LONG).show();
            tap.onChange(null);
            tap.setChecked(false);
            tap.onChange((b, v) -> tapToggled(tap, v));
            return;
        }
        TapShare.setEnabled(c, true);
        if (!TapShare.nfcOn(c)) {
            new Dlg(c).setTitle(R.string.tap_nfc_off_title).setMessage(R.string.tap_nfc_off)
                    .setPositiveButton(R.string.tap_open_nfc, (d, w) -> {
                        try {
                            c.startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
                        } catch (Exception e) {
                            try {
                                c.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void editName() {
        final EditText e = Ui.edit(this, getString(R.string.sh_dev_name), deviceName(this));
        new Dlg(this).setTitle(R.string.sh_dev_name).setView(Ui.block(this, e))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Store.setStrPref(this, "share_name", e.getText().toString().trim());
                    build(false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editDir() {
        final EditText e = Ui.edit(this, getString(R.string.sh_save_to), receiveDir(this).getAbsolutePath());
        new Dlg(this).setTitle(R.string.sh_save_to).setView(Ui.block(this, e))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String v = e.getText().toString().trim();
                    Store.setStrPref(this, "share_dir", v.startsWith("/") ? v : "");
                    build(false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickStreams() {
        final int[] v = {1, 2, 4, 6, 8};
        String[] t = new String[v.length];
        for (int i = 0; i < v.length; i++) t[i] = String.valueOf(v[i]);
        new Dlg(this).setTitle(R.string.sh_streams).setItems(t, (d, w) -> {
            Store.setIntPref(this, "share_streams", v[w]);
            build(false);
        }).show();
    }
}
