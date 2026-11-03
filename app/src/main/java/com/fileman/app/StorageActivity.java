package com.fileman.app;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Storage analysis. A summary card with a donut per kind of file, shortcuts to the big wins (large files, cache),
 * the large files themselves with multi-select and delete, the apps that take the most room and the caches.
 * Everything fills in as soon as it is known, so the screen is never blank while the phone is being scanned.
 */
public class StorageActivity extends BaseActivity {
    private static final long MB = 1024L * 1024;
    private static final long[] THRESH = {10 * MB, 50 * MB, 100 * MB, 500 * MB};
    private static final int PAGE = 30;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AtomicBoolean runStop = new AtomicBoolean(false);
    private int runId;
    private boolean destroyed;

    private LinearLayout content;
    private ScrollView scroll;
    private LinearLayout selBar;
    private TextView selText;
    private int contentBottom;
    private View largeAnchor, cacheAnchor;

    // state, filled in stages
    private long total, free;
    private Cats.Stats stats;
    private List<Big> bigs;          // all files >= 10 MB, largest first (null while scanning)
    private boolean usage;
    private List<AppSize> apps;      // null while scanning
    private long cacheTotal = -1;
    private final Set<File> selected = new LinkedHashSet<>();
    private int thresh = 0;
    private int shownBig = PAGE;
    private boolean appsAll;

    private static final class Big {
        final File f;
        final long size;

        Big(File f, long size) {
            this.f = f;
            this.size = size;
        }
    }

    private static final class AppSize {
        final String label, pkg;
        final long bytes, cache;

        AppSize(String label, String pkg, long bytes, long cache) {
            this.label = label;
            this.pkg = pkg;
            this.bytes = bytes;
            this.cache = cache;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.home_analysis);
        findViewById(R.id.subtitle).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        ImageButton refresh = findViewById(R.id.btnRefresh);
        refresh.setOnClickListener(v -> load(false));
        content = findViewById(R.id.content);
        scroll = (ScrollView) content.getParent();
        contentBottom = content.getPaddingBottom();
        Ui.autoGroup(this, content);
        findViewById(R.id.loading).setVisibility(View.GONE);   // sections show their own progress
        buildSelBar();
    }

    private LiveSync live;
    private long lastLoadAt = 0;
    private final Runnable liveRun = () -> {
        if (!destroyed) load(true);
    };

