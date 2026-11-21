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

    private final ExecutorService io = Bg.single("fm-bg");
    private final ExecutorService fast = Bg.single("fm-bg");
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
        SplashScreen.installSplashScreen(this).setOnExitAnimationListener(p -> p.remove());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        useGroupedBackground();
        if (savedInstanceState == null && android.os.Build.VERSION.SDK_INT < 31 && !Store.reduceMotion(this)) SplashOverlay.show(this);
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
        AutoOrganize.sortDownloadsIfDue(this);
        boolean access = Perms.hasAllFiles(this);
        if (access != hadAccess) {
            stats = null;
            hadAccess = access;
        }
        if (volWatch == null) volWatch = Volumes.watch(this, () -> ui.postDelayed(this::render, 700));
        if (!rendered || renderSignature() != renderSig) render();
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

    @Override
    protected int tapMode() {
        return TapShare.AUTO;
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
            long[] st = Cats.stat(f);
            if (st != null ? st[0] == 1 : f.isDirectory()) {
                if (!Cats.isLink(f)) dirTotals(f, count, size, budget, depth + 1);
            } else {
                count[0]++;
                size[0] += st != null ? st[1] : f.length();
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private boolean entered = false;

    private android.content.BroadcastReceiver volWatch;

    /** The old top-corner button stays hidden: attached USB drives and SD cards are a row in the list now. */
    private void updateUsb() {
        View b = findViewById(R.id.btnUsb);
        if (b != null) b.setVisibility(View.GONE);
    }

    private void openUsb(final List<Volumes.Vol> vols) {
        if (vols.size() == 1) {
            UsbPrompt.open(this, vols.get(0).root);
            return;
        }
        String[] names = new String[vols.size()];
        for (int i = 0; i < names.length; i++) names[i] = vols.get(i).label;
        new Dlg(this).setTitle(R.string.usb_pick)
                .setItems(names, (d, i) -> UsbPrompt.open(this, vols.get(i).root)).show();
    }

    private boolean rendered;
    private int renderSig;

    /** Cheap fingerprint of everything the home screen shows, so coming back from another screen does not rebuild it. */
    private int renderSignature() {
        int h = Perms.hasAllFiles(this) ? 1 : 0;
        h = h * 31 + System.identityHashCode(stats);
        h = h * 31 + statsSig;
        h = h * 31 + dlCount;
        h = h * 31 + Long.hashCode(dlSize);
        try {
            h = h * 31 + (int) (Environment.getExternalStorageDirectory().getFreeSpace() >> 20);
        } catch (Throwable ignored) {
        }
        for (Volumes.Vol v : Volumes.removable(this)) h = h * 31 + (v.mounted ? 7 : 3);
        h = h * 31 + Store.favorites(this).size();
        h = h * 31 + getResources().getConfiguration().toString().hashCode();
        return h;
    }

    private void render() {
        updateUsb();
        final View scroller = (View) content.getParent();
        final int keepY = scroller.getScrollY();
        content.removeAllViews();
        final boolean access = Perms.hasAllFiles(this);

        if (!access) content.addView(permissionCard());

        content.addView(searchBar());

        File internal = Environment.getExternalStorageDirectory();
        final File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        content.addView(storageCard(internal));
        content.addView(smartGrid(dl));

        final List<Volumes.Vol> vols = new ArrayList<>();
        for (Volumes.Vol v : Volumes.removable(this)) if (v.mounted) vols.add(v);
        if (!vols.isEmpty()) {
            LinearLayout cloud = rowGroup();
            cloud.addView(Ui.rowView(this, cloud, new Row(R.drawable.ic_drive, false, getString(R.string.usb_btn),
                    null, false, true).tint(Ui.color(this, R.color.ok)), v -> openUsb(vols)));
            Ui.group(this, cloud);
            content.addView(cloud);
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.home_sec_categories)));
        LinearLayout cats = rowGroup();
        String[] more = {Cats.AUD, Cats.APK, Cats.ARC, Cats.RECENT, Cats.FAV};
        for (String k : more) cats.addView(catRow(cats, k));
        Ui.group(this, cats);
        content.addView(cats);

        content.addView(Ui.sectionTitle(this, getString(R.string.home_tools)));
        LinearLayout tools = rowGroup();
        tools.addView(Ui.rowView(this, tools, new Row(R.drawable.ic_package, false, getString(R.string.home_installer),
                getString(R.string.pk_beta), false, true).tint(Ui.color(this, R.color.lime)),
                v -> startActivity(new Intent(this, InstallerActivity.class))));
        tools.addView(Ui.rowView(this, tools, new Row(R.drawable.ic_wifi, false, getString(R.string.sh_title),
                getString(R.string.sh_sub), false, true).tint(Ui.color(this, R.color.info)),
                v -> startActivity(new Intent(this, ShareActivity.class))));
        Ui.group(this, tools);
        content.addView(tools);

        if (keepY > 0) scroller.post(() -> scroller.scrollTo(0, keepY));
        rendered = true;
        renderSig = renderSignature();
        if (!entered) {   // soft staggered entrance the first time the screen is drawn
            entered = true;
            for (int i = 0; i < Math.min(content.getChildCount(), 8); i++) Ui.enter(content.getChildAt(i), i);
        }
    }

    private LinearLayout rowGroup() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        g.setPadding(0, Ui.dp(this, 14), 0, 0);
        return g;
    }

    /** One row of the grouped list for a category: system-colour chip, name, "count . size" and a chevron. */
    private View catRow(ViewGroup parent, final String cat) {
        int icon;
        int color;
        switch (cat) {
            case Cats.AUD:
                icon = R.drawable.ic_music;
                color = R.color.violet;
                break;
            case Cats.APK:
                icon = R.drawable.ic_package;
                color = R.color.lime;
                break;
            case Cats.RECENT:
                icon = R.drawable.ic_clock;
                color = R.color.info;
                break;
            case Cats.FAV:
                icon = R.drawable.ic_star;
                color = R.color.warn;
                break;
            default:
                icon = R.drawable.ic_archive;
                color = R.color.warn;
                break;
        }
        String sub = "";
        if (Cats.RECENT.equals(cat)) {
            if (stats != null) sub = getString(R.string.home_tile_sub, stats.newCount, Fmt.size(stats.newSize));
        } else if (Cats.FAV.equals(cat)) {
            sub = getString(R.string.fm_items_n, existingFavorites().size());
        } else if (stats != null) {
            sub = getString(R.string.home_tile_sub, Cats.countOf(stats, cat), Fmt.size(Cats.sizeOf(stats, cat)));
        } else if (Perms.hasAllFiles(this)) {
            sub = "\u2026";
        }
        return Ui.rowView(this, parent, new Row(icon, false, getString(Cats.titleOf(cat)), sub, false, true)
                .tint(Ui.color(this, color)), v -> openCategory(cat));
    }

    /** The four smart tiles (Images, Videos, Documents, Downloads) in a 2 x 2 grid; wide windows use more columns. */
    private View smartGrid(final File dl) {
        List<View> tiles = new ArrayList<>();
        tiles.add(smartTile(R.drawable.ic_image, R.color.ok, getString(Cats.titleOf(Cats.IMG)),
                catCount(Cats.IMG), catSize(Cats.IMG), v -> openCategory(Cats.IMG)));
        tiles.add(smartTile(R.drawable.ic_video, R.color.bad, getString(Cats.titleOf(Cats.VID)),
                catCount(Cats.VID), catSize(Cats.VID), v -> openCategory(Cats.VID)));
        tiles.add(smartTile(R.drawable.ic_file_text, R.color.info, getString(Cats.titleOf(Cats.DOC)),
                catCount(Cats.DOC), catSize(Cats.DOC), v -> openCategory(Cats.DOC)));
        boolean haveDl = dlCount >= 0;
        tiles.add(smartTile(R.drawable.ic_download, R.color.warn, getString(R.string.fm_downloads),
                haveDl ? String.valueOf(dlCount) : (Perms.hasAllFiles(this) ? "\u2026" : ""),
                haveDl ? Fmt.size(dlSize) : "", v -> openFolder(dl)));
        final int cols = Layout.isWide(this) ? Layout.columns(this, 2, 4) : 2;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int edge = Math.max(0, Layout.gutter(this) - Ui.dp(this, 6));
        grid.setPadding(edge, Ui.dp(this, 8), edge, 0);
        for (int i = 0; i < tiles.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < cols; k++) {
                View cell = i + k < tiles.size() ? tiles.get(i + k) : new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
                row.addView(cell, lp);
            }
            grid.addView(row);
        }
        return grid;
    }

    private String catCount(String cat) {
        if (stats != null) return String.valueOf(Cats.countOf(stats, cat));
        return Perms.hasAllFiles(this) ? "\u2026" : "";
    }

    private String catSize(String cat) {
        return stats != null ? Fmt.size(Cats.sizeOf(stats, cat)) : "";
    }

    private View smartTile(int icon, int colorRes, String title, String count, String size, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackgroundResource(R.drawable.bg_tile);
        tile.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        tile.setMinimumHeight(Ui.dp(this, 96));

        ImageView chip = new ImageView(this);
        chip.setImageResource(icon);
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Ui.iconChip(this, chip, Ui.color(this, colorRes));
        tile.addView(chip, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));

        TextView c = new TextView(this);
        c.setText(count);
        c.setTextSize(24);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setSingleLine(true);
        c.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        c.setTextColor(Ui.color(this, R.color.text_primary));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 10);
        tile.addView(c, cp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setSingleLine(true);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        tile.addView(t);

        TextView s = new TextView(this);
        s.setText(size);
        s.setTextSize(11);
        s.setSingleLine(true);
        s.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        s.setTextColor(Ui.color(this, R.color.text_secondary));
        tile.addView(s);

        tile.setContentDescription(title);
        Ui.press(this, tile);
        tile.setOnClickListener(click);
        return tile;
    }

    private int sideMargin() {
        return Math.max(Ui.dp(this, 16), Layout.gutter(this));
    }

    /** Storage card: used / total, a segmented bar by kind of file, two shortcuts and one thin bar per extra volume. */
    private View storageCard(final File internal) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p - Ui.dp(this, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(sideMargin(), Ui.dp(this, 12), sideMargin(), Ui.dp(this, 6));
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(R.string.fm_internal);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView pctView = new TextView(this);
        pctView.setTextSize(12);
        pctView.setSingleLine(true);
        pctView.setTextColor(Ui.color(this, R.color.text_secondary));
        top.addView(pctView);
        card.addView(top);

        final TextView sub = new TextView(this);
        sub.setTextSize(11);
        sub.setTextColor(Ui.color(this, R.color.text_secondary));
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        card.addView(sub);

        final SegBarView bar = new SegBarView(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 10));
        bp.topMargin = Ui.dp(this, 12);
        card.addView(bar, bp);
        try {
            StatFs st = new StatFs(internal.getAbsolutePath());
            long total = st.getTotalBytes();
            long used = Math.max(0, total - st.getAvailableBytes());
            if (total <= 0) throw new IllegalStateException();
            double pct = used * 100.0 / total;
            sub.setText(getString(R.string.home_used_of, Fmt.size(used), Fmt.size(total)));
            pctView.setText(getString(R.string.home_used_pct, Math.round(pct)));
            fillBar(bar, total, used);
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
                new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        View gap = new View(this);
        acts.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        acts.addView(cardButton(Cats.titleOf(Cats.LARGE), v -> openCategory(Cats.LARGE)),
                new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));

        for (File sd : sdRoots()) {
            try {
                StatFs st = new StatFs(sd.getAbsolutePath());
                long total = st.getTotalBytes();
                long used = Math.max(0, total - st.getAvailableBytes());
                if (total <= 0) continue;
                TextView l = new TextView(this);
                l.setText(Volumes.labelOf(this, sd) + "  \u00b7  " + getString(R.string.home_used_of, Fmt.size(used), Fmt.size(total)));
                l.setTextSize(12);
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

    /** Colours the bar per kind of file (iOS system colours); before the first scan it shows plain used space. */
    private void fillBar(SegBarView bar, long total, long used) {
        final int[] colorRes = {R.color.ok, R.color.bad, R.color.violet, R.color.info, R.color.lime, R.color.warn};
        final String[] cats = {Cats.IMG, Cats.VID, Cats.AUD, Cats.DOC, Cats.APK, Cats.ARC};
        float[] frac = new float[cats.length + 1];
        int[] cols = new int[cats.length + 1];
        long[] sz = new long[cats.length];
        long sum = 0;
        if (stats != null) {
            for (int i = 0; i < cats.length; i++) {
                sz[i] = Math.max(0, Cats.sizeOf(stats, cats[i]));
                sum += sz[i];
            }
        }
        double scale = sum > used && sum > 0 ? (double) used / sum : 1.0;
        long acc = 0;
        for (int i = 0; i < cats.length; i++) {
            long v = (long) (sz[i] * scale);
            frac[i] = total > 0 ? (float) v / total : 0f;
            cols[i] = Ui.color(this, colorRes[i]);
            acc += v;
        }
        long other = Math.max(0, used - acc);
        frac[cats.length] = total > 0 ? (float) other / total : 0f;
        cols[cats.length] = Ui.color(this, stats != null ? R.color.text_hint : R.color.accent);
        bar.setData(frac, cols, Ui.color(this, R.color.neutral_soft));
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
        bar.setBackgroundResource(R.drawable.bg_search);
        bar.setPaddingRelative(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_search);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_secondary)));
        bar.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        TextView t = new TextView(this);
        t.setText(R.string.home_search_hint);
        t.setTextSize(14);
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
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
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
