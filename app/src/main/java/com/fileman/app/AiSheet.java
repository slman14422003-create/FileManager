package com.fileman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One smart sheet for every screen: it offers what makes sense for the open file or folder, plus the smart tools. */
final class AiSheet {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private AiSheet() {
    }

    /** Shows the sparkle button of the screen's header (when it has one) and wires it. */
    static void install(final Activity a) {
        View b = a.findViewById(R.id.btnAi);
        if (b == null) return;
        b.setVisibility(View.VISIBLE);
        b.setOnClickListener(v -> show(a));
    }

    static void show(final Activity a) {
        final File file = a instanceof BaseActivity ? ((BaseActivity) a).aiFile() : null;
        final File folder = a instanceof BaseActivity ? ((BaseActivity) a).aiFolder() : null;
        final List<String> labels = new ArrayList<>();
        final List<Integer> icons = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        if (file != null && file.isFile() && SmartText.hasText(Cats.extOf(file.getName()))) {
            labels.add(a.getString(R.string.ai_summary));
            icons.add(R.drawable.ic_file_text);
            acts.add(() -> summarize(a, file));
        }
        if (file != null && file.isFile()) {
            labels.add(a.getString(R.string.ai_name));
            icons.add(R.drawable.ic_star);
            acts.add(() -> suggestName(a, file));
        }
        if (folder != null && folder.isDirectory()) {
            labels.add(a.getString(R.string.ai_folder_clean));
            icons.add(R.drawable.ic_search);
            acts.add(() -> open(a, SmartActivity.M_CLEAN, folder));
        }
        add(a, labels, icons, acts, R.string.sm_clean, R.drawable.ic_chart, SmartActivity.M_CLEAN);
        add(a, labels, icons, acts, R.string.sm_org, R.drawable.ic_folder, SmartActivity.M_ORG);
        add(a, labels, icons, acts, R.string.sm_auto, R.drawable.ic_sort, SmartActivity.M_AUTO);
        add(a, labels, icons, acts, R.string.sm_name, R.drawable.ic_file, SmartActivity.M_NAME);
        add(a, labels, icons, acts, R.string.sm_find, R.drawable.ic_search, SmartActivity.M_FIND);
        add(a, labels, icons, acts, R.string.sm_guard, R.drawable.ic_shield, SmartActivity.M_GUARD);

        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        new Dlg(a).setTitle(R.string.ai_title).setMessage(R.string.ai_sub)
                .setItems(labels.toArray(new CharSequence[0]), ic, (d, which) -> acts.get(which).run())
                .show();
    }

    private static void add(final Activity a, List<String> labels, List<Integer> icons, List<Runnable> acts,
                            int label, int icon, final String mode) {
        labels.add(a.getString(label));
        icons.add(icon);
        acts.add(() -> open(a, mode, null));
    }

    private static void open(Activity a, String mode, File root) {
        Intent i = new Intent(a, SmartActivity.class).putExtra(SmartActivity.EXTRA, mode);
        if (root != null) i.putExtra("root", root.getAbsolutePath());
        a.startActivity(i);
    }

    private static void summarize(final Activity a, final File f) {
        android.widget.Toast.makeText(a, R.string.ai_working, android.widget.Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            final SmartSummary.Result r = SmartSummary.of(SmartText.textOf(f, 400000), 4);
            UI.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (r.sentences.isEmpty()) {
                    Dlg.result(a, false, a.getString(R.string.ai_summary_title), a.getString(R.string.ai_summary_none));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (String s : r.sentences) sb.append("• ").append(s).append("\n\n");
                if (!r.keywords.isEmpty()) {
                    sb.append(a.getString(R.string.ai_keywords, android.text.TextUtils.join(" · ", r.keywords)))
                            .append("\n");
                }
                sb.append(a.getString(R.string.ai_words, r.words));
                Dlg.result(a, true, a.getString(R.string.ai_summary_title), sb.toString());
            });
        });
    }

    private static void suggestName(final Activity a, final File f) {
        IO.execute(() -> {
            String t = SmartText.titleOf(f);
            if (t == null || t.trim().isEmpty()) {
                String body = SmartText.textOf(f, 4000);
                SmartSummary.Result r = SmartSummary.of(body, 1);
                if (!r.sentences.isEmpty()) t = r.sentences.get(0);
            }
            final String name = clean(t, f);
            UI.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (name == null) {
                    Dlg.result(a, false, a.getString(R.string.ai_name), a.getString(R.string.ai_name_none));
                    return;
                }
                new Dlg(a).setTitle(R.string.ai_name).setMessage(a.getString(R.string.ai_name_result, name))
                        .setPositiveButton(R.string.ai_copy, (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("name", name));
                        })
                        .setNegativeButton(R.string.cancel, null).show();
            });
        });
    }

    private static String clean(String t, File f) {
        if (t == null) return null;
        String s = t.replaceAll("[\\\\/:*?\"<>|\\n\\r]+", " ").replaceAll("\\s+", " ").trim();
        if (s.length() < 3) return null;
        if (s.length() > 60) s = s.substring(0, 60).trim();
        String ext = Cats.extOf(f.getName());
        return ext.isEmpty() ? s : s + "." + ext;
    }
}
