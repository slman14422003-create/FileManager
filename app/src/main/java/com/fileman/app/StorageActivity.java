package com.fileman.app;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Storage analysis: used space and a size per file kind, the apps that take the most room (needs usage access),
 * the largest files and the caches. Same card style as the rest of the app.
 */
public class StorageActivity extends BaseActivity {
    private static final long LARGE = 10L * 1024 * 1024;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private LinearLayout content;
    private View loading;
    private boolean destroyed;

    private static final class Big {
        final File f;
        final long size;

        Big(File f, long size) {
            this.f = f;
            this.size = size;
        }
    }

    private static final class AppSize {
        final String label;
        final long bytes, cache;

        AppSize(String label, long bytes, long cache) {
            this.label = label;
            this.bytes = bytes;
            this.cache = cache;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        ((TextView) findViewById(R.id.title)).setText(R.string.home_analysis);
        findViewById(R.id.subtitle).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        ImageButton refresh = findViewById(R.id.btnRefresh);
        refresh.setOnClickListener(v -> load());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        loading = findViewById(R.id.loading);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        stop.set(true);
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ data

    private void load() {
        stop.set(true);
        final AtomicBoolean mine = new AtomicBoolean(false);
        stop.set(false);
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            File root = Environment.getExternalStorageDirectory();
            long total = 0, free = 0;
            try {
                StatFs st = new StatFs(root.getAbsolutePath());
                total = st.getTotalBytes();
                free = st.getAvailableBytes();
            } catch (Exception ignored) {
            }
            Cats.Stats stats = Cats.scan(root, true, 1, stop);
            final List<Big> bigs = new ArrayList<>();
            final long[] bigTotal = {0};
            findBig(root, bigs, bigTotal, new int[]{150000}, 0);
            Collections.sort(bigs, (a, c) -> Long.compare(c.size, a.size));
            final boolean usage = hasUsageAccess();
            final List<AppSize> apps = usage ? appSizes() : new ArrayList<>();
            final long cacheTotal = usage ? sumCache(apps) : dirSize(getCacheDir()) + dirSize(getExternalCacheDir());
            final long ft = total, ff = free;
            ui.post(() -> {
                if (destroyed) return;
                loading.setVisibility(View.INVISIBLE);
                render(ft, ff, stats, bigs, bigTotal[0], usage, apps, cacheTotal);
            });
        });
    }

    private static void findBig(File dir, List<Big> out, long[] total, int[] budget, int depth) {
        if (budget[0] <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (budget[0]-- <= 0) return;
            if (f.isDirectory()) {
                if (depth == 0 && f.getName().equals("Android")) continue;
                if (!Cats.isLink(f)) findBig(f, out, total, budget, depth + 1);
            } else {
                long len = f.length();
                if (len >= LARGE) {
                    total[0] += len;
                    out.add(new Big(f, len));
                }
            }
        }
    }

