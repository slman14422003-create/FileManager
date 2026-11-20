package com.fileman.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Pick exactly which photos, videos, songs, documents or archives to share: a thumbnail grid (or a list for
 * non-visual types) with animated selection, select-all and a floating bar that slides up with the count and size.
 */
public class SharePickActivity extends BaseActivity {
    static final String EXTRA_CAT = "cat";
    static final String EXTRA_KEY = "key";
    static final String EXTRA_LABEL = "label";
    private static final int WALK_LIMIT = 150000;

    private static final class Item {
        final File f;
        final String path;
        final long size;
        final long mod;

        Item(File f) {
            this.f = f;
            this.path = f.getAbsolutePath();
            this.size = f.length();
            this.mod = f.lastModified();
        }
    }

    private static final class Holder {
        FrameLayout box;
        ImageView thumb;
        View dim;
        ImageView play;
        FrameLayout fill;
        TextView name, sub;
        String path;
    }

    private static final class Cell extends FrameLayout {
        Cell(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    private final ExecutorService io = Bg.single("fm-bg");
    private final ThreadPoolExecutor thumbIo = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);   // newest request first: what is on screen after a fast scroll loads first
                }
            });
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final Set<String> sel = new LinkedHashSet<>();
    private final Map<String, Item> byPath = new HashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            Math.max(8 * 1024 * 1024, (int) (Runtime.getRuntime().maxMemory() / 10))) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private String cat, key, label;
    private boolean grid, video;
    private long selSize;
    private volatile boolean destroyed;
    private volatile int found;

    private TextView titleView, subtitleView, countView, selectAll, barText, addBtn, emptyView;
    private View spinner, bar;
    private GridView gv;
    private BaseAdapter adapter;
    private boolean barShown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cat = getIntent().getStringExtra(EXTRA_CAT);
        key = getIntent().getStringExtra(EXTRA_KEY);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (cat == null) cat = Cats.IMG;
        if (key == null) key = "";
        if (label == null) label = "";
        video = Cats.VID.equals(cat);
        grid = Cats.IMG.equals(cat) || video;
        setContentView(build());
        useGroupedBackground();
        titleView.setText(label);
        subtitleView.setText(R.string.sx3_scanning_short);
        startScan();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        io.shutdownNow();
        thumbIo.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onPostCreate(Bundle b) {
        super.onPostCreate(b);
        View ai = findViewById(R.id.btnAi);
        if (ai != null) ai.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg_group));

        View header = getLayoutInflater().inflate(R.layout.view_header, root, false);
        titleView = header.findViewById(R.id.title);
        subtitleView = header.findViewById(R.id.subtitle);
        header.findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        View refresh = header.findViewById(R.id.btnRefresh);
        if (refresh != null) refresh.setVisibility(View.GONE);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPaddingRelative(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        countView = new TextView(this);
        countView.setTextSize(14);
        countView.setTextColor(Ui.color(this, R.color.text_secondary));
        countView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        countView.setSingleLine(true);
        tools.addView(countView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectAll = pill(getString(R.string.sp_select_all), false);
        selectAll.setOnClickListener(v -> toggleAll());
        tools.addView(selectAll);
        root.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(this);
        gv = new GridView(this);
        int cols = 1;
        if (grid) {
            float wdp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
            cols = Math.max(3, (int) (wdp / 120f));
        }
        gv.setNumColumns(cols);
        gv.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        int gap = grid ? Ui.dp(this, 3) : 0;
        gv.setHorizontalSpacing(gap);
        gv.setVerticalSpacing(gap);
        int side = grid ? Ui.dp(this, 10) : Ui.dp(this, 16);
        gv.setPadding(side, 0, side, Ui.dp(this, 110));
        gv.setClipToPadding(false);
        gv.setVerticalScrollBarEnabled(false);
        gv.setSelector(new ColorDrawable(0));
        gv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new PickAdapter();
        gv.setAdapter(adapter);
        gv.setOnItemClickListener((p, v, pos, id) -> toggle(pos, v));
        body.addView(gv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setText(R.string.sp_empty);
        emptyView.setTextSize(15);
        emptyView.setTextColor(Ui.color(this, R.color.text_secondary));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        body.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        spinner = new SpinnerView(this);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30), Gravity.CENTER);
        body.addView(spinner, sp);

        bar = buildBar();
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16));
        bar.setVisibility(View.INVISIBLE);
        body.addView(bar, bp);

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        updateBar(false);
        return root;
    }

    private TextView pill(String text, boolean solid) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setTextColor(Ui.color(this, solid ? R.color.on_accent : R.color.accent_text));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.color(this, solid ? R.color.accent : R.color.accent_soft));
        g.setCornerRadius(Ui.dp(this, 100));
        t.setBackground(g);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        Ui.press(this, t);
        return t;
    }

    private View buildBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPaddingRelative(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Blur.surface(this, Ui.color(this, R.color.surface), 0xF2));
        bg.setCornerRadius(Ui.dp(this, 28));
        b.setBackground(bg);
        b.setElevation(Ui.dpf(this, 10));
        barText = new TextView(this);
        barText.setTextSize(14);
        barText.setTypeface(Typeface.DEFAULT_BOLD);
        barText.setTextColor(Ui.color(this, R.color.text_primary));
        barText.setSingleLine(true);
        barText.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        b.addView(barText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addBtn = pill("", true);
        addBtn.setTextSize(15);
        addBtn.setPadding(Ui.dp(this, 22), Ui.dp(this, 11), Ui.dp(this, 22), Ui.dp(this, 11));
        addBtn.setOnClickListener(v -> finishPick());
        b.addView(addBtn);
        return b;
    }

    // ------------------------------------------------------------------ scan

    private void startScan() {
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (destroyed || spinner.getVisibility() != View.VISIBLE) return;
                subtitleView.setText(getString(R.string.sp_found, found));
                ui.postDelayed(this, 250);
            }
        };
        ui.postDelayed(tick, 250);
        io.execute(() -> {
            List<Item> out = new ArrayList<>();
            walk(Environment.getExternalStorageDirectory(), 0, out);
            Collections.sort(out, (a, b2) -> Long.compare(b2.mod, a.mod));
            ui.post(() -> {
                if (destroyed) return;
                items.addAll(out);
                for (Item it : out) byPath.put(it.path, it);
                spinner.setVisibility(View.GONE);
                subtitleView.setText(getString(R.string.sp_found, items.size()));
                emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
                updateBar(false);
            });
        });
    }

    private void walk(File dir, int depth, List<Item> out) {
        if (destroyed || depth > 30 || out.size() >= WALK_LIMIT) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (destroyed || out.size() >= WALK_LIMIT) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (Cats.isLink(f)) continue;
                if (depth == 0 && n.equals("Android")) continue;
                walk(f, depth + 1, out);
            } else if (f.isFile() && f.canRead() && Cats.matches(cat, Cats.extOf(n))) {
                out.add(new Item(f));
                found = out.size();
            }
        }
    }

    // ------------------------------------------------------------------ selection

    private void toggle(int pos, View v) {
        if (pos < 0 || pos >= items.size()) return;
        Item it = items.get(pos);
        boolean on;
        if (sel.remove(it.path)) {
            on = false;
            selSize -= it.size;
        } else {
            on = true;
            sel.add(it.path);
            selSize += it.size;
        }
        Fx.light(v);
        Object h = v == null ? null : v.getTag();
        if (h instanceof Holder) paint((Holder) h, on, true);
        updateBar(true);
    }

    private void toggleAll() {
        if (items.isEmpty()) return;
        boolean all = sel.size() == items.size();
        sel.clear();
        selSize = 0;
        if (!all) {
            for (Item it : items) {
                sel.add(it.path);
                selSize += it.size;
            }
        }
        Fx.done(selectAll);
        adapter.notifyDataSetChanged();
        updateBar(true);
    }

    private void paint(Holder h, boolean on, boolean animate) {
        boolean anim = animate && Fx.motion(this);
        if (h.box != null) {
            float s = on ? 0.86f : 1f;
            h.box.animate().cancel();
            if (anim) h.box.animate().scaleX(s).scaleY(s).setDuration(200)
                    .setInterpolator(new OvershootInterpolator(on ? 2.2f : 0f)).start();
            else {
                h.box.setScaleX(s);
                h.box.setScaleY(s);
            }
        }
        if (h.dim != null) {
            h.dim.animate().cancel();
            if (anim) h.dim.animate().alpha(on ? 1f : 0f).setDuration(160).start();
            else h.dim.setAlpha(on ? 1f : 0f);
        }
        h.fill.animate().cancel();
        if (anim) {
            if (on) {
                h.fill.setAlpha(0f);
                h.fill.setScaleX(0.3f);
                h.fill.setScaleY(0.3f);
                h.fill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                        .setInterpolator(new OvershootInterpolator(3f)).start();
            } else {
                h.fill.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(150).start();
            }
        } else {
            h.fill.setAlpha(on ? 1f : 0f);
            h.fill.setScaleX(on ? 1f : 0.3f);
            h.fill.setScaleY(on ? 1f : 0.3f);
        }
    }

    private void updateBar(boolean animate) {
        int n = sel.size();
        countView.setText(n == 0 ? getString(R.string.sp_hint) : getString(R.string.sp_selected, n, Fmt.size(selSize)));
        selectAll.setText(n > 0 && n == items.size() ? R.string.sp_clear : R.string.sp_select_all);
        barText.setText(getString(R.string.sp_selected, n, Fmt.size(selSize)));
        addBtn.setText(getString(R.string.sp_add, n));
        boolean show = n > 0;
        if (show == barShown) return;
        barShown = show;
        bar.animate().cancel();
        final float off = Ui.dp(this, 120);
        if (!animate || !Fx.motion(this)) {
            bar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            bar.setTranslationY(show ? 0f : off);
            return;
        }
        if (show) {
            bar.setVisibility(View.VISIBLE);
            bar.setTranslationY(off);
            bar.animate().translationY(0f).setDuration(280).setInterpolator(new OvershootInterpolator(1.1f)).start();
        } else {
            bar.animate().translationY(off).setDuration(200).setInterpolator(Motion.EASE)
                    .withEndAction(() -> {
                        if (!barShown) bar.setVisibility(View.INVISIBLE);
                    }).start();
        }
    }

    private void finishPick() {
        if (sel.isEmpty()) return;
        ArrayList<String> out = new ArrayList<>();
        for (Item it : items) if (sel.contains(it.path)) out.add(it.path);
        setResult(RESULT_OK, new Intent().putStringArrayListExtra("picked_all", out)
                .putExtra(EXTRA_KEY, key).putExtra(EXTRA_LABEL, label));
        finish();
    }

    // ------------------------------------------------------------------ adapter

    private final class PickAdapter extends BaseAdapter {
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
        public View getView(int i, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                v = grid ? newCell() : newRow();
                h = (Holder) v.getTag();
            } else {
                h = (Holder) v.getTag();
            }
            Item it = items.get(i);
            h.path = it.path;
            if (grid) {
                bindThumb(h, it);
            } else {
                h.name.setText(it.f.getName());
                h.sub.setText(Fmt.size(it.size) + "  ·  " + Fmt.date(it.mod));
            }
            paint(h, sel.contains(it.path), false);
            return v;
        }
    }

    private GradientDrawable oval(int color, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    private FrameLayout badge(Holder h, int sizeDp) {
        FrameLayout badge = new FrameLayout(this);
        View ring = new View(this);
        ring.setBackground(oval(0x44000000, Ui.dp(this, 2), 0xFFFFFFFF));
        badge.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout fill = new FrameLayout(this);
        fill.setBackground(oval(Ui.color(this, R.color.accent), Ui.dp(this, 2), 0xFFFFFFFF));
        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
        int p = Ui.dp(this, 4);
        check.setPadding(p, p, p, p);
        fill.addView(check, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        badge.addView(fill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        h.fill = fill;
        return badge;
    }

    private View newCell() {
        final Holder h = new Holder();
        Cell cell = new Cell(this);
        h.box = new FrameLayout(this);
        h.box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(SharePickActivity.this, 10));
            }
        });
        h.box.setClipToOutline(true);
        h.thumb = new ImageView(this);
        h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.box.addView(h.thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        h.dim = new View(this);
        h.dim.setBackgroundColor((Ui.color(this, R.color.accent) & 0x00FFFFFF) | 0x55000000);
        h.dim.setAlpha(0f);
        h.box.addView(h.dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (video) {
            h.play = new ImageView(this);
            h.play.setImageResource(R.drawable.ic_play_fill);
            h.play.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20),
                    Gravity.BOTTOM | Gravity.START);
            pp.setMargins(Ui.dp(this, 6), 0, 0, Ui.dp(this, 6));
            h.box.addView(h.play, pp);
        }
        cell.addView(h.box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.TOP | Gravity.END);
        bp.setMargins(0, Ui.dp(this, 7), Ui.dp(this, 7), 0);
        cell.addView(badge(h, 24), bp);
        cell.setTag(h);
        return cell;
    }

    private View newRow() {
        final Holder h = new Holder();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(this, R.color.surface));
        bg.setCornerRadius(Ui.dp(this, 16));
        r.setBackground(bg);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconFor());
        Ui.iconChip(this, ic, Ui.color(this, R.color.accent));
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        h.name = new TextView(this);
        h.name.setTextSize(15);
        h.name.setSingleLine(true);
        h.name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        h.name.setTextColor(Ui.color(this, R.color.text_primary));
        h.name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        h.sub = new TextView(this);
        h.sub.setTextSize(12);
        h.sub.setSingleLine(true);
        h.sub.setTextColor(Ui.color(this, R.color.text_secondary));
        h.sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        col.addView(h.name);
        col.addView(h.sub);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMarginStart(Ui.dp(this, 12));
        cp.setMarginEnd(Ui.dp(this, 10));
        r.addView(col, cp);
        r.addView(badge(h, 26), new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        r.setTag(h);
        Ui.press(this, r);
        AbsGridPad.apply(r, Ui.dp(this, 4));
        return r;
    }

    /** Gives list rows a little space between cards (GridView has no divider for a single column). */
    private static final class AbsGridPad {
        static void apply(View v, int gap) {
            v.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop() + gap / 2, v.getPaddingRight(), v.getPaddingBottom() + gap / 2);
        }
    }

    private int iconFor() {
        if (Cats.AUD.equals(cat)) return R.drawable.ic_music;
        if (Cats.ARC.equals(cat)) return R.drawable.ic_archive;
        return R.drawable.ic_file_text;
    }

    // ------------------------------------------------------------------ thumbnails

    private void bindThumb(final Holder h, Item it) {
        final String p = it.path;
        h.thumb.animate().cancel();
        h.thumb.setAlpha(1f);
        h.thumb.setTag(p);
        Bitmap b = cache.get(p);
        if (b != null) {
            h.thumb.setImageBitmap(b);
            return;
        }
        h.thumb.setImageDrawable(new ColorDrawable(Ui.color(this, R.color.field)));
        final File f = it.f;
        thumbIo.execute(() -> {
            if (destroyed || !p.equals(h.thumb.getTag())) return;
            final Bitmap bm = video ? videoFrame(f) : decode(f);
            if (bm == null) return;
            cache.put(p, bm);
            ui.post(() -> {
                if (destroyed || !p.equals(h.thumb.getTag())) return;
                h.thumb.setImageBitmap(bm);
                if (Fx.motion(this)) {
                    h.thumb.setAlpha(0f);
                    h.thumb.animate().alpha(1f).setDuration(160).start();
                }
            });
        });
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= 360 && o.outHeight / (s * 2) >= 360) s *= 2;
            o.inSampleSize = s;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return null;
            int rot = 0;
            try {
                int ori = new android.media.ExifInterface(f.getAbsolutePath())
                        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == android.media.ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Throwable ignored) {
            }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap videoFrame(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            Bitmap b = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 360) {
                float k = 360f / max;
                Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)), Math.max(1, (int) (b.getHeight() * k)), true);
                if (s != b) b.recycle();
                b = s;
            }
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
