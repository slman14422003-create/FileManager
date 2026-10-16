package com.fileman.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

public class ShareReceiveActivity extends BaseActivity {
    private static final int MAX_ROWS = 400;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ShareNet.Joiner joiner = new ShareNet.Joiner();
    private final ShareNet.Locks locks = new ShareNet.Locks();

    private LinearLayout content;
    private View loading;
    private TextView titleView, subtitleView;
    private volatile boolean destroyed;

    private ShareLink link;
    private ShareClient client;
    private SocketFactory factory;
    private List<String> extraIps = new ArrayList<>();
    private List<ShareItem> items = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();
    private String prefix = "";
    private int stage;
    private File saveDir;

    private ShareDownload dl;
    private ShareUpload up;
    private long dlStart;
    private long lastBytes;
    private long lastTick;
    private double speed;

    private TextView dlLine, dlSpeed, dlFiles, upLine;
    private ProgressBar dlBar;
    private Button pauseBtn;
    private PulseView dlPulse;

    private ActivityResultLauncher<Intent> scanLauncher;
    private ActivityResultLauncher<Intent> pickLauncher;
    private ActivityResultLauncher<String[]> permLauncher;
    private Runnable afterPerm;
    private boolean scanned;
    private boolean awaitWifi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        loading.setVisibility(View.INVISIBLE);
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        saveDir = ShareActivity.receiveDir(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                back();
            }
        });

        scanLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Intent d = r.getData();
            if (r.getResultCode() == RESULT_OK && d != null) {
                String code = d.getStringExtra("code");
                ShareLink l = ShareLink.parse(code);
                if (l != null) {
                    connect(l);
                    return;
                }
            }
            if (stage == 0) showStart();
        });
        pickLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Intent d = r.getData();
            if (r.getResultCode() != RESULT_OK || d == null) return;
            List<String> paths = new ArrayList<>();
            List<String> many = d.getStringArrayListExtra("picked_all");
            if (many != null) paths.addAll(many);
            String one = d.getStringExtra("picked");
            if (one != null) paths.add(one);
            startUpload(paths);
        });
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
            Runnable a = afterPerm;
            afterPerm = null;
            if (a != null) a.run();
        });

        Intent in = getIntent();
        String code = in == null ? null : in.getStringExtra("code");
        ShareLink l = ShareLink.parse(code);
        showStart();
        if (l != null) connect(l);
        else {
            scanned = true;
            scan();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (dlPulse != null) dlPulse.stop();
        joiner.release();
        if (dl != null) dl.cancel();
        if (up != null) up.cancel();
        locks.release();
        io.shutdownNow();
    }

    private void back() {
        if (stage == 3 && dl != null && !dl.finished) {
            new Dlg(this).setTitle(R.string.sh_cancel_title).setMessage(R.string.sh_cancel_q)
                    .setPositiveButton(R.string.sh_cancel_go, (d, w) -> {
                        dl.cancel();
                        finish();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (stage == 2 && !prefix.isEmpty()) {
            int i = prefix.lastIndexOf('/', prefix.length() - 2);
            prefix = i < 0 ? "" : prefix.substring(0, i + 1);
            showBrowse();
            return;
        }
        finish();
    }

    private void header(CharSequence sub) {
        titleView.setText(R.string.sh_recv_title);
        subtitleView.setText(sub);
        subtitleView.setVisibility(sub == null || sub.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private View button(int res, boolean primary, View.OnClickListener l) {
        Button b = Ui.button(this, res, primary);
        b.setOnClickListener(l);
        return Ui.block(this, b);
    }

    private void showStart() {
        stage = 0;
        header(getString(R.string.sh_recv_sub));
        loading.setVisibility(View.INVISIBLE);
        content.removeAllViews();
        content.addView(Ui.noteCard(this, getString(R.string.sh_recv_note), R.color.info));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_qr, true, getString(R.string.sh_scan_btn),
                getString(R.string.sh_scan_btn_sub), false, true), v -> scan()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_clipboard, false, getString(R.string.sh_manual),
                getString(R.string.sh_manual_sub), false, true), v -> manual()));
    }

    private void scan() {
        scanLauncher.launch(new Intent(this, ShareScanActivity.class));
    }

    private void manual() {
        final EditText e = Ui.edit(this, getString(R.string.sh_manual_hint), "");
        new Dlg(this).setTitle(R.string.sh_manual).setView(Ui.block(this, e))
                .setPositiveButton(R.string.sh_connect, (d, w) -> {
                    ShareLink l = ShareLink.parse(e.getText().toString());
                    if (l == null) Toast.makeText(this, R.string.sh_bad_code, Toast.LENGTH_SHORT).show();
                    else connect(l);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String[] needed() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{"android.permission.NEARBY_WIFI_DEVICES"}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void connect(final ShareLink l) {
        link = l;
        if (!l.ssid.isEmpty()) {
            String[] p = needed();
            if (checkSelfPermission(p[0]) != PackageManager.PERMISSION_GRANTED) {
                afterPerm = () -> {
                    if (checkSelfPermission(p[0]) == PackageManager.PERMISSION_GRANTED) connect(l);
                    else {
                        Toast.makeText(this, R.string.sh_perm_denied, Toast.LENGTH_LONG).show();
                        showStart();
                    }
                };
                permLauncher.launch(p);
                return;
            }
            if (!ShareNet.wifiEnabled(this)) {
                new Dlg(this).setTitle(R.string.sx_wifi_off_title).setMessage(R.string.sx_wifi_off_msg)
                        .setPositiveButton(R.string.sx_open_wifi, (d, w) -> {
                            awaitWifi = true;
                            ShareNet.openWifi(this);
                        })
                        .setNegativeButton(R.string.cancel, (d, w) -> showStart())
                        .show();
                return;
            }
        } else if (!ShareNet.onWifi(this)) {
            new Dlg(this).setTitle(R.string.sx_recv_net_title).setMessage(R.string.sx_recv_net_msg)
                    .setPositiveButton(R.string.sx_open_wifi, (d, w) -> {
                        awaitWifi = true;
                        ShareNet.openWifi(this);
                    })
                    .setNeutralButton(R.string.sx_try_anyway, (d, w) -> go(l))
                    .setNegativeButton(R.string.cancel, (d, w) -> showStart())
                    .show();
            return;
        }
        go(l);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitWifi) {
            awaitWifi = false;
            ui.postDelayed(() -> {
                if (!destroyed && link != null && stage != 1 && stage != 2 && stage != 3) connect(link);
            }, 800);
        }
    }

    private void go(final ShareLink l) {
        stage = 1;
        header(l.name);
        loading.setVisibility(View.VISIBLE);
        content.removeAllViews();
        final TextView status = Ui.body(this, getString(l.ssid.isEmpty() ? R.string.sh_conn_link : R.string.sh_conn_wifi, l.ssid), 15, R.color.text_primary);
        content.addView(status);
        content.addView(button(R.string.cancel, false, v -> {
            joiner.release();
            showStart();
        }));
        locks.acquire(this);
        joiner.join(this, l, new ShareNet.Joiner.Cb() {
            @Override
            public void ok(SocketFactory f, List<String> extra) {
                if (destroyed) return;
                factory = f;
                extraIps = extra;
                status.setText(R.string.sh_conn_link);
                reach();
            }

            @Override
            public void fail() {
                if (destroyed) return;
                locks.release();
                failScreen(getString(R.string.sh_wifi_failed));
            }
        });
    }

    private void reach() {
        final ShareLink l = link;
        io.execute(() -> {
            List<String> ips = new ArrayList<>(l.ips);
            for (String g : extraIps) if (!ips.contains(g)) ips.add(g);
            final String me = ShareActivity.deviceName(this);
            final ShareClient primary = new ShareClient(factory, l.port, l.token, me);
            final ShareClient fallback = new ShareClient(SocketFactory.getDefault(), l.port, l.token, me);
            ShareClient used = primary;
            String err = null;
            List<ShareItem> list = null;
            for (int attempt = 0; attempt < 5 && list == null && !destroyed; attempt++) {
                ShareClient cur = attempt % 2 == 1 ? fallback : primary;
                ShareClient.Conn conn = null;
                try {
                    conn = cur.connectAny(ips);
                    list = cur.list(conn);
                    used = cur;
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                    try {
                        Thread.sleep(900);
                    } catch (InterruptedException ignored) {
                    }
                } finally {
                    if (conn != null) conn.close();
                }
            }
            final ShareClient c = used;
            final List<ShareItem> fl = list;
            final String fe = err;
            ui.post(() -> {
                if (destroyed) return;
                if (fl == null) {
                    locks.release();
                    failScreen(getString(R.string.sh_unreachable));
                    return;
                }
                client = c;
                items = fl;
                selected.clear();
                for (ShareItem i : fl) selected.add(i.id);
                prefix = "";
                showBrowse();
            });
        });
    }

    private void failScreen(String msg) {
        stage = 0;
        loading.setVisibility(View.INVISIBLE);
        content.removeAllViews();
        content.addView(Ui.noteCard(this, msg, R.color.bad));
        content.addView(Ui.noteCard(this, getString(R.string.sx_fail_tips), R.color.info));
        content.addView(button(R.string.sh_retry, true, v -> {
            if (link != null) connect(link);
            else showStart();
        }));
        content.addView(button(R.string.sx_open_wifi, false, v -> {
            awaitWifi = true;
            ShareNet.openWifi(this);
        }));
        content.addView(button(R.string.sh_scan_btn, false, v -> scan()));
        content.addView(button(R.string.sh_manual, false, v -> manual()));
    }

    private static final class Level {
        String name;
        int files;
        int sel;
        long size;
    }

    private void showBrowse() {
        stage = 2;
        loading.setVisibility(View.INVISIBLE);
        header(prefix.isEmpty() ? getString(R.string.sh_from, link.name) : prefix);
        content.removeAllViews();

        long selSize = 0;
        int selCount = 0;
        for (ShareItem i : items) {
            if (selected.contains(i.id)) {
                selSize += Math.max(0, i.size);
                selCount++;
            }
        }
        content.addView(Ui.body(this, getString(R.string.sh_selected_sum, selCount, items.size(), Fmt.size(selSize)), 15, R.color.text_primary));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        TextView all = Ui.chip(this, getString(prefix.isEmpty() ? R.string.sh_sel_all : R.string.sh_sel_here), false);
        all.setOnClickListener(v -> setScope(true));
        TextView none = Ui.chip(this, getString(prefix.isEmpty() ? R.string.sh_sel_none : R.string.sh_clear_here), false);
        none.setOnClickListener(v -> setScope(false));
        bar.addView(all);
        bar.addView(none);
        content.addView(Ui.block(this, bar));

        Map<String, Level> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<ShareItem> files = new ArrayList<>();
        for (ShareItem i : items) {
            if (!i.rel.startsWith(prefix)) continue;
            String rest = i.rel.substring(prefix.length());
            int s = rest.indexOf('/');
            if (s < 0) {
                files.add(i);
            } else {
                String n = rest.substring(0, s);
                Level lv = folders.get(n);
                if (lv == null) {
                    lv = new Level();
                    lv.name = n;
                    folders.put(n, lv);
                }
                lv.files++;
                lv.size += Math.max(0, i.size);
                if (selected.contains(i.id)) lv.sel++;
            }
        }
        Collections.sort(files, (a, b) -> a.rel.compareToIgnoreCase(b.rel));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int shown = 0;
        for (final Level lv : folders.values()) {
            if (shown++ >= MAX_ROWS) break;
            list.addView(Ui.rowView(this, list, new Row(R.drawable.ic_folder, lv.sel > 0, lv.name,
                    getString(R.string.sh_folder_sub, lv.sel, lv.files, Fmt.size(lv.size)), false, true), v -> {
                prefix = prefix + lv.name + "/";
                showBrowse();
            }));
        }
        for (final ShareItem it : files) {
            if (shown++ >= MAX_ROWS) break;
            boolean on = selected.contains(it.id);
            String name = it.rel.substring(prefix.length());
            list.addView(Ui.rowView(this, list, new Row(on ? R.drawable.ic_check_circle : R.drawable.ic_file, on, name,
                    Fmt.size(Math.max(0, it.size)), false, false)
                    .tint(Ui.color(this, on ? R.color.ok : R.color.text_hint)), v -> {
                if (!selected.remove(it.id)) selected.add(it.id);
                showBrowse();
            }));
        }
        if (shown > 0) {
            Ui.group(this, list);
            content.addView(list);
        }
        if (folders.size() + files.size() > MAX_ROWS) {
            content.addView(Ui.footer(this, getString(R.string.sh_more_rows, MAX_ROWS)));
        }

        content.addView(button(R.string.sh_download, true, v -> askStart()));
        content.addView(Ui.sectionTitle(this, getString(R.string.sh_more)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_share, false, getString(R.string.sh_upload),
                getString(R.string.sh_upload_sub), false, true), v -> {
            Intent i = new Intent(this, FileManagerActivity.class).putExtra("pick", true).putExtra("pick_multi", true);
            pickLauncher.launch(i);
        }));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_refresh, false, getString(R.string.sh_relist),
                null, false, true), v -> relist()));
        upLine = Ui.body(this, up == null ? "" : upText(), 14, R.color.text_secondary);
        content.addView(upLine);
    }

    private void setScope(boolean on) {
        for (ShareItem i : items) {
            if (!i.rel.startsWith(prefix)) continue;
            if (on) selected.add(i.id);
            else selected.remove(i.id);
        }
        showBrowse();
    }

    private void relist() {
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            ShareClient.Conn c = null;
            List<ShareItem> l = null;
            try {
                c = client.open();
                l = client.list(c);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.close();
            }
            final List<ShareItem> fl = l;
            ui.post(() -> {
                if (destroyed) return;
                loading.setVisibility(View.INVISIBLE);
                if (fl == null) {
                    Toast.makeText(this, R.string.sh_unreachable, Toast.LENGTH_SHORT).show();
                    return;
                }
                Set<Integer> old = new HashSet<>();
                Set<String> oldRel = new HashSet<>();
                for (ShareItem i : items) if (selected.contains(i.id)) oldRel.add(i.rel);
                for (ShareItem i : items) old.add(i.id);
                items = fl;
                selected.clear();
                for (ShareItem i : fl) if (oldRel.contains(i.rel) || !old.contains(i.id)) selected.add(i.id);
                showBrowse();
            });
        });
    }

    private void askStart() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.sh_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Perms.hasAllFiles(this)) {
            new Dlg(this).setTitle(R.string.sh_perm_files_title).setMessage(R.string.sh_perm_files)
                    .setPositiveButton(R.string.perm_open_settings, (d, w) -> Perms.requestAllFiles(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        beginDownload();
    }

    private void beginDownload() {
        List<ShareItem> pick = new ArrayList<>();
        for (ShareItem i : items) if (selected.contains(i.id)) pick.add(i);
        saveDir.mkdirs();
        dl = new ShareDownload(client, saveDir, pick);
        stage = 3;
        showProgress();
        dlStart = System.currentTimeMillis();
        lastTick = dlStart;
        lastBytes = 0;
        speed = 0;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dl.start(ShareActivity.streams(this), true, () -> ui.post(() -> {
            if (!destroyed) showDone();
        }));
        ui.postDelayed(ticker, 500);
    }

    private void showProgress() {
        header(getString(R.string.sh_from, link.name));
        content.removeAllViews();

        android.widget.FrameLayout pulseWrap = new android.widget.FrameLayout(this);
        int pside = getResources().getDimensionPixelSize(R.dimen.share_dl_pulse);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(pside, pside);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = Ui.dp(this, 6);
        dlPulse = new PulseView(this);
        dlPulse.useAccent(this);
        pulseWrap.addView(dlPulse, new android.widget.FrameLayout.LayoutParams(pside, pside));
        ImageView dlIcon = new ImageView(this);
        dlIcon.setImageResource(R.drawable.ic_download);
        dlIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        int isz = getResources().getDimensionPixelSize(R.dimen.share_dl_icon);
        pulseWrap.addView(dlIcon, new android.widget.FrameLayout.LayoutParams(isz, isz, Gravity.CENTER));
        content.addView(pulseWrap, plp);
        dlPulse.start();

        dlLine = Ui.body(this, "", 16, R.color.text_primary);
        content.addView(dlLine);
        dlBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        Ui.tintRounded(this, dlBar);
        dlBar.setMax(1000);
        content.addView(Ui.block(this, dlBar));
        dlSpeed = Ui.body(this, "", 14, R.color.text_secondary);
        content.addView(dlSpeed);
        dlFiles = Ui.body(this, "", 13, R.color.text_hint);
        content.addView(dlFiles);
        pauseBtn = Ui.button(this, R.string.sh_pause, false);
        pauseBtn.setOnClickListener(v -> {
            dl.pause(!dl.paused);
            pauseBtn.setText(dl.paused ? R.string.sh_resume : R.string.sh_pause);
            if (dlPulse != null) {
                if (dl.paused) dlPulse.stop();
                else dlPulse.start();
            }
        });
        content.addView(Ui.block(this, pauseBtn));
        content.addView(button(R.string.sh_cancel_go, true, v -> back()));
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || dl == null || dl.finished || stage != 3) return;
            long now = System.currentTimeMillis();
            long done = dl.done.get();
            long dt = Math.max(1, now - lastTick);
            double inst = (done - lastBytes) * 1000.0 / dt;
            speed = speed == 0 ? inst : speed * 0.7 + inst * 0.3;
            lastTick = now;
            lastBytes = done;
            long total = Math.max(1, dl.total);
            Ui.animateProgress(dlBar, (int) Math.min(1000, done * 1000 / total));
            dlLine.setText(getString(R.string.sh_dl_line, Fmt.size(done), Fmt.size(dl.total), (int) Math.min(100, done * 100 / total)));
            String eta = "";
            if (speed > 1024 && !dl.paused) {
                long sec = (long) ((dl.total - done) / speed);
                eta = "  ·  " + clock(sec);
            }
            dlSpeed.setText(dl.paused ? getString(R.string.sh_paused) : Fmt.size((long) speed) + "/s" + eta);
            StringBuilder sb = new StringBuilder(getString(R.string.sh_files_line, dl.filesDone.get() + dl.filesSkipped.get(), dl.totalFiles));
            if (dl.filesFailed.get() > 0) sb.append("  ·  ").append(getString(R.string.sh_failed_n, dl.filesFailed.get()));
            int n = 0;
            for (String a : dl.active.values()) {
                if (n++ >= 4) break;
                sb.append('\n').append(a);
            }
            dlFiles.setText(sb.toString());
            ui.postDelayed(this, 500);
        }
    };

    private static String clock(long s) {
        if (s < 0) s = 0;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long x = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, x) : String.format(java.util.Locale.US, "%d:%02d", m, x);
    }

    private void showDone() {
        stage = 4;
        locks.release();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loading.setVisibility(View.INVISIBLE);
        if (dlPulse != null) dlPulse.stop();
        header(getString(R.string.sh_from, link.name));
        content.removeAllViews();
        boolean bad = dl.filesFailed.get() > 0 || dl.cancelled;
        long secs = Math.max(1, (System.currentTimeMillis() - dlStart) / 1000);
        TextView doneCard = Ui.noteCard(this, getString(dl.cancelled ? R.string.sh_dl_cancelled : R.string.sh_dl_done,
                dl.filesDone.get() + dl.filesSkipped.get(), dl.totalFiles, Fmt.size(dl.done.get()), clock(secs),
                Fmt.size(dl.done.get() / secs) + "/s"), bad ? R.color.warn : R.color.ok);
        content.addView(doneCard);
        Ui.successPop(doneCard);
        if (dl.filesFailed.get() > 0) content.addView(Ui.footer(this, getString(R.string.sh_failed_n, dl.filesFailed.get())));
        content.addView(Ui.footer(this, saveDir.getAbsolutePath()));
        content.addView(button(R.string.sh_open_folder, true, v ->
                startActivity(new Intent(this, FileManagerActivity.class).putExtra("path", saveDir.getAbsolutePath()))));
        final List<File> pk = new ArrayList<>();
        synchronized (dl.results) {
            for (File f : dl.results) {
                String x = Cats.extOf(f.getName());
                if (x.equals("apk") || x.equals("apks") || x.equals("xapk") || x.equals("apkm")) pk.add(f);
            }
        }
        if (!pk.isEmpty()) {
            content.addView(button(R.string.sh_install_pk, false, v -> {
                if (pk.size() == 1) Opener.open(this, pk.get(0));
                else startActivity(new Intent(this, InstallerActivity.class).putExtra("found", true));
            }));
        }
        content.addView(button(R.string.sh_back_list, false, v -> {
            dl = null;
            showBrowse();
        }));
        content.addView(button(R.string.ok, false, v -> finish()));
        if (Notifs.allowed(this)) {
            try {
                Notifs.installResult(this, getString(R.string.sh_title), getString(R.string.sh_notif_done, dl.filesDone.get()), !bad);
            } catch (Throwable ignored) {
            }
        }
    }

    private String upText() {
        if (up == null) return "";
        return getString(R.string.sh_upload_line, up.filesDone.get(), up.totalFiles, Fmt.size(up.done.get()), Fmt.size(up.total));
    }

    private void startUpload(List<String> paths) {
        final List<ShareItem> out = new ArrayList<>();
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile()) out.add(ShareItem.of(f, f.getName()));
            else if (f.isDirectory()) collect(f, f.getName() + "/", out, 0);
        }
        if (out.isEmpty()) {
            Toast.makeText(this, R.string.sh_nothing_found, Toast.LENGTH_SHORT).show();
            return;
        }
        up = new ShareUpload(this, client, out);
        up.start(ShareActivity.streams(this), () -> ui.post(() -> {
            if (destroyed || up == null) return;
            Toast.makeText(this, getString(R.string.sh_upload_done, up.filesDone.get()), Toast.LENGTH_LONG).show();
            if (stage == 2 && upLine != null) upLine.setText(upText());
        }));
        ui.postDelayed(upTick, 500);
        Toast.makeText(this, getString(R.string.sh_upload_started, out.size()), Toast.LENGTH_SHORT).show();
    }

    private final Runnable upTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || up == null) return;
            if (stage == 2 && upLine != null) upLine.setText(upText());
            if (!up.finished) ui.postDelayed(this, 500);
        }
    };

    private void collect(File dir, String pre, List<ShareItem> out, int depth) {
        if (depth > 30 || out.size() >= ShareProto.MAX_ITEMS) return;
        File[] a = dir.listFiles();
        if (a == null) return;
        for (File f : a) {
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) collect(f, pre + f.getName() + "/", out, depth + 1);
            } else if (f.isFile() && f.canRead()) {
                out.add(ShareItem.of(f, pre + f.getName()));
            }
        }
    }
}
