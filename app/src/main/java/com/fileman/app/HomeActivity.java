package com.fileman.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.StatFs;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Home screen: one grid of big tiles (volumes with a usage ring, downloads, storage analysis, the categories,
 * new files, favorites, archives and largest files). Search and settings live in the bottom bar.
 */
public class HomeActivity extends BaseActivity {
    private static final long SCAN_MAX_AGE_MS = 60_000;
    private static final int RECENT_ON_HOME = 5;
    private static final int PINNED_ON_HOME = 4;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService fast = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stop = new AtomicBoolean(false);

    private LinearLayout content;
    private Cats.Stats stats;
    private long statsAt = 0;
    private boolean scanning = false;
    private boolean hadAccess = false;
    private volatile int dlCount = -1;
    private volatile long dlSize = 0;
    private LiveSync live;
    private int statsSig = 0;
    private boolean rescanQueued;
    private long lastScanStart;
    private final Runnable rescan = () -> {
        if (isFinishing() || isDestroyed() || !Perms.hasAllFiles(this)) return;
        if (scanning) {
            rescanQueued = true;
            return;
        }
        startScan();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        useGroupedBackground();
        content = findViewById(R.id.content);
        findViewById(R.id.btnSearch).setOnClickListener(v -> {
            Intent i = new Intent(this, FileManagerActivity.class);
            i.putExtra("search", true);
            startActivity(i);
        });
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // search and settings moved to the bottom bar (easier to reach with a thumb)
        findViewById(R.id.btnSearch).setVisibility(View.GONE);
        findViewById(R.id.btnSettings).setVisibility(View.GONE);
        NavBar.attach(this, NavBar.HOME);
        loadCache();
    }

    /** Shows the numbers of the last visit at once; a fresh scan replaces them a moment later. */
    private void loadCache() {
        if (!Perms.hasAllFiles(this)) return;
        try {
            String[] p = Store.homeCache(this).split("\\|", -1);
            if (p.length < 6) return;
            Cats.Stats s = new Cats.Stats();
            String[] cn = p[0].split(",", -1), sz = p[1].split(",", -1);
            if (cn.length != s.count.length || sz.length != s.size.length) return;
            for (int i = 0; i < cn.length; i++) {
                s.count[i] = Integer.parseInt(cn[i]);
                s.size[i] = Long.parseLong(sz[i]);
            }
            s.newCount = Integer.parseInt(p[2]);
            s.newSize = Long.parseLong(p[3]);
            dlCount = Integer.parseInt(p[4]);
            dlSize = Long.parseLong(p[5]);
            stats = s;
            statsAt = 0;
            hadAccess = true;
        } catch (Throwable ignored) {
        }
    }

