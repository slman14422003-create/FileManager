package com.fileman.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Content of the "working…" dialogs: a rounded progress bar, the percentage and the file being handled. */
final class BusyBox {
    final LinearLayout view;
    final ProgressBar bar;
    final TextView percent;
    final TextView text;

    BusyBox(Context c, CharSequence initial) {
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 10), Ui.dp(c, 24), Ui.dp(c, 12));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        text = new TextView(c);
        text.setText(initial);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        text.setTextColor(Ui.color(c, R.color.text_secondary));
        text.setTextSize(14);
        text.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        percent = new TextView(c);
        percent.setTextSize(18);
        percent.setTypeface(Typeface.DEFAULT_BOLD);
        percent.setTextColor(Ui.color(c, R.color.text_primary));
        percent.setTextDirection(View.TEXT_DIRECTION_LTR);
        percent.setPaddingRelative(Ui.dp(c, 12), 0, 0, 0);
        percent.setVisibility(View.GONE);
        top.addView(percent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        view.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressDrawable(c.getDrawable(R.drawable.progress_rounded));
        bar.setMax(100);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.color(c, R.color.accent)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 8));
        bp.topMargin = Ui.dp(c, 14);
        view.addView(bar, bp);
    }

    /** 0..100 shows the percentage; a negative value keeps only the indeterminate bar. */
    void setPercent(int pct) {
        if (pct < 0) {
            percent.setVisibility(View.GONE);
            bar.setIndeterminate(true);
            return;
        }
        int p = Math.min(100, pct);
        percent.setVisibility(View.VISIBLE);
        percent.setText(p + "%");
        if (bar.isIndeterminate()) {
            bar.setIndeterminate(false);
        }
        bar.setProgress(p);
    }

    void setText(CharSequence t) {
        text.setText(t);
    }
}
