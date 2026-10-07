package com.fileman.app;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Browses a ZIP / JAR / CBZ like a folder tree without unpacking it: open a file to preview it,
 * long-press to select, extract everything or only the selection.
 */
public class ZipBrowseActivity extends BaseActivity {
    private static final long MAX_PREVIEW = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 200000;

    private static final class Node {
        String name;       // last path segment
        String path;       // full entry name, folders end with "/"
        boolean dir;
        long size, packed, time;
        int kids;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, List<Node>> tree = new HashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<Node> shown = new ArrayList<>();
    private final boolean[] cancel = {false};

    private File zip;
    private Charset charset = StandardCharsets.UTF_8;
    private String cur = "";
    private int fileCount;
    private long totalSize, totalPacked;
    private String query = "";
    private volatile boolean destroyed;

    private View loading;
    private TextView title, subtitle, empty;
    private ListView list;
    private Button extractBtn;
    private ListAdapter adapter;
    private AlertDialog busyDialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_zip);
        String path = getIntent().getStringExtra("path");
        zip = path == null ? null : new File(path);
        if (zip == null || !zip.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        title.setText(zip.getName());
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        empty = findViewById(R.id.empty);
        list = findViewById(R.id.list);
        extractBtn = findViewById(R.id.extractBtn);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ImageButton more = findViewById(R.id.btnA1);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> moreMenu());
        ImageButton find = findViewById(R.id.btnA2);
        find.setImageResource(R.drawable.ic_search);
        find.setContentDescription(getString(R.string.zip_search_hint));
        find.setVisibility(View.VISIBLE);
        find.setOnClickListener(v -> askSearch());

