package com.fileman.app;

import android.animation.ValueAnimator;
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
    final TextView speed;
    private ValueAnimator smooth;
    private float shown;

    BusyBox(Context c, CharSequence initial) {
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPaddingRelative(Ui.dp(c, 22), Ui.dp(c, 6), Ui.dp(c, 22), Ui.dp(c, 8));

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
        text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
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

        speed = new TextView(c);
        speed.setTextSize(12);
        speed.setTextColor(Ui.color(c, R.color.text_secondary));
        speed.setTextDirection(View.TEXT_DIRECTION_LTR);
        speed.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        speed.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(c, 4);
        view.addView(speed, sp);

        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressDrawable(c.getDrawable(R.drawable.progress_rounded));
        bar.setMax(100);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.color(c, R.color.accent)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 6));
        bp.topMargin = Ui.dp(c, 12);
        view.addView(bar, bp);
    }

    /** 0..100 shows the percentage; a negative value keeps only the indeterminate bar. */
    void setPercent(int pct) {
        setProgress(pct, -1L);
    }

    /** Smoothly interpolated percentage with the transfer speed (bytes per second, negative = unknown). */
    void setProgress(int pct, long bytesPerSec) {
        if (pct < 0) {
            percent.setVisibility(View.GONE);
            bar.setIndeterminate(true);
            return;
        }
        final int target = Math.min(100, pct);
        percent.setVisibility(View.VISIBLE);
        if (bar.isIndeterminate()) bar.setIndeterminate(false);
        if (bytesPerSec > 0) {
            speed.setText(Fmt.size(bytesPerSec) + "/s");
            speed.setVisibility(View.VISIBLE);
        }
        if (smooth != null) smooth.cancel();
        if (!Fx.motion(bar.getContext())) {
            shown = target;
            draw();
            return;
        }
        smooth = ValueAnimator.ofFloat(shown, target);
        smooth.setDuration(220);
        smooth.setInterpolator(Motion.EASE);
        smooth.addUpdateListener(a -> {
            shown = (Float) a.getAnimatedValue();
            draw();
        });
        smooth.start();
    }

    private void draw() {
        int p = Math.round(shown);
        percent.setText(p + "%");
        bar.setProgress(p);
    }

    /** A gentle pulse when the work is done. */
    void pulse() {
        setProgress(100, -1L);
        if (!Fx.motion(view.getContext())) return;
        view.animate().cancel();
        view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(110).withLayer()
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).withLayer().start()).start();
    }

    void stop() {
        if (smooth != null) smooth.cancel();
        view.animate().cancel();
    }

    void setText(CharSequence t) {
        text.setText(t);
    }
}
