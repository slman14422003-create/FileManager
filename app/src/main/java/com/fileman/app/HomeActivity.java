package com.fileman.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stop = new AtomicBoolean(false);

    private LinearLayout content;
    private Cats.Stats stats;
    private long statsAt = 0;
    private boolean scanning = false;
    private boolean hadAccess = false;
    private volatile int dlCount = -1;
    private volatile long dlSize = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean access = Perms.hasAllFiles(this);
        if (access != hadAccess) {
            stats = null;
            hadAccess = access;
        }
        render();
        if (!access) Perms.promptFirstRun(this);
        else Updater.autoCheck(this, io, ui);
        if (access && (stats == null || System.currentTimeMillis() - statsAt > SCAN_MAX_AGE_MS)) startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stop.set(true);
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ scan

    private void startScan() {
        if (scanning) return;
        scanning = true;
        stop.set(false);
        final boolean hidden = Store.showHidden(this);
        io.execute(() -> {
            Cats.Stats s = null;
            try {
                s = Cats.scan(Environment.getExternalStorageDirectory(), hidden, RECENT_ON_HOME, stop);
            } catch (Throwable ignored) {
            }
            final int[] dlc = {0};
            final long[] dls = {0};
            try {
                dirTotals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        dlc, dls, new int[]{30000}, 0);
            } catch (Throwable ignored) {
            }
            dlCount = dlc[0];
            dlSize = dls[0];
            final Cats.Stats res = s;
            ui.post(() -> {
                scanning = false;
                if (isFinishing() || isDestroyed() || res == null || stop.get()) return;
                stats = res;
                statsAt = System.currentTimeMillis();
                render();
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

    private void render() {
        final View scroller = (View) content.getParent();
        final int keepY = scroller.getScrollY();
        content.removeAllViews();
        final boolean access = Perms.hasAllFiles(this);

        if (!access) content.addView(permissionCard());

        // one grid of big tiles: volumes, downloads, analysis, the categories, then the smart lists
        List<View> tiles = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        tiles.add(storageTile(internal, getString(R.string.fm_internal)));
        int n = 1;
        for (File sd : sdRoots()) {
            tiles.add(storageTile(sd, getString(R.string.fm_sdcard) + (n > 1 ? " " + n : "")));
            n++;
        }
        final File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String dlSub = dlCount >= 0 ? getString(R.string.home_tile_sub, dlCount, Fmt.size(dlSize)) : (access ? "…" : "");
        tiles.add(homeTile(R.drawable.ic_download, R.color.ok, getString(R.string.fm_downloads), dlSub,
                v -> openFolder(dl)));
        tiles.add(analysisTile(internal));
        tiles.add(catTile(Cats.IMG));
        tiles.add(catTile(Cats.AUD));
        tiles.add(catTile(Cats.VID));
        tiles.add(catTile(Cats.DOC));
        tiles.add(catTile(Cats.APK));
        tiles.add(catTile(Cats.RECENT));
        tiles.add(catTile(Cats.FAV));
        tiles.add(catTile(Cats.ARC));
        tiles.add(catTile(Cats.LARGE));
        tiles.add(homeTile(R.drawable.ic_package, R.color.lime, getString(R.string.home_installer),
                getString(R.string.pk_beta), v -> startActivity(new Intent(this, InstallerActivity.class))));

        for (int i = 0; i < tiles.size(); i += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(this, 10), i == 0 ? Ui.dp(this, 6) : 0, Ui.dp(this, 10), 0);
            for (int k = 0; k < 3; k++) {
                View cell = i + k < tiles.size() ? tiles.get(i + k) : new View(this);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            content.addView(row);
        }
        if (keepY > 0) scroller.post(() -> scroller.scrollTo(0, keepY));
        if (!entered) {   // soft staggered entrance the first time the screen is drawn
            entered = true;
            for (int i = 0; i < Math.min(content.getChildCount(), 8); i++) Ui.enter(content.getChildAt(i), i);
        }
    }

    /** One grid cell: a rounded icon box, the name under it and a small detail line. */
    private View homeTile(int icon, int colorRes, String title, String sub, View.OnClickListener click) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_home_tile, content, false);
        ImageView iv = v.findViewById(R.id.icon);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, colorRes)));
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
        View v = homeTile(R.drawable.ic_drive, R.color.accent_text, label, "…", x -> openFolder(root));
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
                x -> startActivity(new Intent(this, ToolsActivity.class)));
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
        List<File> out = new ArrayList<>();
        File[] dirs = getExternalFilesDirs(null);
        if (dirs == null) return out;
        for (int i = 1; i < dirs.length; i++) {
            if (dirs[i] == null) continue;
            String p = dirs[i].getAbsolutePath();
            int k = p.indexOf("/Android/data");
            if (k > 0) {
                File r = new File(p.substring(0, k));
                if (r.exists()) out.add(r);
            }
        }
        return out;
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
