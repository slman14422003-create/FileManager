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
 * Home screen: storage overview, quick-access tiles (images, videos, audio, documents, apps,
 * archives), tools (recent, largest, favorites), pinned favorites, common places and the newest files.
 */
public class HomeActivity extends AppCompatActivity {
    private static final long SCAN_MAX_AGE_MS = 60_000;
    private static final int RECENT_ON_HOME = 6;
    private static final int PINNED_ON_HOME = 4;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stop = new AtomicBoolean(false);

    private LinearLayout content;
    private Cats.Stats stats;
    private long statsAt = 0;
    private boolean scanning = false;
    private boolean hadAccess = false;

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

    // ------------------------------------------------------------------ rendering

    private boolean entered = false;

    private void render() {
        final View scroller = (View) content.getParent();
        final int keepY = scroller.getScrollY();
        content.removeAllViews();
        boolean access = Perms.hasAllFiles(this);

        if (!access) content.addView(permissionCard());
        content.addView(searchPill());

        // storage
        File internal = Environment.getExternalStorageDirectory();
        content.addView(storageCard(internal, getString(R.string.fm_internal), R.drawable.ic_drive));
        int n = 1;
        for (File sd : sdRoots()) {
            content.addView(storageCard(sd, getString(R.string.fm_sdcard) + (n > 1 ? " " + n : ""),
                    R.drawable.ic_drive));
            n++;
        }

        // places as chips: one tap to the folders used most
        content.addView(placeChips());

        // quick access tiles
        content.addView(Ui.sectionTitle(this, getString(R.string.home_quick)));
        content.addView(tileRow(Cats.IMG, Cats.VID, Cats.AUD));
        content.addView(tileRow(Cats.DOC, Cats.APK, Cats.ARC));

        // tools
        content.addView(Ui.sectionTitle(this, getString(R.string.home_tools)));
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.addView(catRow(R.drawable.ic_clock, Cats.RECENT, getString(R.string.home_recent_sub), 0, R.color.accent_text));
        tools.addView(catRow(R.drawable.ic_chart, Cats.LARGE, getString(R.string.home_large_sub), 0, R.color.warn));
        int favCount = existingFavorites().size();
        tools.addView(catRow(R.drawable.ic_star, Cats.FAV, getString(R.string.home_fav_sub), favCount, R.color.warn));
        Ui.group(this, tools);
        content.addView(tools);

        // pinned favorites
        List<File> favs = existingFavorites();
        if (!favs.isEmpty()) {
            content.addView(Ui.sectionTitle(this, getString(R.string.home_pinned)));
            LinearLayout pinned = new LinearLayout(this);
            pinned.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < Math.min(PINNED_ON_HOME, favs.size()); i++) pinned.addView(fileRow(favs.get(i), true));
            Ui.group(this, pinned);
            content.addView(pinned);
        }

