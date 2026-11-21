package com.fileman.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The permanent-delete flow, in the app's own dialog style:
 * <ol>
 *   <li>Options: how many overwrite passes, plus a few switches.</li>
 *   <li>The permanent-delete screen: the three stages (corrupt, overwrite, delete), a warning, and a Start button.
 *       Once started it shows live progress per stage and the file being worked on; Stop ends it after the current file.</li>
 * </ol>
 */
final class SecureDeleteSheet {
    interface Done {
        /** Runs on the UI thread as soon as the work has finished (or was stopped). */
        void onDone(SecureWipe.Result r);
    }

    /** Share of the overall bar that each stage takes: overwriting is by far the longest. */
    private static final float[] WEIGHT = {0.10f, 0.80f, 0.10f};
    private static final int[] PASS_CHOICES = {1, 3, 5, 7};

    private final Activity act;
    private final List<File> files;
    private final ExecutorService io;
    private final Done done;
    private final SecureWipe.Options opt = new SecureWipe.Options();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout box;
    private AlertDialog dlg;
    private SecureWipe.Plan plan;
    private boolean running;
    private boolean gone;

    // views of the permanent-delete screen
    private final ImageView[] marks = new ImageView[3];
    private final ProgressBar[] spins = new ProgressBar[3];
    private ProgressBar bar;
    private TextView pct;
    private TextView current;
    private TextView status;
    private LinearLayout buttons;

    private SecureDeleteSheet(Activity act, List<File> files, ExecutorService io, Done done) {
        this.act = act;
        this.files = files;
        this.io = io;
        this.done = done;
    }

    static void show(Activity act, List<File> files, ExecutorService io, Done done) {
        if (files == null || files.isEmpty()) return;
        new SecureDeleteSheet(act, files, io, done).open();
    }

    // ------------------------------------------------------------------ frame

    private void open() {
        box = Ui.box(act);
        ScrollView sv = new ScrollView(act);
        sv.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg = new Dlg(act).setTitle(R.string.sd_title).setView(sv).create();
        dlg.setOnDismissListener(d -> {
            gone = true;
            if (running) cancel.set(true);
        });
        dlg.show();

        box.addView(text(act.getString(R.string.sd_measuring), 14, R.color.text_secondary, false));
        final List<File> roots = files;
        io.execute(() -> {
            final SecureWipe.Plan p = SecureWipe.plan(roots);
            ui.post(() -> {
                if (gone) return;
                plan = p;
                renderOptions();
            });
        });
    }

    // ------------------------------------------------------------------ 1) options

    private void renderOptions() {
        box.removeAllViews();
        box.addView(text(act.getString(R.string.sd_summary, plan.files.size() + plan.links.size(), Fmt.size(plan.bytes)),
                14, R.color.text_secondary, false));

        box.addView(section(act.getString(R.string.sd_passes_title)));
        final LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] cs = new TextView[PASS_CHOICES.length];
        for (int i = 0; i < PASS_CHOICES.length; i++) {
            final int v = PASS_CHOICES[i];
            final TextView chip = Ui.chip(act, String.valueOf(v), v == opt.passes);
            cs[i] = chip;
            chip.setOnClickListener(x -> {
                opt.passes = v;
                for (TextView o : cs) Ui.setChip(act, o, o == chip);
                Fx.tick(chip);
            });
            chips.addView(chip);
        }
        box.addView(chips);
        TextView hint = text(act.getString(R.string.sd_passes_hint), 12, R.color.text_secondary, false);
        hint.setPadding(0, Ui.dp(act, 8), 0, 0);
        box.addView(hint);

        box.addView(section(act.getString(R.string.sd_opt_title)));
        LinearLayout g = new LinearLayout(act);
        g.setOrientation(LinearLayout.VERTICAL);
        final Ui.Toggle zero = Ui.toggle(act, g, R.string.sd_tg_zero, R.string.sd_tg_zero_sub, opt.zeroPass);
        zero.onChange((b, on) -> opt.zeroPass = on);
        g.addView(zero.view);
        final Ui.Toggle ren = Ui.toggle(act, g, R.string.sd_tg_rename, R.string.sd_tg_rename_sub, opt.rename);
        ren.onChange((b, on) -> opt.rename = on);
        g.addView(ren.view);
        final Ui.Toggle trc = Ui.toggle(act, g, R.string.sd_tg_traces, R.string.sd_tg_traces_sub, opt.clearTraces);
        trc.onChange((b, on) -> opt.clearTraces = on);
        g.addView(trc.view);
        Ui.group(act, g);
        box.addView(g);

