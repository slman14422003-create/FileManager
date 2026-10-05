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
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
public class InstallerActivity extends AppCompatActivity {
    private static final int S_HOME = 0, S_FOUND = 1, S_APPS = 2;
    private static final int MAX_FOUND = 200, MAX_APPS = 400, MAX_SHOWN_APPS = 300;

    private static final class App {
        String pkg = "", label = "", ver = "";
        long size;
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
    private List<App> apps = new ArrayList<>();
    private LinearLayout appList;
    private String appQuery = "";
    private boolean showSystem = false;

    private volatile PkgInstaller.Info installing;   // package being installed right now

    private final ArrayDeque<File> queue = new ArrayDeque<>();
    private boolean batchActive = false;
    private int batchOk, batchFail, batchTotal;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
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

        ContextCompat.registerReceiver(this, rx, new IntentFilter(PkgInstaller.action(this)),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        showHome();
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
        content.addView(Ui.noteCard(this, getString(R.string.pk_beta_note), R.color.warn));

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

        content.addView(Ui.sectionTitle(this, getString(R.string.pk_tools)));
        content.addView(row(R.drawable.ic_search, getString(R.string.pk_found), getString(R.string.pk_found_sub),
                v -> scanFound()));
        content.addView(row(R.drawable.ic_archive, getString(R.string.pk_apps), getString(R.string.pk_apps_sub),
                v -> loadApps()));
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
            ui.post(() -> {
                if (destroyed || screen != S_FOUND) return;
                foundFiles = out;
                showFoundList();
            });
        });
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
        final List<File> singles = new ArrayList<>();
        for (File f : foundFiles) if (Cats.extOf(f.getName()).equals("apk")) singles.add(f);
        if (singles.size() > 1) {
            content.addView(row(R.drawable.ic_download, getString(R.string.pk_install_all, singles.size()),
                    getString(R.string.pk_install_all_sub), v -> confirmBatch(singles)));
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final File f : foundFiles) {
            String ext = Cats.extOf(f.getName());
            File p = f.getParentFile();
            String sub = Fmt.size(f.length()) + " · " + Fmt.ago(f.lastModified())
                    + (p != null ? " · " + p.getName() : "");
            Row r = new Row(R.drawable.ic_package, false, f.getName(), sub, false, true)
                    .tint(Ui.color(this, R.color.ok));
            if (!ext.equals("apk")) r.badge(ext.toUpperCase(Locale.ROOT), Ui.color(this, R.color.accent_text));
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
        busy(true);
        setStatus(getString(R.string.pk_inspecting));
        io.execute(() -> {
            try {
                PkgInstaller.Info in = known != null ? known : PkgInstaller.inspect(this, f);
                installing = in;
                ui.post(() -> setStatus(getString(R.string.pk_installing, 0)));
                PkgInstaller.install(this, in, (done, total) -> ui.post(() -> {
                    int pct = (int) Math.min(100, done * 100 / Math.max(1, total));
                    setStatus(getString(R.string.pk_installing, pct));
                }));
                // the final result arrives through the receiver below
                ui.post(() -> setStatus(getString(R.string.pk_waiting)));
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
        PkgInstaller.Info in = installing;
        String label = in != null && !in.label.isEmpty() ? in.label : getString(R.string.pk_title);
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
                renderApps();
            });
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

        Ui.Toggle sys = Ui.toggle(this, content, R.string.pk_show_system, R.string.pk_show_system_sub, showSystem);
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
        String[] items = {getString(R.string.pk_open_app), getString(R.string.pk_app_info),
                getString(R.string.pk_extract), getString(R.string.pk_uninstall)};
        new Dlg(this).setTitle(a.label).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    openApp(a.pkg);
                    break;
                case 1:
                    appInfo(a.pkg);
                    break;
                case 2:
                    extractApk(a);
                    break;
                default:
                    uninstall(a.pkg);
                    break;
            }
        }).show();
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

    private void uninstall(String pkg) {
        dirty = true;
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            dirty = false;
            toast(R.string.pk_no_launch);
        }
    }

    /** Copies the installed APK (or all its splits as one .apks bundle) to Download/FileManager-APKs. */
    private void extractApk(final App a) {
        busy(true);
        io.execute(() -> {
            String result;
            boolean ok = false;
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(a.pkg, 0);
                File base = Perms.hasAllFiles(this)
                        ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        : getExternalFilesDir(null);
                File dir = new File(base, Perms.hasAllFiles(this) ? "FileManager-APKs" : "apks");
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
                ok = true;
                result = out.getAbsolutePath();
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

    private static void pump(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = i.read(buf)) > 0) out.write(buf, 0, r);
        }
    }
}
