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

import java.util.List;
import java.util.function.BooleanSupplier;

final class ActionSheet {
    static final class Item {
        final int icon;
        final String label;
        final Runnable run;
        BooleanSupplier active;
        boolean keepOpen;

        Item(int icon, String label, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.run = run;
        }

        Item toggle(BooleanSupplier state) {
            this.active = state;
            this.keepOpen = true;
            return this;
        }
    }

    private ActionSheet() {
    }

    static AlertDialog show(final Context c, String title, String subtitle, int badgeIcon,
                            List<Item> tiles, List<Item> rows) {
        final AlertDialog[] ref = new AlertDialog[1];

        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPaddingRelative(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 12));

        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Blur.surface(c, Ui.color(c, R.color.surface), 0xEC));
        bg.setCornerRadius(Ui.dp(c, 26));
        panel.setBackground(bg);
        panel.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 14));
        panel.setElevation(Ui.dpf(c, 12));
        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout gripBox = new FrameLayout(c);
        View grip = new View(c);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Ui.color(c, R.color.text_hint));
        gd.setCornerRadius(Ui.dp(c, 2));
        grip.setBackground(gd);
        gripBox.addView(grip, new FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 4), Gravity.CENTER));
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 18));
        gbp.bottomMargin = Ui.dp(c, 6);
        panel.addView(gripBox, gbp);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView badge = new ImageView(c);
        badge.setImageResource(badgeIcon);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setPadding(Ui.dp(c, 11), Ui.dp(c, 11), Ui.dp(c, 11), Ui.dp(c, 11));
        badge.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.accent_text)));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(Ui.color(c, R.color.accent_soft));
        bb.setCornerRadius(Ui.dp(c, 14));
        badge.setBackground(bb);
        head.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)));

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setTextColor(Ui.color(c, R.color.text_primary));
        info.addView(t);
        TextView s = new TextView(c);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setSingleLine(true);
        s.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        s.setTextColor(Ui.color(c, R.color.text_secondary));
        info.addView(s);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.setMarginStart(Ui.dp(c, 12));
        head.addView(info, ip);
        panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (tiles != null && !tiles.isEmpty()) {
            LinearLayout grid = new LinearLayout(c);
            grid.setOrientation(LinearLayout.HORIZONTAL);
            for (final Item it : tiles) {
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.setClickable(true);
                col.setContentDescription(it.label);

                final ImageView iv = new ImageView(c);
                iv.setImageResource(it.icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
                iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                paint(c, iv, it.active != null && it.active.getAsBoolean());
                col.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54)));

                TextView tv = new TextView(c);
                tv.setText(it.label);
                tv.setTextSize(11);
                tv.setMaxLines(2);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(Ui.color(c, R.color.text_secondary));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.topMargin = Ui.dp(c, 6);
                col.addView(tv, tp);

                Ui.press(c, col);
                col.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    if (it.keepOpen) {
                        it.run.run();
                        paint(c, iv, it.active != null && it.active.getAsBoolean());
                    } else {
                        if (ref[0] != null) ref[0].dismiss();
                        it.run.run();
                    }
                });
                grid.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.topMargin = Ui.dp(c, 16);
            panel.addView(grid, gp);
        }

        if (rows != null && !rows.isEmpty()) {
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable lb = new GradientDrawable();
            lb.setColor(Ui.color(c, R.color.field));
            lb.setCornerRadius(Ui.dp(c, 16));
            list.setBackground(lb);
            for (int i = 0; i < rows.size(); i++) {
                final Item it = rows.get(i);
                LinearLayout r = new LinearLayout(c);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setClickable(true);
                r.setPaddingRelative(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
                ImageView ic = new ImageView(c);
                ic.setImageResource(it.icon);
                ic.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.accent_text)));
                r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
                TextView tx = new TextView(c);
                tx.setText(it.label);
                tx.setTextSize(15);
                tx.setSingleLine(true);
                tx.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                tx.setTextColor(Ui.color(c, R.color.text_primary));
                LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                xp.setMarginStart(Ui.dp(c, 14));
                r.addView(tx, xp);
                Ui.press(c, r);
                r.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    if (ref[0] != null) ref[0].dismiss();
                    it.run.run();
                });
                list.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 52)));
                if (i < rows.size() - 1) {
                    View line = new View(c);
                    line.setBackgroundColor(Ui.color(c, R.color.stroke));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.max(1, Ui.dp(c, 1) / 2));
                    lp.setMarginStart(Ui.dp(c, 50));
                    list.addView(line, lp);
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.dp(c, 16);
            panel.addView(list, rp);
        }

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
        d.show();
        return d;
    }

    private static void paint(Context c, ImageView iv, boolean on) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(on ? Ui.color(c, R.color.accent) : Ui.color(c, R.color.field));
        iv.setBackground(circle);
        iv.setImageTintList(ColorStateList.valueOf(on ? Ui.color(c, R.color.on_accent) : Ui.color(c, R.color.text_primary)));
    }
}
