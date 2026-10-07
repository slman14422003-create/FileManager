package com.fileman.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Advanced storage tools: duplicate finder (size, then a 64 KB head hash, then a full SHA-256),
 * junk cleaner (empty folders, empty files, temporary files) and a folder size analyzer.
 * Every scan is bounded, runs off the UI thread and is cancelled when the screen goes away.
 */
public class ToolsActivity extends BaseActivity {
    private static final int S_HOME = 0, S_DUP = 1, S_JUNK = 2, S_SPACE = 3;
    private static final long MIN_DUP_BYTES = 16 * 1024;
    private static final int WALK_BUDGET = 200000;

    private static final class Group {
        final List<File> files;
        final long size;

        Group(List<File> files, long size) {
            this.files = files;
            this.size = size;
        }

        long wasted() {
            return size * (files.size() - 1);
        }
    }

    private static final class Junk {
        final File f;
        final int kind;   // 0 empty folder, 1 empty file, 2 temporary file
        final long size;

        Junk(File f, int kind, long size) {
            this.f = f;
            this.kind = kind;
            this.size = size;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long gen = 0;
    private volatile boolean destroyed = false;

    private LinearLayout content;
    private View loading;
    private TextView titleView, subtitleView;
    private int screen = S_HOME;

    private List<Group> dupGroups = new ArrayList<>();
    private List<Junk> junk = new ArrayList<>();
    private final ArrayDeque<File> spaceStack = new ArrayDeque<>();

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
        showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen == S_HOME) showHome();   // the all-files permission may have just been granted
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        gen++;
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private void onBack() {
        if (screen == S_SPACE && spaceStack.size() > 1) {
            spaceStack.pop();
            analyze(spaceStack.pop());
            return;
        }
        if (screen == S_HOME) {
            finish();
        } else {
            gen++;   // cancels a running scan
            showHome();
        }
    }

    private void reload() {
        switch (screen) {
            case S_DUP:
                scanDup();
                break;
            case S_JUNK:
                scanJunk();
                break;
            case S_SPACE:
                if (!spaceStack.isEmpty()) analyze(spaceStack.pop());
                break;
            default:
                showHome();
                break;
        }
    }

