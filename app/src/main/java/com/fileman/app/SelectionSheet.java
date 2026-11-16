package com.fileman.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Small floating glass card shown for a selection: count and total size, a "select all" pill and five round
 * actions. It is built once, sized before it is shown and never reloads the file list, so it appears at once.
 */
final class SelectionSheet {
    interface Host {
        List<File> files();

        void onShare();

        void onCopy();

        void onMove();

        void onCompress();

        void onDelete();

        void onSelectAll();

        void onMore();
    }

    private static final int WALK_BUDGET = 60000;

    private SelectionSheet() {
    }

    static AlertDialog show(final Context c, final Host h) {
        final AlertDialog[] ref = new AlertDialog[1];
        final int[] gen = {0};
        final boolean[] open = {true};

        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPaddingRelative(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 12));

        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Blur.surface(c, Ui.color(c, R.color.surface), 0xE0));
        bg.setCornerRadius(Ui.dp(c, 26));
        panel.setBackground(bg);
        panel.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        panel.setElevation(Ui.dpf(c, 10));
        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout gripBox = new FrameLayout(c);
        View grip = new View(c);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Ui.color(c, R.color.text_hint));
        gd.setCornerRadius(Ui.dp(c, 2));
        grip.setBackground(gd);
        gripBox.addView(grip, new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 4), Gravity.CENTER));
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(c, 16));
        gbp.bottomMargin = Ui.dp(c, 6);
        panel.addView(gripBox, gbp);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        final TextView count = new TextView(c);
        count.setTextSize(15);
        count.setTypeface(Typeface.DEFAULT_BOLD);
        count.setSingleLine(true);
        count.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        count.setTextColor(Ui.color(c, R.color.text_primary));
        final TextView size = new TextView(c);
        size.setTextSize(11);
        size.setSingleLine(true);
        size.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        size.setTextColor(Ui.color(c, R.color.text_secondary));
        info.addView(count);
        info.addView(size);
        head.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView all = new TextView(c);
        all.setText(R.string.fm_select_all);
        all.setTextSize(12);
        all.setTypeface(Typeface.DEFAULT_BOLD);
        all.setSingleLine(true);
        all.setGravity(Gravity.CENTER);
        all.setTextColor(Ui.color(c, R.color.accent_text));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Ui.color(c, R.color.accent_soft));
        pill.setCornerRadius(Ui.dp(c, 100));
        all.setBackground(pill);
        all.setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));
        Ui.press(c, all);
        head.addView(all, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        int[] icons = {R.drawable.ic_share, R.drawable.ic_copy, R.drawable.ic_scissors, R.drawable.ic_archive,
                R.drawable.ic_delete};
        int[] labels = {R.string.share, R.string.fm_copy, R.string.fm_move, R.string.fm_compress, R.string.delete};
        final View[] cols = new View[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            boolean danger = i == 4;
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setClickable(true);
            col.setContentDescription(c.getString(labels[i]));

            ImageView iv = new ImageView(c);
            iv.setImageResource(icons[i]);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(Ui.dp(c, 11), Ui.dp(c, 11), Ui.dp(c, 11), Ui.dp(c, 11));
            iv.setImageTintList(ColorStateList.valueOf(Ui.color(c, danger ? R.color.bad : R.color.text_primary)));
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Ui.color(c, R.color.field));
            iv.setBackground(circle);
            col.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));

            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setTextSize(9.5f);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Ui.color(c, danger ? R.color.bad : R.color.text_secondary));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = Ui.dp(c, 4);
            col.addView(t, tp);

            Ui.press(c, col);
            col.setOnClickListener(v -> {
                if (v.getAlpha() < 1f) return;
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                if (ref[0] != null) ref[0].dismiss();
                switch (idx) {
                    case 0:
                        h.onShare();
                        break;
                    case 1:
                        h.onCopy();
                        break;
                    case 2:
                        h.onMove();
                        break;
                    case 3:
                        h.onCompress();
                        break;
                    default:
                        h.onDelete();
                        break;
                }
            });
            cols[i] = col;
            actions.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(c, 12);
        panel.addView(actions, ap);

        TextView more = new TextView(c);
        more.setText(R.string.more);
        more.setTextSize(12);
        more.setTypeface(Typeface.DEFAULT_BOLD);
        more.setGravity(Gravity.CENTER);
        more.setTextColor(Ui.color(c, R.color.accent_text));
        more.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 2));
        more.setClickable(true);
        Ui.press(c, more);
        more.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            h.onMore();
        });
        panel.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable refresh = () -> {
            final List<File> files = h.files();
            count.setText(c.getString(R.string.fm_selected_n, files.size()));
            boolean anyFile = false;
            for (File f : files) {
                if (f.isFile()) {
                    anyFile = true;
                    break;
                }
            }
            cols[0].setAlpha(anyFile ? 1f : 0.4f);
            size.setText("\u2026");
            final int my = ++gen[0];
            final List<File> snapshot = new ArrayList<>(files);
            Thread t = new Thread(() -> {
                final long total = totalOf(snapshot, open);
                size.post(() -> {
                    if (open[0] && my == gen[0]) size.setText(Fmt.size(total));
                });
            }, "sel-size");
            t.setDaemon(true);
            t.start();
        };
        refresh.run();

        all.setOnClickListener(v -> {
            h.onSelectAll();
            if (h.files().isEmpty()) {
                if (ref[0] != null) ref[0].dismiss();
            } else {
                refresh.run();
            }
        });

        final AlertDialog d = new AlertDialog.Builder(c).setView(root).create();
        ref[0] = d;
        Window w = d.getWindow();
        final float base = Blur.dim(c, 0.5f);
        if (w != null) {
            w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            w.setWindowAnimations(R.style.SheetAnim);
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(base);
            int ww = Math.min(c.getResources().getDisplayMetrics().widthPixels, Ui.dp(c, 560));
            w.setLayout(ww, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        Blur.dialogWindow(w, c, true);
        Dlg.attachDrag(gripBox, root, ref, base);
        d.setCanceledOnTouchOutside(true);
        d.setOnDismissListener(x -> open[0] = false);
        d.show();
        return d;
    }

    private static long totalOf(List<File> files, boolean[] open) {
        long[] sum = {0};
        int[] budget = {WALK_BUDGET};
        for (File f : files) {
            if (!open[0]) return sum[0];
            walk(f, sum, budget, open, 0);
        }
        return sum[0];
    }

    private static void walk(File f, long[] sum, int[] budget, boolean[] open, int depth) {
        if (!open[0] || budget[0] <= 0 || depth > 16) return;
        budget[0]--;
        if (f.isDirectory()) {
            if (Cats.isLink(f)) return;
            File[] kids = f.listFiles();
            if (kids == null) return;
            for (File k : kids) walk(k, sum, budget, open, depth + 1);
        } else {
            sum[0] += f.length();
        }
    }
}
