package com.fileman.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ShareSendActivity extends BaseActivity {
    private static final class Group {
        String label;
        List<ShareItem> items = new ArrayList<>();
        long size;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Group> groups = new ArrayList<>();
    private final ShareNet.Hotspot hotspot = new ShareNet.Hotspot();
    private final ShareNet.Locks locks = new ShareNet.Locks();

    private LinearLayout content;
    private View loading;
    private TextView titleView, subtitleView;
    private volatile boolean destroyed;
    private boolean live;
    private boolean useHotspot;
    private ShareServer server;
    private String token;
    private String deviceName;
    private File receiveDir;
    private ShareLink link;
    private int ipTries;

    private TextView liveStatus, livePeers, liveSpeed;
    private ProgressBar liveBar;
    private long lastBytes;
    private long lastTick;
    private double speed;

    private ActivityResultLauncher<Intent> pickFiles;
    private ActivityResultLauncher<Intent> pickDir;
    private ActivityResultLauncher<String[]> permLauncher;
    private Runnable afterPerm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        loading = findViewById(R.id.loading);
        content = findViewById(R.id.content);
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        Ui.autoGroup(this, content);
        deviceName = ShareActivity.deviceName(this);
        receiveDir = ShareActivity.receiveDir(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (live) confirmStop();
                else finish();
            }
        });

        pickFiles = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Intent d = r.getData();
            if (r.getResultCode() != RESULT_OK || d == null) return;
            List<String> paths = new ArrayList<>();
            List<String> many = d.getStringArrayListExtra("picked_all");
            if (many != null) paths.addAll(many);
            String one = d.getStringExtra("picked");
            if (one != null) paths.add(one);
            addPaths(paths, false);
        });
        pickDir = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Intent d = r.getData();
            if (r.getResultCode() != RESULT_OK || d == null) return;
            String dir = d.getStringExtra("picked_dir");
            if (dir != null) addPaths(Collections.singletonList(dir), true);
        });
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
            Runnable a = afterPerm;
            afterPerm = null;
            if (a != null) a.run();
        });
        showCompose();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        stopAll();
        io.shutdownNow();
    }

    private void stopAll() {
        if (server != null) server.stop();
        server = null;
        hotspot.stop();
        locks.release();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void header(CharSequence sub) {
        titleView.setText(R.string.sh_send_title);
        subtitleView.setText(sub);
        subtitleView.setVisibility(sub == null || sub.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private View row(int icon, String title, String sub, View.OnClickListener click) {
        return Ui.rowView(this, content, new Row(icon, false, title, sub, false, click != null), click);
    }

    private View button(int res, boolean primary, View.OnClickListener l) {
        Button b = Ui.button(this, res, primary);
        b.setOnClickListener(l);
        return Ui.block(this, b);
    }

    private long totalSize() {
        long t = 0;
        for (Group g : groups) t += g.size;
        return t;
    }

    private int totalCount() {
        int n = 0;
        for (Group g : groups) n += g.items.size();
        return n;
    }

    private void showCompose() {
        live = false;
        header(getString(R.string.sh_send_sub));
        loading.setVisibility(View.INVISIBLE);
        content.removeAllViews();
        content.addView(Ui.noteCard(this, getString(R.string.sh_send_note), R.color.info));

        content.addView(Ui.sectionTitle(this, getString(R.string.sh_add_section)));
        content.addView(row(R.drawable.ic_file, getString(R.string.sh_add_files), getString(R.string.sh_add_files_sub), v -> openPicker(false)));
        content.addView(row(R.drawable.ic_folder, getString(R.string.sh_add_folder), getString(R.string.sh_add_folder_sub), v -> openPicker(true)));
        content.addView(row(R.drawable.ic_package, getString(R.string.sh_add_apps), getString(R.string.sh_add_apps_sub), v -> pickApps()));

        content.addView(Ui.sectionTitle(this, getString(R.string.sh_cat_title)));
        content.addView(row(R.drawable.ic_image, getString(R.string.cat_images), null, v -> addCategory(Cats.IMG, getString(R.string.cat_images))));
        content.addView(row(R.drawable.ic_video, getString(R.string.cat_videos), null, v -> addCategory(Cats.VID, getString(R.string.cat_videos))));
        content.addView(row(R.drawable.ic_music, getString(R.string.cat_audio), null, v -> addCategory(Cats.AUD, getString(R.string.cat_audio))));
        content.addView(row(R.drawable.ic_file_text, getString(R.string.cat_docs), null, v -> addCategory(Cats.DOC, getString(R.string.cat_docs))));
        content.addView(row(R.drawable.ic_archive, getString(R.string.cat_archives), null, v -> addCategory(Cats.ARC, getString(R.string.cat_archives))));
        content.addView(row(R.drawable.ic_drive, getString(R.string.sh_everything), getString(R.string.sh_everything_sub), v -> addEverything()));

        if (!groups.isEmpty()) {
            content.addView(Ui.sectionTitle(this, getString(R.string.sh_selected)));
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (final Group g : groups) {
                list.addView(Ui.rowView(this, list, new Row(R.drawable.ic_check_circle, false, g.label,
                        getString(R.string.sh_group_sub, g.items.size(), Fmt.size(g.size)), false, true)
                        .tint(Ui.color(this, R.color.ok)), v -> {
                    groups.remove(g);
                    showCompose();
                }));
            }
            Ui.group(this, list);
            content.addView(list);
            content.addView(Ui.footer(this, getString(R.string.sh_tap_remove)));
            content.addView(Ui.body(this, getString(R.string.sh_summary, totalCount(), Fmt.size(totalSize())), 15, R.color.text_primary));
            content.addView(button(R.string.sh_start, true, v -> askHotspot()));
        }
    }

    private boolean needFiles() {
        if (Perms.hasAllFiles(this)) return false;
        new Dlg(this).setTitle(R.string.sh_perm_files_title)
                .setMessage(R.string.sh_perm_files)
                .setPositiveButton(R.string.perm_open_settings, (d, w) -> Perms.requestAllFiles(this))
                .setNegativeButton(R.string.cancel, null)
                .show();
        return true;
    }

    private void openPicker(boolean dir) {
        if (needFiles()) return;
        Intent i = new Intent(this, FileManagerActivity.class);
        if (dir) {
            i.putExtra("pick_dir", true);
            pickDir.launch(i);
        } else {
            i.putExtra("pick", true).putExtra("pick_multi", true);
            pickFiles.launch(i);
        }
    }

    private void busy(boolean on) {
        loading.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void walk(File dir, String prefix, String cat, List<ShareItem> out, int depth) {
        if (destroyed || depth > 30 || out.size() >= ShareProto.MAX_ITEMS) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (destroyed || out.size() >= ShareProto.MAX_ITEMS) return;
            String n = f.getName();
            if (n.endsWith(".fmpart")) continue;
            if (f.isDirectory()) {
                if (Cats.isLink(f)) continue;
                if (depth == 0 && prefix.isEmpty() && n.equals("Android")) continue;
                walk(f, prefix + n + "/", cat, out, depth + 1);
            } else if (f.isFile() && f.canRead()) {
                if (cat != null && !Cats.matches(cat, Cats.extOf(n))) continue;
                out.add(ShareItem.of(f, prefix + n));
            }
        }
    }

    private void commit(String label, List<ShareItem> items) {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.sh_nothing_found, Toast.LENGTH_SHORT).show();
            return;
        }
        Group g = new Group();
        g.label = label;
        g.items = items;
        for (ShareItem i : items) g.size += Math.max(0, i.size);
        if (live && server != null) {
            server.addItems(items);
            Toast.makeText(this, getString(R.string.sh_added_live, items.size()), Toast.LENGTH_SHORT).show();
            return;
        }
        groups.add(g);
        showCompose();
    }

    private void addPaths(final List<String> paths, final boolean dirMode) {
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            String label = "";
            for (String p : paths) {
                File f = new File(p);
                if (f.isDirectory()) {
                    walk(f, f.getName() + "/", null, out, 1);
                    label = f.getName();
                } else if (f.isFile()) {
                    out.add(ShareItem.of(f, f.getName()));
                    if (label.isEmpty()) label = f.getName();
                }
            }
            final String fl = paths.size() > 1 && !dirMode ? getString(R.string.sh_n_files, paths.size()) : label;
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(fl, out);
            });
        });
    }

    private void addCategory(final String cat, final String label) {
        if (needFiles()) return;
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            File root = Environment.getExternalStorageDirectory();
            walk(root, label + "/", cat, out, 0);
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(label, out);
            });
        });
    }

    private void addEverything() {
        if (needFiles()) return;
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            walk(Environment.getExternalStorageDirectory(), "", null, out, 0);
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(getString(R.string.sh_everything), out);
            });
        });
    }

    private void pickApps() {
        busy(true);
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            final List<String[]> apps = new ArrayList<>();
            try {
                for (PackageInfo pi : pm.getInstalledPackages(0)) {
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai == null) continue;
                    boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                    if (sys) continue;
                    apps.add(new String[]{String.valueOf(ai.loadLabel(pm)), pi.packageName,
                            pi.versionName == null ? "" : pi.versionName});
                }
            } catch (Throwable ignored) {
            }
            Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                showAppDialog(apps);
            });
        });
    }

    private void showAppDialog(final List<String[]> apps) {
        final List<CheckBox> boxes = new ArrayList<>();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 8));
        for (String[] a : apps) {
            CheckBox cb = Ui.check(this, R.string.cv_select_all, false);
            cb.setText(a[0]);
            boxes.add(cb);
            box.addView(cb);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new Dlg(this).setTitle(getString(R.string.sh_add_apps)).setView(sv)
                .setPositiveButton(R.string.sh_add, (d, w) -> {
                    List<String[]> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) chosen.add(apps.get(i));
                    if (!chosen.isEmpty()) buildApps(chosen);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildApps(final List<String[]> chosen) {
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            PackageManager pm = getPackageManager();
            File dir = new File(getCacheDir(), "share-apps");
            dir.mkdirs();
            for (String[] a : chosen) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(a[1], 0);
                    String name = ApkConverter.safeName(a[0], a[2]);
                    if (ai.splitSourceDirs == null || ai.splitSourceDirs.length == 0) {
                        out.add(ShareItem.of(new File(ai.sourceDir), "Apps/" + name + ".apk"));
                    } else {
                        File z = new File(dir, name + ".apks");
                        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(z)))) {
                            zo.setLevel(Deflater.NO_COMPRESSION);
                            put(zo, "splits/base.apk", ai.sourceDir);
                            for (String s : ai.splitSourceDirs) put(zo, "splits/" + new File(s).getName(), s);
                        }
                        out.add(ShareItem.of(z, "Apps/" + name + ".apks"));
                    }
                } catch (Throwable ignored) {
                }
            }
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(getString(R.string.sh_add_apps), out);
            });
        });
    }

    private static void put(ZipOutputStream zo, String name, String path) throws java.io.IOException {
        zo.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) zo.write(buf, 0, r);
        }
        zo.closeEntry();
    }

    private void askHotspot() {
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.sh_need_items, Toast.LENGTH_SHORT).show();
            return;
        }
        new Dlg(this).setTitle(getString(R.string.sh_ask_hotspot_title)).setMessage(getString(R.string.sh_ask_hotspot_msg))
                .setPositiveButton(R.string.sh_hs_auto, (d, w) -> beginLive(true, false))
                .setNeutralButton(R.string.sh_hs_manual, (d, w) -> beginLive(false, true))
                .setNegativeButton(R.string.sh_hs_current, (d, w) -> beginLive(false, false))
                .show();
    }

    private void beginLive(final boolean auto, final boolean openSettings) {
        useHotspot = auto;
        if (auto) {
            final String[] perms = Build.VERSION.SDK_INT >= 33
                    ? new String[]{"android.permission.NEARBY_WIFI_DEVICES"}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            boolean ok = checkSelfPermission(perms[0]) == PackageManager.PERMISSION_GRANTED;
            if (!ok) {
                afterPerm = () -> {
                    if (checkSelfPermission(perms[0]) == PackageManager.PERMISSION_GRANTED) beginLive(true, false);
                    else Toast.makeText(this, R.string.sh_perm_denied, Toast.LENGTH_LONG).show();
                };
                permLauncher.launch(perms);
                return;
            }
            if (Build.VERSION.SDK_INT < 33 && !locationOn()) {
                new Dlg(this).setTitle(R.string.sh_loc_title).setMessage(R.string.sh_loc_msg)
                        .setPositiveButton(R.string.perm_open_settings, (d, w) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                            } catch (Exception ignored) {
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return;
            }
        } else if (openSettings) {
            try {
                startActivity(new Intent("android.settings.TETHER_SETTINGS"));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        }
        if (!startServer()) return;
        showLive();
        if (auto) {
            liveStatus.setText(R.string.sh_starting);
            hotspot.start(this, new ShareNet.Hotspot.Cb() {
                @Override
                public void started() {
                    if (destroyed) return;
                    ipTries = 0;
                    publish();
                }

                @Override
                public void failed(int reason) {
                    if (destroyed) return;
                    hotspotFailed();
                }
            });
        } else {
            publish();
        }
    }

    private boolean locationOn() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return true;
        }
    }

    private void hotspotFailed() {
        useHotspot = false;
        new Dlg(this).setTitle(R.string.sh_ask_hotspot_title).setMessage(R.string.sh_hs_failed_msg)
                .setPositiveButton(R.string.sh_hs_manual, (d, w) -> {
                    try {
                        startActivity(new Intent("android.settings.TETHER_SETTINGS"));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    }
                    ipTries = 0;
                    publish();
                })
                .setNegativeButton(R.string.sh_hs_current, (d, w) -> {
                    ipTries = 0;
                    publish();
                })
                .show();
    }

    private boolean startServer() {
        try {
            token = ShareProto.newToken();
            server = new ShareServer(this, deviceName, token, receiveDir);
            List<ShareItem> all = new ArrayList<>();
            for (Group g : groups) all.addAll(g.items);
            server.addItems(all);
            server.listener = new ShareServer.Listener() {
                @Override
                public void onPeers() {
                }

                @Override
                public void onReceived(File f) {
                }
            };
            int port = server.start();
            link = new ShareLink();
            link.port = port;
            link.token = token;
            link.name = deviceName;
            locks.acquire(this);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return true;
        } catch (Throwable e) {
            Toast.makeText(this, R.string.sh_server_fail, Toast.LENGTH_LONG).show();
            stopAll();
            return false;
        }
    }

    private ImageView qrView;
    private TextView credsView;

    private void showLive() {
        live = true;
        header(getString(R.string.sh_live_sub));
        content.removeAllViews();
        liveStatus = Ui.body(this, getString(R.string.sh_starting), 15, R.color.text_primary);
        content.addView(liveStatus);

        qrView = new ImageView(this);
        qrView.setBackgroundColor(0xFFFFFFFF);
        qrView.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        int side = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 80), Ui.dp(this, 320));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(side, side);
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        qp.topMargin = Ui.dp(this, 10);
        qrView.setVisibility(View.GONE);
        content.addView(qrView, qp);

        credsView = Ui.body(this, "", 14, R.color.text_secondary);
        credsView.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(credsView);

        content.addView(Ui.sectionTitle(this, getString(R.string.sh_activity)));
        livePeers = Ui.body(this, getString(R.string.sh_devices_none), 15, R.color.text_primary);
        content.addView(livePeers);
        liveSpeed = Ui.body(this, "", 14, R.color.text_secondary);
        content.addView(liveSpeed);
        liveBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        Ui.tint(this, liveBar);
        liveBar.setMax(1000);
        content.addView(Ui.block(this, liveBar));

        content.addView(row(R.drawable.ic_add, getString(R.string.sh_add_more), null, v -> addMoreLive()));
        content.addView(button(R.string.sh_copy_code, false, v -> copyCode()));
        content.addView(button(R.string.sh_stop, true, v -> confirmStop()));
        lastTick = System.currentTimeMillis();
        lastBytes = 0;
        ui.postDelayed(ticker, 500);
    }

    private void addMoreLive() {
        String[] items = {getString(R.string.sh_add_files), getString(R.string.sh_add_folder)};
        new Dlg(this).setTitle(getString(R.string.sh_add_more)).setItems(items, (d, w) -> openPicker(w == 1)).show();
    }

    private void copyCode() {
        if (link == null) return;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("fmshare", link.encode()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void publish() {
        if (destroyed || server == null) return;
        List<String> ips = ShareNet.localIps();
        if (ips.isEmpty() && ipTries < 12) {
            ipTries++;
            liveStatus.setText(R.string.sh_live_wait_ip);
            ui.postDelayed(this::publish, 700);
            return;
        }
        link.ips = ips;
        if (useHotspot && hotspot.active) {
            link.ssid = hotspot.ssid;
            link.pass = hotspot.pass;
            link.sec = hotspot.sec;
        } else {
            link.ssid = "";
            link.pass = "";
        }
        try {
            Bitmap bm = ShareQr.make(link.encode(), 720);
            qrView.setImageBitmap(bm);
            qrView.setVisibility(View.VISIBLE);
        } catch (Throwable e) {
            qrView.setVisibility(View.GONE);
        }
        liveStatus.setText(link.ssid.isEmpty() ? R.string.sh_live_ready_manual : R.string.sh_live_ready);
        StringBuilder sb = new StringBuilder();
        if (!link.ssid.isEmpty()) {
            sb.append(getString(R.string.sh_ssid, link.ssid)).append('\n');
            sb.append(getString(R.string.sh_pass, link.pass.isEmpty() ? "—" : link.pass)).append('\n');
        }
        StringBuilder a = new StringBuilder();
        for (String ip : ips) {
            if (a.length() > 0) a.append("  ·  ");
            a.append(ip);
        }
        sb.append(getString(R.string.sh_addr, a + ":" + link.port));
        credsView.setText(sb.toString());
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !live || server == null) return;
            long now = System.currentTimeMillis();
            long bytes = server.sent.get() + server.received.get();
            long dt = Math.max(1, now - lastTick);
            double inst = (bytes - lastBytes) * 1000.0 / dt;
            speed = speed == 0 ? inst : speed * 0.6 + inst * 0.4;
            lastTick = now;
            lastBytes = bytes;
            List<String> peers = server.peers();
            if (peers.isEmpty()) {
                livePeers.setText(R.string.sh_devices_none);
            } else {
                StringBuilder sb = new StringBuilder(getString(R.string.sh_devices, peers.size()));
                for (String p : peers) sb.append('\n').append(p);
                livePeers.setText(sb.toString());
            }
            long total = Math.max(1, server.total());
            liveSpeed.setText(getString(R.string.sh_live_stats, Fmt.size(server.sent.get()), Fmt.size(server.received.get()),
                    Fmt.size((long) speed) + "/s", server.count()));
            liveBar.setProgress((int) Math.min(1000, server.sent.get() * 1000 / total));
            ui.postDelayed(this, 500);
        }
    };

    private void confirmStop() {
        new Dlg(this).setTitle(R.string.sh_stop)
                .setMessage(R.string.sh_stop_q)
                .setPositiveButton(R.string.sh_stop, (d, w) -> finish())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
