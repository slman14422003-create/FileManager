package com.fileman.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Package installer (beta). Opens with a package file (APK / APKS / XAPK / APKM) to inspect and install it,
 * or without one to find packages on the device and manage installed apps (open, info, extract, uninstall).
 */
public class InstallerActivity extends BaseActivity {
    private static final int S_HOME = 0, S_FOUND = 1, S_APPS = 2, S_HISTORY = 3;
    private static final int MAX_FOUND = 200, MAX_APPS = 400, MAX_SHOWN_APPS = 300;

    private static final class App {
        String pkg = "", label = "", ver = "";
        long size, updated;
        Drawable icon;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private View loading;
    private TextView titleView, subtitleView, statusLine;

    private int screen = S_HOME;
    private boolean dirty = false;           // an uninstall may have changed things: refresh on return
    private volatile boolean destroyed = false;

    private List<File> foundFiles = new ArrayList<>();
    private java.util.Map<File, Found> foundInfo = new java.util.HashMap<>();

    /** What an .apk on the device is, and how it compares with the copy installed now. */
    private static final class Found {
        String pkg = "", label = "", ver = "";
        long code;
        int state;   // 0 unknown, 1 new app, 2 update, 3 same version installed, 4 older than installed
    }
    private List<App> apps = new ArrayList<>();
    private LinearLayout appList;
    private String appQuery = "";
    private boolean showSystem = false;
    private int sortMode = 0;               // 0 name, 1 size, 2 recently updated

    private volatile PkgInstaller.Info installing;   // package being installed right now

