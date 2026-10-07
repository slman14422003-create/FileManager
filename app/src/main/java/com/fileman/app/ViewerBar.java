package com.fileman.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;

/** Shared pieces of the viewer screens: the floating action pill and the file-type badge in the header. */
final class ViewerBar {
    private ViewerBar() {
    }

    /** A rounded floating bar of icon buttons. {@code dark} gives the translucent black style used over media. */
    static LinearLayout build(Activity a, boolean dark, int[] icons, int[] descs, View.OnClickListener[] clicks) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        if (dark) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(a, 30));
            g.setColor(0x99000000);
            g.setStroke(Ui.dp(a, 1), 0x33FFFFFF);
            bar.setBackground(g);
        } else {
            bar.setBackgroundResource(R.drawable.bg_nav_float);
        }
        bar.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        int tint = dark ? 0xFFFFFFFF : Ui.color(a, R.color.text_primary);
        for (int i = 0; i < icons.length; i++) {
            ImageButton b = new ImageButton(a);
            b.setImageResource(icons[i]);
            b.setImageTintList(ColorStateList.valueOf(tint));
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setBackgroundResource(R.drawable.bg_tile_round);
            b.setContentDescription(a.getString(descs[i]));
            Ui.press(a, b);
            b.setOnClickListener(clicks[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 50), Ui.dp(a, 46));
            lp.setMargins(Ui.dp(a, 3), 0, Ui.dp(a, 3), 0);
            bar.addView(b, lp);
        }
        return bar;
    }

    /** Builds the bar and floats it at the bottom centre of {@code holder}. */
    static LinearLayout attach(Activity a, FrameLayout holder, boolean dark, int[] icons, int[] descs,
                               View.OnClickListener[] clicks) {
        LinearLayout bar = build(a, dark, icons, descs, clicks);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(a, 16);
        holder.addView(bar, lp);
        return bar;
    }

    /** Shows the file-type badge (icon in a tinted rounded square) in the shared viewer header. */
    static void headerIcon(Activity a, File f) {
        ImageView iv = a.findViewById(R.id.headerIcon);
        if (iv == null || f == null) return;
        int type = f.isDirectory() ? Cats.T_DIR : Cats.typeOfExt(Cats.extOf(f.getName()));
        int col = Ui.color(a, Cats.colorFor(type));
        iv.setImageResource(Cats.iconFor(type));
        iv.setImageTintList(ColorStateList.valueOf(col));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(a, 14));
        g.setColor((col & 0x00FFFFFF) | 0x26000000);
        iv.setBackground(g);
        iv.setVisibility(View.VISIBLE);
    }
}