    @Override
    protected void onResume() {
        super.onResume();
        load(stats != null);
        if (live == null) live = new LiveSync(this, this::liveReload);
        File root = Environment.getExternalStorageDirectory();
        java.util.List<File> dirs = new ArrayList<>();
        dirs.add(root);
        String[] names = {Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOCUMENTS};
        for (String n : names) dirs.add(Environment.getExternalStoragePublicDirectory(n));
        live.watch(dirs, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (live != null) live.stop();
        ui.removeCallbacks(liveRun);
    }

    /** Folders changed: rescan quietly, at most once every few seconds, and keep the current numbers meanwhile. */
    private void liveReload() {
        if (destroyed) return;
        long wait = Math.max(0, lastLoadAt + 4000 - android.os.SystemClock.uptimeMillis());
        ui.removeCallbacks(liveRun);
        ui.postDelayed(liveRun, wait);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (live != null) live.stop();
        runStop.set(true);
        pool.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ data (three independent stages)

    private void load(final boolean quiet) {
        runStop.set(true);
        final AtomicBoolean st = new AtomicBoolean(false);
        runStop = st;
        final int run = ++runId;
        lastLoadAt = android.os.SystemClock.uptimeMillis();
        if (!quiet) {
            stats = null;
            bigs = null;
            apps = null;
            cacheTotal = -1;
            selected.clear();
            shownBig = PAGE;
            updateSel();
        }
        final File root = Environment.getExternalStorageDirectory();
        try {
            StatFs sf = new StatFs(root.getAbsolutePath());
            total = sf.getTotalBytes();
            free = sf.getAvailableBytes();
        } catch (Exception ignored) {
        }
        usage = Perms.hasUsageAccess(this);
        render();

        pool.execute(() -> {
            Cats.Stats s = null;
            try {
                s = Cats.scan(root, true, 1, st);
            } catch (Throwable ignored) {
            }
            final Cats.Stats fs = s;
            ui.post(() -> {
                if (destroyed || run != runId || fs == null) return;
                stats = fs;
                render();
            });
        });
        pool.execute(() -> {
            final List<Big> out = new ArrayList<>();
            findBig(root, out, new int[]{150000}, 0, st);
            Collections.sort(out, (a, c) -> Long.compare(c.size, a.size));
            ui.post(() -> {
                if (destroyed || run != runId) return;
                bigs = out;
                if (quiet) {
                    Set<File> alive = new java.util.HashSet<>();
                    for (Big b : out) alive.add(b.f);
                    selected.retainAll(alive);
                    updateSel();
                }
                render();
            });
        });
        pool.execute(() -> {
            final List<AppSize> list = usage ? appSizes(st) : new ArrayList<>();
            final long ct = usage ? sumCache(list) : dirSize(getCacheDir()) + dirSize(getExternalCacheDir());
            ui.post(() -> {
                if (destroyed || run != runId) return;
                apps = list;
                cacheTotal = ct;
                render();
            });
        });
    }

    private static void findBig(File dir, List<Big> out, int[] budget, int depth, AtomicBoolean stop) {
        if (budget[0] <= 0 || depth > 20 || stop.get()) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (budget[0]-- <= 0 || stop.get()) return;
            if (f.isDirectory()) {
                if (depth == 0 && f.getName().equals("Android")) continue;
                if (!Cats.isLink(f)) findBig(f, out, budget, depth + 1, stop);
            } else {
                long len = f.length();
                if (len >= THRESH[0]) out.add(new Big(f, len));
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

    private List<AppSize> appSizes(AtomicBoolean stop) {
        List<AppSize> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 26) return out;
        try {
            StorageStatsManager sm = (StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
            PackageManager pm = getPackageManager();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (stop.get()) break;
                try {
                    StorageStats s = sm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle());
                    long t = s.getAppBytes() + s.getDataBytes();
                    if (t <= 0) continue;
                    out.add(new AppSize(String.valueOf(ai.loadLabel(pm)), ai.packageName, t, s.getCacheBytes()));
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

    // ------------------------------------------------------------------ rendering

    private void render() {
        if (destroyed || content == null) return;
        final int keep = scroll.getScrollY();
        content.removeAllViews();
        content.addView(hero());
        content.addView(quickCards());
        renderLarge();
        renderApps();
        renderCache();
        content.addView(Ui.sectionTitle(this, getString(R.string.home_analysis)));
        addRow(new Row(R.drawable.ic_chart, false, getString(R.string.sa_folders), null, false, true),
                v -> startActivity(new Intent(this, ToolsActivity.class)));
        scroll.post(() -> scroll.scrollTo(0, keep));
    }

    private void addRow(Row row, View.OnClickListener click) {
        content.addView(Ui.rowView(this, content, row, click));
    }

    private int side() {
        return Math.max(Ui.dp(this, 16), Layout.gutter(this));
    }

    private TextView text(CharSequence s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Ui.color(this, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable oval(int color, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** Summary: donut + used / free + one legend line per kind of file (tap to open that kind). */
    private View hero() {
        LinearLayout card = Ui.formCard(this);
        card.setPaddingRelative(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 10));

        long usedReal = Math.max(0, total - free);
        double pct = total > 0 ? usedReal * 100.0 / total : 0;

        long img = 0, vid = 0, aud = 0, doc = 0, arc = 0;
        if (stats != null) {
            img = stats.size[Cats.T_IMG];
            vid = stats.size[Cats.T_VID];
            aud = stats.size[Cats.T_AUD];
            doc = stats.size[Cats.T_PDF] + stats.size[Cats.T_DOC];
            arc = stats.size[Cats.T_ARC];
        }
        long known = img + vid + aud + doc + arc;
        if (known > usedReal) known = usedReal;
        long system = Math.max(0, usedReal - (img + vid + aud + doc + arc));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        SegRingView ring = new SegRingView(this);
        float t = Math.max(1f, total);
        int[] cols;
        float[] frac;
        if (stats != null) {
            cols = new int[]{Ui.color(this, Cats.colorFor(Cats.T_IMG)), Ui.color(this, Cats.colorFor(Cats.T_VID)),
                    Ui.color(this, Cats.colorFor(Cats.T_AUD)), Ui.color(this, Cats.colorFor(Cats.T_DOC)),
                    Ui.color(this, Cats.colorFor(Cats.T_ARC)), Ui.color(this, R.color.text_hint)};
            frac = new float[]{img / t, vid / t, aud / t, doc / t, arc / t, system / t};
        } else {
            cols = new int[]{Ui.color(this, pct > 90 ? R.color.bad : R.color.accent)};
            frac = new float[]{(float) (pct / 100.0)};
        }
        ring.setData(frac, cols, Ui.color(this, R.color.neutral_soft), Math.round(pct) + "%",
                getString(R.string.sa_used_label), Ui.color(this, R.color.text_primary), Ui.color(this, R.color.text_secondary));
        top.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 128), Ui.dp(this, 128)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPaddingRelative(Ui.dp(this, 18), 0, 0, 0);
        right.addView(text(Fmt.size(usedReal), 28, R.color.text_primary, true));
        right.addView(text(getString(R.string.sa_of_total, Fmt.size(total)), 14, R.color.text_secondary, false));
        TextView chip = text(getString(R.string.sa_free, Fmt.size(free)), 13, R.color.accent_text, true);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Ui.color(this, R.color.accent_soft));
        cb.setCornerRadius(Ui.dp(this, 100));
        chip.setBackground(cb);
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 10);
        right.addView(chip, cp);
        top.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        View line = new View(this);
        line.setBackgroundColor(Ui.color(this, R.color.stroke_soft));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, Ui.dp(this, 1) / 2));
        lp.topMargin = Ui.dp(this, 16);
        card.addView(line, lp);

        String[] keys = {Cats.IMG, Cats.VID, Cats.AUD, Cats.DOC, Cats.ARC, null};
        int[] types = {Cats.T_IMG, Cats.T_VID, Cats.T_AUD, Cats.T_DOC, Cats.T_ARC, -1};
        long[] sizes = {img, vid, aud, doc, arc, system};
        for (int i = 0; i < keys.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = i; k < i + 2; k++) {
                int dot = types[k] < 0 ? Ui.color(this, R.color.text_hint) : Ui.color(this, Cats.colorFor(types[k]));
                String name = keys[k] == null ? getString(R.string.sa_system) : getString(Cats.titleOf(keys[k]));
                row.addView(legend(dot, name, stats == null ? "…" : Fmt.size(sizes[k]), keys[k]),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            card.addView(row);
        }
        return card;
    }

    private View legend(int dot, String name, String size, final String catKey) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(0, Ui.dp(this, 11), Ui.dp(this, 8), Ui.dp(this, 11));
        View d = new View(this);
        d.setBackground(oval(dot, 0, 0));
        c.addView(d, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPaddingRelative(Ui.dp(this, 10), 0, 0, 0);
        TextView n = text(name, 14.5f, R.color.text_primary, false);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        tx.addView(n);
        tx.addView(text(size, 13, R.color.text_secondary, false));
        c.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (catKey != null) {
            Ui.press(this, c);
            c.setOnClickListener(v -> {
                Intent it = new Intent(this, FileManagerActivity.class);
                it.putExtra("cat", catKey);
                startActivity(it);
            });
        }
        return c;
    }

    /** Two shortcuts to the biggest wins: large files and cache. */
    private View quickCards() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(side() - Ui.dp(this, 4), Ui.dp(this, 2), side() - Ui.dp(this, 4), Ui.dp(this, 4));
        long bigSum = 0;
        int bigN = 0;
        if (bigs != null) for (Big b : bigs) {
            bigSum += b.size;
            bigN++;
        }
        row.addView(quick(Cats.iconFor(Cats.T_VID), getString(R.string.sa_large),
                bigs == null ? "…" : Fmt.size(bigSum),
                bigs == null ? "" : getString(R.string.sa_card_large_sub, bigN, Fmt.size(THRESH[0])),
                v -> scrollTo(largeAnchor)));
        row.addView(quick(R.drawable.ic_delete, getString(R.string.sa_cache),
                cacheTotal < 0 ? "…" : Fmt.size(cacheTotal), getString(R.string.sa_card_cache_sub),
                v -> scrollTo(cacheAnchor)));
        return row;
    }

    private View quick(int icon, String title, String big, String sub, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        Ui.outlineIcon(this, iv, 10);
        c.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        TextView t = text(title, 13.5f, R.color.text_secondary, false);
        t.setPadding(0, Ui.dp(this, 12), 0, 0);
        c.addView(t);
        c.addView(text(big, 22, R.color.text_primary, true));
        TextView s = text(sub, 12.5f, R.color.text_hint, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(s);
        Ui.press(this, c);
        c.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private void scrollTo(View v) {
        if (v == null) return;
        scroll.smoothScrollTo(0, Math.max(0, v.getTop() - Ui.dp(this, 8)));
    }

    private View progressCard(String msg) {
        LinearLayout c = Ui.formCard(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        c.addView(new SpinnerView(this), new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView t = text(msg, 14.5f, R.color.text_secondary, false);
        t.setPaddingRelative(Ui.dp(this, 14), 0, 0, 0);
        c.addView(t);
        return c;
    }

    // ------------------------------------------------------------------ large files: filter, select, delete

    private List<Big> visibleBigs() {
        List<Big> out = new ArrayList<>();
        if (bigs == null) return out;
        for (Big b : bigs) if (b.size >= THRESH[thresh]) out.add(b);
        return out;
    }

    private void renderLarge() {
        List<Big> vis = visibleBigs();
        long sum = 0;
        for (Big b : vis) sum += b.size;
        largeAnchor = Ui.sectionTitle(this, getString(R.string.sa_large) + (bigs == null ? "" : " · " + Fmt.size(sum)));
        content.addView(largeAnchor);
        if (bigs == null) {
            content.addView(progressCard(getString(R.string.sa_analyzing)));
            return;
        }

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(side(), 0, side(), Ui.dp(this, 8));
        for (int i = 0; i < THRESH.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, "≥ " + Fmt.size(THRESH[i]), i == thresh);
            c.setOnClickListener(v -> {
                thresh = idx;
                shownBig = PAGE;
                render();
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMarginEnd(Ui.dp(this, 8));
            chips.addView(c, cp);
        }
        TextView all = Ui.chip(this, getString(R.string.fm_select_all), false);
        all.setOnClickListener(v -> {
            List<Big> v2 = visibleBigs();
            boolean every = !v2.isEmpty() && selected.size() >= v2.size();
            selected.clear();
            if (!every) for (Big b : v2) selected.add(b.f);
            updateSel();
            render();
        });
        chips.addView(all);
        hs.addView(chips);
        content.addView(hs);

        if (vis.isEmpty()) {
            content.addView(Ui.block(this, Ui.noteCard(this, getString(R.string.sa_none_large), R.color.text_secondary)));
            return;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(side(), 0, side(), Ui.dp(this, 6));
        card.setLayoutParams(cl);
        int n = Math.min(vis.size(), shownBig);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                View d = new View(this);
                d.setBackgroundColor(Ui.color(this, R.color.stroke_soft));
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(1, Ui.dp(this, 1) / 2));
                dp.setMarginStart(Ui.dp(this, 70));
                card.addView(d, dp);
            }
            card.addView(bigRow(vis.get(i)));
        }
        content.addView(card);
        if (vis.size() > n) {
            Button more = Ui.button(this, R.string.sa_show_more, false);
            content.addView(Ui.block(this, more));
            more.setOnClickListener(v -> {
                shownBig += PAGE;
                render();
            });
        }
        TextView hint = text(getString(R.string.sa_select_hint), 12.5f, R.color.text_hint, false);
        hint.setPadding(side() + Ui.dp(this, 4), Ui.dp(this, 2), side(), Ui.dp(this, 4));
        content.addView(hint);
    }

    private View bigRow(final Big b) {
        final boolean sel = selected.contains(b.f);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        row.setMinimumHeight(Ui.dp(this, 68));
        row.setBackgroundResource(sel ? R.drawable.bg_row_selected : R.drawable.bg_tab_btn);

        int type = Cats.typeOfExt(Cats.extOf(b.f.getName()));
        ImageView icon = new ImageView(this);
        icon.setImageResource(Cats.iconFor(type));
        icon.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        Ui.outlineIcon(this, icon, 10);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPaddingRelative(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        TextView name = text(b.f.getName(), 15.5f, R.color.text_primary, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tx.addView(name);
        File par = b.f.getParentFile();
        String where = par == null ? "" : par.getAbsolutePath().replace(Environment.getExternalStorageDirectory().getAbsolutePath(), "");
        TextView sub = text(where.isEmpty() ? "/" : where, 12.5f, R.color.text_secondary, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.START);
        sub.setTextDirection(View.TEXT_DIRECTION_LTR);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tx.addView(sub);
        row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView size = text(Fmt.size(b.size), 14, R.color.accent_text, true);
        size.setPaddingRelative(0, 0, Ui.dp(this, 12), 0);
        row.addView(size);

        ImageView check = new ImageView(this);
        int pad = Ui.dp(this, 5);
        check.setPadding(pad, pad, pad, pad);
        if (sel) {
            check.setBackground(oval(Ui.color(this, R.color.accent), 0, 0));
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
        } else {
            check.setBackground(oval(0, Ui.color(this, R.color.stroke), Math.max(2, Math.round(Ui.dpf(this, 1.5f)))));
        }
        row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));

        row.setOnClickListener(v -> {
            if (!selected.remove(b.f)) selected.add(b.f);
            updateSel();
            render();
        });
        row.setOnLongClickListener(v -> {
            Intent it = new Intent(this, FileManagerActivity.class);
            if (b.f.getParentFile() != null) it.putExtra("path", b.f.getParentFile().getAbsolutePath());
            startActivity(it);
            return true;
        });
        return row;
    }

    // ------------------------------------------------------------------ selection bar + delete

    private void buildSelBar() {
        FrameLayout frame = (FrameLayout) scroll.getParent();
        selBar = new LinearLayout(this);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(this, R.color.surface_high));
        bg.setCornerRadius(Ui.dp(this, 26));
        bg.setStroke(Math.max(1, Ui.dp(this, 1) / 2), Ui.color(this, R.color.stroke));
        selBar.setBackground(bg);
        selBar.setElevation(Ui.dp(this, 6));
        selBar.setPaddingRelative(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));

        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        close.setBackground(null);
        close.setContentDescription(getString(R.string.cancel));
        close.setOnClickListener(v -> {
            selected.clear();
            updateSel();
            render();
        });
        ((LinearLayout) selBar).addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        selText = text("", 15, R.color.text_primary, true);
        selText.setSingleLine(true);
        selText.setEllipsize(TextUtils.TruncateAt.END);
        ((LinearLayout) selBar).addView(selText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button del = new Button(this);
        del.setText(R.string.delete);
        del.setAllCaps(false);
        del.setTypeface(Typeface.DEFAULT_BOLD);
        del.setTextColor(Ui.color(this, R.color.on_bad));
        del.setBackgroundResource(R.drawable.dlg_btn_danger);
        del.setStateListAnimator(null);
        del.setOnClickListener(v -> confirmDelete());
        ((LinearLayout) selBar).addView(del, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 44)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 16));
        frame.addView(selBar, lp);
        selBar.setVisibility(View.GONE);
    }

    private long selectedBytes() {
        long t = 0;
        if (bigs != null) for (Big b : bigs) if (selected.contains(b.f)) t += b.size;
        return t;
    }

    private void updateSel() {
        if (selBar == null) return;
        boolean any = !selected.isEmpty();
        if (any) selText.setText(getString(R.string.sa_selected_n, selected.size()) + " · " + Fmt.size(selectedBytes()));
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(),
                contentBottom + (any ? Ui.dp(this, 84) : 0));
        if (any && selBar.getVisibility() != View.VISIBLE) {
            selBar.setVisibility(View.VISIBLE);
            selBar.setTranslationY(Ui.dp(this, 90));
            selBar.animate().translationY(0).setDuration(Motion.DUR).setInterpolator(Motion.OUT).start();
        } else if (!any && selBar.getVisibility() == View.VISIBLE) {
            selBar.animate().translationY(Ui.dp(this, 90)).setDuration(Motion.DUR_SHORT).setInterpolator(Motion.EASE)
                    .withEndAction(() -> selBar.setVisibility(View.GONE)).start();
        }
    }

    private void confirmDelete() {
        if (selected.isEmpty()) return;
        final List<File> files = new ArrayList<>(selected);
        final long bytes = selectedBytes();
        new Dlg(this)
                .setTitle(getString(R.string.sa_delete_title, files.size()))
                .setMessage(getString(R.string.sa_delete_msg, Fmt.size(bytes)))
                .setPositiveButton(R.string.delete, (d, w) -> deleteFiles(files))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteFiles(final List<File> files) {
        pool.execute(() -> {
            final List<File> gone = new ArrayList<>();
            long freed = 0;
            for (File f : files) {
                long len = f.length();
                if (Safe.mayExpose(this, f) && f.delete()) {
                    gone.add(f);
                    freed += len;
                }
            }
            final long ff = freed;
            String[] paths = new String[gone.size()];
            for (int i = 0; i < paths.length; i++) paths[i] = gone.get(i).getAbsolutePath();
            if (paths.length > 0) MediaScannerConnection.scanFile(this, paths, null, null);
            ui.post(() -> {
                if (destroyed) return;
                if (bigs != null) {
                    List<Big> keep = new ArrayList<>();
                    for (Big b : bigs) if (!gone.contains(b.f)) keep.add(b);
                    bigs = keep;
                }
                selected.removeAll(gone);
                try {
                    StatFs sf = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
                    free = sf.getAvailableBytes();
                } catch (Exception ignored) {
                    free += ff;
                }
                Toast.makeText(this, getString(R.string.sa_deleted, gone.size(), Fmt.size(ff)), Toast.LENGTH_LONG).show();
                updateSel();
                render();
            });
        });
    }

    // ------------------------------------------------------------------ apps and cache

    private void renderApps() {
        content.addView(Ui.sectionTitle(this, getString(R.string.sa_apps)));
        if (!usage) {
            TextView note = Ui.noteCard(this, getString(R.string.sa_apps_need), R.color.text_secondary);
            content.addView(Ui.block(this, note));
            Button permit = Ui.button(this, R.string.sa_permit, false);
            content.addView(Ui.block(this, permit));
            permit.setOnClickListener(v -> Perms.openUsageAccess(this));
            return;
        }
        if (apps == null) {
            content.addView(progressCard(getString(R.string.sa_analyzing)));
            return;
        }
        if (apps.isEmpty()) {
            content.addView(Ui.block(this, Ui.noteCard(this, getString(R.string.sa_none), R.color.text_secondary)));
            return;
        }
        int n = appsAll ? Math.min(apps.size(), 25) : Math.min(apps.size(), 6);
        for (int i = 0; i < n; i++) {
            final AppSize a = apps.get(i);
            String sub = a.cache > 0 ? getString(R.string.sa_app_cache, Fmt.size(a.cache)) : null;
            addRow(new Row(R.drawable.ic_package, false, a.label, sub, false, true)
                    .badge(Fmt.size(a.bytes), Ui.color(this, R.color.accent_text)), v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                } catch (Exception ignored) {
                }
            });
        }
        if (!appsAll && apps.size() > n) {
            addRow(new Row(R.drawable.ic_sort, false, getString(R.string.sa_apps_all), null, false, true), v -> {
                appsAll = true;
                render();
            });
        }
    }

    private void renderCache() {
        cacheAnchor = Ui.sectionTitle(this, getString(R.string.sa_cache) + (cacheTotal < 0 ? "" : " · " + Fmt.size(cacheTotal)));
        content.addView(cacheAnchor);
        addRow(new Row(R.drawable.ic_delete, false, getString(R.string.sa_clean_own),
                getString(usage ? R.string.sa_cache_apps : R.string.sa_cache_own), false, false), v -> {
            long before = dirSize(getCacheDir()) + dirSize(getExternalCacheDir());
            deleteContents(getCacheDir());
            deleteContents(getExternalCacheDir());
            Toast.makeText(this, getString(R.string.sa_cleaned, Fmt.size(before)), Toast.LENGTH_SHORT).show();
            load(true);
        });
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