    private final ArrayDeque<File> queue = new ArrayDeque<>();
    private boolean batchActive = false;
    private int batchOk, batchFail, batchTotal;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> reload());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        ContextCompat.registerReceiver(this, pkgRx, pf, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, rx, new IntentFilter(PkgInstaller.action(this)),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // deep links: launcher shortcuts and the result notification
        Intent launch = getIntent();
        if (launch != null && launch.getBooleanExtra("history", false)) showHistory();
        else if (launch != null && launch.getBooleanExtra("found", false)) scanFound();
        else showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen == S_HOME) {
            showHome();   // the install permission may have just been granted
        } else if (dirty) {
            dirty = false;
            if (screen == S_APPS) loadApps();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        try {
            unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(pkgRx);
        } catch (Exception ignored) {
        }
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private void onBack() {
        if (screen == S_HOME) {
            finish();
        } else {
            showHome();
        }
    }

    private void reload() {
        switch (screen) {
            case S_FOUND:
                scanFound();
                break;
            case S_APPS:
                loadApps();
                break;
            case S_HISTORY:
                showHistory();
                break;
            default:
                showHome();
                break;
        }
    }

    // ------------------------------------------------------------------ small helpers

    private void header(CharSequence sub) {
        titleView.setText(R.string.pk_title);
        subtitleView.setText(sub);
        subtitleView.setVisibility(sub == null || sub.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void busy(boolean on) {
        loading.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void clear() {
        content.removeAllViews();
        statusLine = null;
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private View row(int icon, String title, String sub, View.OnClickListener click) {
        return Ui.rowView(this, content, new Row(icon, false, title, sub, false, click != null), click);
    }

    private boolean ensureCanInstall() {
        if (Perms.canInstall(this)) return true;
        new Dlg(this)
                .setTitle(R.string.perm_install_title)
                .setMessage(R.string.perm_install_dialog)
                .setPositiveButton(R.string.perm_open_settings, (d, w) -> Perms.requestInstall(this))
                .setNegativeButton(R.string.cancel, null)
                .show();
        return false;
    }

    private void setStatus(String s) {
        if (statusLine == null) return;
        statusLine.setText(s);
        statusLine.setVisibility(View.VISIBLE);
    }

    /** Puts the real app icon into a row built by {@link Ui#rowView}. */
    private static void realIcon(View row, Drawable d) {
        if (d == null) return;
        ImageView iv = row.findViewById(R.id.icon);
        iv.setImageTintList(null);
        iv.setBackground(null);
        iv.setImageDrawable(d);
    }

    // ------------------------------------------------------------------ home

    private void showHome() {
        screen = S_HOME;
        header(getString(R.string.pk_subtitle));
        busy(false);
        clear();
        content.addView(Ui.noteCard(this, getString(R.string.pk_beta_note), R.color.info));

        // ---- permissions the installer works with
        content.addView(Ui.sectionTitle(this, getString(R.string.perm_section_needed)));
        boolean can = Perms.canInstall(this);
        Row r = new Row(R.drawable.ic_package, true, getString(R.string.perm_install_title),
                getString(R.string.perm_install_sub), false, !can);
        r.badge(getString(can ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, can ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, r, v -> {
            if (Perms.canInstall(this)) Perms.openAppSettings(this);
            else Perms.requestInstall(this);
        }));

        boolean files = Perms.hasAllFiles(this);
        Row rf = new Row(R.drawable.ic_folder, true, getString(R.string.pk_perm_files),
                getString(R.string.pk_perm_files_sub), false, !files);
        rf.badge(getString(files ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, files ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, rf, v -> {
            if (Perms.hasAllFiles(this)) Perms.openAppSettings(this);
            else Perms.requestAllFiles(this);
        }));

        if (Build.VERSION.SDK_INT >= 33) {
            boolean notif = Perms.hasNotifications(this);
            Row rn = new Row(R.drawable.ic_info, true, getString(R.string.pk_perm_notif),
                    getString(R.string.pk_perm_notif_sub), false, !notif);
            rn.badge(getString(notif ? R.string.perm_granted : R.string.perm_denied),
                    Ui.color(this, notif ? R.color.ok : R.color.bad));
            content.addView(Ui.rowView(this, content, rn, v -> {
                if (Perms.hasNotifications(this)) Perms.openAppSettings(this);
                else Perms.requestNotifications(this);
            }));
        }
        boolean batt = Perms.ignoresBattery(this);
        Row rb = new Row(R.drawable.ic_timer, true, getString(R.string.pk_perm_battery),
                getString(R.string.pk_perm_battery_sub), false, !batt);
        rb.badge(getString(batt ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, batt ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, rb, v -> {
            if (Perms.ignoresBattery(this)) Perms.openAppSettings(this);
            else Perms.requestBattery(this);
        }));

        boolean usage = Perms.hasUsageAccess(this);
        Row ru = new Row(R.drawable.ic_chart, true, getString(R.string.pk_perm_usage),
                getString(R.string.pk_perm_usage_sub), false, !usage);
        ru.badge(getString(usage ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, usage ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, ru, v -> Perms.openUsageAccess(this)));

        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_shield, false,
                getString(R.string.pk_perm_installer), getString(R.string.pk_perm_installer_sub), false, false)
                .badge(getString(R.string.perm_granted), Ui.color(this, R.color.ok)), null));

        // ---- security (beta 5)
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_section)));
        addOption("warn_high", true, R.string.sec_opt_warn, R.string.sec_opt_warn_sub);
        addOption("lock", false, R.string.sec_opt_lock, R.string.sec_opt_lock_sub);
        boolean secure = AuthGate.isSecure(this);
        Row rlock = new Row(R.drawable.ic_lock, true, getString(R.string.sec_perm_lock),
                getString(R.string.sec_perm_lock_sub), false, !secure);
        rlock.badge(getString(secure ? R.string.pk_default_yes : R.string.pk_default_no),
                Ui.color(this, secure ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, rlock, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception e) {
                Perms.openAppSettings(this);
            }
        }));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_cancel, false,
                getString(R.string.sec_blocked_list, Store.blockedPkgs(this).size()),
                getString(R.string.sec_list_sub), false, true), v -> manageSecurityList(true)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_lock, false,
                getString(R.string.sec_trusted_list, Store.trustedCerts(this).size()),
                getString(R.string.sec_list_sub), false, true), v -> manageSecurityList(false)));

        // ---- integration: make this the app that opens APK files
        final String apkMime = "application/vnd.android.package-archive";
        boolean isDef = Perms.isDefaultFor(this, apkMime, "apk");
        Row rdef = new Row(R.drawable.ic_open, true, getString(R.string.pk_default_installer),
                getString(R.string.pk_default_installer_sub), false, !isDef);
        rdef.badge(getString(isDef ? R.string.pk_default_yes : R.string.pk_default_no),
                Ui.color(this, isDef ? R.color.ok : R.color.text_secondary));
        content.addView(Ui.rowView(this, content, rdef, v -> Perms.chooseDefault(this, apkMime, "apk")));

        // ---- installer options
        content.addView(Ui.sectionTitle(this, getString(R.string.pk_h_settings)));
        addOption("smart", true, R.string.pk_opt_smart, R.string.pk_opt_smart_sub);
        addOption("verify", true, R.string.pk_opt_verify, R.string.pk_opt_verify_sub);
        addOption("deep", true, R.string.pk_opt_deep, R.string.pk_opt_deep_sub);
        addOption("delete_after", false, R.string.pk_opt_delete, R.string.pk_opt_delete_sub);
        addOption("notify", true, R.string.pk_opt_notify, R.string.pk_opt_notify_sub);
        addOption("awake", true, R.string.pk_opt_awake, R.string.pk_opt_awake_sub);
        if (Build.VERSION.SDK_INT >= 34) {
            addOption("owner", false, R.string.pk_opt_owner, R.string.pk_opt_owner_sub);
        }

        // ---- tools
        content.addView(Ui.sectionTitle(this, getString(R.string.pk_tools)));
        content.addView(row(R.drawable.ic_search, getString(R.string.pk_found), getString(R.string.pk_found_sub),
                v -> scanFound()));
        content.addView(row(R.drawable.ic_archive, getString(R.string.pk_apps), getString(R.string.pk_apps_sub),
                v -> loadApps()));
        content.addView(row(R.drawable.ic_clock, getString(R.string.pk_h_history), getString(R.string.pk_h_history_sub),
                v -> showHistory()));
    }

    /** Block list (package names) or trusted signers (certificate fingerprints): tap one to remove it. */
    private void manageSecurityList(final boolean blocked) {
        final List<String> keys = new ArrayList<>(blocked ? Store.blockedPkgs(this) : Store.trustedCerts(this));
        if (keys.isEmpty()) {
            toast(R.string.sec_empty);
            return;
        }
        String[] labels = new String[keys.size()];
        for (int i = 0; i < labels.length; i++) {
            String k = keys.get(i);
            if (blocked) {
                labels[i] = k;
            } else {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j + 2 <= Math.min(k.length(), 16); j += 2) {
                    if (sb.length() > 0) sb.append(':');
                    sb.append(k, j, j + 2);
                }
                labels[i] = sb.toString().toUpperCase(java.util.Locale.ROOT) + "…";
            }
        }
        Dlg dlg = new Dlg(this);
        dlg.setTitle(getString(blocked ? R.string.sec_blocked_list_t : R.string.sec_trusted_list_t));
        dlg.setItems(labels, (d, w) -> {
            if (blocked) Store.setBlocked(this, keys.get(w), false);
            else Store.setTrusted(this, keys.get(w), false);
            toast(blocked ? R.string.sec_unblocked : R.string.sec_untrusted);
            showHome();
        });
        dlg.show();
    }

    private static int optionIcon(String key) {
        switch (key) {
            case "smart":
                return R.drawable.ic_package;
            case "verify":
            case "deep":
                return R.drawable.ic_shield;
            case "delete_after":
                return R.drawable.ic_delete;
            case "notify":
                return R.drawable.ic_info;
            case "warn_high":
                return R.drawable.ic_shield;
            case "awake":
                return R.drawable.ic_timer;
            default:
                return R.drawable.ic_lock;
        }
    }

    private void addOption(final String key, boolean def, int titleRes, int subRes) {
        Ui.Toggle t = Ui.toggle(this, content, titleRes, subRes, Store.instBool(this, key, def));
        t.withIcon(optionIcon(key));
        t.onChange((b, on) -> Store.setInstBool(this, key, on));
        content.addView(t.view);
    }

    // ------------------------------------------------------------------ install history

    private void showHistory() {
        screen = S_HISTORY;
        header(getString(R.string.pk_h_history));
        busy(false);
        clear();
        String raw = Store.instHistory(this);
        if (raw.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.pk_hist_none), 14, R.color.text_secondary));
            return;
        }
        content.addView(row(R.drawable.ic_delete, getString(R.string.pk_hist_clear), null, v ->
                new Dlg(this).setTitle(R.string.pk_hist_clear)
                        .setMessage(R.string.pk_hist_clear_q)
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            Store.setInstHistory(this, "");
                            showHistory();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show()));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (String line : raw.split("\n")) {
            final String[] f = line.split("\t", -1);
            if (f.length < 5) continue;
            long t = 0;
            try {
                t = Long.parseLong(f[0]);
            } catch (NumberFormatException ignored) {
            }
            boolean ok = "1".equals(f[1]);
            String sub = (f[4].isEmpty() ? "" : f[4] + " · ") + Fmt.ago(t)
                    + (!ok && f.length > 5 && !f[5].isEmpty() ? "\n" + f[5] : "");
            Row hr = new Row(ok ? R.drawable.ic_check_circle : R.drawable.ic_cancel, false, f[2], sub, false, ok)
                    .tint(Ui.color(this, ok ? R.color.ok : R.color.bad));
            hr.badge(getString(ok ? R.string.pk_hist_ok : R.string.pk_hist_fail), Ui.color(this, ok ? R.color.ok : R.color.bad));
            final String pkg = f[3];
            list.addView(Ui.rowView(this, list, hr, ok ? v -> openApp(pkg) : null));
        }
        Ui.group(this, list);
        content.addView(list);
    }

    // ------------------------------------------------------------------ packages on the device

    private void scanFound() {
        screen = S_FOUND;
        header(getString(R.string.pk_found));
        clear();
        busy(true);
        content.addView(Ui.body(this, getString(R.string.pk_scanning), 14, R.color.text_secondary));
        io.execute(() -> {
            List<Cats.Hit> hits = new ArrayList<>();
            walk(Environment.getExternalStorageDirectory(), hits, new int[]{150000}, 0);
            Collections.sort(hits, Cats.HIT_NEWEST);
            final List<File> out = new ArrayList<>();
            for (Cats.Hit h : hits) {
                out.add(h.f);
                if (out.size() >= MAX_FOUND) break;
            }
            final java.util.Map<File, Found> info = new java.util.HashMap<>();
            PackageManager pm = getPackageManager();
            for (File f : out) {
                if (destroyed) return;
                if (!"apk".equals(Cats.extOf(f.getName()))) continue;
                Found fi = quickInfo(pm, f);
                if (fi != null) info.put(f, fi);
            }
            ui.post(() -> {
                if (destroyed || screen != S_FOUND) return;
                foundFiles = out;
                foundInfo = info;
                showFoundList();
            });
        });
    }

    @SuppressWarnings("deprecation")
    private static long codeOf(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    /** Reads an .apk's package name / version and compares it with the installed copy. */
    private Found quickInfo(PackageManager pm, File f) {
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || pi.packageName == null) return null;
            Found x = new Found();
            x.pkg = pi.packageName;
            x.ver = pi.versionName == null ? "" : pi.versionName;
            x.code = codeOf(pi);
            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null) {
                ai.sourceDir = f.getAbsolutePath();
                ai.publicSourceDir = f.getAbsolutePath();
                try {
                    x.label = String.valueOf(ai.loadLabel(pm));
                } catch (Throwable ignored) {
                }
            }
            try {
                long cur = codeOf(pm.getPackageInfo(x.pkg, 0));
                x.state = x.code > cur ? 2 : x.code == cur ? 3 : 4;
            } catch (PackageManager.NameNotFoundException e) {
                x.state = 1;
            }
            return x;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Older copies of a package that has a newer (or equal, newer-dated) file in the list. */
    private List<File> duplicateFiles() {
        java.util.Map<String, List<File>> groups = new java.util.HashMap<>();
        for (File f : foundFiles) {
            Found fi = foundInfo.get(f);
            if (fi == null) continue;
            List<File> g = groups.get(fi.pkg);
            if (g == null) groups.put(fi.pkg, g = new ArrayList<>());
            g.add(f);
        }
        List<File> del = new ArrayList<>();
        for (List<File> g : groups.values()) {
            if (g.size() < 2) continue;
            Collections.sort(g, (x, y) -> {
                int c = Long.compare(foundInfo.get(y).code, foundInfo.get(x).code);
                return c != 0 ? c : Long.compare(y.lastModified(), x.lastModified());
            });
            for (int i = 1; i < g.size(); i++) del.add(g.get(i));
        }
        return del;
    }

    private void confirmDupes(final List<File> del) {
        long total = 0;
        for (File f : del) total += f.length();
        new Dlg(this)
                .setTitle(R.string.pk_dupes_sub)
                .setMessage(getString(R.string.pk_dupes_q, del.size(), Fmt.size(total)))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    busy(true);
                    io.execute(() -> {
                        int n = 0;
                        for (File f : del) if (f.delete()) n++;
                        final int done = n;
                        ui.post(() -> {
                            if (destroyed) return;
                            Toast.makeText(this, getString(R.string.pk_dupes_done, done), Toast.LENGTH_SHORT).show();
                            scanFound();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void walk(File dir, List<Cats.Hit> out, int[] budget, int depth) {
        if (destroyed || budget[0] <= 0 || depth > 12) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (destroyed || budget[0] <= 0) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (depth == 0 && n.equals("Android")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) walk(f, out, budget, depth + 1);
            } else if (PkgInstaller.isPackageExt(Cats.extOf(n))) {
                out.add(new Cats.Hit(f));
            }
        }
    }

    private void showFoundList() {
        screen = S_FOUND;
        header(getString(R.string.pk_found));
        busy(false);
        clear();
        if (foundFiles.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.pk_none_found), 14, R.color.text_secondary));
            return;
        }
        final List<File> singles = new ArrayList<>(foundFiles);
        final List<File> updates = new ArrayList<>();
        for (File f : foundFiles) {
            Found fi = foundInfo.get(f);
            if (fi != null && fi.state == 2) updates.add(f);
        }
        if (!updates.isEmpty()) {
            content.addView(row(R.drawable.ic_refresh, getString(R.string.pk_updates, updates.size()),
                    getString(R.string.pk_updates_sub), v -> confirmBatch(updates)));
        }
        if (singles.size() > 1) {
            content.addView(row(R.drawable.ic_download, getString(R.string.pk_install_all, singles.size()),
                    getString(R.string.pk_install_all_sub), v -> confirmBatch(singles)));
        }
        final List<File> dupes = duplicateFiles();
        if (!dupes.isEmpty()) {
            content.addView(row(R.drawable.ic_delete, getString(R.string.pk_dupes, dupes.size()),
                    getString(R.string.pk_dupes_sub), v -> confirmDupes(dupes)));
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final File f : foundFiles) {
            String ext = Cats.extOf(f.getName());
            File p = f.getParentFile();
            String sub = Fmt.size(f.length()) + " · " + Fmt.ago(f.lastModified())
                    + (p != null ? " · " + p.getName() : "");
            Found fi = foundInfo.get(f);
            String title = f.getName();
            if (fi != null) {
                if (!fi.label.isEmpty()) title = fi.label;
                if (!fi.ver.isEmpty()) sub = fi.ver + " · " + sub;
            }
            Row r = new Row(R.drawable.ic_package, false, title, sub, false, true)
                    .tint(Ui.color(this, R.color.ok));
            if (!ext.equals("apk")) {
                r.badge(ext.toUpperCase(Locale.ROOT), Ui.color(this, R.color.accent_text));
            } else if (fi != null && fi.state != 0) {
                int[] names = {0, R.string.pk_st_new_chip, R.string.pk_st_update_chip, R.string.pk_st_same_chip,
                        R.string.pk_st_older_chip};
                int[] cols = {0, R.color.accent_text, R.color.ok, R.color.text_secondary, R.color.warn};
                r.badge(getString(names[fi.state]), Ui.color(this, cols[fi.state]));
            }
            list.addView(Ui.rowView(this, list, r, v -> showFile(f)));
        }
        Ui.group(this, list);
        content.addView(list);
    }

    // ------------------------------------------------------------------ one package

    private void showFile(File f) {
        startActivity(new Intent(this, InstallActivity.class).setData(Uri.fromFile(f)));
    }

    // ------------------------------------------------------------------ installing

    private void runInstall(final File f, final PkgInstaller.Info known) {
        AuthGate.require(this, () -> runInstallNow(f, known));
    }

    /** Blocks the worker thread until the person answers the high-risk question. */
    private boolean askHighRisk(final PkgInstaller.Info in) throws InterruptedException {
        final boolean[] ok = {false};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            if (destroyed) {
                latch.countDown();
                return;
            }
            SecurityScan.hold(new Dlg(this).setTitle(getString(R.string.sec_high_title))
                    .setMessage(getString(R.string.sec_high_msg, in.label.isEmpty() ? in.pkg : in.label))
                    .setPositiveButton(R.string.sec_install_anyway, (d, w) -> {
                        ok[0] = true;
                        latch.countDown();
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> latch.countDown())
                    .setOnCancelListener(d -> latch.countDown())
                    .show(), SecurityScan.HIGH_RISK_WAIT_S);
        });
        latch.await();
        return ok[0];
    }

    private void runInstallNow(final File f, final PkgInstaller.Info known) {
        if (Store.instBool(this, "awake", true)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        busy(true);
        setStatus(getString(R.string.pk_inspecting));
        io.execute(() -> {
            try {
                PkgInstaller.Info in = known != null ? known : PkgInstaller.inspect(this, f);
                installing = in;
                SecurityScan.Result sr = SecurityScan.scan(this, in);
                if (sr.blocked) {
                    ui.post(() -> onInstallResult(false, getString(R.string.sec_blocked_m)));
                    return;
                }
                if (sr.level == SecurityScan.HIGH && Store.instBool(this, "warn_high", true) && !askHighRisk(in)) {
                    ui.post(() -> onInstallResult(false, getString(R.string.sec_cancelled)));
                    return;
                }
                ui.post(() -> setStatus(getString(R.string.pk_installing, 0)));
                PkgInstaller.install(this, in, (done, total) -> ui.post(() -> {
                    int pct = (int) Math.min(100, done * 100 / Math.max(1, total));
                    setStatus(getString(R.string.pk_installing, pct));
                }));
                // the final result arrives through the receiver below
                ui.post(() -> setStatus(getString(R.string.pk_waiting)));
            } catch (PkgInstaller.NoSpace e) {
                ui.post(() -> onInstallResult(false, getString(R.string.pk_fail_storage)));
            } catch (Throwable e) {
                ui.post(() -> onInstallResult(false, getString(R.string.pk_fail_invalid)));
            }
        });
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    try {
                        startActivity(confirm);
                    } catch (Exception e) {
                        onInstallResult(false, getString(R.string.pk_fail_blocked));
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                onInstallResult(true, null);
            } else {
                onInstallResult(false, friendly(st, msg));
            }
        }
    };

    private String friendly(int st, String msg) {
        int fr = PkgInstaller.friendlyMessage(msg);
        if (fr != 0) return getString(fr);
        switch (st) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return getString(R.string.pk_fail_aborted);
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return getString(R.string.pk_fail_blocked);
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return getString(R.string.pk_fail_conflict);
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return getString(R.string.pk_fail_incompat);
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return getString(R.string.pk_fail_storage);
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return getString(R.string.pk_fail_invalid);
            default:
                return getString(R.string.pk_fail_other, msg == null || msg.isEmpty() ? String.valueOf(st) : msg);
        }
    }

    private void onInstallResult(boolean ok, String msg) {
        if (destroyed) return;
        busy(false);
        if (!batchActive || queue.isEmpty()) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        PkgInstaller.Info in = installing;
        String label = in != null && !in.label.isEmpty() ? in.label : getString(R.string.pk_title);
        if (in != null) PkgInstaller.log(this, in, ok, msg);
        if (batchActive) {
            if (ok) batchOk++;
            else batchFail++;
            nextInBatch();
            return;
        }
        Dlg.result(this, ok, getString(ok ? R.string.pk_ok_title : R.string.pk_fail_title),
                ok ? getString(R.string.pk_ok_msg, label) : msg);
    }

    // ------------------------------------------------------------------ batch

    private void confirmBatch(final List<File> files) {
        new Dlg(this)
                .setTitle(R.string.pk_batch_title)
                .setMessage(getString(R.string.pk_batch_msg, files.size()))
                .setPositiveButton(R.string.pk_install, (d, w) -> {
                    if (!ensureCanInstall()) return;
                    queue.clear();
                    queue.addAll(files);
                    batchOk = 0;
                    batchFail = 0;
                    batchTotal = files.size();
                    batchActive = true;
                    nextInBatch();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void nextInBatch() {
        final File f = queue.poll();
        if (f == null) {
            batchActive = false;
            busy(false);
            Dlg.result(this, batchFail == 0, getString(R.string.pk_title),
                    getString(R.string.pk_batch_done, batchOk, batchFail));
            showFoundList();
            return;
        }
        clear();
        busy(true);
        int n = batchTotal - queue.size();
        content.addView(Ui.sectionTitle(this, getString(R.string.pk_batch_title) + " " + n + "/" + batchTotal));
        content.addView(Ui.body(this, f.getName(), 15, R.color.text_primary));
        statusLine = Ui.body(this, "", 14, R.color.text_secondary);
        content.addView(statusLine);
        runInstall(f, null);
    }

    // ------------------------------------------------------------------ installed apps

    private void loadApps() {
        screen = S_APPS;
        header(getString(R.string.pk_apps));
        clear();
        busy(true);
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            List<App> out = new ArrayList<>();
            try {
                for (PackageInfo pi : pm.getInstalledPackages(0)) {
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai == null) continue;
                    boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                    if (sys && !showSystem) continue;
                    App a = new App();
                    a.pkg = pi.packageName;
                    a.label = String.valueOf(ai.loadLabel(pm));
                    a.ver = pi.versionName == null ? "" : pi.versionName;
                    a.size = ai.sourceDir == null ? 0 : new File(ai.sourceDir).length();
                    a.updated = pi.lastUpdateTime;
                    out.add(a);
                    if (out.size() >= MAX_APPS) break;
                }
            } catch (Throwable ignored) {
            }
            Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
            for (App a : out) {
                if (destroyed) return;
                try {
                    a.icon = pm.getApplicationIcon(a.pkg);
                } catch (Exception ignored) {
                }
            }
            ui.post(() -> {
                if (destroyed || screen != S_APPS) return;
                apps = out;
                sortApps();
                renderApps();
            });
        });
    }

    /** One app was installed, updated or removed: patch just that entry in the shown list. */
    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            if (destroyed || screen != S_APPS || apps == null || i.getData() == null) return;
            final String pkg = i.getData().getSchemeSpecificPart();
            final boolean removed = Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction())
                    && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            io.execute(() -> {
                final App a = removed ? null : loadApp(pkg);
                ui.post(() -> {
                    if (destroyed || screen != S_APPS || apps == null) return;
                    for (int k = apps.size() - 1; k >= 0; k--) {
                        if (pkg.equals(apps.get(k).pkg)) apps.remove(k);
                    }
                    if (a != null) apps.add(a);
                    sortApps();
                    renderApps();
                });
            });
        }
    };

    private App loadApp(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) return null;
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (sys && !showSystem) return null;
            App a = new App();
            a.pkg = pi.packageName;
            a.label = String.valueOf(ai.loadLabel(pm));
            a.ver = pi.versionName == null ? "" : pi.versionName;
            a.size = ai.sourceDir == null ? 0 : new File(ai.sourceDir).length();
            a.updated = pi.lastUpdateTime;
            try {
                a.icon = pm.getApplicationIcon(a.pkg);
            } catch (Exception ignored) {
            }
            return a;
        } catch (Throwable e) {
            return null;
        }
    }

    private void sortApps() {
        Collections.sort(apps, (x, y) -> {
            if (sortMode == 1) return Long.compare(y.size, x.size);
            if (sortMode == 2) return Long.compare(y.updated, x.updated);
            return x.label.compareToIgnoreCase(y.label);
        });
    }

    private void renderApps() {
        busy(false);
        clear();
        EditText q = Ui.block(this, Ui.edit(this, getString(R.string.pk_search_hint), appQuery));
        q.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                appQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                fillApps();
            }
        });
        content.addView(q);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 4));
        final int[] sortNames = {R.string.pk_sort_name, R.string.pk_sort_size, R.string.pk_sort_date};
        for (int k = 0; k < sortNames.length; k++) {
            final int mode = k;
            TextView ch = Ui.chip(this, getString(sortNames[k]), sortMode == k);
            ch.setOnClickListener(v -> {
                sortMode = mode;
                sortApps();
                renderApps();
            });
            chips.addView(ch);
        }
        content.addView(chips);

        Ui.Toggle sys = Ui.toggle(this, content, R.string.pk_show_system, R.string.pk_show_system_sub, showSystem);
        sys.withIcon(R.drawable.ic_shield);
        sys.onChange((b, on) -> {
            showSystem = on;
            loadApps();
        });
        content.addView(sys.view);

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        content.addView(appList);
        fillApps();
    }

    private void fillApps() {
        if (appList == null) return;
        appList.removeAllViews();
        int shown = 0;
        int matches = 0;
        for (final App a : apps) {
            if (!appQuery.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(appQuery)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(appQuery)) continue;
            matches++;
            if (shown >= MAX_SHOWN_APPS) continue;
            shown++;
            String sub = a.pkg + (a.ver.isEmpty() ? "" : " · " + a.ver) + " · " + Fmt.size(a.size);
            View v = Ui.rowView(this, appList, new Row(R.drawable.ic_package, false, a.label, sub, false, true),
                    x -> appMenu(a));
            realIcon(v, a.icon);
            appList.addView(v);
        }
        Ui.group(this, appList);
        if (matches == 0) {
            appList.addView(Ui.body(this, getString(R.string.pk_none_found), 14, R.color.text_secondary));
        } else {
            appList.addView(Ui.body(this, getString(R.string.pk_n_apps, matches), 13, R.color.text_hint), 0);
        }
    }

    private void appMenu(final App a) {
        String[] items = {getString(R.string.pk_open_app), getString(R.string.pk_details),
                getString(R.string.pk_app_info), getString(R.string.pk_extract),
                getString(R.string.pk_share), getString(R.string.pk_uninstall)};
        new Dlg(this).setTitle(a.label).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    openApp(a.pkg);
                    break;
                case 1:
                    appDetails(a);
                    break;
                case 2:
                    appInfo(a.pkg);
                    break;
                case 3:
                    extractApk(a);
                    break;
                case 4:
                    shareApk(a);
                    break;
                default:
                    uninstall(a.pkg);
                    break;
            }
        }).show();
    }

    private void detail(StringBuilder sb, int label, String value) {
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(getString(label)).append("\n").append(value);
    }

    /** One dialog with everything useful about an installed app. */
    private void appDetails(final App a) {
        PackageManager pm = getPackageManager();
        StringBuilder sb = new StringBuilder();
        try {
            PackageInfo pi = pm.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS);
            ApplicationInfo ai = pi.applicationInfo;
            java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance();
            detail(sb, R.string.pk_ad_pkg, a.pkg);
            detail(sb, R.string.pk_d_version, (pi.versionName == null ? "" : pi.versionName) + " (" + codeOf(pi) + ")");
            detail(sb, R.string.pk_d_size, Fmt.size(a.size));
            if (ai != null) {
                detail(sb, R.string.pk_d_sdk, getString(R.string.pk_d_sdk_sub, ai.minSdkVersion, ai.targetSdkVersion));
                if (ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0) {
                    detail(sb, R.string.pk_d_splits, getString(R.string.pk_d_splits_sub, ai.splitSourceDirs.length + 1));
                }
            }
            detail(sb, R.string.pk_ad_first, df.format(new java.util.Date(pi.firstInstallTime)));
            detail(sb, R.string.pk_ad_updated, df.format(new java.util.Date(pi.lastUpdateTime)));
            String src = null;
            try {
                src = Build.VERSION.SDK_INT >= 30 ? pm.getInstallSourceInfo(a.pkg).getInstallingPackageName()
                        : pm.getInstallerPackageName(a.pkg);
            } catch (Throwable ignored) {
            }
            if (src != null) {
                try {
                    src = pm.getApplicationLabel(pm.getApplicationInfo(src, 0)) + " (" + src + ")";
                } catch (Throwable ignored) {
                }
            }
            detail(sb, R.string.pk_ad_source, src == null ? getString(R.string.pk_ad_unknown) : src);
            detail(sb, R.string.pk_ad_perms, String.valueOf(pi.requestedPermissions == null ? 0 : pi.requestedPermissions.length));
        } catch (Throwable e) {
            sb.append(a.pkg);
        }
        new Dlg(this).setTitle(a.label).setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast(R.string.pk_no_launch);
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(R.string.pk_no_launch);
        }
    }

    private void appInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (Exception ignored) {
        }
    }

    private void uninstall(final String pkg) {
        AuthGate.require(this, () -> uninstallNow(pkg));
    }

    private void uninstallNow(String pkg) {
        dirty = true;
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            dirty = false;
            toast(R.string.pk_no_launch);
        }
    }

    /** Writes the installed app as .apk (or all its splits as one .apks bundle) into dir. */
    private File writeApp(App a, File dir) throws Exception {
        ApplicationInfo ai = getPackageManager().getApplicationInfo(a.pkg, 0);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String name = (a.label + "_" + a.ver).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        String[] splits = ai.splitSourceDirs;
        File out;
        if (splits == null || splits.length == 0) {
            out = new File(dir, name + ".apk");
            PkgInstaller.copy(new FileInputStream(ai.sourceDir), new FileOutputStream(out), null);
        } else {
            out = new File(dir, name + ".apks");
            try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                z.setLevel(Deflater.NO_COMPRESSION);   // APKs are compressed already
                z.putNextEntry(new ZipEntry("splits/base.apk"));
                pump(new FileInputStream(ai.sourceDir), z);
                z.closeEntry();
                for (String s : splits) {
                    z.putNextEntry(new ZipEntry("splits/" + new File(s).getName()));
                    pump(new FileInputStream(s), z);
                    z.closeEntry();
                }
            }
        }
        return out;
    }

    /** Copies the installed APK (or all its splits as one .apks bundle) to Download/FileManager-APKs. */
    private void extractApk(final App a) {
        busy(true);
        io.execute(() -> {
            String result;
            boolean ok = false;
            try {
                File base = Perms.hasAllFiles(this)
                        ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        : getExternalFilesDir(null);
                File dir = new File(base, Perms.hasAllFiles(this) ? "FileManager-APKs" : "apks");
                result = writeApp(a, dir).getAbsolutePath();
                ok = true;
            } catch (Throwable e) {
                result = String.valueOf(e.getMessage());
            }
            final boolean fok = ok;
            final String fres = result;
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                Dlg.result(this, fok, getString(fok ? R.string.pk_extract_done : R.string.pk_extract_fail), fres);
            });
        });
    }

    /** Builds the package in the cache folder and opens the system share sheet for it. */
    private void shareApk(final App a) {
        busy(true);
        io.execute(() -> {
            File out = null;
            String err = null;
            try {
                File dir = new File(getCacheDir(), "share");
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
                    o.delete();
                out = writeApp(a, dir);
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final File fo = out;
            final String ferr = err;
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                if (fo == null) {
                    Dlg.result(this, false, getString(R.string.pk_extract_fail), ferr);
                    return;
                }
                try {
                    Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", fo);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(fo.getName().endsWith(".apk") ? "application/vnd.android.package-archive"
                            : "application/octet-stream");
                    send.putExtra(Intent.EXTRA_STREAM, u);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, a.label));
                } catch (Exception e) {
                    Dlg.result(this, false, getString(R.string.pk_extract_fail), String.valueOf(e.getMessage()));
                }
            });
        });
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = i.read(buf)) > 0) out.write(buf, 0, r);
        }
    }
}