        // newest files
        if (stats != null && !stats.recent.isEmpty()) {
            content.addView(Ui.sectionTitle(this, getString(R.string.home_newest)));
            LinearLayout recent = new LinearLayout(this);
            recent.setOrientation(LinearLayout.VERTICAL);
            for (File f : stats.recent) recent.addView(fileRow(f, false));
            Ui.group(this, recent);
            content.addView(recent);
        }
        if (keepY > 0) scroller.post(() -> scroller.scrollTo(0, keepY));
        if (!entered) {   // soft staggered entrance the first time the screen is drawn
            entered = true;
            for (int i = 0; i < Math.min(content.getChildCount(), 8); i++) Ui.enter(content.getChildAt(i), i);
        }
    }

    /** Big search field at the top; tapping it opens the search screen with the keyboard up. */
    private View searchPill() {
        TextView t = new TextView(this);
        t.setText(R.string.fm_search_hint);
        t.setTextSize(15);
        t.setTextColor(Ui.color(this, R.color.text_hint));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackgroundResource(R.drawable.bg_input);
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        t.setCompoundDrawablePadding(Ui.dp(this, 12));
        t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_hint)));
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 15), Ui.dp(this, 18), Ui.dp(this, 15));
        Ui.block(this, t);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = Ui.dp(this, 4);
        Ui.press(this, t);
        t.setOnClickListener(v -> {
            Intent i = new Intent(this, FileManagerActivity.class);
            i.putExtra("search", true);
            startActivity(i);
        });
        return t;
    }

    private View placeChips() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addChip(row, R.drawable.ic_download, R.string.fm_downloads,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        addChip(row, R.drawable.ic_camera, R.string.fm_dcim,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        addChip(row, R.drawable.ic_image, R.string.fm_pictures,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        addChip(row, R.drawable.ic_file_text, R.string.fm_documents,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        addChip(row, R.drawable.ic_music, R.string.fm_music,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
        addChip(row, R.drawable.ic_video, R.string.fm_movies,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        addChip(row, R.drawable.ic_folder, R.string.fm_app_folder, appDir());
        hs.addView(row);
        return hs;
    }

    private void addChip(LinearLayout row, int icon, int title, final File dir) {
        if (dir == null || !dir.exists()) return;
        TextView t = Ui.chip(this, getString(title), false);
        t.setTextSize(14);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        t.setCompoundDrawablePadding(Ui.dp(this, 8));
        t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        t.setOnClickListener(v -> openFolder(dir));
        row.addView(t);
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

    /** Card with the volume name, used / total size and a usage bar. Tapping it opens the volume. */
    private View storageCard(final File root, String label, int iconRes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_hero);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 14));
        Ui.block(this, card);
        Ui.press(this, card);
        card.setOnClickListener(v -> openFolder(root));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        int p = Ui.dp(this, 10);
        icon.setPadding(p, p, p, p);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Ui.color(this, R.color.accent_soft));
        icon.setBackground(circle);
        top.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Ui.color(this, R.color.text_primary));
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(Ui.dp(this, 14));
        top.addView(name, nl);

        TextView pctView = new TextView(this);
        pctView.setTextSize(13);
        pctView.setTextColor(Ui.color(this, R.color.text_secondary));
        top.addView(pctView);
        card.addView(top);

        TextView big = new TextView(this);
        big.setTextSize(30);
        big.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        big.setTextColor(Ui.color(this, R.color.text_primary));
        big.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        big.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 2));
        card.addView(big);

        TextView line = new TextView(this);
        line.setTextSize(13);
        line.setTextColor(Ui.color(this, R.color.text_secondary));
        line.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        line.setPadding(0, 0, 0, Ui.dp(this, 10));
        card.addView(line);

        try {
            StatFs st = new StatFs(root.getAbsolutePath());
            long total = st.getTotalBytes();
            long free = st.getAvailableBytes();
            long used = Math.max(0, total - free);
            if (total <= 0) throw new IllegalStateException();
            double pct = used * 100.0 / total;
            big.setText(Fmt.size(used));
            line.setText(getString(R.string.home_storage_line, Fmt.size(total), Fmt.size(free)));
            pctView.setText(getString(R.string.home_used_pct, Math.round(pct)));
            card.addView(Ui.bar(this, pct, pct > 90 ? R.color.bad : R.color.accent, 0, 4));
        } catch (Exception e) {
            big.setText("—");
            line.setText(R.string.fm_unreadable);
        }
        return card;
    }

    /** A horizontal row of three category tiles with equal width. */
    private View tileRow(String... cats) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        for (String c : cats) {
            View t = tile(c);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private View tile(final String cat) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_tile, content, false);
        int icon;
        int color;
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
                color = R.color.info;
                break;
            case Cats.DOC:
                icon = R.drawable.ic_file_text;
                color = R.color.accent_text;
                break;
            case Cats.APK:
                icon = R.drawable.ic_package;
                color = R.color.ok;
                break;
            default:
                icon = R.drawable.ic_archive;
                color = R.color.warn;
                break;
        }
        int col = Ui.color(this, color);
        ImageView iv = v.findViewById(R.id.icon);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(col));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 14));
        g.setColor((col & 0x00FFFFFF) | 0x26000000);
        iv.setBackground(g);

        ((TextView) v.findViewById(R.id.title)).setText(Cats.titleOf(cat));
        TextView sub = v.findViewById(R.id.sub);
        if (stats != null) {
            sub.setText(getString(R.string.home_tile_sub, Cats.countOf(stats, cat),
                    Fmt.size(Cats.sizeOf(stats, cat))));
        } else {
            sub.setText(Perms.hasAllFiles(this) ? "…" : "");
        }
        Ui.press(this, v.findViewById(R.id.card));
        v.findViewById(R.id.card).setOnClickListener(x -> openCategory(cat));
        return v;
    }

    private View catRow(int icon, final String cat, String sub, int count, int color) {
        Row r = new Row(icon, false, getString(Cats.titleOf(cat)), sub, false, true)
                .tint(Ui.color(this, color));
        if (count > 0) r.badge(String.valueOf(count), Ui.color(this, R.color.warn));
        return Ui.rowView(this, content, r, v -> openCategory(cat));
    }

    /** A row for a file or folder (pinned favorites and the newest files). */
    private View fileRow(final File f, boolean showPath) {
        boolean dir = f.isDirectory();
        int type = dir ? Cats.T_DIR : Cats.typeOfExt(Cats.extOf(f.getName()));
        String sub;
        if (dir) {
            String[] kids = f.list();
            sub = kids == null ? getString(R.string.fm_unreadable) : getString(R.string.fm_items_n, kids.length);
            File p = f.getParentFile();
            if (showPath && p != null) sub += " · " + p.getName();
        } else {
            sub = Fmt.size(f.length()) + " · " + Fmt.ago(f.lastModified());
        }
        Row r = new Row(Cats.iconFor(type), false, f.getName(), sub, false, dir)
                .tint(Ui.color(this, Cats.colorFor(type)));
        return Ui.rowView(this, content, r, v -> {
            if (dir) openFolder(f);
            else Opener.open(this, f);
        });
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