        Button cancelBtn = Ui.button(act, R.string.cancel, false);
        cancelBtn.setOnClickListener(v -> dlg.dismiss());
        Button next = Ui.button(act, R.string.sd_next, true);
        next.setOnClickListener(v -> renderRun());
        box.addView(buttonRow(cancelBtn, next));
    }

    // ------------------------------------------------------------------ 2) the permanent-delete screen

    private void renderRun() {
        box.removeAllViews();
        box.addView(text(act.getString(R.string.sd_summary, plan.files.size() + plan.links.size(), Fmt.size(plan.bytes)),
                14, R.color.text_secondary, false));

        TextView warn = text(act.getString(R.string.sd_warn), 13, R.color.bad, false);
        warn.setBackgroundResource(R.drawable.bg_note);
        warn.setPaddingRelative(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 12));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = Ui.dp(act, 10);
        wl.bottomMargin = Ui.dp(act, 4);
        box.addView(warn, wl);

        box.addView(stageRow(0, R.string.sd_st1, act.getString(R.string.sd_st1_sub)));
        box.addView(stageRow(1, R.string.sd_st2, act.getString(R.string.sd_st2_sub, opt.passes)));
        box.addView(stageRow(2, R.string.sd_st3, act.getString(R.string.sd_st3_sub)));

        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(act, 6)));
        bar.setMax(1000);
        bar.setProgress(0);
        Ui.tintRounded(act, bar);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(act, 6));
        bl.topMargin = Ui.dp(act, 14);
        box.addView(bar, bl);

        LinearLayout line = new LinearLayout(act);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        current = text("", 12, R.color.text_secondary, false);
        current.setSingleLine(true);
        current.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        line.addView(current, new LinearLayout.LayoutParams(0, -2, 1f));
        pct = text("", 12, R.color.text_secondary, true);
        line.addView(pct, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = Ui.dp(act, 6);
        box.addView(line, ll);

        status = text("", 13, R.color.text_primary, true);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Ui.dp(act, 10);
        box.addView(status, sl);

        buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bll = new LinearLayout.LayoutParams(-1, -2);
        bll.topMargin = Ui.dp(act, 16);
        box.addView(buttons, bll);
        readyButtons();
        setStage(-1);
    }

    private void readyButtons() {
        buttons.removeAllViews();
        Button back = Ui.button(act, R.string.back, false);
        back.setOnClickListener(v -> renderOptions());
        Button go = Ui.button(act, R.string.sd_start, true);
        go.setBackgroundResource(R.drawable.dlg_btn_danger);
        go.setTextColor(0xFFFFFFFF);
        go.setOnClickListener(v -> start());
        addPair(buttons, back, go);
    }

    private View stageRow(int idx, int titleRes, String sub) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));

        FrameLayout slot = new FrameLayout(act);
        marks[idx] = new ImageView(act);
        marks[idx].setImageResource(R.drawable.bg_check);
        slot.addView(marks[idx], new FrameLayout.LayoutParams(Ui.dp(act, 26), Ui.dp(act, 26), Gravity.CENTER));
        spins[idx] = new ProgressBar(act);
        Ui.tint(act, spins[idx]);
        spins[idx].setVisibility(View.GONE);
        slot.addView(spins[idx], new FrameLayout.LayoutParams(Ui.dp(act, 22), Ui.dp(act, 22), Gravity.CENTER));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(act, 30), Ui.dp(act, 30));
        sp.setMarginEnd(Ui.dp(act, 12));
        row.addView(slot, sp);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(act.getString(titleRes), 15, R.color.text_primary, true));
        TextView s = text(sub, 12, R.color.text_secondary, false);
        s.setPadding(0, Ui.dp(act, 2), 0, 0);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    /** Stages before {@code active} are ticked, {@code active} shows a spinner, later ones show an empty ring. */
    private void setStage(int active) {
        for (int i = 0; i < 3; i++) {
            if (marks[i] == null) continue;
            boolean isDone = i < active;
            boolean isNow = i == active;
            marks[i].setSelected(isDone);
            marks[i].setVisibility(isNow ? View.INVISIBLE : View.VISIBLE);
            spins[i].setVisibility(isNow ? View.VISIBLE : View.GONE);
        }
    }

    private void setProgress(int stage, float frac, String name) {
        if (gone || bar == null) return;
        float all = 0f;
        for (int i = 0; i < stage; i++) all += WEIGHT[i];
        all += WEIGHT[Math.max(0, Math.min(2, stage))] * frac;
        int v = Math.round(Math.max(0f, Math.min(1f, all)) * 1000f);
        Ui.animateProgress(bar, v);
        pct.setText(Math.round(v / 10f) + "%");
        current.setText(name == null ? "" : name);
    }

    // ------------------------------------------------------------------ run

    private void start() {
        running = true;
        cancel.set(false);
        dlg.setCancelable(false);
        dlg.setCanceledOnTouchOutside(false);
        if (dlg.getWindow() != null) dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        current.setText(R.string.sd_preparing);
        buttons.removeAllViews();
        final Button stop = Ui.button(act, R.string.sd_stop, false);
        stop.setOnClickListener(v -> {
            cancel.set(true);
            stop.setEnabled(false);
            status.setText(R.string.sd_stopping);
            status.setVisibility(View.VISIBLE);
        });
        buttons.addView(stop, new LinearLayout.LayoutParams(-1, -2));

        final SecureWipe.Plan p = plan;
        final SecureWipe.Options o = opt;
        io.execute(() -> {
            final SecureWipe.Result r = SecureWipe.run(act, p, o, new SecureWipe.Listener() {
                @Override
                public void onStage(int stage) {
                    ui.post(() -> {
                        if (!gone) setStage(stage);
                    });
                }

                @Override
                public void onProgress(int stage, float fraction, String name) {
                    ui.post(() -> setProgress(stage, fraction, name));
                }
            }, cancel);
            ui.post(() -> finished(r));
        });
    }

    private void finished(SecureWipe.Result r) {
        running = false;
        try {
            done.onDone(r);
        } catch (Throwable ignored) {
        }
        if (gone) return;
        if (dlg.getWindow() != null) dlg.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dlg.setCancelable(true);
        dlg.setCanceledOnTouchOutside(true);
        if (!r.cancelled) {
            setStage(3);
            setProgress(2, 1f, "");
        }
        current.setText("");
        status.setVisibility(View.VISIBLE);
        if (r.cancelled) {
            status.setTextColor(Ui.color(act, R.color.warn));
            status.setText(R.string.sd_stopped);
        } else if (r.failed == 0) {
            status.setTextColor(Ui.color(act, R.color.ok));
            status.setText(act.getString(R.string.sd_done_n, r.erased));
            Fx.done(status);
        } else {
            status.setTextColor(Ui.color(act, R.color.bad));
            status.setText(act.getString(R.string.sd_failed_n, r.failed));
        }
        buttons.removeAllViews();
        Button ok = Ui.button(act, R.string.sd_finish, true);
        ok.setOnClickListener(v -> dlg.dismiss());
        buttons.addView(ok, new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ small helpers

    private TextView text(CharSequence s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Ui.color(act, colorRes));
        t.setLineSpacing(0, 1.15f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView section(CharSequence s) {
        TextView t = text(s, 12, R.color.text_secondary, false);
        t.setPadding(0, Ui.dp(act, 18), 0, Ui.dp(act, 8));
        return t;
    }

    private View buttonRow(Button a, Button b) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(act, 18);
        row.setLayoutParams(lp);
        addPair(row, a, b);
        return row;
    }

    private void addPair(LinearLayout row, Button a, Button b) {
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -2, 1f);
        la.setMarginEnd(Ui.dp(act, 6));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, -2, 1f);
        lb.setMarginStart(Ui.dp(act, 6));
        row.addView(a, la);
        row.addView(b, lb);
    }
}
