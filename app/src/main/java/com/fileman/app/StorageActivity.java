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

    // ------------------------------------------------------------------ cards

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        content.addView(c, lp);
        return c;
    }

    private LinearLayout titleRow(LinearLayout card, String title, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(22);
        t.setTypeface(android.graphics.Typeface.SERIF);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        r.addView(t);
        if (value != null) {
            TextView v = new TextView(this);
            v.setText(value);
            v.setTextSize(18);
            v.setTextColor(Ui.color(this, R.color.accent_text));
            v.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
            r.addView(v);
        }
        card.addView(r);
        return r;
    }

    private void moreButton(LinearLayout card, int textRes, View.OnClickListener l) {
        TextView m = new TextView(this);
        m.setText(textRes);
        m.setAllCaps(true);
        m.setGravity(Gravity.CENTER);
        m.setTextSize(14);
        m.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        m.setTextColor(Ui.color(this, R.color.accent_text));
        m.setMinHeight(Ui.dp(this, 48));
        m.setOnClickListener(l);
        Ui.press(this, m);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        card.addView(m, lp);
    }

    private void kv(LinearLayout row, int icon, int color, String name, long bytes) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView i = new android.widget.ImageView(this);
        i.setImageResource(icon);
        i.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, color)));
        c.addView(i, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(15);
        n.setSingleLine(true);
        n.setTextColor(Ui.color(this, R.color.text_primary));
        n.setPaddingRelative(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        c.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView s = new TextView(this);
        s.setText(Fmt.size(bytes));
        s.setTextSize(13);
        s.setTextColor(Ui.color(this, R.color.text_secondary));
        c.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 8));
        row.addView(c, lp);
    }

    private void render(long total, long free, Cats.Stats st, List<Big> bigs, long bigTotal, boolean usage,
                        List<AppSize> apps, long cacheTotal) {
        content.removeAllViews();

        // 1) main storage
        LinearLayout c1 = card();
        titleRow(c1, getString(R.string.fm_internal), getString(R.string.sa_free, Fmt.size(free)));
        double pct = total > 0 ? (total - free) * 100.0 / total : 0;
        TextView big = new TextView(this);
        big.setText(Math.round(pct) + "%");
        big.setTextSize(38);
        big.setGravity(Gravity.CENTER);
        big.setTextColor(Ui.color(this, R.color.text_primary));
        big.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        c1.addView(big);
        c1.addView(Ui.bar(this, pct, pct > 90 ? R.color.bad : R.color.accent, 0, 14));

        long img = st.size[Cats.T_IMG], vid = st.size[Cats.T_VID], aud = st.size[Cats.T_AUD];
        long doc = st.size[Cats.T_PDF] + st.size[Cats.T_DOC], arc = st.size[Cats.T_ARC];
        long others = 0;
        for (int t = 0; t < st.size.length; t++) {
            if (t == Cats.T_DIR || t == Cats.T_IMG || t == Cats.T_VID || t == Cats.T_AUD || t == Cats.T_PDF
                    || t == Cats.T_DOC || t == Cats.T_ARC) continue;
            others += st.size[t];
        }
        LinearLayout r1 = gridRow(c1), r2 = gridRow(c1), r3 = gridRow(c1);
        kv(r1, Cats.iconFor(Cats.T_IMG), Cats.colorFor(Cats.T_IMG), getString(Cats.titleOf(Cats.IMG)), img);
        kv(r1, Cats.iconFor(Cats.T_AUD), Cats.colorFor(Cats.T_AUD), getString(Cats.titleOf(Cats.AUD)), aud);
        kv(r2, Cats.iconFor(Cats.T_VID), Cats.colorFor(Cats.T_VID), getString(Cats.titleOf(Cats.VID)), vid);
        kv(r2, Cats.iconFor(Cats.T_DOC), Cats.colorFor(Cats.T_DOC), getString(Cats.titleOf(Cats.DOC)), doc);
        kv(r3, Cats.iconFor(Cats.T_ARC), Cats.colorFor(Cats.T_ARC), getString(Cats.titleOf(Cats.ARC)), arc);
        kv(r3, Cats.iconFor(Cats.T_OTHER), Cats.colorFor(Cats.T_OTHER), getString(R.string.sa_others), others);
        moreButton(c1, R.string.sa_more, v -> categoryMenu());

        // 2) apps
        LinearLayout c2 = card();
        titleRow(c2, getString(R.string.sa_apps), null);
        if (!usage) {
            TextView t = Ui.body(this, getString(R.string.sa_apps_need), 15, R.color.text_secondary);
            t.setPadding(0, Ui.dp(this, 10), 0, 0);
            c2.addView(t);
            moreButton(c2, R.string.sa_permit, v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        } else {
            for (int i = 0; i < apps.size() && i < 5; i++) {
                LinearLayout row = gridRow(c2);
                kv(row, R.drawable.ic_package, R.color.accent_text, apps.get(i).label, apps.get(i).bytes);
            }
            if (apps.isEmpty()) c2.addView(Ui.body(this, getString(R.string.sa_none), 15, R.color.text_secondary));
        }

        // 3) large files
        LinearLayout c3 = card();
        titleRow(c3, getString(R.string.sa_large), Fmt.size(bigTotal));
        TextView hint = Ui.body(this, getString(R.string.sa_large_hint), 13, R.color.text_secondary);
        hint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        c3.addView(hint);
        for (int i = 0; i < bigs.size() && i < 2; i++) {
            final Big bf = bigs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = new TextView(this);
            n.setText(bf.f.getName());
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            n.setTextSize(15);
            n.setTextColor(Ui.color(this, R.color.text_primary));
            line.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView sz = new TextView(this);
            sz.setText(Fmt.size(bf.size));
            sz.setTextSize(15);
            sz.setTextColor(Ui.color(this, R.color.accent_text));
            line.addView(sz);
            row.addView(line);
            TextView p = new TextView(this);
            File par = bf.f.getParentFile();
            p.setText(par == null ? "" : par.getAbsolutePath());
            p.setSingleLine(true);
            p.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            p.setTextSize(12);
            p.setTextColor(Ui.color(this, R.color.text_secondary));
            row.addView(p);
            row.setOnClickListener(v -> {
                Intent it = new Intent(this, FileManagerActivity.class);
                if (bf.f.getParentFile() != null) it.putExtra("path", bf.f.getParentFile().getAbsolutePath());
                startActivity(it);
            });
            c3.addView(row);
        }
        moreButton(c3, R.string.sa_more, v -> {
            Intent it = new Intent(this, FileManagerActivity.class);
            it.putExtra("cat", Cats.LARGE);
            startActivity(it);
        });

        // 4) cache
        LinearLayout c4 = card();
        titleRow(c4, getString(R.string.sa_cache), Fmt.size(cacheTotal));
        TextView ch = Ui.body(this, getString(usage ? R.string.sa_cache_apps : R.string.sa_cache_own), 13, R.color.text_secondary);
        ch.setPadding(0, Ui.dp(this, 6), 0, 0);
        c4.addView(ch);
        moreButton(c4, R.string.sa_clean_own, v -> {
            long before = dirSize(getCacheDir()) + dirSize(getExternalCacheDir());
            deleteContents(getCacheDir());
            deleteContents(getExternalCacheDir());
            android.widget.Toast.makeText(this, getString(R.string.sa_cleaned, Fmt.size(before)), android.widget.Toast.LENGTH_SHORT).show();
            load();
        });

        // the old folder-by-folder analyzer stays one tap away
        LinearLayout c5 = card();
        moreButton(c5, R.string.sa_folders, v -> startActivity(new Intent(this, ToolsActivity.class)));
    }

    private LinearLayout gridRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        parent.addView(r);
        return r;
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