    private void saveCache(Cats.Stats s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.count.length; i++) b.append(i == 0 ? "" : ",").append(s.count[i]);
        b.append('|');
        for (int i = 0; i < s.size.length; i++) b.append(i == 0 ? "" : ",").append(s.size[i]);
        b.append('|').append(s.newCount).append('|').append(s.newSize)
                .append('|').append(dlCount).append('|').append(dlSize);
        Store.setHomeCache(this, b.toString());
    }

    private final Runnable tipRun = () -> Assistant.suggest(this, new Assistant.Host() {
        @Override
        public void openStorage() {
            startActivity(new Intent(HomeActivity.this, StorageActivity.class));
        }

        @Override
        public void openCategory(String key) {
            Intent i = new Intent(HomeActivity.this, FileManagerActivity.class);
            i.putExtra("cat", key);
            startActivity(i);
        }

        @Override
        public void addFavorite(String path) {
            Set<String> s = new LinkedHashSet<>(Store.favorites(HomeActivity.this));
            s.add(path);
            Store.setFavorites(HomeActivity.this, s);
        }

        @Override
        public boolean isFavorite(String path) {
            return Store.favorites(HomeActivity.this).contains(path);
        }

        @Override
        public void openFolder(String path) {
            Intent i = new Intent(HomeActivity.this, FileManagerActivity.class);
            i.putExtra("path", path);
            startActivity(i);
        }

        @Override
        public void openSmart(String mode) {
            startActivity(new Intent(HomeActivity.this, SmartActivity.class).putExtra(SmartActivity.EXTRA, mode));
        }
    });

    @Override
    protected void onResume() {
        super.onResume();
        boolean access = Perms.hasAllFiles(this);
        if (access != hadAccess) {
            stats = null;
            hadAccess = access;
        }
        if (volWatch == null) volWatch = Volumes.watch(this, () -> ui.postDelayed(this::render, 700));
        render();
        if (!access) Perms.promptFirstRun(this);
        else Updater.autoCheck(this, io, ui);
        if (access && (stats == null || System.currentTimeMillis() - statsAt > SCAN_MAX_AGE_MS)) startScan();
        if (live == null) live = new LiveSync(this, this::liveRescan);
        if (access) startWatch();
        if (access) {
            SmartGuard.checkAsync(this);
            ui.removeCallbacks(tipRun);
            if (Assistant.enabled(this)) ui.postDelayed(tipRun, 2500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tipRun);
        if (live != null) live.stop();
        ui.removeCallbacks(rescan);
    }

    private void liveRescan() {
        long wait = Math.max(0, lastScanStart + 3000 - SystemClock.uptimeMillis());
        ui.removeCallbacks(rescan);
        ui.postDelayed(rescan, wait);
    }

    private void startWatch() {
        File root = Environment.getExternalStorageDirectory();
        List<File> dirs = new ArrayList<>();
        dirs.add(root);
        String[] names = {Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOCUMENTS};
        for (String n : names) dirs.add(Environment.getExternalStoragePublicDirectory(n));
        dirs.add(new File(root, "DCIM/Camera"));
        dirs.add(new File(root, "Pictures/Screenshots"));
        live.watch(dirs, true);
    }

    @Override
    protected void onLayoutChanged() {
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (volWatch != null) {
            try {
                unregisterReceiver(volWatch);
            } catch (Exception ignored) {
            }
            volWatch = null;
        }
        if (live != null) live.stop();
        stop.set(true);
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        fast.shutdownNow();
    }

    // ------------------------------------------------------------------ scan

    private void startScan() {
        if (scanning) return;
        scanning = true;
        lastScanStart = SystemClock.uptimeMillis();
        stop.set(false);
        final boolean hidden = Store.showHidden(this);
        // the downloads tile does not wait for the full scan
        fast.execute(() -> {
            final int[] dlc = {0};
            final long[] dls = {0};
            try {
                dirTotals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        dlc, dls, new int[]{30000}, 0);
            } catch (Throwable ignored) {
            }
            if (stop.get()) return;
            dlCount = dlc[0];
            dlSize = dls[0];
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed()) render();
            });
        });
        io.execute(() -> {
            Cats.Stats s = null;
            try {
                s = Cats.scan(Environment.getExternalStorageDirectory(), hidden, RECENT_ON_HOME, stop);
            } catch (Throwable ignored) {
            }
            final Cats.Stats res = s;
            ui.post(() -> {
                scanning = false;
                if (isFinishing() || isDestroyed() || res == null || stop.get()) return;
                int sig = java.util.Arrays.hashCode(res.count) * 31 + java.util.Arrays.hashCode(res.size);
                sig = sig * 31 + res.recent.hashCode();
                sig = sig * 31 + res.newCount;
                boolean same = stats != null && sig == statsSig;
                statsSig = sig;
                stats = res;
                saveCache(res);
                statsAt = System.currentTimeMillis();
                if (!same) render();
                if (rescanQueued) {
                    rescanQueued = false;
                    liveRescan();
                }
            });
        });
    }

    /** Counts the files under a folder (bounded, so a huge folder never stalls the scan). */
    private void dirTotals(File dir, int[] count, long[] size, int[] budget, int depth) {
        if (dir == null || stop.get() || budget[0] <= 0 || depth > 12) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (stop.get() || budget[0] <= 0) return;
            budget[0]--;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) dirTotals(f, count, size, budget, depth + 1);
            } else {
                count[0]++;
                size[0] += f.length();
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private boolean entered = false;

    private android.content.BroadcastReceiver volWatch;

    private void render() {
        final View scroller = (View) content.getParent();
        final int keepY = scroller.getScrollY();
        content.removeAllViews();
        final boolean access = Perms.hasAllFiles(this);

        if (!access) content.addView(permissionCard());

        content.addView(searchBar());

        File internal = Environment.getExternalStorageDirectory();
        final File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        // 1) one storage card: ring, used / total, analysis and biggest files, extra volumes as bars
        content.addView(storageCard(internal));

        // 2) quick access as a swipeable row of chips
        content.addView(chipRow(
                chip(R.drawable.ic_download, R.color.ok, getString(R.string.fm_downloads), v -> openFolder(dl)),
                chip(R.drawable.ic_clock, R.color.info, getString(Cats.titleOf(Cats.RECENT)), v -> openCategory(Cats.RECENT)),
                chip(R.drawable.ic_star, R.color.warn, getString(Cats.titleOf(Cats.FAV)), v -> openCategory(Cats.FAV))));

        // 3) categories: 3 x 2 grid
        List<View> cats = new ArrayList<>();
        cats.add(catTile(Cats.IMG));
        cats.add(catTile(Cats.VID));
        cats.add(catTile(Cats.AUD));
        cats.add(catTile(Cats.DOC));
        cats.add(catTile(Cats.APK));
        cats.add(catTile(Cats.ARC));
        addSection(getString(R.string.home_sec_categories), cats);

        // 4) tools: one small row
        List<View> tools = new ArrayList<>();
        tools.add(homeTile(R.drawable.ic_package, R.color.lime, getString(R.string.home_installer),
                getString(R.string.pk_beta), v -> startActivity(new Intent(this, InstallerActivity.class))));
        tools.add(homeTile(R.drawable.ic_wifi, R.color.info, getString(R.string.sh_title),
                getString(R.string.sh_sub), v -> startActivity(new Intent(this, ShareActivity.class))));
        tools.add(homeTile(R.drawable.ic_drive, R.color.accent_text, getString(R.string.drive_title),
                "", v -> startActivity(new Intent(this, DriveActivity.class)), false));
        addSection(getString(R.string.home_tools), tools);

        if (keepY > 0) scroller.post(() -> scroller.scrollTo(0, keepY));
        if (!entered) {   // soft staggered entrance the first time the screen is drawn
            entered = true;
            for (int i = 0; i < Math.min(content.getChildCount(), 8); i++) Ui.enter(content.getChildAt(i), i);
        }
    }

    private int sideMargin() {
        return Math.max(Ui.dp(this, 10), Layout.gutter(this) - Ui.dp(this, 4));
    }

    /** Storage summary: ring + used / total for the main volume, shortcuts, and one thin bar per extra volume. */
    private View storageCard(final File internal) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p - Ui.dp(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(sideMargin(), Ui.dp(this, 12), sideMargin(), Ui.dp(this, 6));
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final UsageView ring = new UsageView(this);
        top.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(this, 16), 0, 0, 0);
        TextView t = new TextView(this);
        t.setText(R.string.fm_internal);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        final TextView sub = new TextView(this);
        sub.setTextSize(13);
        sub.setTextColor(Ui.color(this, R.color.text_secondary));
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tx.addView(t);
        tx.addView(sub);
        top.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);
        try {
            StatFs st = new StatFs(internal.getAbsolutePath());
            long total = st.getTotalBytes();
            long used = Math.max(0, total - st.getAvailableBytes());
            if (total <= 0) throw new IllegalStateException();
            final double pct = used * 100.0 / total;
            final int fg = Ui.color(this, pct > 90 ? R.color.bad : R.color.accent);
            final int track = Ui.color(this, R.color.neutral_soft);
            sub.setText(getString(R.string.home_used_of, Fmt.size(used), Fmt.size(total)));
            ring.setCenterIcon(R.drawable.ic_drive, Ui.color(this, R.color.accent_text));
            if (!entered) {
                ring.setUsage(UsageView.RING, 0, fg, track);
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(700);
                va.setStartDelay(120);
                va.setInterpolator(Motion.OUT);
                va.addUpdateListener(a -> ring.setUsage(UsageView.RING, pct * (float) a.getAnimatedValue(), fg, track));
                va.start();
            } else {
                ring.setUsage(UsageView.RING, pct, fg, track);
            }
        } catch (Exception e) {
            sub.setText(R.string.fm_unreadable);
        }

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 14);
        card.addView(acts, ap);
        acts.addView(cardButton(R.string.home_analysis, v -> startActivity(new Intent(this, StorageActivity.class))),
                new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));
        View gap = new View(this);
        acts.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        acts.addView(cardButton(Cats.titleOf(Cats.LARGE), v -> openCategory(Cats.LARGE)),
                new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));

        for (File sd : sdRoots()) {
            try {
                StatFs st = new StatFs(sd.getAbsolutePath());
                long total = st.getTotalBytes();
                long used = Math.max(0, total - st.getAvailableBytes());
                if (total <= 0) continue;
                TextView l = new TextView(this);
                l.setText(Volumes.labelOf(this, sd) + "  \u00b7  " + getString(R.string.home_used_of, Fmt.size(used), Fmt.size(total)));
                l.setTextSize(13);
                l.setTextColor(Ui.color(this, R.color.text_secondary));
                l.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = Ui.dp(this, 14);
                card.addView(l, llp);
                card.addView(Ui.bar(this, used * 100.0 / total, R.color.accent, 0, 6));
                final File root = sd;
                l.setOnClickListener(v -> openFolder(root));
            } catch (Exception ignored) {
            }
        }
        card.setContentDescription(getString(R.string.fm_internal));
        Ui.press(this, card);
        card.setOnClickListener(v -> openFolder(internal));
        return card;
    }

    private View cardButton(int textRes, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(textRes);
        b.setGravity(android.view.Gravity.CENTER);
        b.setSingleLine(true);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Ui.color(this, R.color.text_primary));
        b.setBackgroundResource(R.drawable.btn_secondary);
        Ui.press(this, b);
        b.setOnClickListener(click);
        return b;
    }

    private View chip(int icon, int colorRes, String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
        d.setTint(Ui.color(this, colorRes));
        d.setBounds(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(Ui.dp(this, 8));
        t.setBackgroundResource(R.drawable.btn_secondary);
        t.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 18), 0);
        t.setMinHeight(Ui.dp(this, 44));
        t.setContentDescription(text);
        Ui.press(this, t);
        t.setOnClickListener(click);
        return t;
    }

    private View chipRow(View... chips) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(sideMargin(), Ui.dp(this, 6), sideMargin(), Ui.dp(this, 6));
        for (View c : chips) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            row.addView(c, lp);
        }
        hs.addView(row);
        return hs;
    }

    /** Prominent Google Drive card at the top: a usage ring and the used space once signed in, an invitation before. */
    private View driveCard() {
        boolean in = DriveStore.signedIn(this);
        long used = DriveStore.used(this);
        long limit = DriveStore.limit(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p);
        UsageView ring = new UsageView(this);
        double pct = in && limit > 0 && used >= 0 ? used * 100.0 / limit : 0;
        ring.setUsage(UsageView.RING, pct, Ui.color(this, pct > 90 ? R.color.bad : R.color.accent), 0x33FFFFFF);
        ring.setCenterIcon(R.drawable.ic_drive, Ui.color(this, R.color.accent_text));
        card.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView t = new TextView(this);
        t.setText(R.string.drive_title);
        t.setTextSize(17);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        TextView s = new TextView(this);
        s.setTextSize(13);
        s.setTextColor(Ui.color(this, R.color.text_secondary));
        s.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        s.setText(!in ? getString(R.string.drive_home_connect)
                : used < 0 ? getString(R.string.drive_sub_in)
                : limit > 0 ? getString(R.string.drive_home_used, Fmt.size(used), Fmt.size(limit))
                : getString(R.string.drive_home_unlimited, Fmt.size(used)));
        tx.addView(t);
        tx.addView(s);
        card.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int side = Math.max(Ui.dp(this, 10), Layout.gutter(this) - Ui.dp(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(side, Ui.dp(this, 6), side, Ui.dp(this, 4));
        card.setLayoutParams(lp);
        card.setContentDescription(getString(R.string.drive_title));
        Ui.press(this, card);
        card.setOnClickListener(v -> startActivity(new Intent(this, DriveActivity.class)));
        return card;
    }

    /** A titled group of tiles laid out in as many equal columns as the window allows. */
    private void addSection(String title, List<View> tiles) {
        content.addView(Ui.sectionTitle(this, title));
        final int cols = Layout.columns(this, 3, 6);
        int side = Math.max(Ui.dp(this, 10), Layout.gutter(this) - Ui.dp(this, 4));
        for (int i = 0; i < tiles.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(side, 0, side, 0);
            for (int k = 0; k < cols; k++) {
                View cell = i + k < tiles.size() ? tiles.get(i + k) : new View(this);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            content.addView(row);
        }
    }

    /** Tappable search field at the top of the home screen. */
    private View searchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_input);
        bar.setPaddingRelative(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_search);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_secondary)));
        bar.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        TextView t = new TextView(this);
        t.setText(R.string.home_search_hint);
        t.setTextSize(15);
        t.setTextColor(Ui.color(this, R.color.text_hint));
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(this, 10));
        bar.addView(t, tlp);
        bar.setContentDescription(getString(R.string.fm_search));
        Ui.press(this, bar);
        bar.setOnClickListener(v -> {
            Intent i = new Intent(this, FileManagerActivity.class);
            i.putExtra("search", true);
            startActivity(i);
        });
        int side = Math.max(Ui.dp(this, 16), Layout.gutter(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        lp.setMargins(side, Ui.dp(this, 4), side, 0);
        bar.setLayoutParams(lp);
        return bar;
    }

    /** One grid cell: an iOS Settings-style rounded square in the category's own colour, white glyph, the name and a detail line. */
    private View homeTile(int icon, int colorRes, String title, String sub, View.OnClickListener click) {
        return homeTile(icon, colorRes, title, sub, click, true);
    }

    /** {@code solid} false uses a soft tinted card instead (used by the tiles that draw a usage ring or pie in it). */
    private View homeTile(int icon, int colorRes, String title, String sub, View.OnClickListener click, boolean solid) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_home_tile, content, false);
        ImageView iv = v.findViewById(R.id.icon);
        iv.setImageResource(icon);
        View box = v.findViewById(R.id.box);
        box.setBackground(Ui.colorTile(this, getResources().getDimensionPixelSize(R.dimen.tile_box), colorRes, solid));
        box.setElevation(Ui.dpf(this, solid ? 3f : 1.5f));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            box.setOutlineAmbientShadowColor(Ui.color(this, R.color.shadow));
            box.setOutlineSpotShadowColor(Ui.color(this, R.color.shadow));
        }
        if (solid) {
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        } else {
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, colorRes)));
        }
        ((TextView) v.findViewById(R.id.title)).setText(title);
        ((TextView) v.findViewById(R.id.sub)).setText(sub);
        v.setContentDescription(title);
        Ui.press(this, v);
        v.setOnClickListener(click);
        return v;
    }

    /** A category tile: icon, name and "count · size" from the last scan. */
    private View catTile(final String cat) {
        int icon;
        int color;
        String sub = null;
        switch (cat) {
            case Cats.IMG:
                icon = R.drawable.ic_image;
                color = R.color.ok;
                break;
            case Cats.VID:
                icon = R.drawable.ic_video;
                color = R.color.bad;
                break;
            case Cats.AUD:
                icon = R.drawable.ic_music;
                color = R.color.violet;
                break;
            case Cats.DOC:
                icon = R.drawable.ic_file_text;
                color = R.color.info;
                break;
            case Cats.APK:
                icon = R.drawable.ic_package;
                color = R.color.lime;
                break;
            case Cats.RECENT:
                icon = R.drawable.ic_clock;
                color = R.color.info;
                if (stats != null) sub = getString(R.string.home_tile_sub, stats.newCount, Fmt.size(stats.newSize));
                break;
            case Cats.FAV:
                icon = R.drawable.ic_star;
                color = R.color.warn;
                sub = getString(R.string.fm_items_n, existingFavorites().size());
                break;
            case Cats.LARGE:
                icon = R.drawable.ic_chart;
                color = R.color.violet;
                sub = getString(R.string.home_by_size);
                break;
            default:
                icon = R.drawable.ic_archive;
                color = R.color.warn;
                break;
        }
        if (sub == null) {
            if (stats != null) {
                sub = getString(R.string.home_tile_sub, Cats.countOf(stats, cat), Fmt.size(Cats.sizeOf(stats, cat)));
            } else {
                sub = Perms.hasAllFiles(this) ? "…" : "";
            }
        }
        return homeTile(icon, color, getString(Cats.titleOf(cat)), sub, v -> openCategory(cat));
    }

    /** Volume tile: a usage ring around the drive icon, "used / total" under the name. */
    private View storageTile(final File root, String label) {
        View v = homeTile(R.drawable.ic_drive, R.color.accent_text, label, "…", x -> openFolder(root), false);
        try {
            StatFs st = new StatFs(root.getAbsolutePath());
            long total = st.getTotalBytes();
            long used = Math.max(0, total - st.getAvailableBytes());
            if (total <= 0) throw new IllegalStateException();
            double pct = used * 100.0 / total;
            ((TextView) v.findViewById(R.id.sub)).setText(getString(R.string.home_used_of, Fmt.size(used), Fmt.size(total)));
            v.findViewById(R.id.icon).setVisibility(View.GONE);
            UsageView u = v.findViewById(R.id.usage);
            u.setVisibility(View.VISIBLE);
            u.setUsage(UsageView.RING, pct, Ui.color(this, pct > 90 ? R.color.bad : R.color.accent),
                    Ui.color(this, R.color.neutral_soft));
            u.setCenterIcon(R.drawable.ic_drive, Ui.color(this, R.color.accent_text));
        } catch (Exception e) {
            ((TextView) v.findViewById(R.id.sub)).setText(R.string.fm_unreadable);
        }
        return v;
    }

    /** Storage analysis tile: a small pie of the used space, opens the advanced tools. */
    private View analysisTile(File root) {
        View v = homeTile(R.drawable.ic_chart, R.color.accent_text, getString(R.string.home_analysis), "",
                x -> startActivity(new Intent(this, StorageActivity.class)), false);
        try {
            StatFs st = new StatFs(root.getAbsolutePath());
            long total = st.getTotalBytes();
            long used = Math.max(0, total - st.getAvailableBytes());
            if (total <= 0) throw new IllegalStateException();
            double pct = used * 100.0 / total;
            ((TextView) v.findViewById(R.id.sub)).setText(getString(R.string.home_used_pct, Math.round(pct)));
            v.findViewById(R.id.icon).setVisibility(View.GONE);
            UsageView u = v.findViewById(R.id.usage);
            u.setVisibility(View.VISIBLE);
            u.setUsage(UsageView.PIE, pct, Ui.color(this, R.color.accent), Ui.color(this, R.color.neutral_soft));
        } catch (Exception ignored) {
        }
        return v;
    }

    private View permissionCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        Ui.block(this, box);

        TextView t = new TextView(this);
        t.setText(R.string.fm_perm_title);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        box.addView(t);

        TextView b = new TextView(this);
        b.setText(R.string.fm_perm_body);
        b.setTextColor(Ui.color(this, R.color.text_secondary));
        b.setTextSize(13);
        b.setLineSpacing(0, 1.15f);
        b.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        b.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        box.addView(b);

        Button grant = Ui.button(this, R.string.fm_perm_grant, true);
        grant.setOnClickListener(v -> Perms.requestAllFiles(this));
        box.addView(grant, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    // ------------------------------------------------------------------ navigation

    private void openFolder(File dir) {
        Intent i = new Intent(this, FileManagerActivity.class);
        i.putExtra("path", dir.getAbsolutePath());
        startActivity(i);
    }

    private void openCategory(String cat) {
        Intent i = new Intent(this, FileManagerActivity.class);
        i.putExtra("cat", cat);
        startActivity(i);
    }

    // ------------------------------------------------------------------ data

    private File appDir() {
        File d = getExternalFilesDir(null);
        return d != null ? d : getFilesDir();
    }

    private List<File> sdRoots() {
        return Volumes.roots(this);
    }

    private List<File> existingFavorites() {
        Set<String> all = Store.favorites(this);
        List<File> out = new ArrayList<>();
        for (String p : all) {
            File f = new File(p);
            if (f.exists()) out.add(f);
        }
        return out;
    }
}