    private static long dirSize(File d) {
        if (d == null) return 0;
        File[] k = d.listFiles();
        if (k == null) return d.length();
        long t = 0;
        for (File f : k) t += f.isDirectory() ? dirSize(f) : f.length();
        return t;
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private List<AppSize> appSizes() {
        List<AppSize> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 26) return out;
        try {
            StorageStatsManager sm = (StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
            PackageManager pm = getPackageManager();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (stop.get()) break;
                try {
                    StorageStats s = sm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle());
                    long total = s.getAppBytes() + s.getDataBytes();
                    if (total <= 0) continue;
                    out.add(new AppSize(String.valueOf(ai.loadLabel(pm)), total, s.getCacheBytes()));
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, (a, c) -> Long.compare(c.bytes, a.bytes));
        return out;
    }

    private static long sumCache(List<AppSize> apps) {
        long t = 0;
        for (AppSize a : apps) t += a.cache;
        return t;
    }

    // ------------------------------------------------------------------ cards (same blocks as Settings: titles, grouped rows, cards)

    /** One "icon  name  size" cell of the category grid inside the storage card. */
    private void kv(LinearLayout row, int icon, int color, String name, long bytes) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView i = new android.widget.ImageView(this);
        i.setImageResource(icon);
        i.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, color)));
        c.addView(i, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPaddingRelative(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(15);
        n.setSingleLine(true);
        n.setTextColor(Ui.color(this, R.color.text_primary));
        t.addView(n);
        TextView s = new TextView(this);
        s.setText(Fmt.size(bytes));
        s.setTextSize(13);
        s.setTextColor(Ui.color(this, R.color.text_secondary));
        t.addView(s);
        c.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private LinearLayout gridRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        parent.addView(r);
        return r;
    }

    private void addRow(Row row, View.OnClickListener click) {
        content.addView(Ui.rowView(this, content, row, click));
    }

    private void render(long total, long free, Cats.Stats st, List<Big> bigs, long bigTotal, boolean usage,
                        List<AppSize> apps, long cacheTotal) {
        content.removeAllViews();

        // main storage: one card with the percentage, the bar and the size of every kind of file
        content.addView(Ui.sectionTitle(this, getString(R.string.fm_internal) + " · " + getString(R.string.sa_free, Fmt.size(free))));
        LinearLayout card = Ui.formCard(this);
        card.setPaddingRelative(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 10));
        double pct = total > 0 ? (total - free) * 100.0 / total : 0;
        TextView big = new TextView(this);
        big.setText(getString(R.string.home_used_pct, Math.round(pct)));
        big.setTextSize(22);
        big.setTypeface(android.graphics.Typeface.SERIF);
        big.setTextColor(Ui.color(this, R.color.text_primary));
        big.setPadding(0, 0, 0, Ui.dp(this, 4));
        card.addView(big);
        card.addView(Ui.bar(this, pct, pct > 90 ? R.color.bad : R.color.accent, 0, 10));
        long img = st.size[Cats.T_IMG], vid = st.size[Cats.T_VID], aud = st.size[Cats.T_AUD];
        long doc = st.size[Cats.T_PDF] + st.size[Cats.T_DOC], arc = st.size[Cats.T_ARC];
        long others = 0;
        for (int t = 0; t < st.size.length; t++) {
            if (t == Cats.T_DIR || t == Cats.T_IMG || t == Cats.T_VID || t == Cats.T_AUD || t == Cats.T_PDF
                    || t == Cats.T_DOC || t == Cats.T_ARC) continue;
            others += st.size[t];
        }
        LinearLayout r1 = gridRow(card), r2 = gridRow(card), r3 = gridRow(card);
        kv(r1, Cats.iconFor(Cats.T_IMG), Cats.colorFor(Cats.T_IMG), getString(Cats.titleOf(Cats.IMG)), img);
        kv(r1, Cats.iconFor(Cats.T_AUD), Cats.colorFor(Cats.T_AUD), getString(Cats.titleOf(Cats.AUD)), aud);
        kv(r2, Cats.iconFor(Cats.T_VID), Cats.colorFor(Cats.T_VID), getString(Cats.titleOf(Cats.VID)), vid);
        kv(r2, Cats.iconFor(Cats.T_DOC), Cats.colorFor(Cats.T_DOC), getString(Cats.titleOf(Cats.DOC)), doc);
        kv(r3, Cats.iconFor(Cats.T_ARC), Cats.colorFor(Cats.T_ARC), getString(Cats.titleOf(Cats.ARC)), arc);
        kv(r3, Cats.iconFor(Cats.T_OTHER), Cats.colorFor(Cats.T_OTHER), getString(R.string.sa_others), others);
        content.addView(card);
        addRow(new Row(R.drawable.ic_folder, false, getString(R.string.sa_more), null, false, true), v -> categoryMenu());

        // apps
        content.addView(Ui.sectionTitle(this, getString(R.string.sa_apps)));
        if (!usage) {
            TextView note = Ui.noteCard(this, getString(R.string.sa_apps_need), R.color.text_secondary);
            content.addView(Ui.block(this, note));
            Button permit = Ui.button(this, R.string.sa_permit, false);
            content.addView(Ui.block(this, permit));
            permit.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        } else if (apps.isEmpty()) {
            content.addView(Ui.block(this, Ui.noteCard(this, getString(R.string.sa_none), R.color.text_secondary)));
        } else {
            for (int i = 0; i < apps.size() && i < 5; i++) {
                AppSize a = apps.get(i);
                addRow(new Row(R.drawable.ic_package, false, a.label, Fmt.size(a.bytes), false, false), null);
            }
        }

        // large files
        content.addView(Ui.sectionTitle(this, getString(R.string.sa_large) + " · " + Fmt.size(bigTotal)));
        for (int i = 0; i < bigs.size() && i < 3; i++) {
            final Big bf = bigs.get(i);
            int t = Cats.typeOfExt(Cats.extOf(bf.f.getName()));
            File par = bf.f.getParentFile();
            addRow(new Row(Cats.iconFor(t), false, bf.f.getName(), par == null ? "" : par.getAbsolutePath(), false, false)
                    .badge(Fmt.size(bf.size), Ui.color(this, R.color.accent_text)).tint(Ui.color(this, Cats.colorFor(t))), v -> {
                Intent it = new Intent(this, FileManagerActivity.class);
                if (bf.f.getParentFile() != null) it.putExtra("path", bf.f.getParentFile().getAbsolutePath());
                startActivity(it);
            });
        }
        addRow(new Row(R.drawable.ic_sort, false, getString(R.string.sa_more), getString(R.string.sa_large_hint), false, true), v -> {
            Intent it = new Intent(this, FileManagerActivity.class);
            it.putExtra("cat", Cats.LARGE);
            startActivity(it);
        });

        // cache
        content.addView(Ui.sectionTitle(this, getString(R.string.sa_cache) + " · " + Fmt.size(cacheTotal)));
        addRow(new Row(R.drawable.ic_delete, false, getString(R.string.sa_clean_own),
                getString(usage ? R.string.sa_cache_apps : R.string.sa_cache_own), false, false), v -> {
            long before = dirSize(getCacheDir()) + dirSize(getExternalCacheDir());
            deleteContents(getCacheDir());
            deleteContents(getExternalCacheDir());
            android.widget.Toast.makeText(this, getString(R.string.sa_cleaned, Fmt.size(before)), android.widget.Toast.LENGTH_SHORT).show();
            load();
        });

        // the folder-by-folder analyzer stays one tap away
        content.addView(Ui.sectionTitle(this, getString(R.string.home_analysis)));
        addRow(new Row(R.drawable.ic_chart, false, getString(R.string.sa_folders), null, false, true),
                v -> startActivity(new Intent(this, ToolsActivity.class)));
    }

    private void categoryMenu() {
        final String[] keys = {Cats.IMG, Cats.VID, Cats.AUD, Cats.DOC, Cats.APK, Cats.ARC};
        String[] names = new String[keys.length];
        for (int i = 0; i < keys.length; i++) names[i] = getString(Cats.titleOf(keys[i]));
        new Dlg(this).setTitle(R.string.sa_more).setItems(names, (d, w) -> {
            Intent it = new Intent(this, FileManagerActivity.class);
            it.putExtra("cat", keys[w]);
            startActivity(it);
        }).show();
    }

    private static void deleteContents(File d) {
        if (d == null) return;
        File[] k = d.listFiles();
        if (k == null) return;
        for (File f : k) {
            if (f.isDirectory()) deleteContents(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