        adapter = new ListAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onClick(shown.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (!selected.isEmpty()) toggle(shown.get(pos));
            else itemMenu(shown.get(pos));
            return true;
        });
        extractBtn.setOnClickListener(v -> chooseDestination());

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!selected.isEmpty()) {
                    selected.clear();
                    refreshChrome();
                } else if (!query.isEmpty()) {
                    query = "";
                    show();
                } else if (!cur.isEmpty()) {
                    int cut = cur.lastIndexOf('/', cur.length() - 2);
                    cur = cut < 0 ? "" : cur.substring(0, cut + 1);
                    show();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (!inPreviewCache()) deleteTree(new File(getCacheDir(), "zipview"));
        loading.setVisibility(View.VISIBLE);
        io.execute(this::load);
    }

    // ------------------------------------------------------------------ reading the archive

    private ZipFile openZip() throws IOException {
        Charset[] tries = {StandardCharsets.UTF_8, Charset.forName("windows-1256"), StandardCharsets.ISO_8859_1};
        IOException last = null;
        for (Charset cs : tries) {
            try {
                ZipFile z = new ZipFile(zip, cs);
                charset = cs;
                return z;
            } catch (IOException | IllegalArgumentException e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        throw last == null ? new IOException("zip") : last;
    }

    private void load() {
        int err = 0;
        try (ZipFile z = openZip()) {
            Enumeration<? extends ZipEntry> en = z.entries();
            Map<String, Node> dirs = new HashMap<>();
            int n = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++n > MAX_ENTRIES) break;
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.contains("../") || name.equals("..") || name.startsWith("..")) continue;
                boolean dir = e.isDirectory() || name.endsWith("/");
                String[] parts = (dir ? name.substring(0, name.length() - 1) : name).split("/");
                StringBuilder acc = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    boolean last = i == parts.length - 1;
                    String parent = acc.toString();
                    acc.append(parts[i]).append(last && !dir ? "" : "/");
                    if (parts[i].isEmpty()) continue;
                    String full = acc.toString();
                    if (last && !dir) {
                        Node f = new Node();
                        f.name = parts[i];
                        f.path = full;
                        f.size = Math.max(0, e.getSize());
                        f.packed = Math.max(0, e.getCompressedSize());
                        f.time = e.getTime();
                        add(parent, f);
                        fileCount++;
                        totalSize += f.size;
                        totalPacked += f.packed;
                    } else if (!dirs.containsKey(full)) {
                        Node d = new Node();
                        d.name = parts[i];
                        d.path = full;
                        d.dir = true;
                        d.time = last ? e.getTime() : 0;
                        dirs.put(full, d);
                        add(parent, d);
                    }
                }
            }
            for (List<Node> l : tree.values()) {
                for (Node nd : l) if (nd.dir) nd.kids = tree.containsKey(nd.path) ? tree.get(nd.path).size() : 0;
                Collections.sort(l, (a, c) -> {
                    if (a.dir != c.dir) return a.dir ? -1 : 1;
                    return a.name.compareToIgnoreCase(c.name);
                });
            }
        } catch (ZipException e) {
            err = R.string.zip_failed;
        } catch (IOException | RuntimeException e) {
            err = R.string.zip_failed;
        }
        final int ferr = err;
        ui.post(() -> {
            if (destroyed) return;
            loading.setVisibility(View.INVISIBLE);
            if (ferr != 0) {
                Toast.makeText(this, ferr, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            show();
        });
    }

    private void add(String parent, Node n) {
        List<Node> l = tree.get(parent);
        if (l == null) {
            l = new ArrayList<>();
            tree.put(parent, l);
        }
        l.add(n);
    }

    // ------------------------------------------------------------------ list

    private void show() {
        shown.clear();
        if (!query.isEmpty()) {
            for (List<Node> l : tree.values()) {
                for (Node n : l) {
                    if (n.name.toLowerCase(Locale.ROOT).contains(query)) shown.add(n);
                    if (shown.size() >= 500) break;
                }
                if (shown.size() >= 500) break;
            }
            Collections.sort(shown, (a, c) -> a.name.compareToIgnoreCase(c.name));
        } else {
            List<Node> l = tree.get(cur);
            if (l != null) shown.addAll(l);
        }
        selected.clear();
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        refreshChrome();
    }

    private void refreshChrome() {
        String where = cur.isEmpty() ? "/" : "/" + cur;
        subtitle.setText(query.isEmpty() ? where + " · " + Fmt.size(zip.length())
                : getString(R.string.zip_search_n, shown.size(), query));
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(tree.isEmpty() ? R.string.zip_empty : R.string.zip_folder_empty);
        boolean any = !tree.isEmpty();
        extractBtn.setVisibility(any ? View.VISIBLE : View.GONE);
        extractBtn.setText(selected.isEmpty() ? getString(R.string.zip_extract_all)
                : getString(R.string.zip_extract_sel, selected.size()));
        adapter.notifyDataSetChanged();
    }

    private void toggle(Node n) {
        if (!selected.remove(n.path)) selected.add(n.path);
        refreshChrome();
    }

    private void onClick(Node n) {
        if (!selected.isEmpty()) {
            toggle(n);
            return;
        }
        if (n.dir) {
            cur = n.path;
            query = "";
            show();
        } else {
            preview(n);
        }
    }

    private final class ListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : android.view.LayoutInflater.from(ZipBrowseActivity.this).inflate(R.layout.item_row, parent, false);
            Node n = shown.get(position);
            int type = n.dir ? Cats.T_DIR : Cats.typeOfExt(Cats.extOf(n.name));
            String sub = n.dir ? getString(R.string.fm_items_n, n.kids)
                    : Fmt.size(n.size) + (n.time > 0 ? " · " + Fmt.date(n.time) : "");
            if (!query.isEmpty()) {
                int cut = n.path.lastIndexOf('/', n.path.length() - 2);
                sub = (cut < 0 ? "/" : "/" + n.path.substring(0, cut + 1)) + " · " + sub;
            }
            boolean sel = selected.contains(n.path);
            Row r = new Row(sel ? R.drawable.ic_check_circle : Cats.iconFor(type), false, n.name, sub, false, n.dir && !sel)
                    .tint(Ui.color(ZipBrowseActivity.this, sel ? R.color.accent_text : Cats.colorFor(type)));
            RowAdapter.bind(ZipBrowseActivity.this, v, r);
            Ui.shapeRow(ZipBrowseActivity.this, v, position == 0, position == shown.size() - 1,
                    sel ? R.color.accent_soft : R.color.surface);
            return v;
        }
    }

    // ------------------------------------------------------------------ menu

    private void moreMenu() {
        String[] items = {getString(R.string.zip_select_all), getString(R.string.zip_info),
                getString(R.string.fm_open_with)};
        new Dlg(this).setTitle(zip.getName()).setItems(items, (d, which) -> {
            if (which == 0) {
                selected.clear();
                for (Node n : shown) selected.add(n.path);
                refreshChrome();
            } else if (which == 1) {
                new Dlg(this).setTitle(R.string.zip_info)
                        .setMessage(getString(R.string.zip_info_body, fileCount, Fmt.size(totalSize),
                                Fmt.size(totalPacked > 0 ? totalPacked : zip.length())))
                        .setPositiveButton(android.R.string.ok, null).show();
            } else {
                Opener.external(this, zip, true);
            }
        }).show();
    }

    // ------------------------------------------------------------------ item actions

    private void askSearch() {
        final android.widget.EditText q = Ui.edit(this, getString(R.string.zip_search_hint), query);
        new Dlg(this).setTitle(R.string.zip_search_hint).setView(q)
                .setPositiveButton(android.R.string.search_go, (d, w) -> {
                    query = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                    show();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    if (!query.isEmpty()) {
                        query = "";
                        show();
                    }
                })
                .show();
    }

    private void itemMenu(final Node n) {
        final File parent = zip.getParentFile();
        String[] items = n.dir
                ? new String[]{getString(R.string.zip_item_open), getString(R.string.zip_item_copy),
                        getString(R.string.zip_item_select)}
                : new String[]{getString(R.string.zip_item_preview), getString(R.string.zip_item_copy),
                        getString(R.string.zip_item_share), getString(R.string.fm_open_with),
                        getString(R.string.zip_item_details), getString(R.string.zip_item_select)};
        new Dlg(this).setTitle(n.name).setItems(items, (d, which) -> {
            String pick = items[which];
            if (pick.equals(getString(R.string.zip_item_open))) {
                cur = n.path;
                query = "";
                show();
            } else if (pick.equals(getString(R.string.zip_item_preview))) {
                preview(n);
            } else if (pick.equals(getString(R.string.zip_item_copy))) {
                if (parent == null) return;
                final Set<String> one = new HashSet<>();
                one.add(n.path);
                destinationMenu(R.string.zip_copy_to, parent, dest -> extract(dest, one, !n.dir));
            } else if (pick.equals(getString(R.string.zip_item_share))) {
                exportEntry(n, true);
            } else if (pick.equals(getString(R.string.fm_open_with))) {
                exportEntry(n, false);
            } else if (pick.equals(getString(R.string.zip_item_details))) {
                details(n);
            } else {
                toggle(n);
            }
        }).show();
    }

    /** Unpacks one entry into the cache, then shares it or hands it to another app. */
    private void exportEntry(final Node n, final boolean share) {
        if (n.size > MAX_PREVIEW) {
            Toast.makeText(this, R.string.zip_too_big, Toast.LENGTH_LONG).show();
            return;
        }
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            File out = null;
            try (ZipFile z = openZip()) {
                ZipEntry e = z.getEntry(rawName(z, n.path));
                if (e == null) throw new IOException("missing");
                File dir = new File(getCacheDir(), "zipview/" + System.nanoTime());
                if (!dir.mkdirs()) throw new IOException("mkdir");
                out = new File(dir, safeName(n.name));
                copyEntry(z, e, out, MAX_PREVIEW);
            } catch (Exception e) {
                out = null;
            }
            final File f = out;
            ui.post(() -> {
                if (destroyed) return;
                loading.setVisibility(View.INVISIBLE);
                if (f == null) Toast.makeText(this, R.string.zip_failed, Toast.LENGTH_LONG).show();
                else if (share) Opener.share(this, f);
                else Opener.external(this, f, true);
            });
        });
    }

    private void details(Node n) {
        String crc = "—";
        String method = "—";
        long packed = n.packed;
        try (ZipFile z = openZip()) {
            ZipEntry e = z.getEntry(rawName(z, n.path));
            if (e != null) {
                if (e.getCrc() >= 0) crc = String.format(Locale.US, "%08X", e.getCrc());
                method = e.getMethod() == ZipEntry.STORED ? getString(R.string.zip_stored) : "Deflate";
                packed = Math.max(0, e.getCompressedSize());
            }
        } catch (Exception ignored) {
        }
        int ratio = n.size > 0 ? (int) Math.round(100.0 - packed * 100.0 / n.size) : 0;
        new Dlg(this).setTitle(n.name)
                .setMessage(getString(R.string.zip_detail_body, "/" + n.path, Fmt.size(n.size), Fmt.size(packed),
                        Math.max(0, ratio), n.time > 0 ? Fmt.date(n.time) : "—", method, crc))
                .setPositiveButton(android.R.string.ok, null).show();
    }

    // ------------------------------------------------------------------ preview

    private void preview(final Node n) {
        if (n.size > MAX_PREVIEW) {
            Toast.makeText(this, R.string.zip_too_big, Toast.LENGTH_LONG).show();
            return;
        }
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            File out = null;
            int err = 0;
            try (ZipFile z = openZip()) {
                ZipEntry e = z.getEntry(rawName(z, n.path));
                if (e == null) throw new IOException("missing");
                File dir = new File(getCacheDir(), "zipview/" + System.nanoTime());
                if (!dir.mkdirs()) throw new IOException("mkdir");
                out = new File(dir, safeName(n.name));
                copyEntry(z, e, out, MAX_PREVIEW);
            } catch (ZipException e) {
                err = R.string.zip_encrypted;
            } catch (Exception e) {
                err = R.string.zip_failed;
            }
            final File f = out;
            final int ferr = err;
            ui.post(() -> {
                if (destroyed) return;
                loading.setVisibility(View.INVISIBLE);
                if (ferr != 0 || f == null) {
                    Toast.makeText(this, ferr != 0 ? ferr : R.string.zip_failed, Toast.LENGTH_LONG).show();
                } else {
                    Opener.open(this, f);
                }
            });
        });
    }

    /** The entry as stored (the archive may use backslashes or a leading slash). */
    private String rawName(ZipFile z, String path) {
        if (z.getEntry(path) != null) return path;
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String name = e.getName().replace('\\', '/');
            while (name.startsWith("/")) name = name.substring(1);
            if (name.equals(path)) return e.getName();
        }
        return path;
    }

    private static String safeName(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "file";
        return s;
    }

    private static long copyEntry(ZipFile z, ZipEntry e, File out, long limit) throws IOException {
        long total = 0;
        try (InputStream in = new BufferedInputStream(z.getInputStream(e));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > limit) throw new IOException("too large");
                os.write(buf, 0, r);
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ extraction

    private void chooseDestination() {
        final File parent = zip.getParentFile();
        if (parent == null) return;
        final Set<String> sel = new HashSet<>(selected);
        destinationMenu(R.string.zip_dest_title, parent, dest -> extract(dest, sel, false));
    }

    interface Dest {
        void to(File dir);
    }

    /** Where to put the files: next to the archive, a new folder named after it, Downloads, or any folder. */
    private void destinationMenu(int titleRes, final File parent, final Dest d) {
        final File dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        String[] items = {getString(R.string.zip_dest_here), getString(R.string.zip_dest_folder),
                getString(R.string.zip_dest_download), getString(R.string.zip_dest_pick)};
        new Dlg(this).setTitle(titleRes).setItems(items, (dlg, which) -> {
            if (which == 0) d.to(parent);
            else if (which == 1) d.to(unique(parent, stripExt(zip.getName())));
            else if (which == 2) d.to(dl);
            else pickFolder(parent, d);
        }).show();
    }

    private AlertDialog pickDialog;

    /** A small folder browser: go into folders, go up, then choose the current one. */
    private void pickFolder(final File start, final Dest d) {
        if (pickDialog != null) {
            try {
                pickDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        final File[] kids = start.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        final List<File> dirs = new ArrayList<>();
        if (kids != null) Collections.addAll(dirs, kids);
        Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        final File up = start.getParentFile();
        final boolean canUp = up != null && up.canRead();
        List<String> names = new ArrayList<>();
        if (canUp) names.add(getString(R.string.zip_pick_up));
        for (File f : dirs) names.add(f.getName());
        pickDialog = new Dlg(this).setTitle(start.getAbsolutePath())
                .setItems(names.toArray(new String[0]), (dlg, which) -> {
                    int idx = canUp ? which - 1 : which;
                    pickFolder(idx < 0 ? up : dirs.get(idx), d);
                })
                .setPositiveButton(R.string.zip_pick_here, (dlg, w) -> d.to(start))
                .setNegativeButton(R.string.cancel, null)
                .create();
        pickDialog.show();
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 2;
        while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ext);
        return f;
    }

    private static File unique(File parent, String base) {
        File f = new File(parent, base);
        int i = 2;
        while (f.exists()) f = new File(parent, base + " (" + (i++) + ")");
        return f;
    }

    private boolean wanted(String name, Set<String> sel) {
        if (sel.isEmpty()) return true;
        for (String s : sel) {
            if (s.endsWith("/") ? name.startsWith(s) : name.equals(s)) return true;
        }
        return false;
    }

    private void extract(final File dest, final Set<String> sel, final boolean flat) {
        cancel[0] = false;
        showBusy();
        io.execute(() -> {
            int count = 0;
            int err = 0;
            String errText = null;
            try (ZipFile z = openZip()) {
                if (!dest.exists() && !dest.mkdirs()) throw new IOException(getString(R.string.fm_err_mkdir, dest.getName()));
                final String root = dest.getCanonicalPath() + File.separator;
                long room = Math.max(0L, dest.getUsableSpace() - 64L * 1024 * 1024);
                long written = 0;
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    if (cancel[0]) throw new IOException("cancelled");
                    ZipEntry e = en.nextElement();
                    String name = e.getName().replace('\\', '/');
                    while (name.startsWith("/")) name = name.substring(1);
                    if (name.isEmpty() || !wanted(name, sel)) continue;
                    if (count > MAX_ENTRIES) throw new IOException(getString(R.string.fm_err_zip_unsafe));
                    if (flat) {   // a single file copied out: just its own name, never overwrite
                        if (e.isDirectory() || name.endsWith("/")) continue;
                        name = safeName(name.substring(name.lastIndexOf('/') + 1));
                    }
                    File out = flat ? uniqueFile(dest, name) : new File(dest, name);
                    if (!out.getCanonicalPath().startsWith(root)) throw new IOException(getString(R.string.fm_err_zip_unsafe));
                    if (e.isDirectory() || name.endsWith("/")) {
                        out.mkdirs();
                        continue;
                    }
                    File p = out.getParentFile();
                    if (p != null) p.mkdirs();
                    written += copyEntry(z, e, out, Math.max(0, room - written));
                    count++;
                }
            } catch (ZipException e) {
                err = R.string.zip_encrypted;
            } catch (IOException e) {
                if (cancel[0]) err = R.string.fm_cancelled;
                else errText = e.getMessage() == null ? getString(R.string.zip_failed) : e.getMessage();
            } catch (RuntimeException e) {
                err = R.string.zip_failed;
            }
            final int fc = count;
            final int ferr = err;
            final String ftext = errText;
            ui.post(() -> {
                if (destroyed) return;
                hideBusy();
                String msg = ferr != 0 ? getString(ferr)
                        : ftext != null ? ftext
                        : getString(R.string.zip_extracted_to, fc, dest.getName());
                if (ferr == 0 && ftext == null) {
                    selected.clear();
                    refreshChrome();
                    new Dlg(this).setTitle(R.string.zip_extract_all).setMessage(msg)
                            .setPositiveButton(R.string.zip_item_open, (d, w) -> {
                                android.content.Intent i = new android.content.Intent(this, FileManagerActivity.class);
                                i.putExtra("path", dest.getAbsolutePath());
                                startActivity(i);
                            })
                            .setNegativeButton(android.R.string.ok, null).show();
                } else {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showBusy() {
        hideBusy();
        LinearLayout box = Ui.box(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 8));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        Ui.tint(this, pb);
        box.addView(pb);
        busyDialog = new Dlg(this).setTitle(R.string.fm_extracting).setView(box).setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> cancel[0] = true).create();
        busyDialog.show();
    }

    private void hideBusy() {
        if (busyDialog != null) {
            try {
                busyDialog.dismiss();
            } catch (Exception ignored) {
            }
            busyDialog = null;
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        cancel[0] = true;
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        hideBusy();
        if (pickDialog != null) {
            try {
                pickDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        if (isFinishing() && !inPreviewCache()) deleteTree(new File(getCacheDir(), "zipview"));
    }

    /** True when this archive was itself unpacked from another archive (nested zip). */
    private boolean inPreviewCache() {
        try {
            return zip.getCanonicalPath().startsWith(new File(getCacheDir(), "zipview").getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}
