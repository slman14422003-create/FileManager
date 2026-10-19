package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Small on-device helper: it keeps a short log of what the user does with files and, now and then, offers one
 * suggestion in a dialog. Everything stays on the device; the rules are plain code, no model is downloaded.
 */
final class Assistant {
    private Assistant() {
    }

    interface Host {
        void openStorage();

        void openCategory(String key);

        void addFavorite(String path);

        boolean isFavorite(String path);
    }

    private static final String PREFS = "assistant";
    private static final String K_LOG = "log";
    private static final String K_ON = "on";
    private static final String K_LAST = "last";
    private static final String K_DAY = "day";
    private static final String K_DAY_N = "dayN";
    private static final String K_OFF = "off";
    private static final int MAX_LINES = 160;
    private static final long WINDOW = 30L * 60 * 1000;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long GAP = 20L * 60 * 1000;
    private static final int PER_DAY = 3;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return p(c).getBoolean(K_ON, true);
    }

    /** act: delete, extract, visit, paste, open. n: how many items. arg: a folder path or file type. */
    static synchronized void log(Context c, String act, int n, String arg) {
        try {
            SharedPreferences sp = p(c);
            if (!sp.getBoolean(K_ON, true)) return;
            String old = sp.getString(K_LOG, "");
            String line = System.currentTimeMillis() + "|" + act + "|" + n + "|" + (arg == null ? "" : arg.replace('|', ' ').replace('\n', ' '));
            String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.length - (MAX_LINES - 1));
            for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
            sb.append(line);
            sp.edit().putString(K_LOG, sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static final class Tip {
        String id;
        int title;
        String message;
        int yes;
        Runnable action;
    }

    /** Shows one suggestion if the recent activity suggests something useful and it is not too soon after the last one. */
    static synchronized void suggest(final Activity a, final Host h) {
        try {
            if (a.isFinishing() || a.isDestroyed()) return;
            final SharedPreferences sp = p(a);
            if (!sp.getBoolean(K_ON, true)) return;
            long now = System.currentTimeMillis();
            if (now - sp.getLong(K_LAST, 0) < GAP) return;
            long day = now / DAY;
            int shownToday = sp.getLong(K_DAY, -1) == day ? sp.getInt(K_DAY_N, 0) : 0;
            if (shownToday >= PER_DAY) return;
            Set<String> off = new LinkedHashSet<>(sp.getStringSet(K_OFF, new LinkedHashSet<String>()));
            final Tip t = pick(a, sp.getString(K_LOG, ""), now, off, h);
            if (t == null) return;
            sp.edit().putLong(K_LAST, now).putLong(K_DAY, day).putInt(K_DAY_N, shownToday + 1).apply();
            new Dlg(a).setTitle(t.title).setMessage(t.message)
                    .setPositiveButton(t.yes, (d, w) -> t.action.run())
                    .setNeutralButton(R.string.as_later, null)
                    .setNegativeButton(R.string.as_stop, (d, w) -> sp.edit().putBoolean(K_ON, false).apply())
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private static Tip pick(final Context c, String log, long now, Set<String> off, final Host h) {
        int deleted = 0;
        int extracted = 0;
        Map<String, Integer> visits = new HashMap<>();
        if (!log.isEmpty()) {
            for (String line : log.split("\n")) {
                String[] f = line.split("\\|", 4);
                if (f.length < 4) continue;
                long ts;
                int n;
                try {
                    ts = Long.parseLong(f[0]);
                    n = Integer.parseInt(f[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                long age = now - ts;
                if (f[1].equals("delete") && age <= WINDOW) deleted += n;
                else if (f[1].equals("extract") && age <= WINDOW) extracted += n;
                else if (f[1].equals("visit") && age <= DAY) {
                    Integer v = visits.get(f[3]);
                    visits.put(f[3], v == null ? 1 : v + 1);
                }
            }
        }
        if (deleted >= 8 && !off.contains("cleanup")) {
            Tip t = new Tip();
            t.id = "cleanup";
            t.title = R.string.as_cleanup_t;
            t.message = c.getString(R.string.as_cleanup_m, deleted);
            t.yes = R.string.as_cleanup_y;
            t.action = h::openStorage;
            return t;
        }
        if (extracted >= 2 && !off.contains("archives")) {
            Tip t = new Tip();
            t.id = "archives";
            t.title = R.string.as_arc_t;
            t.message = c.getString(R.string.as_arc_m, extracted);
            t.yes = R.string.as_arc_y;
            t.action = () -> h.openCategory(Cats.ARC);
            return t;
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : visits.entrySet()) {
            File dir = new File(e.getKey());
            if (e.getValue() >= 4 && e.getValue() > bestN && dir.isDirectory() && !h.isFavorite(e.getKey())
                    && !off.contains("fav:" + e.getKey())) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        if (best != null) {
            final String path = best;
            Tip t = new Tip();
            t.id = "fav:" + path;
            t.title = R.string.as_fav_t;
            t.message = c.getString(R.string.as_fav_m, new File(path).getName(), bestN);
            t.yes = R.string.as_fav_y;
            t.action = () -> h.addFavorite(path);
            return t;
        }
        return null;
    }
}
