package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-device assistant available on every screen: a floating glass button opens a sheet with suggestions for the
 * current screen and a command box. Commands are matched against keyword lists kept in the string resources, then
 * routed to the existing smart tools, categories, storage and search screens. Nothing leaves the device.
 */
final class Ai {
    private Ai() {
    }

    private static final String TAG = "fm_ai_button";

    private static final class Action {
        final int label;
        final int icon;
        final Runnable run;

        Action(int label, int icon, Runnable run) {
            this.label = label;
            this.icon = icon;
            this.run = run;
        }
    }

    /** Adds the floating assistant button to a screen (once). */
    static void attach(final Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.findViewWithTag(TAG) != null) return;
        ImageView b = new ImageView(a);
        b.setTag(TAG);
        b.setImageResource(R.drawable.ic_ai);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(Glass.solid(a, Ui.dp(a, 24)));
        b.setElevation(Ui.dp(a, 6));
        b.setContentDescription(a.getString(R.string.ai_title));
        b.setClickable(true);
        Ui.press(a, b);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            open(a);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48),
                Gravity.BOTTOM | Gravity.START);
        lp.setMargins(Ui.dp(a, 20), 0, 0, Ui.dp(a, 100));
        content.addView(b, lp);
    }

    static void open(final Activity a) {
        final AlertDialog[] ref = new AlertDialog[1];
        LinearLayout box = Ui.box(a);
        final EditText input = Ui.edit(a, a.getString(R.string.ai_hint), null);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        box.addView(input);

        TextView sub = new TextView(a);
        sub.setText(R.string.ai_suggest);
        sub.setTextColor(Ui.color(a, R.color.text_secondary));
        sub.setTextSize(13);
        sub.setPadding(Ui.dp(a, 4), Ui.dp(a, 14), Ui.dp(a, 4), Ui.dp(a, 6));
        box.addView(sub);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final Action act : suggestions(a)) {
            list.addView(chip(a, act, ref), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 48)));
        }
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) Math.min(Ui.dp(a, 300), a.getResources().getDisplayMetrics().heightPixels * 0.4f)));

        input.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) {
                String q = input.getText().toString();
                if (ref[0] != null) ref[0].dismiss();
                run(a, q);
                return true;
            }
            return false;
        });

        ref[0] = new Dlg(a).setTitle(R.string.ai_title).setView(box)
                .setPositiveButton(R.string.ai_go, (d, w) -> run(a, input.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static View chip(final Activity a, final Action act, final AlertDialog[] ref) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Glass.chip(a, Ui.dp(a, 16)));
        row.setPaddingRelative(Ui.dp(a, 14), 0, Ui.dp(a, 14), 0);
        ImageView ic = new ImageView(a);
        ic.setImageResource(act.icon);
        ic.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent)));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));
        TextView t = new TextView(a);
        t.setText(act.label);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Ui.color(a, R.color.text_primary));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(Ui.dp(a, 12));
        row.addView(t, tp);
        row.setClickable(true);
        Ui.press(a, row);
        row.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            act.run.run();
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(a, 44));
        gap.bottomMargin = Ui.dp(a, 8);
        row.setLayoutParams(gap);
        return row;
    }

    private static Intent smart(Context c, String mode) {
        return new Intent(c, SmartActivity.class).putExtra(SmartActivity.EXTRA, mode);
    }

    private static Intent category(Context c, String key) {
        return new Intent(c, FileManagerActivity.class).putExtra("cat", key);
    }

    private static Intent search(Context c, String q) {
        Intent i = new Intent(c, FileManagerActivity.class).putExtra("search", true);
        if (q != null && !q.isEmpty()) i.putExtra("q", q);
        return i;
    }

    private static List<Action> suggestions(final Activity a) {
        List<Action> out = new ArrayList<>();
        String screen = a.getClass().getSimpleName();
        if (screen.startsWith("Storage") || screen.startsWith("Home")) {
            out.add(new Action(R.string.ai_sg_clean, R.drawable.ic_delete, () -> a.startActivity(smart(a, SmartActivity.M_CLEAN))));
            out.add(new Action(R.string.ai_sg_dupes, R.drawable.ic_copy, () -> a.startActivity(smart(a, SmartActivity.M_FIND))));
        } else if (screen.startsWith("FileManager") || screen.startsWith("Zip")) {
            out.add(new Action(R.string.ai_sg_organize, R.drawable.ic_sort, () -> a.startActivity(smart(a, SmartActivity.M_ORG))));
            out.add(new Action(R.string.ai_sg_rename, R.drawable.ic_file_text, () -> a.startActivity(smart(a, SmartActivity.M_NAME))));
        } else if (screen.startsWith("Install")) {
            out.add(new Action(R.string.ai_sg_guard, R.drawable.ic_shield, () -> a.startActivity(smart(a, SmartActivity.M_GUARD))));
        }
        addUnique(out, new Action(R.string.ai_sg_clean, R.drawable.ic_delete, () -> a.startActivity(smart(a, SmartActivity.M_CLEAN))));
        addUnique(out, new Action(R.string.ai_sg_dupes, R.drawable.ic_copy, () -> a.startActivity(smart(a, SmartActivity.M_FIND))));
        addUnique(out, new Action(R.string.ai_sg_organize, R.drawable.ic_sort, () -> a.startActivity(smart(a, SmartActivity.M_ORG))));
        addUnique(out, new Action(R.string.ai_sg_guard, R.drawable.ic_shield, () -> a.startActivity(smart(a, SmartActivity.M_GUARD))));
        addUnique(out, new Action(R.string.ai_sg_storage, R.drawable.ic_chart, () -> a.startActivity(new Intent(a, StorageActivity.class))));
        addUnique(out, new Action(R.string.ai_sg_images, R.drawable.ic_image, () -> a.startActivity(category(a, Cats.IMG))));
        return out;
    }

    private static void addUnique(List<Action> out, Action x) {
        for (Action e : out) if (e.label == x.label) return;
        out.add(x);
    }

    private static boolean has(Context c, int kwRes, String q) {
        for (String k : c.getString(kwRes).split("\\|")) {
            String w = k.trim().toLowerCase(Locale.ROOT);
            if (!w.isEmpty() && q.contains(w)) return true;
        }
        return false;
    }

    /** Matches a typed command and opens the right tool; anything unmatched becomes a file search. */
    static void run(Activity a, String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        String l = q.toLowerCase(Locale.ROOT);
        Intent i;
        if (has(a, R.string.ai_kw_dupes, l)) i = smart(a, SmartActivity.M_FIND);
        else if (has(a, R.string.ai_kw_clean, l)) i = smart(a, SmartActivity.M_CLEAN);
        else if (has(a, R.string.ai_kw_organize, l)) i = smart(a, SmartActivity.M_ORG);
        else if (has(a, R.string.ai_kw_rename, l)) i = smart(a, SmartActivity.M_NAME);
        else if (has(a, R.string.ai_kw_guard, l)) i = smart(a, SmartActivity.M_GUARD);
        else if (has(a, R.string.ai_kw_storage, l)) i = new Intent(a, StorageActivity.class);
        else if (has(a, R.string.ai_kw_images, l)) i = category(a, Cats.IMG);
        else if (has(a, R.string.ai_kw_videos, l)) i = category(a, Cats.VID);
        else if (has(a, R.string.ai_kw_audio, l)) i = category(a, Cats.AUD);
        else if (has(a, R.string.ai_kw_docs, l)) i = category(a, Cats.DOC);
        else if (has(a, R.string.ai_kw_apps, l)) i = category(a, Cats.APK);
        else i = search(a, q);
        a.startActivity(i);
    }
}