    private void header(String sub) {
        titleView.setText(R.string.tl_title);
        subtitleView.setText(sub);
        subtitleView.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void busy(boolean on) {
        loading.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private File root() {
        return Perms.hasAllFiles(this) ? Environment.getExternalStorageDirectory() : appDir();
    }

    private File appDir() {
        File d = getExternalFilesDir(null);
        return d != null ? d : getFilesDir();
    }

    private boolean alive(long my) {
        return !destroyed && my == gen;
    }

    // ------------------------------------------------------------------ home

    private void showHome() {
        screen = S_HOME;
        header(getString(R.string.tl_subtitle));
        busy(false);
        content.removeAllViews();
        if (!Perms.hasAllFiles(this)) {
            content.addView(Ui.noteCard(this, getString(R.string.tl_need_files), R.color.warn));
            Button grant = Ui.block(this, Ui.button(this, R.string.fm_perm_grant, true));
            grant.setOnClickListener(v -> Perms.requestAllFiles(this));
            content.addView(grant);
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.home_tools)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_copy, false, getString(R.string.tl_dup),
                getString(R.string.tl_dup_sub), false, true).tint(Ui.color(this, R.color.info)), v -> scanDup()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_scissors, false, getString(R.string.tl_junk),
                getString(R.string.tl_junk_sub), false, true).tint(Ui.color(this, R.color.warn)), v -> scanJunk()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_chart, false, getString(R.string.tl_space),
                getString(R.string.tl_space_sub), false, true).tint(Ui.color(this, R.color.ok)), v -> {
            spaceStack.clear();
            analyze(root());
        }));
    }

    private void scanning(String sub) {
        header(sub);
        content.removeAllViews();
        busy(true);
        content.addView(Ui.body(this, getString(R.string.tl_scanning), 14, R.color.text_secondary));
    }

    // ------------------------------------------------------------------ duplicates

    private void scanDup() {
        screen = S_DUP;
        scanning(getString(R.string.tl_dup));
        final long my = ++gen;
        final File base = root();
        io.execute(() -> {
            Map<Long, List<File>> bySize = new HashMap<>();
            walkSizes(base, bySize, new int[]{WALK_BUDGET}, 0, my);
            List<Group> out = new ArrayList<>();
            for (Map.Entry<Long, List<File>> e : bySize.entrySet()) {
                if (!alive(my)) return;
                List<File> same = e.getValue();
                if (same.size() < 2) continue;
                Map<String, List<File>> byHead = new HashMap<>();
                for (File f : same) {
                    if (!alive(my)) return;
                    String h = hash(f, 64 * 1024, my);
                    if (h != null) addTo(byHead, h, f);
                }
                for (List<File> hl : byHead.values()) {
                    if (hl.size() < 2) continue;
                    Map<String, List<File>> full = new HashMap<>();
                    for (File f : hl) {
                        if (!alive(my)) return;
                        String h = hash(f, Long.MAX_VALUE, my);
                        if (h != null) addTo(full, h, f);
                    }
                    for (List<File> g : full.values()) if (g.size() >= 2) out.add(new Group(g, e.getKey()));
                }
            }
            Collections.sort(out, (a, b) -> Long.compare(b.wasted(), a.wasted()));
            ui.post(() -> {
                if (!alive(my)) return;
                dupGroups = out;
                renderDup();
            });
        });
    }

    private void walkSizes(File dir, Map<Long, List<File>> out, int[] budget, int depth, long my) {
        if (!alive(my) || budget[0] <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (!alive(my) || budget[0] <= 0) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (depth == 0 && n.equals("Android")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) walkSizes(f, out, budget, depth + 1, my);
            } else {
                long len = f.length();
                if (len >= MIN_DUP_BYTES) {
                    List<File> l = out.get(len);
                    if (l == null) {
                        l = new ArrayList<>(2);
                        out.put(len, l);
                    }
                    l.add(f);
                }
            }
        }
    }

    private static void addTo(Map<String, List<File>> m, String key, File f) {
        List<File> l = m.get(key);
        if (l == null) {
            l = new ArrayList<>(2);
            m.put(key, l);
        }
        l.add(f);
    }

    /** SHA-256 of the first {@code limit} bytes, or null when the file cannot be read. */
    private String hash(File f, long limit, long my) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            long left = limit;
            while (left > 0) {
                if (!alive(my)) return null;
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r <= 0) break;
                md.update(buf, 0, r);
                left -= r;
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void renderDup() {
        busy(false);
        content.removeAllViews();
        header(getString(R.string.tl_dup));
        if (dupGroups.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.tl_dup_none), 14, R.color.text_secondary));
            return;
        }
        long wasted = 0;
        for (Group g : dupGroups) wasted += g.wasted();
        content.addView(Ui.noteCard(this, getString(R.string.tl_dup_summary, dupGroups.size(), Fmt.size(wasted)),
                R.color.accent_text));
        int shown = Math.min(dupGroups.size(), 40);
        for (int i = 0; i < shown; i++) {
            final Group g = dupGroups.get(i);
            content.addView(Ui.sectionTitle(this, getString(R.string.tl_dup_group,
                    g.files.get(0).getName(), g.files.size(), Fmt.size(g.size))));
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            for (final File f : g.files) {
                File p = f.getParentFile();
                String sub = (p != null ? p.getAbsolutePath() : "") + " · " + Fmt.ago(f.lastModified());
                int type = Cats.typeOfExt(Cats.extOf(f.getName()));
                Row r = new Row(Cats.iconFor(type), false, f.getName(), sub, false, true)
                        .tint(Ui.color(this, Cats.colorFor(type)));
                box.addView(Ui.rowView(this, box, r, v -> dupMenu(g, f)));
            }
            Ui.group(this, box);
            content.addView(box);
        }
    }

    private void dupMenu(final Group g, final File f) {
        String[] items = {getString(R.string.tl_keep_this), getString(R.string.tl_delete_this),
                getString(R.string.tl_open_folder)};
        new Dlg(this).setTitle(f.getName()).setItems(items, (d, which) -> {
            if (which == 0) {
                List<File> others = new ArrayList<>(g.files);
                others.remove(f);
                confirmDelete(others, () -> {
                    g.files.retainAll(Collections.singletonList(f));
                    dupGroups.remove(g);
                    renderDup();
                });
            } else if (which == 1) {
                confirmDelete(Collections.singletonList(f), () -> {
                    g.files.remove(f);
                    if (g.files.size() < 2) dupGroups.remove(g);
                    renderDup();
                });
            } else {
                File p = f.getParentFile();
                if (p != null) {
                    Intent i = new Intent(this, FileManagerActivity.class);
                    i.putExtra("path", p.getAbsolutePath());
                    startActivity(i);
                }
            }
        }).show();
    }

    // ------------------------------------------------------------------ junk

    private void scanJunk() {
        screen = S_JUNK;
        scanning(getString(R.string.tl_junk));
        final long my = ++gen;
        final File base = root();
        io.execute(() -> {
            List<Junk> out = new ArrayList<>();
            walkJunk(base, out, new int[]{WALK_BUDGET}, 0, my);
            Collections.sort(out, (a, b) -> Long.compare(b.size, a.size));
            ui.post(() -> {
                if (!alive(my)) return;
                junk = out;
                renderJunk();
            });
        });
    }

    private static boolean isTemp(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("thumbs.db") || n.equals("desktop.ini") || n.startsWith("~$")) return true;
        String ext = Cats.extOf(n);
        return ext.equals("tmp") || ext.equals("temp") || ext.equals("bak") || ext.equals("chk") || ext.equals("dmp");
    }

    /** Collects empty folders (below the top level), empty files and temporary files. */
    private void walkJunk(File dir, List<Junk> out, int[] budget, int depth, long my) {
        if (!alive(my) || budget[0] <= 0 || depth > 20 || out.size() >= 5000) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (!alive(my) || budget[0] <= 0) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;   // keeps .nomedia and other markers safe
            if (depth == 0 && n.equals("Android")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (Cats.isLink(f)) continue;
                walkJunk(f, out, budget, depth + 1, my);
                String[] kids = f.list();
                if (depth >= 1 && kids != null && kids.length == 0) out.add(new Junk(f, 0, 0));   // not top-level folders
            } else {
                long len = f.length();
                if (len == 0) out.add(new Junk(f, 1, 0));
                else if (isTemp(n)) out.add(new Junk(f, 2, len));
            }
        }
    }

    private void renderJunk() {
        busy(false);
        content.removeAllViews();
        header(getString(R.string.tl_junk));
        if (junk.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.tl_junk_none), 14, R.color.text_secondary));
            return;
        }
        long total = 0;
        for (Junk j : junk) total += j.size;
        content.addView(Ui.noteCard(this, getString(R.string.tl_junk_summary, junk.size(), Fmt.size(total)),
                R.color.accent_text));
        Button all = Ui.block(this, Ui.button(this, R.string.delete, true));
        all.setText(getString(R.string.tl_junk_all, junk.size()));
        all.setOnClickListener(v -> deleteJunk());
        content.addView(all);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int[] kindRes = {R.string.tl_k_empty_dir, R.string.tl_k_empty_file, R.string.tl_k_temp};
        int shown = Math.min(junk.size(), 60);
        for (int i = 0; i < shown; i++) {
            Junk j = junk.get(i);
            File p = j.f.getParentFile();
            String sub = (j.size > 0 ? Fmt.size(j.size) + " · " : "") + (p != null ? p.getAbsolutePath() : "");
            Row r = new Row(j.kind == 0 ? R.drawable.ic_folder : R.drawable.ic_file, false, j.f.getName(), sub, false, false)
                    .badge(getString(kindRes[j.kind]), Ui.color(this, R.color.warn));
            box.addView(Ui.rowView(this, box, r, null));
        }
        Ui.group(this, box);
        content.addView(box);
    }

    private void deleteJunk() {
        final List<File> files = new ArrayList<>();
        final List<File> dirs = new ArrayList<>();
        for (Junk j : junk) (j.kind == 0 ? dirs : files).add(j.f);
        // deepest folders first so a parent that became empty can go in the same pass
        Collections.sort(dirs, (a, b) -> b.getAbsolutePath().length() - a.getAbsolutePath().length());
        final List<File> all = new ArrayList<>(files);
        all.addAll(dirs);
        confirmDelete(all, this::scanJunk);
    }

    // ------------------------------------------------------------------ space analyzer

    private void analyze(final File dir) {
        screen = S_SPACE;
        spaceStack.push(dir);
        scanning(dir.equals(Environment.getExternalStorageDirectory()) ? getString(R.string.fm_internal) : dir.getName());
        final long my = ++gen;
        io.execute(() -> {
            File[] kids = dir.listFiles();
            final List<File> items = new ArrayList<>();
            final List<Long> sizes = new ArrayList<>();
            final boolean[] partial = {false};
            if (kids != null) {
                int[] budget = {WALK_BUDGET};
                List<long[]> pairs = new ArrayList<>();
                List<File> files = new ArrayList<>();
                for (File k : kids) {
                    if (!alive(my)) return;
                    if (k.getName().startsWith(".")) continue;
                    long s = k.isDirectory() ? (Cats.isLink(k) ? 0 : sizeOf(k, budget, my, 0)) : k.length();
                    files.add(k);
                    pairs.add(new long[]{files.size() - 1, s});
                }
                if (budget[0] <= 0) partial[0] = true;
                Collections.sort(pairs, (a, b) -> Long.compare(b[1], a[1]));
                for (long[] p : pairs) {
                    items.add(files.get((int) p[0]));
                    sizes.add(p[1]);
                }
            }
            ui.post(() -> {
                if (!alive(my)) return;
                renderSpace(dir, items, sizes, partial[0]);
            });
        });
    }

    private long sizeOf(File dir, int[] budget, long my, int depth) {
        if (!alive(my) || budget[0] <= 0 || depth > 25) return 0;
        File[] arr = dir.listFiles();
        if (arr == null) return 0;
        long sum = 0;
        for (File f : arr) {
            if (!alive(my) || budget[0] <= 0) return sum;
            budget[0]--;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) sum += sizeOf(f, budget, my, depth + 1);
            } else {
                sum += f.length();
            }
        }
        return sum;
    }

    private void renderSpace(File dir, List<File> items, List<Long> sizes, boolean partial) {
        busy(false);
        content.removeAllViews();
        header(dir.getAbsolutePath());
        if (items.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.tl_space_empty), 14, R.color.text_secondary));
            return;
        }
        long total = 0;
        for (long s : sizes) total += s;
        content.addView(Ui.noteCard(this, getString(R.string.tl_this_folder, Fmt.size(total))
                + (partial ? "\n" + getString(R.string.tl_space_partial) : ""), R.color.accent_text));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int shown = Math.min(items.size(), 80);
        for (int i = 0; i < shown; i++) {
            final File f = items.get(i);
            long s = sizes.get(i);
            int pct = total <= 0 ? 0 : (int) Math.round(s * 100.0 / total);
            int type = f.isDirectory() ? Cats.T_DIR : Cats.typeOfExt(Cats.extOf(f.getName()));
            Row r = new Row(Cats.iconFor(type), false, f.getName(), pct + "%", false, f.isDirectory())
                    .tint(Ui.color(this, Cats.colorFor(type)))
                    .badge(Fmt.size(s), Ui.color(this, pct >= 40 ? R.color.bad : R.color.accent_text));
            box.addView(Ui.rowView(this, box, r, v -> {
                if (f.isDirectory()) analyze(f);
                else Opener.open(this, f);
            }));
        }
        Ui.group(this, box);
        content.addView(box);
    }

    // ------------------------------------------------------------------ deleting

    private void confirmDelete(final List<File> files, final Runnable after) {
        if (files.isEmpty()) return;
        new Dlg(this)
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.tl_confirm_del, files.size()))
                .setPositiveButton(R.string.delete, (d, w) -> doDelete(files, after))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doDelete(final List<File> files, final Runnable after) {
        busy(true);
        io.execute(() -> {
            int ok = 0;
            for (File f : files) {
                if (destroyed) return;
                if (f.exists() && !Cats.isLink(f) && f.delete()) ok++;
            }
            final int done = ok;
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                Dlg.result(this, done > 0 || files.isEmpty(), getString(R.string.delete),
                        getString(R.string.tl_deleted, done, files.size()));
                after.run();
            });
        });
    }
}
