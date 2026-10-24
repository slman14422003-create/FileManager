package com.fileman.app;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One attached SD card / USB drive: usage ring, free space, and what is in its top folder. */
public class UsbActivity extends BaseActivity {
    private static final int MAX_ITEMS = 60;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private File root;
    private FrameLayout holder;
    private BroadcastReceiver watch;
    private boolean entered;
    private int token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        root = path == null ? null : new File(path);
        if (root == null) {
            finish();
            return;
        }
        ((TextView) findViewById(R.id.title)).setText(Volumes.labelOf(this, root));
        ((TextView) findViewById(R.id.subtitle)).setText(root.getAbsolutePath());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        findViewById(R.id.btnA1).setVisibility(View.GONE);
        holder = findViewById(R.id.holder);
        watch = Volumes.watch(this, () -> ui.postDelayed(this::build, 600));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) build();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        try {
            if (watch != null) unregisterReceiver(watch);
        } catch (Exception ignored) {
        }
    }

    private void build() {
        if (isFinishing() || isDestroyed() || root == null) return;
        if (!Volumes.isMounted(this, root)) {
            Toast.makeText(this, R.string.usb_removed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final int mine = ++token;
        final Volumes.Vol vol = volOf();
        final int side = Ui.dp(this, 16);

        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 28));

        col.addView(heroCard(vol), margins(side, 8, side, 6));

        Button browse = Ui.button(this, R.string.usb_open_root, true);
        browse.setOnClickListener(v -> startActivity(new Intent(this, FileManagerActivity.class)
                .putExtra("path", root.getAbsolutePath())));
        col.addView(browse, margins(side, 8, side, 4));

        col.addView(Ui.sectionTitle(this, getString(R.string.usb_contents)));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundResource(R.drawable.bg_card);
        col.addView(list, margins(side, 0, side, 0));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.removeAllViews();
        holder.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        new Thread(() -> {
            File[] all = root.listFiles();
            final List<File> items = new ArrayList<>();
            if (all != null) {
                for (File f : all) {
                    String n = f.getName();
                    if (n.startsWith(".") || n.equals("System Volume Information") || n.startsWith("$") || n.equals("LOST.DIR")) continue;
                    items.add(f);
                }
            }
            Collections.sort(items, (x, y) -> {
                if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                return x.getName().toLowerCase(Locale.ROOT).compareTo(y.getName().toLowerCase(Locale.ROOT));
            });
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || mine != token) return;
                fill(list, items);
            });
        }).start();
    }

    private Volumes.Vol volOf() {
        for (Volumes.Vol v : Volumes.removable(this)) if (v.root.equals(root)) return v;
        Volumes.Vol v = new Volumes.Vol();
        v.root = root;
        v.label = Volumes.labelOf(this, root);
        return v;
    }

    private View heroCard(Volumes.Vol vol) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 18);
        card.setPadding(p, p, p, p);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final UsageView ring = new UsageView(this);
        top.addView(ring, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)));

        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPaddingRelative(Ui.dp(this, 16), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(vol.label);
        name.setTextSize(19);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Ui.color(this, R.color.text_primary));
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tx.addView(name);
        TextView used = new TextView(this);
        used.setTextSize(14);
        used.setTextColor(Ui.color(this, R.color.text_secondary));
        used.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tx.addView(used);
        top.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        try {
            StatFs st = new StatFs(root.getAbsolutePath());
            long total = st.getTotalBytes();
            long free = st.getAvailableBytes();
            long u = Math.max(0, total - free);
            if (total <= 0) throw new IllegalStateException();
            final double pct = u * 100.0 / total;
            final int fg = Ui.color(this, pct > 90 ? R.color.bad : R.color.accent);
            final int track = Ui.color(this, R.color.neutral_soft);
            used.setText(getString(R.string.home_used_of, Fmt.size(u), Fmt.size(total)));
            ring.setCenterIcon(R.drawable.ic_drive, Ui.color(this, R.color.accent_text));
            if (!entered) {
                entered = true;
                ring.setUsage(UsageView.RING, 0, fg, track);
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(700);
                va.setInterpolator(Motion.OUT);
                va.addUpdateListener(a -> ring.setUsage(UsageView.RING, pct * (float) a.getAnimatedValue(), fg, track));
                va.start();
            } else {
                ring.setUsage(UsageView.RING, pct, fg, track);
            }
            card.addView(Ui.bar(this, pct, pct > 90 ? R.color.bad : R.color.accent, 0, 6));
            LinearLayout badges = new LinearLayout(this);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setGravity(Gravity.CENTER_VERTICAL);
            TextView fr = new TextView(this);
            fr.setText(getString(R.string.usb_free, Fmt.size(free)));
            fr.setTextSize(14);
            fr.setTypeface(Typeface.DEFAULT_BOLD);
            fr.setTextColor(Ui.color(this, R.color.text_primary));
            badges.addView(fr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (vol.readOnly) {
                TextView ro = new TextView(this);
                ro.setText(R.string.usb_badge_ro);
                ro.setTextSize(12);
                ro.setTypeface(Typeface.DEFAULT_BOLD);
                ro.setTextColor(Ui.color(this, R.color.bad));
                badges.addView(ro);
            }
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = Ui.dp(this, 10);
            card.addView(badges, bp);
        } catch (Exception e) {
            used.setText(R.string.fm_unreadable);
        }
        return card;
    }

    private void fill(LinearLayout list, List<File> items) {
        list.removeAllViews();
        if (items.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(R.string.usb_empty);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Ui.color(this, R.color.text_secondary));
            t.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 28));
            list.addView(t);
            return;
        }
        int n = Math.min(items.size(), MAX_ITEMS);
        for (int i = 0; i < n; i++) list.addView(row(items.get(i)));
    }

    private View row(final File f) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        ImageView ic = new ImageView(this);
        ic.setImageResource(f.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file);
        ic.setColorFilter(Ui.color(this, R.color.accent));
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView n = new TextView(this);
        n.setText(f.getName());
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        n.setTextSize(15);
        n.setTextColor(Ui.color(this, R.color.text_primary));
        n.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.setMarginStart(Ui.dp(this, 14));
        np.setMarginEnd(Ui.dp(this, 10));
        r.addView(n, np);
        if (f.isFile()) {
            TextView s = new TextView(this);
            s.setText(Fmt.size(f.length()));
            s.setTextSize(12);
            s.setTextColor(Ui.color(this, R.color.text_secondary));
            r.addView(s);
        }
        Ui.press(this, r);
        r.setOnClickListener(v -> {
            if (f.isDirectory()) {
                startActivity(new Intent(this, FileManagerActivity.class).putExtra("path", f.getAbsolutePath()));
            } else {
                Opener.open(this, f);
            }
        });
        return r;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, Ui.dp(this, t), r, Ui.dp(this, b));
        return lp;
    }
}
