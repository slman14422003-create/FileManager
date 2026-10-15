package com.fileman.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.File;

public class ShareActivity extends BaseActivity {
    private LinearLayout content;

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
        ((android.widget.TextView) findViewById(R.id.title)).setText(R.string.sh_title);
        android.widget.TextView sub = findViewById(R.id.subtitle);
        sub.setText(R.string.sh_sub);
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        findViewById(R.id.loading).setVisibility(View.INVISIBLE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        content.removeAllViews();
        content.addView(Ui.noteCard(this, getString(R.string.sh_hub_note), R.color.info));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_wifi, true, getString(R.string.sh_send_title),
                getString(R.string.sh_send_row_sub), false, true),
                v -> startActivity(new Intent(this, ShareSendActivity.class))));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_qr, true, getString(R.string.sh_recv_title),
                getString(R.string.sh_recv_row_sub), false, true),
                v -> startActivity(new Intent(this, ShareReceiveActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.sh_settings)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_phone, false, getString(R.string.sh_dev_name),
                deviceName(this), false, true), v -> editName()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_folder, false, getString(R.string.sh_save_to),
                receiveDir(this).getAbsolutePath(), false, true), v -> editDir()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_timer, false, getString(R.string.sh_streams),
                getString(R.string.sh_streams_sub, streams(this)), false, true), v -> pickStreams()));
        content.addView(Ui.footer(this, getString(R.string.sh_streams_hint)));
    }

    private void editName() {
        final EditText e = Ui.edit(this, getString(R.string.sh_dev_name), deviceName(this));
        new Dlg(this).setTitle(R.string.sh_dev_name).setView(Ui.block(this, e))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Store.setStrPref(this, "share_name", e.getText().toString().trim());
                    build();
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
                    build();
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
            build();
        }).show();
    }
}
