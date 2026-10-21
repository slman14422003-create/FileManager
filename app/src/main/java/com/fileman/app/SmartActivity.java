package com.fileman.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmartActivity extends BaseActivity {
    static final String EXTRA = "mode";
    static final String M_CLEAN = "clean", M_ORG = "organize", M_NAME = "rename", M_FIND = "find", M_GUARD = "guard";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long gen = 0;
    private volatile boolean destroyed = false;

    private LinearLayout content;
    private View loading;
    private TextView titleView, subtitleView, status;
    private LinearLayout results;
    private EditText query;
    private Button action;
    private String mode = M_CLEAN;
    private boolean needPerm;

    private List<SmartScan.Item> items = new ArrayList<>();
    private List<SmartPlan.Move> moves = new ArrayList<>();
    private List<SmartGuard.Alert> alerts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        String m = getIntent().getStringExtra(EXTRA);
        if (m != null) mode = m;
        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> start());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
        start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needPerm && Perms.hasAllFiles(this)) start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        gen++;
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private boolean alive(long my) {
        return !destroyed && my == gen;
    }

    private void busy(boolean on) {
        loading.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private File root() {
        return Environment.getExternalStorageDirectory();
    }

    private int titleRes() {
        switch (mode) {
            case M_ORG:
                return R.string.sm_org;
            case M_NAME:
                return R.string.sm_name;
            case M_FIND:
                return R.string.sm_find;
            case M_GUARD:
                return R.string.sm_guard;
            default:
                return R.string.sm_clean;
        }
    }

    private int subRes() {
        switch (mode) {
            case M_ORG:
                return R.string.sm_org_sub;
            case M_NAME:
                return R.string.sm_name_sub;
            case M_FIND:
                return R.string.sm_find_sub;
            case M_GUARD:
                return R.string.sm_guard_sub;
            default:
                return R.string.sm_clean_sub;
        }
    }

    private void header() {
        titleView.setText(titleRes());
        subtitleView.setText(subRes());
        subtitleView.setVisibility(View.VISIBLE);
    }

    private void start() {
        gen++;
        header();
        content.removeAllViews();
        if (!Perms.hasAllFiles(this)) {
            needPerm = true;
            busy(false);
            content.addView(Ui.noteCard(this, getString(R.string.sm_need_files), R.color.warn));
            Button grant = Ui.block(this, Ui.button(this, R.string.fm_perm_grant, true));
            grant.setOnClickListener(v -> Perms.requestAllFiles(this));
            content.addView(grant);
            return;
        }
        needPerm = false;
        switch (mode) {
            case M_ORG:
            case M_NAME:
                scanMoves();
                break;
            case M_FIND:
                showFind();
                break;
            case M_GUARD:
                scanGuard();
                break;
            default:
                scanClean();
                break;
        }
    }

    private void scanning() {
        content.removeAllViews();
        busy(true);
        content.addView(Ui.body(this, getString(R.string.sm_scanning), 14, R.color.text_secondary));
    }

    private void confirmDelete(final List<File> files, final Runnable after) {
        if (files.isEmpty()) return;
        new Dlg(this)
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.sm_confirm_del, files.size()))
                .setPositiveButton(R.string.delete, (d, w) -> deleteFiles(files, after))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteFiles(final List<File> files, final Runnable after) {
        busy(true);
        io.execute(() -> {
            int ok = 0;
            List<String> paths = new ArrayList<>();
            for (File f : files) {
                if (destroyed) return;
                if (f.exists() && !Cats.isLink(f) && f.delete()) {
                    ok++;
                    paths.add(f.getAbsolutePath());
                }
            }
            SmartPlan.scan(this, paths);
            final int done = ok;
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                Dlg.result(this, done > 0, getString(R.string.delete), getString(R.string.sm_deleted, done, files.size()));
                after.run();
            });
        });
    }

    private CheckBox checkRow(String title, String sub, boolean checked) {
        CheckBox cb = Ui.check(this, R.string.delete, checked);
        cb.setText(title + "\n" + sub);
        return cb;
    }

    private void openFolder(File f) {
        File p = f.getParentFile();
        if (p == null) return;
        Intent i = new Intent(this, FileManagerActivity.class);
        i.putExtra("path", p.getAbsolutePath());
        startActivity(i);
    }

    // ------------------------------------------------------------------ cleanup

    private void scanClean() {
        scanning();
        final long my = ++gen;
        io.execute(() -> {
            SmartScan.Result r = SmartScan.scan(this, root(), () -> !alive(my));
            ui.post(() -> {
                if (!alive(my)) return;
                items = r.items;
                renderClean(r.partial);
            });
        });
    }

    private String itemNote(SmartScan.Item it) {
        String note;
        switch (it.kind) {
            case SmartScan.K_DUP:
                note = getString(R.string.sm_dup_of, it.note);
                break;
            case SmartScan.K_APK:
                note = getString(R.string.sm_apk_note, it.note);
                break;
            case SmartScan.K_STALE:
                note = getString(R.string.sm_stale_note, it.note);
                break;
            default:
                note = "";
        }
        File p = it.f.getParentFile();
        String path = p != null ? p.getAbsolutePath() : "";
        String size = it.size > 0 ? Fmt.size(it.size) + " \u00b7 " : "";
        return size + (note.isEmpty() ? "" : note + " \u00b7 ") + path;
    }

    private void renderClean(boolean partial) {
        busy(false);
        content.removeAllViews();
        if (items.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.sm_clean_none), 14, R.color.text_secondary));
            return;
        }
        content.addView(Ui.noteCard(this, getString(R.string.sm_clean_summary, items.size())
                + (partial ? "\n" + getString(R.string.sm_partial) : ""), R.color.accent_text));
        action = Ui.block(this, Ui.button(this, R.string.delete, true));
        action.setOnClickListener(v -> deleteChecked());
        content.addView(action);
        int[] titles = {R.string.sm_k_dup, R.string.sm_k_apk, R.string.sm_k_stale, R.string.sm_k_junk, R.string.sm_k_empty};
        for (int k = 0; k < titles.length; k++) {
            List<SmartScan.Item> part = new ArrayList<>();
            for (SmartScan.Item it : items) if (it.kind == k) part.add(it);
            if (part.isEmpty()) continue;
            content.addView(Ui.sectionTitle(this, getString(titles[k]) + " (" + part.size() + ")"));
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int shown = Math.min(part.size(), 150);
            for (int i = 0; i < shown; i++) {
                final SmartScan.Item it = part.get(i);
                CheckBox cb = checkRow(it.f.getName(), itemNote(it), it.checked);
                cb.setOnCheckedChangeListener((b, on) -> {
                    it.checked = on;
                    updateAction();
                });
                box.addView(cb);
            }
            Ui.group(this, box);
            content.addView(box);
        }
        updateAction();
    }

    private void updateAction() {
        int n = 0;
        long total = 0;
        for (SmartScan.Item it : items) {
            if (it.checked) {
                n++;
                total += it.size;
            }
        }
        action.setText(getString(R.string.sm_delete_sel, n, Fmt.size(total)));
        action.setEnabled(n > 0);
    }

    private void deleteChecked() {
        final List<File> files = new ArrayList<>();
        final List<File> dirs = new ArrayList<>();
        for (SmartScan.Item it : items) {
            if (!it.checked) continue;
            (it.kind == SmartScan.K_EMPTY ? dirs : files).add(it.f);
        }
        Collections.sort(dirs, (a, b) -> b.getAbsolutePath().length() - a.getAbsolutePath().length());
        files.addAll(dirs);
        confirmDelete(files, this::scanClean);
    }

    // ------------------------------------------------------------------ organize and rename

    private void scanMoves() {
        scanning();
        final long my = ++gen;
        final boolean organize = mode.equals(M_ORG);
        io.execute(() -> {
            List<SmartPlan.Move> r = organize
                    ? SmartPlan.organize(SmartPlan.downloads(), () -> !alive(my))
                    : SmartPlan.renames(SmartPlan.renameRoots(), () -> !alive(my));
            ui.post(() -> {
                if (!alive(my)) return;
                moves = r;
                renderMoves();
            });
        });
    }

    private void renderMoves() {
        busy(false);
        content.removeAllViews();
        final boolean organize = mode.equals(M_ORG);
        final int undo = SmartPlan.journalSize(this);
        if (undo > 0) {
            Button b = Ui.block(this, Ui.button(this, R.string.cancel, false));
            b.setText(getString(R.string.sm_undo, undo));
            b.setOnClickListener(v -> {
                busy(true);
                io.execute(() -> {
                    final int n = SmartPlan.undo(this);
                    ui.post(() -> {
                        if (destroyed) return;
                        busy(false);
                        Dlg.result(this, n > 0, getString(titleRes()), getString(R.string.sm_undone, n));
                        scanMoves();
                    });
                });
            });
            content.addView(b);
        }
        if (moves.isEmpty()) {
            content.addView(Ui.body(this, getString(organize ? R.string.sm_org_none : R.string.sm_name_none), 14,
                    R.color.text_secondary));
            return;
        }
        Set<String> groups = new HashSet<>();
        for (SmartPlan.Move m : moves) groups.add(m.group);
        content.addView(Ui.noteCard(this, organize
                ? getString(R.string.sm_org_summary, moves.size(), groups.size())
                : getString(R.string.sm_name_summary, moves.size()), R.color.accent_text));
        action = Ui.block(this, Ui.button(this, R.string.delete, true));
        action.setOnClickListener(v -> applyMoves(organize));
        content.addView(action);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int shown = Math.min(moves.size(), 200);
        for (int i = 0; i < shown; i++) {
            final SmartPlan.Move m = moves.get(i);
            String sub = organize ? getString(R.string.sm_move_to, m.group) : "\u2192 " + m.to.getName();
            CheckBox cb = checkRow(m.from.getName(), sub, m.checked);
            cb.setOnCheckedChangeListener((b, on) -> {
                m.checked = on;
                updateMoveAction(organize);
            });
            box.addView(cb);
        }
        Ui.group(this, box);
        content.addView(box);
        updateMoveAction(organize);
    }

    private void updateMoveAction(boolean organize) {
        int n = 0;
        for (SmartPlan.Move m : moves) if (m.checked) n++;
        action.setText(getString(organize ? R.string.sm_org_apply : R.string.sm_name_apply, n));
        action.setEnabled(n > 0);
    }

    private void applyMoves(final boolean organize) {
        busy(true);
        io.execute(() -> {
            final int n = SmartPlan.apply(this, moves);
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                Dlg.result(this, n > 0, getString(titleRes()),
                        getString(organize ? R.string.sm_org_done : R.string.sm_name_done, n));
                scanMoves();
            });
        });
    }

    // ------------------------------------------------------------------ search

    private void showFind() {
        busy(false);
        content.removeAllViews();
        query = Ui.edit(this, getString(R.string.sm_find_hint), null);
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, a, e) -> {
            doSearch();
            return true;
        });
        content.addView(query);
        Button go = Ui.block(this, Ui.button(this, R.string.sm_find_btn, true));
        go.setOnClickListener(v -> doSearch());
        content.addView(go);
        status = Ui.noteCard(this, getString(R.string.sm_scanning), R.color.accent_text);
        content.addView(status);
        Button rebuild = Ui.block(this, Ui.button(this, R.string.sm_find_btn, false));
        rebuild.setText(R.string.sm_find_build);
        rebuild.setOnClickListener(v -> buildIndex());
        content.addView(rebuild);
        content.addView(Ui.body(this, getString(R.string.sm_find_note), 12, R.color.text_secondary));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);
        final long my = gen;
        io.execute(() -> {
            final int n = SmartIndex.size(this);
            final long at = SmartIndex.builtAt(this);
            ui.post(() -> {
                if (!alive(my)) return;
                if (n == 0) buildIndex();
                else showStatus(n, at);
            });
        });
    }

    private void showStatus(int n, long at) {
        status.setText(getString(R.string.sm_find_status, n, Fmt.ago(at)));
    }

    private void buildIndex() {
        busy(true);
        status.setText(getString(R.string.sm_find_building, 0));
        final long my = ++gen;
        io.execute(() -> {
            SmartIndex.build(this, () -> !alive(my), n -> ui.post(() -> {
                if (alive(my)) status.setText(getString(R.string.sm_find_building, n));
            }));
            final int n = SmartIndex.size(this);
            final long at = SmartIndex.builtAt(this);
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                showStatus(n, at);
            });
        });
    }

    private void doSearch() {
        final String q = query.getText().toString().trim();
        if (q.isEmpty()) return;
        final long my = gen;
        io.execute(() -> {
            final List<SmartIndex.Hit> hits = SmartIndex.search(this, q, 50);
            ui.post(() -> {
                if (destroyed || my != gen) return;
                renderHits(hits);
            });
        });
    }

    private void renderHits(List<SmartIndex.Hit> hits) {
        results.removeAllViews();
        if (hits.isEmpty()) {
            results.addView(Ui.body(this, getString(R.string.sm_find_none), 14, R.color.text_secondary));
            return;
        }
        results.addView(Ui.sectionTitle(this, getString(R.string.sm_find_results, hits.size())));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (final SmartIndex.Hit h : hits) {
            File p = h.f.getParentFile();
            String sub = Fmt.size(h.f.length()) + " \u00b7 " + (p != null ? p.getAbsolutePath() : "");
            int type = Cats.typeOfExt(Cats.extOf(h.f.getName()));
            Row r = new Row(Cats.iconFor(type), false, h.f.getName(), sub, false, true)
                    .tint(Ui.color(this, Cats.colorFor(type)));
            box.addView(Ui.rowView(this, box, r, v -> Opener.open(this, h.f)));
        }
        Ui.group(this, box);
        results.addView(box);
    }

    // ------------------------------------------------------------------ protection

    private void scanGuard() {
        scanning();
        final long my = ++gen;
        io.execute(() -> {
            List<SmartGuard.Alert> r = SmartGuard.scan(this, () -> !alive(my));
            ui.post(() -> {
                if (!alive(my)) return;
                alerts = r;
                SmartGuard.markSeen(this, r);
                renderGuard();
            });
        });
    }

    private void renderGuard() {
        busy(false);
        content.removeAllViews();
        if (alerts.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.sm_guard_none), 14, R.color.text_secondary));
            content.addView(Ui.body(this, getString(R.string.sm_guard_note), 12, R.color.text_secondary));
            return;
        }
        content.addView(Ui.noteCard(this, getString(R.string.sm_guard_summary, alerts.size()), R.color.accent_text));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (final SmartGuard.Alert a : alerts) {
            boolean high = a.level >= SecurityScan.HIGH;
            Row r = new Row(R.drawable.ic_shield, false, a.f.getName(), a.reason, false, true)
                    .tint(Ui.color(this, high ? R.color.bad : R.color.warn))
                    .badge(getString(high ? R.string.sm_lvl_high : R.string.sm_lvl_caution),
                            Ui.color(this, high ? R.color.bad : R.color.warn));
            box.addView(Ui.rowView(this, box, r, v -> alertMenu(a)));
        }
        Ui.group(this, box);
        content.addView(box);
        content.addView(Ui.body(this, getString(R.string.sm_guard_note), 12, R.color.text_secondary));
    }

    private void alertMenu(final SmartGuard.Alert a) {
        String[] items = {getString(R.string.sm_open_folder), getString(R.string.sm_delete_file)};
        new Dlg(this).setTitle(a.f.getName()).setItems(items, (d, which) -> {
            if (which == 0) {
                openFolder(a.f);
            } else {
                confirmDelete(Collections.singletonList(a.f), this::scanGuard);
            }
        }).show();
    }
}
