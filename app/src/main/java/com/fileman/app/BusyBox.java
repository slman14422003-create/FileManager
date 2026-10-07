package com.fileman.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Content of the "working…" dialogs: the spinner, a big percentage and the name of the file being handled. */
final class BusyBox {
    final LinearLayout view;
    final SpinnerView spinner;
    final TextView percent;
    final TextView text;

    BusyBox(Context c, CharSequence initial) {
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(Ui.dp(c, 22), Ui.dp(c, 14), Ui.dp(c, 22), Ui.dp(c, 8));

        spinner = new SpinnerView(c);
        view.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54)));

        percent = new TextView(c);
        percent.setTextSize(26);
        percent.setTypeface(Typeface.create("serif", Typeface.BOLD));
        percent.setTextColor(Ui.color(c, R.color.text_primary));
        percent.setGravity(Gravity.CENTER);
        percent.setTextDirection(View.TEXT_DIRECTION_LTR);
        percent.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 2));
        percent.setVisibility(View.GONE);
        view.addView(percent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        text = new TextView(c);
        text.setText(initial);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        text.setTextColor(Ui.color(c, R.color.text_secondary));
        text.setTextSize(13.5f);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 8));
        view.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 0..100 shows the percentage; a negative value keeps only the spinning indicator. */
    void setPercent(int pct) {
        if (pct < 0) {
            percent.setVisibility(View.GONE);
            return;
        }
        percent.setVisibility(View.VISIBLE);
        percent.setText(Math.min(100, pct) + "%");
    }

    void setText(CharSequence t) {
        text.setText(t);
    }
}
