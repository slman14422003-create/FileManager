package com.fileman.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google Drive manager: sign-in, storage overview, photos, videos, files, backups and trash. It is its own
 * screen (not exported, hidden from screenshots and recents) and talks to Google only through DriveApi / DriveHttp.
 */
public class DriveActivity extends BaseActivity {
    private static final int REQ_UPLOAD = 7302, REQ_FOLDER = 7303;
    private static final String C_PHOTOS = "photos", C_VIDEOS = "videos", C_FILES = "files",
            C_BACKUPS = "backups", C_TRASH = "trash";

    private interface Job {
        void run() throws Exception;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final List<DriveApi.DFile> items = new ArrayList<>();
    private final ArrayDeque<String[]> stack = new ArrayDeque<>();   // folder id + name, the last one is open
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private ScrollView dash;
    private LinearLayout dashContent;
    private ListView list;
    private View loading;
    private TextView empty;
    private TextView status;
    private TextView title;
    private ImageButton trashBtn;
    private LinearLayout bar;
    private ItemAdapter adapter;

    private DriveApi.About about;
    private String cat;            // null on the dashboard
    private String nextToken;
    private boolean paging;
    private boolean busy;
    private int gen;
    private int lastPct = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // hidden from screenshots and the recent-apps preview: the screen shows private files
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drive);
        title = findViewById(R.id.title);
        title.setText(R.string.drive_title);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.drive_sub_in);
        ImageView hi = findViewById(R.id.headerIcon);
        hi.setImageResource(R.drawable.ic_drive);
        Ui.outlineIcon(this, hi, 10);
        hi.setVisibility(View.VISIBLE);
        dash = findViewById(R.id.dash);
        dashContent = findViewById(R.id.dashContent);
        list = findViewById(R.id.list);
        loading = findViewById(R.id.loading);
        empty = findViewById(R.id.empty);
        status = findViewById(R.id.status);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            if (cat == null) showDash();
            else loadPage(true);
        });
        trashBtn = findViewById(R.id.btnA1);
        trashBtn.setImageResource(R.drawable.ic_delete);
        trashBtn.setContentDescription(getString(R.string.drive_empty_trash));
        trashBtn.setVisibility(View.GONE);
        trashBtn.setOnClickListener(v -> confirmEmptyTrash());
        status.setOnClickListener(v -> {
            if (busy) confirmCancel();
        });

        adapter = new ItemAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onItem(items.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (cat != null) sheet(items.get(pos));
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int state) {
            }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
                if (nextToken != null && !paging && total > 0 && first + visible >= total - 12) loadPage(false);
            }
        });

        bar = ViewerBar.attach(this, (FrameLayout) findViewById(R.id.body), false,
                new int[]{R.drawable.ic_add, R.drawable.ic_folder},
                new int[]{R.string.drive_upload, R.string.drive_new_folder},
                new View.OnClickListener[]{v -> pickUpload(), v -> newFolderDialog()});
        bar.setVisibility(View.GONE);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (busy) {
                    confirmCancel();
                } else if (cat != null && stack.size() > 1) {
                    stack.pop();
                    loadPage(true);
                    refreshHeader();
                } else if (cat != null) {
                    showDash();
                } else {
                    finish();
                }
            }
        });
        showDash();
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void toast(int res, Object... a) {
        Toast.makeText(this, getString(res, a), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ dashboard

    private void showDash() {
        cat = null;
        stack.clear();
        items.clear();
        adapter.notifyDataSetChanged();
        list.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        dash.setVisibility(View.VISIBLE);
        bar.setVisibility(View.GONE);
        trashBtn.setVisibility(View.GONE);
        title.setText(R.string.drive_title);
        dashContent.removeAllViews();
        if (!DriveStore.signedIn(this)) {
            renderSignedOut();
        } else {
            renderSignedIn();
            loadAbout();
        }
    }

    private TextView text(CharSequence s, float sp, boolean bold, int colorRes) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Ui.color(this, colorRes));
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 18);
        c.setPadding(p, p, p, p);
        int side = Math.max(Ui.dp(this, 10), Layout.gutter(this) - Ui.dp(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(side, Ui.dp(this, 6), side, Ui.dp(this, 8));
        c.setLayoutParams(lp);
        return c;
    }

    private void renderSignedOut() {
        LinearLayout c = card();
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_drive);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        c.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView t = text(getString(R.string.drive_intro_title), 21, true, R.color.text_primary);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        c.addView(t);
        c.addView(text(getString(R.string.drive_intro_body), 14.5f, false, R.color.text_secondary));
        int[] bullets = {R.string.drive_priv_1, R.string.drive_priv_2, R.string.drive_priv_3, R.string.drive_priv_4};
        for (int b : bullets) {
            TextView x = text("\u2713  " + getString(b), 13.5f, false, R.color.text_secondary);
            x.setPadding(0, Ui.dp(this, 8), 0, 0);
            c.addView(x);
        }
        Button in = Ui.button(this, R.string.drive_signin, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 18);
        in.setOnClickListener(v -> AuthGate.require(this, this::startSignIn));
        c.addView(in, lp);
        Button copy = Ui.button(this, R.string.drive_bridge_copy, false);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.dp(this, 10);
        copy.setOnClickListener(v -> copyScript());
        c.addView(copy, lp2);
        Button setup = Ui.button(this, R.string.drive_bridge_setup, false);
        setup.setOnClickListener(v -> AuthGate.require(this, this::bridgeDialog));
        c.addView(setup, lp2);
        dashContent.addView(c);
    }

    private void startSignIn() {
        if (!DriveStore.bridgeSet(this)) {
            bridgeDialog();
            return;
        }
        DriveAuth.signIn(this, new DriveAuth.Done() {
            @Override
            public void ok() {
                ui.post(() -> {
                    if (alive()) showDash();
                });
            }

            @Override
            public void fail() {
                ui.post(() -> {
                    if (alive()) toast(R.string.drive_signin_failed);
                });
            }
        });
    }

    private void bridgeDialog() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.box(this);
        sv.addView(box);
        TextView steps = text(getString(R.string.drive_bridge_steps), 13.5f, false, R.color.text_secondary);
        steps.setPadding(0, 0, 0, Ui.dp(this, 14));
        box.addView(steps);
        final EditText url = Ui.edit(this, getString(R.string.drive_bridge_url_hint), DriveStore.bridgeUrl(this));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText key = Ui.edit(this, getString(R.string.drive_bridge_key_hint), DriveStore.bridgeSecret(this));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(url);
        box.addView(key);
        final AlertDialog d = new Dlg(this).setTitle(R.string.drive_bridge_title).setView(sv)
                .setPositiveButton(R.string.drive_bridge_save, null)
                .setNegativeButton(R.string.cancel, null).create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            String k = key.getText().toString().trim();
            if (!DriveHttp.bridgeUrl(u)) {
                toast(R.string.drive_bridge_bad_url);
                return;
            }
            if (k.length() < 12) {
                toast(R.string.drive_bridge_bad_key);
                return;
            }
            if (!DriveStore.setBridge(this, u, k)) {
                toast(R.string.drive_signin_failed);
                return;
            }
            d.dismiss();
            startSignIn();
        });
    }

    private void copyScript() {
        try (InputStream in = getResources().openRawResource(R.raw.drive_bridge)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("", bo.toString("UTF-8")));
            toast(R.string.copied);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UPLOAD && resultCode == RESULT_OK && data != null) {
            ArrayList<String> paths = new ArrayList<>();
            ArrayList<String> many = data.getStringArrayListExtra("picked_all");
            if (many != null) paths.addAll(many);
            String one = data.getStringExtra("picked");
            if (one != null) paths.add(one);
            upload(paths);
        } else if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null) {
            String dir = data.getStringExtra("picked_dir");
            if (dir != null && Safe.mayExpose(this, new File(dir))) {
                List<String> f = DriveStore.folders(this);
                if (!f.contains(dir)) {
                    f.add(dir);
                    DriveStore.setFolders(this, f);
                }
                backupMenu();
            }
        }
    }

    private void loadAbout() {
        io.execute(() -> {
            try {
                final DriveApi.About a = DriveApi.about(this);
                DriveStore.setAccount(this, a.name, a.email);
                DriveStore.setQuota(this, a.usage, a.limit);
                ui.post(() -> {
                    if (!alive() || cat != null) return;
                    about = a;
                    dashContent.removeAllViews();
                    renderSignedIn();
                });
            } catch (Exception e) {
                ui.post(() -> handleError(e));
            }
        });
    }

    private void renderSignedIn() {
        // account
        LinearLayout acc = card();
        String name = about != null ? about.name : DriveStore.name(this);
        String mail = about != null ? about.email : DriveStore.email(this);
        acc.addView(text(name.isEmpty() ? getString(R.string.drive_account) : name, 17, true, R.color.text_primary));
        if (!mail.isEmpty()) acc.addView(text(mail, 13.5f, false, R.color.text_secondary));
        dashContent.addView(acc);

        // storage
        LinearLayout st = card();
        long used = about != null ? about.usage : DriveStore.used(this);
        long limit = about != null ? about.limit : DriveStore.limit(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        UsageView ring = new UsageView(this);
        double pct = limit > 0 && used >= 0 ? used * 100.0 / limit : 0;
        ring.setUsage(UsageView.RING, pct, Ui.color(this, pct > 90 ? R.color.bad : R.color.accent), 0x33FFFFFF);
        ring.setCenterIcon(R.drawable.ic_drive, Ui.color(this, R.color.accent_text));
        row.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(Ui.dp(this, 16), 0, 0, 0);
        tx.addView(text(getString(R.string.drive_storage), 13, false, R.color.text_secondary));
        String line = used < 0 ? "\u2026" : limit > 0
                ? getString(R.string.drive_storage_of, Fmt.size(used), Fmt.size(limit))
                : getString(R.string.drive_storage_unlimited, Fmt.size(used));
        tx.addView(text(line, 17, true, R.color.text_primary));
        row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        st.addView(row);
        if (about != null) {
            long other = Math.max(0, about.usage - about.inDrive - about.inTrash);
            TextView br = text(getString(R.string.drive_breakdown, Fmt.size(about.inDrive), Fmt.size(about.inTrash),
                    Fmt.size(other)), 12.5f, false, R.color.text_secondary);
            br.setPadding(0, Ui.dp(this, 12), 0, 0);
            st.addView(br);
        }
        dashContent.addView(st);

        // categories
        dashContent.addView(Ui.sectionTitle(this, getString(R.string.drive_manage)));
        List<View> tiles = new ArrayList<>();
        tiles.add(tile(R.drawable.ic_image, R.color.ok, R.string.drive_cat_photos, C_PHOTOS));
        tiles.add(tile(R.drawable.ic_video, R.color.bad, R.string.drive_cat_videos, C_VIDEOS));
        tiles.add(tile(R.drawable.ic_folder, R.color.accent_text, R.string.drive_cat_files, C_FILES));
        tiles.add(tile(R.drawable.ic_archive, R.color.warn, R.string.drive_cat_backups, C_BACKUPS));
        tiles.add(tile(R.drawable.ic_delete, R.color.text_secondary, R.string.drive_cat_trash, C_TRASH));
        final int cols = Layout.columns(this, 3, 6);
        int side = Math.max(Ui.dp(this, 10), Layout.gutter(this) - Ui.dp(this, 4));
        for (int i = 0; i < tiles.size(); i += cols) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setPadding(side, 0, side, 0);
            for (int k = 0; k < cols; k++) {
                View cell = i + k < tiles.size() ? tiles.get(i + k) : new View(this);
                r.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            dashContent.addView(r);
        }

        // actions
        LinearLayout acts = card();
        Button up = Ui.button(this, R.string.drive_upload, true);
        up.setOnClickListener(v -> pickUpload());
        acts.addView(up, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button bk = Ui.button(this, R.string.drive_backup, false);
        bk.setOnClickListener(v -> backupMenu());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        acts.addView(bk, lp);
        Button out = Ui.button(this, R.string.drive_signout, false);
        out.setOnClickListener(v -> confirmSignOut());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.dp(this, 10);
        acts.addView(out, lp2);
        dashContent.addView(acts);
    }

    private View tile(int icon, int colorRes, int titleRes, final String category) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_home_tile, dashContent, false);
        ImageView iv = v.findViewById(R.id.icon);
        iv.setImageResource(icon);
        v.findViewById(R.id.box).setBackground(Ui.iconTile(this, getResources().getDimensionPixelSize(R.dimen.tile_box)));
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, colorRes)));
        ((TextView) v.findViewById(R.id.title)).setText(titleRes);
        v.findViewById(R.id.sub).setVisibility(View.GONE);
        v.setContentDescription(getString(titleRes));
        Ui.press(this, v);
        v.setOnClickListener(x -> openCat(category));
        return v;
    }

    private void confirmSignOut() {
        new Dlg(this).setTitle(R.string.drive_signout).setMessage(R.string.drive_signout_confirm)
                .setPositiveButton(R.string.drive_signout, (d, w) -> AuthGate.require(this, () -> io.execute(() -> {
                    DriveAuth.signOut(this);
                    ui.post(() -> {
                        if (alive()) {
                            about = null;
                            showDash();
                        }
                    });
                })))
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ lists

    private void openCat(String c) {
        cat = c;
        stack.clear();
        if (C_FILES.equals(c)) {
            stack.push(new String[]{"root", getString(R.string.drive_cat_files)});
            refreshHeader();
            loadPage(true);
        } else if (C_BACKUPS.equals(c)) {
            loading.setVisibility(View.VISIBLE);
            io.execute(() -> {
                try {
                    final String id = DriveApi.ensureFolder(this, "root", DriveBackup.ROOT_NAME);
                    ui.post(() -> {
                        if (!alive() || !C_BACKUPS.equals(cat)) return;
                        stack.clear();
                        stack.push(new String[]{id, getString(R.string.drive_cat_backups)});
                        loadPage(true);
                    });
                } catch (Exception e) {
                    ui.post(() -> handleError(e));
                }
            });
            refreshHeader();
        } else {
            refreshHeader();
            loadPage(true);
        }
    }

    private boolean folderMode() {
        return C_FILES.equals(cat) || C_BACKUPS.equals(cat);
    }

    private void refreshHeader() {
        dash.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        if (cat == null) return;
        String t;
        switch (cat) {
            case C_PHOTOS:
                t = getString(R.string.drive_cat_photos);
                break;
            case C_VIDEOS:
                t = getString(R.string.drive_cat_videos);
                break;
            case C_TRASH:
                t = getString(R.string.drive_cat_trash);
                break;
            default:
                t = stack.isEmpty() ? getString(R.string.drive_cat_files) : stack.peek()[1];
        }
        title.setText(t);
        bar.setVisibility(C_FILES.equals(cat) ? View.VISIBLE : View.GONE);
        trashBtn.setVisibility(C_TRASH.equals(cat) ? View.VISIBLE : View.GONE);
    }

    private String query() {
        switch (cat) {
            case C_PHOTOS:
                return "mimeType contains 'image/' and trashed=false";
            case C_VIDEOS:
                return "mimeType contains 'video/' and trashed=false";
            case C_TRASH:
                return "trashed=true";
            default:
                return "'" + DriveApi.esc(stack.isEmpty() ? "root" : stack.peek()[0]) + "' in parents and trashed=false";
        }
    }

    private void loadPage(boolean reset) {
        if (cat == null || (folderMode() && stack.isEmpty())) return;
        if (paging && !reset) return;
        if (reset) {
            gen++;
            items.clear();
            nextToken = null;
            adapter.notifyDataSetChanged();
            empty.setVisibility(View.GONE);
        }
        paging = true;
        loading.setVisibility(View.VISIBLE);
        final int my = gen;
        final String q = query();
        final String order = folderMode() ? "folder,name" : "modifiedTime desc";
        final String tok = reset ? null : nextToken;
        io.execute(() -> {
            try {
                final DriveApi.Page p = DriveApi.list(this, q, order, tok);
                ui.post(() -> {
                    if (!alive() || my != gen) return;
                    items.addAll(p.files);
                    nextToken = p.next;
                    paging = false;
                    loading.setVisibility(View.INVISIBLE);
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (my != gen) return;
                    paging = false;
                    loading.setVisibility(View.INVISIBLE);
                    handleError(e);
                });
            }
        });
    }

    private void onItem(DriveApi.DFile f) {
        if (f.isFolder() && folderMode() && !f.trashed) {
            stack.push(new String[]{f.id, f.name});
            refreshHeader();
            loadPage(true);
        } else {
            sheet(f);
        }
    }

    private final class ItemAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView iv;
            TextView name;
            TextView sub;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                iv = (ImageView) row.getChildAt(0);
                LinearLayout tx = (LinearLayout) row.getChildAt(1);
                name = (TextView) tx.getChildAt(0);
                sub = (TextView) tx.getChildAt(1);
            } else {
                row = new LinearLayout(DriveActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(DriveActivity.this, 16), Ui.dp(DriveActivity.this, 8), Ui.dp(DriveActivity.this, 16), Ui.dp(DriveActivity.this, 8));
                row.setBackgroundResource(R.drawable.bg_tab_btn);
                iv = new ImageView(DriveActivity.this);
                row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(DriveActivity.this, 48), Ui.dp(DriveActivity.this, 48)));
                LinearLayout tx = new LinearLayout(DriveActivity.this);
                tx.setOrientation(LinearLayout.VERTICAL);
                tx.setPadding(Ui.dp(DriveActivity.this, 14), 0, 0, 0);
                name = text("", 16, false, R.color.text_primary);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                sub = text("", 12.5f, false, R.color.text_secondary);
                sub.setSingleLine(true);
                tx.addView(name);
                tx.addView(sub);
                row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            DriveApi.DFile f = items.get(pos);
            name.setText(f.name);
            sub.setText(f.isFolder() ? Fmt.date(f.modified) : Fmt.size(f.size) + "  \u00B7  " + Fmt.date(f.modified));
            bindIcon(iv, f);
            return row;
        }
    }

    private void bindIcon(final ImageView iv, DriveApi.DFile f) {
        final String url = f.thumb;
        Bitmap cached = url == null ? null : thumbs.get(url);
        iv.setTag(url);
        if (cached != null) {
            showThumb(iv, cached);
            return;
        }
        int type = f.isFolder() ? Cats.T_DIR : f.isGoogleDoc() ? Cats.T_DOC : Cats.typeOfExt(Cats.extOf(f.name));
        if (f.mime.startsWith("image/")) type = Cats.T_IMG;
        else if (f.mime.startsWith("video/")) type = Cats.T_VID;
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setClipToOutline(false);
        iv.setImageResource(Cats.iconFor(type));
        Ui.outlineIcon(this, iv, 12);
        if (url == null) return;
        thumbIo.execute(() -> {
            try {
                byte[] b = DriveHttp.getThumb(url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 192 && o.outHeight / (s * 2) >= 192) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                final Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                if (bmp == null) return;
                thumbs.put(url, bmp);
                ui.post(() -> {
                    if (url.equals(iv.getTag())) showThumb(iv, bmp);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void showThumb(ImageView iv, Bitmap b) {
        iv.setImageTintList(null);
        iv.setPadding(0, 0, 0, 0);
        iv.setBackgroundResource(R.drawable.bg_tile_round);
        iv.setClipToOutline(true);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
    }

    // ------------------------------------------------------------------ actions on one item

    private void sheet(final DriveApi.DFile f) {
        final List<Integer> acts = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Integer> icons = new ArrayList<>();
        if (C_TRASH.equals(cat)) {
            acts.add(1);
            labels.add(getString(R.string.drive_restore));
            icons.add(R.drawable.ic_refresh);
            acts.add(2);
            labels.add(getString(R.string.drive_delete_forever));
            icons.add(R.drawable.ic_delete);
        } else {
            if (!f.isFolder()) {
                acts.add(0);
                labels.add(getString(R.string.drive_download));
                icons.add(R.drawable.ic_download);
            }
            acts.add(3);
            labels.add(getString(R.string.drive_rename));
            icons.add(R.drawable.ic_file_text);
            acts.add(4);
            labels.add(getString(R.string.drive_trash));
            icons.add(R.drawable.ic_delete);
        }
        acts.add(5);
        labels.add(getString(R.string.drive_details));
        icons.add(R.drawable.ic_info);
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        new Dlg(this).setTitle(f.name).setItems(labels.toArray(new String[0]), ic, (d, which) -> {
            switch (acts.get(which)) {
                case 0:
                    download(f);
                    break;
                case 1:
                    run(() -> DriveApi.setTrashed(this, f.id, false), R.string.drive_restored);
                    break;
                case 2:
                    new Dlg(this).setTitle(R.string.drive_delete_forever)
                            .setMessage(getString(R.string.drive_delete_forever_msg, f.name))
                            .setPositiveButton(R.string.drive_delete_forever, (d2, w2) -> AuthGate.require(this,
                                    () -> run(() -> DriveApi.deleteForever(this, f.id), R.string.drive_deleted)))
                            .setNegativeButton(R.string.cancel, null).show();
                    break;
                case 3:
                    renameDialog(f);
                    break;
                case 4:
                    run(() -> DriveApi.setTrashed(this, f.id, true), R.string.drive_trashed);
                    break;
                default:
                    new Dlg(this).setTitle(f.name).setMessage(getString(R.string.drive_details_msg,
                            f.isFolder() ? getString(R.string.drive_cat_files) : f.mime, Fmt.size(f.size), Fmt.date(f.modified)))
                            .setPositiveButton(android.R.string.ok, null).show();
            }
        }).show();
    }

    private void run(final Job job, final int doneRes) {
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                job.run();
                ui.post(() -> {
                    if (!alive()) return;
                    toast(doneRes);
                    loadPage(true);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    loading.setVisibility(View.INVISIBLE);
                    handleError(e);
                });
            }
        });
    }

    private void renameDialog(final DriveApi.DFile f) {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.drive_name_hint), f.name);
        box.addView(name);
        new Dlg(this).setTitle(R.string.drive_rename).setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty() || n.equals(f.name)) return;
                    run(() -> DriveApi.rename(this, f.id, n), R.string.drive_renamed);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void newFolderDialog() {
        if (!C_FILES.equals(cat) || stack.isEmpty()) return;
        final String parent = stack.peek()[0];
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.drive_name_hint), "");
        box.addView(name);
        new Dlg(this).setTitle(R.string.drive_new_folder).setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    run(() -> DriveApi.createFolder(this, parent, n), R.string.drive_folder_created);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void confirmEmptyTrash() {
        new Dlg(this).setTitle(R.string.drive_empty_trash).setMessage(R.string.drive_empty_trash_msg)
                .setPositiveButton(R.string.drive_empty_trash, (d, w) -> AuthGate.require(this,
                        () -> run(() -> DriveApi.emptyTrash(this), R.string.drive_deleted)))
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ long running work (download, upload, backup)

    private void begin(String text) {
        busy = true;
        cancel.set(false);
        lastPct = -1;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void end() {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setVisibility(View.GONE);
        loading.setVisibility(View.INVISIBLE);
    }

    private void postStatus(final String text, long done, long total) {
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        if (pct == lastPct) return;
        lastPct = pct;
        ui.post(() -> {
            if (busy && alive()) status.setText(text);
        });
    }

    private void confirmCancel() {
        new Dlg(this).setTitle(R.string.drive_cancel_ask)
                .setPositiveButton(R.string.drive_cancel_yes, (d, w) -> cancel.set(true))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private static String safeName(String n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length() && b.length() < 120; i++) {
            char c = n.charAt(i);
            b.append(c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' ? '_' : c);
        }
        String s = b.toString().trim();
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "file" : s;
    }

    private void download(final DriveApi.DFile f) {
        if (busy) return;
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileManager Drive");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            toast(R.string.drive_err_generic, 0);
            return;
        }
        String nm = safeName(f.name);
        if (f.isGoogleDoc() && !nm.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) nm += ".pdf";
        final File out = ExternalDocs.uniqueIn(dir, nm, false);
        begin(getString(R.string.drive_downloading, f.name, 0));
        io.execute(() -> {
            try {
                DriveApi.download(this, f, out, (d, t) -> postStatus(
                        getString(R.string.drive_downloading, f.name, t > 0 ? (int) (d * 100 / t) : 0), d, t), cancel);
                ui.post(() -> {
                    end();
                    if (alive()) toast(R.string.drive_done_download, out.getAbsolutePath());
                });
            } catch (Exception e) {
                out.delete();
                ui.post(() -> {
                    end();
                    handleError(e);
                });
            }
        });
    }

    private void pickUpload() {
        if (busy) return;
        startActivityForResult(new Intent(this, FileManagerActivity.class).putExtra("pick", true).putExtra("pick_multi", true), REQ_UPLOAD);
    }

    private void upload(final List<String> paths) {
        if (busy || paths.isEmpty()) return;
        final String parent = C_FILES.equals(cat) && !stack.isEmpty() ? stack.peek()[0] : "root";
        begin(getString(R.string.drive_uploading, "", 0, paths.size()));
        io.execute(() -> {
            int ok = 0;
            Exception last = null;
            for (int i = 0; i < paths.size(); i++) {
                if (cancel.get()) break;
                final File f = new File(paths.get(i));
                if (!f.isFile() || !Safe.mayExpose(this, f)) continue;
                final int no = i + 1;
                try {
                    DriveApi.upload(this, f, parent, null, (d, t) -> postStatus(
                            getString(R.string.drive_uploading, f.getName(), t > 0 ? (int) (d * 100 / t) : 0, paths.size()) , d, t), cancel);
                    ok++;
                } catch (Exception e) {
                    last = e;
                    if (e instanceof DriveApi.DriveError && ((DriveApi.DriveError) e).code == 403) break;
                }
            }
            final int done = ok;
            final Exception err = last;
            ui.post(() -> {
                end();
                if (!alive()) return;
                if (done > 0) {
                    toast(R.string.drive_done_upload, done);
                    if (C_FILES.equals(cat)) loadPage(true);
                } else if (err != null) {
                    handleError(err);
                }
            });
        });
    }

    // ------------------------------------------------------------------ backup

    private void backupMenu() {
        if (busy) return;
        final List<String> folders = DriveStore.folders(this);
        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.drive_backup_now, folders.size()));
        labels.add(getString(R.string.drive_backup_add));
        for (String f : folders) labels.add(getString(R.string.drive_backup_remove, new File(f).getName()));
        new Dlg(this).setTitle(R.string.drive_backup_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        backupNow(folders);
                    } else if (which == 1) {
                        startActivityForResult(new Intent(this, FileManagerActivity.class).putExtra("pick_dir", true), REQ_FOLDER);
                    } else {
                        folders.remove(which - 2);
                        DriveStore.setFolders(this, folders);
                        backupMenu();
                    }
                }).show();
    }

    private void backupNow(final List<String> folders) {
        if (folders.isEmpty()) {
            toast(R.string.drive_backup_none);
            return;
        }
        toast(R.string.drive_backup_info);
        begin(getString(R.string.drive_uploading, "", 0, 0));
        io.execute(() -> {
            try {
                final DriveBackup.Result r = DriveBackup.run(this, folders, (name, no, count, done, total) -> postStatus(
                        getString(R.string.drive_uploading, name, total > 0 ? (int) (done * 100 / total) : 0, count), done, total), cancel);
                ui.post(() -> {
                    end();
                    if (alive()) {
                        Dlg.result(this, r.failed == 0, getString(R.string.drive_backup_title),
                                getString(R.string.drive_backup_result, r.uploaded, r.skipped, r.failed));
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    end();
                    handleError(e);
                });
            }
        });
    }

    // ------------------------------------------------------------------ errors

    private void handleError(Exception e) {
        if (!alive()) return;
        loading.setVisibility(View.INVISIBLE);
        if (e instanceof DriveApi.DriveError) {
            int code = ((DriveApi.DriveError) e).code;
            if (code == 401 || code == 403) toast(R.string.drive_err_auth);
            else toast(R.string.drive_err_generic, code);
        } else if (e instanceof IllegalStateException && "consent".equals(e.getMessage())) {
            toast(R.string.drive_err_auth);
        } else if (e.getMessage() != null && e.getMessage().contains("cancelled")) {
            toast(R.string.drive_cancelled);
        } else {
            toast(R.string.drive_err_net);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel.set(true);
        io.shutdownNow();
        thumbIo.shutdownNow();
    }
}
