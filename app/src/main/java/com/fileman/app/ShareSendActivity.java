package com.fileman.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ShareSendActivity extends BaseActivity {
    private static final class Group {
        String label;
        List<ShareItem> items = new ArrayList<>();
        long size;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Group> groups = new ArrayList<>();
    private final ShareNet.Hotspot hotspot = new ShareNet.Hotspot();
    private final ShareNet.Locks locks = new ShareNet.Locks();

    private LinearLayout content;
    private View loading;
    private TextView titleView, subtitleView;
    private volatile boolean destroyed;
    private boolean live;
    private static final int M_HOTSPOT = 0;
    private static final int M_DIRECT = 1;
    private static final int M_NETWORK = 2;

    private final ShareNet.P2p p2p = new ShareNet.P2p();
    private int method = M_HOTSPOT;
    private String lastIps = "";
    private int tickCount;
    private ShareServer server;
    private String token;
    private String deviceName;
    private File receiveDir;
    private ShareLink link;
    private int ipTries;

    private TextView liveStatus, livePeers, liveSpeed;
    private ProgressBar liveBar;
    private long lastBytes;
    private long lastTick;
    private double speed;

    private ActivityResultLauncher<Intent> pickFiles;
    private ActivityResultLauncher<Intent> pickDir;
    private ActivityResultLauncher<String[]> permLauncher;
    private Runnable afterPerm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        loading = findViewById(R.id.loading);
        content = findViewById(R.id.content);
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        Ui.autoGroup(this, content);
        deviceName = ShareActivity.deviceName(this);
        receiveDir = ShareActivity.receiveDir(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (live) confirmStop();
                else finish();
            }
        });

        pickFiles = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Intent d = r.getData();
            if (r.getResultCode() != RESULT_OK || d == null) return;
            List<String> paths = new ArrayList<>();
            List<String> many = d.getStringArrayListExtra("picked_all");
            if (many != null) paths.addAll(many);
            String one = d.getStringExtra("picked");
            if (one != null) paths.add(one);
            addPaths(paths, false);
        });
        pickDir = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            Intent d = r.getData();
            if (r.getResultCode() != RESULT_OK || d == null) return;
            String dir = d.getStringExtra("picked_dir");
            if (dir != null) addPaths(Collections.singletonList(dir), true);
        });
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
            Runnable a = afterPerm;
            afterPerm = null;
            if (a != null) a.run();
        });
        showCompose();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        stopAll();
        io.shutdownNow();
    }

    private void stopAll() {
        if (server != null) server.stop();
        server = null;
        hotspot.stop();
        p2p.stop();
        locks.release();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void header(CharSequence sub) {
        titleView.setText(R.string.sh_send_title);
        subtitleView.setText(sub);
        subtitleView.setVisibility(sub == null || sub.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private View row(int icon, String title, String sub, View.OnClickListener click) {
        return Ui.rowView(this, content, new Row(icon, false, title, sub, false, click != null), click);
    }

    private View button(int res, boolean primary, View.OnClickListener l) {
        Button b = Ui.button(this, res, primary);
        b.setOnClickListener(l);
        return Ui.block(this, b);
    }

    private long totalSize() {
        long t = 0;
        for (Group g : groups) t += g.size;
        return t;
    }

    private int totalCount() {
        int n = 0;
        for (Group g : groups) n += g.items.size();
        return n;
    }

    private View tile(int icon, String label, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
        t.setClickable(true);
        t.setOnClickListener(click);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        int pad = Ui.dp(this, 15);
        iv.setPadding(pad, pad, pad, pad);
        Ui.outlineIcon(this, iv, 15);
        t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setMaxLines(2);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Ui.color(this, R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        t.addView(tv, lp);
        return t;
    }

    private View grid(View... tiles) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < tiles.length; i += 4) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 4; j++) {
                View v = i + j < tiles.length ? tiles[i + j] : new View(this);
                r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            col.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return Ui.block(this, col);
    }

    private void showCompose() {
        live = false;
        header(getString(R.string.sx_pick_hint));
        loading.setVisibility(View.INVISIBLE);
        content.removeAllViews();

        if (!groups.isEmpty()) {
            content.addView(Ui.sectionTitle(this, getString(R.string.sh_selected)));
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (final Group g : groups) {
                list.addView(Ui.rowView(this, list, new Row(R.drawable.ic_check_circle, false, g.label,
                        getString(R.string.sh_group_sub, g.items.size(), Fmt.size(g.size)), false, true)
                        .tint(Ui.color(this, R.color.ok)), v -> {
                    groups.remove(g);
                    showCompose();
                }));
            }
            Ui.group(this, list);
            content.addView(list);
            content.addView(Ui.footer(this, getString(R.string.sh_tap_remove)));
            content.addView(Ui.body(this, getString(R.string.sh_summary, totalCount(), Fmt.size(totalSize())), 15, R.color.text_primary));
            content.addView(button(R.string.sx_next, true, v -> askMethod()));
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.sx_pick_title)));
        content.addView(grid(
                tile(R.drawable.ic_image, getString(R.string.cat_images), v -> addCategory(Cats.IMG, getString(R.string.cat_images))),
                tile(R.drawable.ic_video, getString(R.string.cat_videos), v -> addCategory(Cats.VID, getString(R.string.cat_videos))),
                tile(R.drawable.ic_music, getString(R.string.cat_audio), v -> addCategory(Cats.AUD, getString(R.string.cat_audio))),
                tile(R.drawable.ic_file, getString(R.string.sx_t_files), v -> openPicker(false)),
                tile(R.drawable.ic_file_text, getString(R.string.cat_docs), v -> addCategory(Cats.DOC, getString(R.string.cat_docs))),
                tile(R.drawable.ic_package, getString(R.string.sx_t_apps), v -> pickApps()),
                tile(R.drawable.ic_contact, getString(R.string.sx_t_contacts), v -> pickContacts()),
                tile(R.drawable.ic_clipboard, getString(R.string.sx_t_text), v -> pickText()),
                tile(R.drawable.ic_folder, getString(R.string.sx_t_folder), v -> openPicker(true)),
                tile(R.drawable.ic_archive, getString(R.string.cat_archives), v -> addCategory(Cats.ARC, getString(R.string.cat_archives))),
                tile(R.drawable.ic_drive, getString(R.string.sx_t_all), v -> addEverything())));
    }

    private boolean needFiles() {
        if (Perms.hasAllFiles(this)) return false;
        new Dlg(this).setTitle(R.string.sh_perm_files_title)
                .setMessage(R.string.sh_perm_files)
                .setPositiveButton(R.string.perm_open_settings, (d, w) -> Perms.requestAllFiles(this))
                .setNegativeButton(R.string.cancel, null)
                .show();
        return true;
    }

    private void openPicker(boolean dir) {
        if (needFiles()) return;
        Intent i = new Intent(this, FileManagerActivity.class);
        if (dir) {
            i.putExtra("pick_dir", true);
            pickDir.launch(i);
        } else {
            i.putExtra("pick", true).putExtra("pick_multi", true);
            pickFiles.launch(i);
        }
    }

    private void busy(boolean on) {
        loading.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void walk(File dir, String prefix, String cat, List<ShareItem> out, int depth) {
        if (destroyed || depth > 30 || out.size() >= ShareProto.MAX_ITEMS) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (destroyed || out.size() >= ShareProto.MAX_ITEMS) return;
            String n = f.getName();
            if (n.endsWith(".fmpart")) continue;
            if (f.isDirectory()) {
                if (Cats.isLink(f)) continue;
                if (depth == 0 && prefix.isEmpty() && n.equals("Android")) continue;
                walk(f, prefix + n + "/", cat, out, depth + 1);
            } else if (f.isFile() && f.canRead()) {
                if (cat != null && !Cats.matches(cat, Cats.extOf(n))) continue;
                out.add(ShareItem.of(f, prefix + n));
            }
        }
    }

    private void commit(String label, List<ShareItem> items) {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.sh_nothing_found, Toast.LENGTH_SHORT).show();
            return;
        }
        Group g = new Group();
        g.label = label;
        g.items = items;
        for (ShareItem i : items) g.size += Math.max(0, i.size);
        if (live && server != null) {
            server.addItems(items);
            Toast.makeText(this, getString(R.string.sh_added_live, items.size()), Toast.LENGTH_SHORT).show();
            return;
        }
        groups.add(g);
        showCompose();
    }

    private void addPaths(final List<String> paths, final boolean dirMode) {
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            String label = "";
            for (String p : paths) {
                File f = new File(p);
                if (f.isDirectory()) {
                    walk(f, f.getName() + "/", null, out, 1);
                    label = f.getName();
                } else if (f.isFile()) {
                    out.add(ShareItem.of(f, f.getName()));
                    if (label.isEmpty()) label = f.getName();
                }
            }
            final String fl = paths.size() > 1 && !dirMode ? getString(R.string.sh_n_files, paths.size()) : label;
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(fl, out);
            });
        });
    }

    private void addCategory(final String cat, final String label) {
        if (needFiles()) return;
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            File root = Environment.getExternalStorageDirectory();
            walk(root, label + "/", cat, out, 0);
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(label, out);
            });
        });
    }

    private void addEverything() {
        if (needFiles()) return;
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            walk(Environment.getExternalStorageDirectory(), "", null, out, 0);
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(getString(R.string.sh_everything), out);
            });
        });
    }

    private void pickContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            afterPerm = () -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) pickContacts();
                else Toast.makeText(this, R.string.sx_c_perm, Toast.LENGTH_LONG).show();
            };
            permLauncher.launch(new String[]{Manifest.permission.READ_CONTACTS});
            return;
        }
        busy(true);
        io.execute(() -> {
            final List<ShareContacts.Person> all = ShareContacts.list(this);
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                showContactDialog(all);
            });
        });
    }

    private void showContactDialog(final List<ShareContacts.Person> all) {
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.sx_c_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final Set<String> chosen = new HashSet<>();
        final List<ShareContacts.Person> shown = new ArrayList<>(all);
        final TextView count = Ui.body(this, getString(R.string.sx_c_count, 0), 14, R.color.text_secondary);
        final EditText search = Ui.edit(this, getString(R.string.sx_c_search), "");
        final ListView lv = new ListView(this);
        lv.setDivider(null);
        final BaseAdapter ad = new BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int i) {
                return shown.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                final ShareContacts.Person ps = shown.get(i);
                CheckBox cb;
                if (v instanceof CheckBox) {
                    cb = (CheckBox) v;
                } else {
                    cb = Ui.check(ShareSendActivity.this, R.string.cv_select_all, false);
                    cb.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                cb.setOnCheckedChangeListener(null);
                cb.setText(ps.name);
                cb.setChecked(chosen.contains(ps.key));
                cb.setOnCheckedChangeListener((b, on) -> {
                    if (on) chosen.add(ps.key);
                    else chosen.remove(ps.key);
                    count.setText(getString(R.string.sx_c_count, chosen.size()));
                });
                return cb;
            }
        };
        lv.setAdapter(ad);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence cs, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence cs, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (ShareContacts.Person ps : all) if (q.isEmpty() || ps.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(ps);
                ad.notifyDataSetChanged();
            }
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        TextView allChip = Ui.chip(this, getString(R.string.sx_c_all), false);
        allChip.setOnClickListener(v -> {
            for (ShareContacts.Person ps : shown) chosen.add(ps.key);
            count.setText(getString(R.string.sx_c_count, chosen.size()));
            ad.notifyDataSetChanged();
        });
        TextView noneChip = Ui.chip(this, getString(R.string.sx_c_none), false);
        noneChip.setOnClickListener(v -> {
            chosen.clear();
            count.setText(getString(R.string.sx_c_count, 0));
            ad.notifyDataSetChanged();
        });
        bar.addView(allChip);
        bar.addView(noneChip);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        box.addView(search);
        box.addView(bar);
        box.addView(count);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.4f)));
        new Dlg(this).setTitle(R.string.sx_c_title).setView(box)
                .setPositiveButton(R.string.sh_add, (d, w) -> {
                    List<ShareContacts.Person> sel = new ArrayList<>();
                    for (ShareContacts.Person ps : all) if (chosen.contains(ps.key)) sel.add(ps);
                    if (!sel.isEmpty()) buildContacts(sel);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildContacts(final List<ShareContacts.Person> sel) {
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            try {
                File dir = new File(getCacheDir(), "incoming/share-contacts/" + System.currentTimeMillis());
                File f = ShareContacts.export(this, sel, dir);
                if (f.length() > 0) out.add(ShareItem.of(f, "Contacts/" + f.getName()));
            } catch (Throwable ignored) {
            }
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(getString(R.string.sx_t_contacts) + " (" + sel.size() + ")", out);
            });
        });
    }

    private void pickText() {
        String clip = "";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null) clip = t.toString();
            }
        } catch (Throwable ignored) {
        }
        final EditText e = Ui.editMulti(this, getString(R.string.sx_x_hint), clip, 5);
        new Dlg(this).setTitle(R.string.sx_x_title).setView(Ui.block(this, e))
                .setPositiveButton(R.string.sh_add, (d, w) -> {
                    String t = e.getText().toString();
                    if (!t.trim().isEmpty()) buildText(t);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildText(final String text) {
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            try {
                String first = text.trim().split("\\r?\\n")[0];
                String base = ShareContacts.safeName(first.length() > 24 ? first.substring(0, 24) : first);
                File dir = new File(getCacheDir(), "incoming/share-text/" + System.currentTimeMillis());
                if (dir.mkdirs() || dir.isDirectory()) {
                    File f = new File(dir, base + ".txt");
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write(text.getBytes("UTF-8"));
                    }
                    out.add(ShareItem.of(f, "Notes/" + f.getName()));
                }
            } catch (Throwable ignored) {
            }
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(getString(R.string.sx_t_text), out);
            });
        });
    }

    private void pickApps() {
        busy(true);
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            final List<String[]> apps = new ArrayList<>();
            try {
                for (PackageInfo pi : pm.getInstalledPackages(0)) {
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai == null) continue;
                    boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                    if (sys) continue;
                    apps.add(new String[]{String.valueOf(ai.loadLabel(pm)), pi.packageName,
                            pi.versionName == null ? "" : pi.versionName});
                }
            } catch (Throwable ignored) {
            }
            Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                showAppDialog(apps);
            });
        });
    }

    private void showAppDialog(final List<String[]> apps) {
        final List<CheckBox> boxes = new ArrayList<>();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 8));
        for (String[] a : apps) {
            CheckBox cb = Ui.check(this, R.string.cv_select_all, false);
            cb.setText(a[0]);
            boxes.add(cb);
            box.addView(cb);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new Dlg(this).setTitle(getString(R.string.sh_add_apps)).setView(sv)
                .setPositiveButton(R.string.sh_add, (d, w) -> {
                    List<String[]> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) chosen.add(apps.get(i));
                    if (!chosen.isEmpty()) buildApps(chosen);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildApps(final List<String[]> chosen) {
        busy(true);
        io.execute(() -> {
            List<ShareItem> out = new ArrayList<>();
            PackageManager pm = getPackageManager();
            File dir = new File(getCacheDir(), "share-apps");
            dir.mkdirs();
            for (String[] a : chosen) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(a[1], 0);
                    String name = ApkConverter.safeName(a[0], a[2]);
                    if (ai.splitSourceDirs == null || ai.splitSourceDirs.length == 0) {
                        out.add(ShareItem.of(new File(ai.sourceDir), "Apps/" + name + ".apk"));
                    } else {
                        File z = new File(dir, name + ".apks");
                        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(z)))) {
                            zo.setLevel(Deflater.NO_COMPRESSION);
                            put(zo, "splits/base.apk", ai.sourceDir);
                            for (String s : ai.splitSourceDirs) put(zo, "splits/" + new File(s).getName(), s);
                        }
                        out.add(ShareItem.of(z, "Apps/" + name + ".apks"));
                    }
                } catch (Throwable ignored) {
                }
            }
            ui.post(() -> {
                if (destroyed) return;
                busy(false);
                commit(getString(R.string.sh_add_apps), out);
            });
        });
    }

    private static void put(ZipOutputStream zo, String name, String path) throws java.io.IOException {
        zo.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) zo.write(buf, 0, r);
        }
        zo.closeEntry();
    }

    private void askMethod() {
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.sh_need_items, Toast.LENGTH_SHORT).show();
            return;
        }
        chooseMethod(false);
    }

    private View methodRow(int icon, int title, int sub, boolean rec, final AlertDialog[] ref, final Runnable act) {
        Row r = new Row(icon, true, getString(title), getString(sub), false, true);
        if (rec) r.badge(getString(R.string.sx_m_rec), Ui.color(this, R.color.ok));
        return Ui.rowView(this, content, r, v -> {
            if (ref[0] != null) ref[0].dismiss();
            act.run();
        });
    }

    private void chooseMethod(final boolean switching) {
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] ref = new AlertDialog[1];
        list.addView(methodRow(R.drawable.ic_wifi, R.string.sx_m_hotspot, R.string.sx_m_hotspot_sub, true, ref,
                () -> pickMethod(M_HOTSPOT, switching)));
        list.addView(methodRow(R.drawable.ic_phone, R.string.sx_m_direct, R.string.sx_m_direct_sub, false, ref,
                () -> pickMethod(M_DIRECT, switching)));
        list.addView(methodRow(R.drawable.ic_qr, R.string.sx_m_network, R.string.sx_m_network_sub, false, ref,
                () -> pickMethod(M_NETWORK, switching)));
        if (!switching) {
            list.addView(methodRow(R.drawable.ic_share, R.string.sx_m_system, R.string.sx_m_system_sub, false, ref,
                    this::shareViaSystem));
        }
        Ui.group(this, list);
        ref[0] = new Dlg(this).setTitle(R.string.sx_m_title).setView(list)
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void pickMethod(int m, boolean switching) {
        if (switching) switchTo(m);
        else beginLive(m);
    }

    private boolean radioReady(final Runnable again) {
        final String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{"android.permission.NEARBY_WIFI_DEVICES"}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        if (checkSelfPermission(perms[0]) != PackageManager.PERMISSION_GRANTED) {
            afterPerm = () -> {
                if (checkSelfPermission(perms[0]) == PackageManager.PERMISSION_GRANTED) again.run();
                else Toast.makeText(this, R.string.sh_perm_denied, Toast.LENGTH_LONG).show();
            };
            permLauncher.launch(perms);
            return false;
        }
        if (Build.VERSION.SDK_INT < 33 && !locationOn()) {
            new Dlg(this).setTitle(R.string.sh_loc_title).setMessage(R.string.sh_loc_msg)
                    .setPositiveButton(R.string.perm_open_settings, (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return false;
        }
        return true;
    }

    private boolean radioChecks(int m, Runnable again) {
        if (m == M_NETWORK) return true;
        if (!radioReady(again)) return false;
        if (m == M_DIRECT && !ShareNet.wifiEnabled(this)) {
            new Dlg(this).setTitle(R.string.sx_wifi_off_title).setMessage(R.string.sx_wifi_off_send)
                    .setPositiveButton(R.string.sx_open_wifi, (d, w) -> ShareNet.openWifi(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return false;
        }
        return true;
    }

    private void networkNeeded(final boolean fresh) {
        final Runnable go = fresh ? this::launchNetwork : () -> startTransport(M_NETWORK);
        new Dlg(this).setTitle(R.string.sx_net_none_title).setMessage(R.string.sx_net_none_msg)
                .setPositiveButton(R.string.sx_open_hotspot, (d, w) -> {
                    ShareNet.openTether(this);
                    go.run();
                })
                .setNeutralButton(R.string.sx_open_wifi, (d, w) -> {
                    ShareNet.openWifi(this);
                    go.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchNetwork() {
        method = M_NETWORK;
        if (!startServer()) return;
        showLive();
        startTransport(M_NETWORK);
    }

    private void beginLive(final int m) {
        method = m;
        if (!radioChecks(m, () -> beginLive(m))) return;
        if (m == M_NETWORK && ShareNet.localIps().isEmpty()) {
            networkNeeded(true);
            return;
        }
        if (!startServer()) return;
        showLive();
        startTransport(m);
    }

    private void switchTo(final int m) {
        if (!radioChecks(m, () -> switchTo(m))) return;
        if (m == M_NETWORK && ShareNet.localIps().isEmpty()) {
            method = M_NETWORK;
            showLive();
            networkNeeded(false);
            return;
        }
        method = m;
        showLive();
        startTransport(m);
    }

    private void startTransport(int m) {
        if (destroyed || server == null || link == null) return;
        method = m;
        hotspot.stop();
        p2p.stop();
        link.ssid = "";
        link.pass = "";
        link.sec = 1;
        lastIps = "";
        ipTries = 0;
        qrView.setVisibility(View.GONE);
        credsView.setText("");
        if (m == M_HOTSPOT) {
            liveStatus.setText(R.string.sh_starting);
            hotspot.start(this, new ShareNet.Hotspot.Cb() {
                @Override
                public void started() {
                    if (destroyed || method != M_HOTSPOT) return;
                    ipTries = 0;
                    publish();
                }

                @Override
                public void failed(int reason) {
                    if (destroyed || method != M_HOTSPOT) return;
                    hotspotFailed(reason);
                }
            });
        } else if (m == M_DIRECT) {
            liveStatus.setText(R.string.sh_starting);
            p2p.start(this, new ShareNet.P2p.Cb() {
                @Override
                public void started() {
                    if (destroyed || method != M_DIRECT) return;
                    ipTries = 0;
                    publish();
                }

                @Override
                public void failed(int reason) {
                    if (destroyed || method != M_DIRECT) return;
                    p2pFailed(reason);
                }
            });
        } else {
            publish();
        }
    }

    private boolean locationOn() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return true;
        }
    }

    private void hotspotFailed(int reason) {
        if (destroyed) return;
        new Dlg(this).setTitle(R.string.sx_fail_title).setMessage(ShareNet.hotspotReasonText(reason))
                .setPositiveButton(R.string.sx_fail_direct, (d, w) -> switchTo(M_DIRECT))
                .setNeutralButton(R.string.sx_fail_net, (d, w) -> switchTo(M_NETWORK))
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    stopAll();
                    showCompose();
                })
                .show();
    }

    private void p2pFailed(int reason) {
        if (destroyed) return;
        new Dlg(this).setTitle(R.string.sx_fail_title).setMessage(ShareNet.p2pReasonText(reason))
                .setPositiveButton(R.string.sx_fail_hotspot, (d, w) -> switchTo(M_HOTSPOT))
                .setNeutralButton(R.string.sx_fail_net, (d, w) -> switchTo(M_NETWORK))
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    stopAll();
                    showCompose();
                })
                .show();
    }

    private void shareViaSystem() {
        if (totalCount() > 250) {
            Toast.makeText(this, R.string.sx_sys_many, Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        File first = null;
        for (Group g : groups) {
            for (ShareItem i : g.items) {
                if (i.file == null || !Safe.mayExpose(this, i.file)) continue;
                try {
                    uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", i.file));
                    if (first == null) first = i.file;
                } catch (Throwable ignored) {
                }
            }
        }
        if (uris.isEmpty() || first == null) {
            Toast.makeText(this, R.string.sx_sys_none, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent in;
            if (uris.size() == 1) {
                String name = first.getName();
                int dot = name.lastIndexOf('.');
                String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
                in = new Intent(Intent.ACTION_SEND);
                in.setType(mime == null ? "*/*" : mime);
                in.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                in.setClipData(ClipData.newRawUri("", uris.get(0)));
            } else {
                in = new Intent(Intent.ACTION_SEND_MULTIPLE);
                in.setType("*/*");
                in.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                ClipData cd = ClipData.newRawUri("", uris.get(0));
                for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
                in.setClipData(cd);
            }
            in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(in, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean startServer() {
        try {
            token = ShareProto.newToken();
            server = new ShareServer(this, deviceName, token, receiveDir);
            List<ShareItem> all = new ArrayList<>();
            for (Group g : groups) all.addAll(g.items);
            server.addItems(all);
            server.listener = new ShareServer.Listener() {
                @Override
                public void onPeers() {
                }

                @Override
                public void onReceived(File f) {
                }
            };
            int port = server.start();
            link = new ShareLink();
            link.port = port;
            link.token = token;
            link.name = deviceName;
            locks.acquire(this);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return true;
        } catch (Throwable e) {
            Toast.makeText(this, R.string.sh_server_fail, Toast.LENGTH_LONG).show();
            stopAll();
            return false;
        }
    }

    private ImageView qrView;
    private TextView credsView;

    private void showLive() {
        live = true;
        header(getString(R.string.sh_live_sub));
        content.removeAllViews();
        liveStatus = Ui.body(this, getString(R.string.sh_starting), 15, R.color.text_primary);
        content.addView(liveStatus);

        qrView = new ImageView(this);
        qrView.setBackgroundColor(0xFFFFFFFF);
        qrView.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        int side = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 80), Ui.dp(this, 320));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(side, side);
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        qp.topMargin = Ui.dp(this, 10);
        qrView.setVisibility(View.GONE);
        content.addView(qrView, qp);

        credsView = Ui.body(this, "", 14, R.color.text_secondary);
        credsView.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(credsView);

        content.addView(Ui.noteCard(this, getString(R.string.sx_live_steps), R.color.info));

        content.addView(Ui.sectionTitle(this, getString(R.string.sh_activity)));
        livePeers = Ui.body(this, getString(R.string.sh_devices_none), 15, R.color.text_primary);
        content.addView(livePeers);
        liveSpeed = Ui.body(this, "", 14, R.color.text_secondary);
        content.addView(liveSpeed);
        liveBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        Ui.tint(this, liveBar);
        liveBar.setMax(1000);
        content.addView(Ui.block(this, liveBar));

        content.addView(row(R.drawable.ic_add, getString(R.string.sh_add_more), null, v -> addMoreLive()));
        if (method == M_NETWORK) {
            content.addView(row(R.drawable.ic_wifi, getString(R.string.sx_open_hotspot), null, v -> ShareNet.openTether(this)));
            content.addView(row(R.drawable.ic_wifi, getString(R.string.sx_open_wifi), null, v -> ShareNet.openWifi(this)));
        }
        content.addView(row(R.drawable.ic_refresh, getString(R.string.sx_switch), getString(R.string.sx_switch_sub), v -> chooseMethod(true)));
        content.addView(button(R.string.sh_copy_code, false, v -> copyCode()));
        content.addView(button(R.string.sh_stop, true, v -> confirmStop()));
        lastTick = System.currentTimeMillis();
        lastBytes = 0;
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 500);
    }

    private void addMoreLive() {
        String[] items = {getString(R.string.sh_add_files), getString(R.string.sh_add_folder),
                getString(R.string.sx_t_contacts), getString(R.string.sx_t_text)};
        new Dlg(this).setTitle(getString(R.string.sh_add_more)).setItems(items, (d, w) -> {
            if (w == 0) openPicker(false);
            else if (w == 1) openPicker(true);
            else if (w == 2) pickContacts();
            else pickText();
        }).show();
    }

    private void copyCode() {
        if (link == null) return;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("fmshare", link.encode()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (live) refreshNet();
    }

    private static String joinIps(List<String> ips) {
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) sb.append(',');
            sb.append(ip);
        }
        return sb.toString();
    }

    private String currentSsid() {
        if (method == M_HOTSPOT && hotspot.active) return hotspot.ssid;
        if (method == M_DIRECT && p2p.active) return p2p.ssid;
        return "";
    }

    private void refreshNet() {
        if (destroyed || !live || server == null || link == null || qrView == null) return;
        if ((method == M_HOTSPOT && !hotspot.active) || (method == M_DIRECT && !p2p.active)) return;
        List<String> ips = ShareNet.localIps();
        String key = joinIps(ips) + "|" + currentSsid();
        if (!key.equals(lastIps)) applyLink(ips);
    }

    private void publish() {
        if (destroyed || server == null) return;
        List<String> ips = ShareNet.localIps();
        if (ips.isEmpty() && ipTries < 12 && method != M_NETWORK) {
            ipTries++;
            liveStatus.setText(R.string.sh_live_wait_ip);
            ui.postDelayed(this::publish, 700);
            return;
        }
        applyLink(ips);
    }

    private void applyLink(List<String> ips) {
        if (destroyed || server == null || link == null) return;
        link.ips = ips;
        if (method == M_HOTSPOT && hotspot.active) {
            link.ssid = hotspot.ssid;
            link.pass = hotspot.pass;
            link.sec = hotspot.sec;
        } else if (method == M_DIRECT && p2p.active) {
            link.ssid = p2p.ssid;
            link.pass = p2p.pass;
            link.sec = 1;
        } else {
            link.ssid = "";
            link.pass = "";
        }
        lastIps = joinIps(ips) + "|" + link.ssid;
        boolean usable = !ips.isEmpty() || !link.ssid.isEmpty();
        if (usable) {
            try {
                Bitmap bm = ShareQr.make(link.encode(), 720);
                qrView.setImageBitmap(bm);
                qrView.setVisibility(View.VISIBLE);
            } catch (Throwable e) {
                qrView.setVisibility(View.GONE);
            }
        } else {
            qrView.setVisibility(View.GONE);
        }
        liveStatus.setText(!usable ? R.string.sx_live_wait_net
                : (link.ssid.isEmpty() ? R.string.sh_live_ready_manual : R.string.sh_live_ready));
        StringBuilder sb = new StringBuilder();
        if (!link.ssid.isEmpty()) {
            sb.append(getString(R.string.sh_ssid, link.ssid)).append('\n');
            sb.append(getString(R.string.sh_pass, link.pass.isEmpty() ? "-" : link.pass)).append('\n');
        }
        if (!ips.isEmpty()) {
            StringBuilder a = new StringBuilder();
            for (String ip : ips) {
                if (a.length() > 0) a.append("  |  ");
                a.append(ip);
            }
            sb.append(getString(R.string.sh_addr, a + ":" + link.port));
        }
        credsView.setText(sb.toString());
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !live || server == null) return;
            long now = System.currentTimeMillis();
            long bytes = server.sent.get() + server.received.get();
            long dt = Math.max(1, now - lastTick);
            double inst = (bytes - lastBytes) * 1000.0 / dt;
            speed = speed == 0 ? inst : speed * 0.6 + inst * 0.4;
            lastTick = now;
            lastBytes = bytes;
            List<String> peers = server.peers();
            if (peers.isEmpty()) {
                livePeers.setText(R.string.sh_devices_none);
            } else {
                StringBuilder sb = new StringBuilder(getString(R.string.sh_devices, peers.size()));
                for (String p : peers) sb.append('\n').append(p);
                livePeers.setText(sb.toString());
            }
            long total = Math.max(1, server.total());
            liveSpeed.setText(getString(R.string.sh_live_stats, Fmt.size(server.sent.get()), Fmt.size(server.received.get()),
                    Fmt.size((long) speed) + "/s", server.count()));
            liveBar.setProgress((int) Math.min(1000, server.sent.get() * 1000 / total));
            if (++tickCount % 6 == 0) refreshNet();
            ui.postDelayed(this, 500);
        }
    };

    private void confirmStop() {
        new Dlg(this).setTitle(R.string.sh_stop)
                .setMessage(R.string.sh_stop_q)
                .setPositiveButton(R.string.sh_stop, (d, w) -> finish())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
