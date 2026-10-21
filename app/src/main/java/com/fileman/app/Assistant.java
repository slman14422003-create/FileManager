package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

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

        default void openFolder(String path) {
        }

        default void openSmart(String mode) {
        }
    }

    private static final String PREFS = "assistant";
    private static final String K_LOG = "log";
    private static final String K_ON = "on";
    private static final String K_LAST = "last";
    private static final String K_DAY = "day";
    private static final String K_DAY_N = "dayN";
    private static final String K_OFF = "off";
    private static final String K_HAB = "habit";
    private static final String K_ORG_AT = "orgAt";
    private static final int MAX_HABITS = 40;
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
            if (act.equals("visit") && arg != null) habit(sp, arg, System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    private static long localDay(long t) {
        return (t + TimeZone.getDefault().getOffset(t)) / DAY;
    }

    private static Map<String, long[]> readHabits(SharedPreferences sp) {
        Map<String, long[]> m = new LinkedHashMap<>();
        String raw = sp.getString(K_HAB, "");
        if (raw.isEmpty()) return m;
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t");
            if (f.length != 3) continue;
            String[] a = f[1].split(",");
            String[] b = f[2].split(",");
            if (a.length != 24 || b.length != 24) continue;
            long[] v = new long[48];
            try {
                for (int i = 0; i < 24; i++) {
                    v[i] = Long.parseLong(a[i]);
                    v[24 + i] = Long.parseLong(b[i]);
                }
            } catch (NumberFormatException e) {
                continue;
            }
            m.put(f[0], v);
        }
        return m;
    }

    private static void writeHabits(SharedPreferences sp, Map<String, long[]> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, long[]> e : m.entrySet()) {
            long[] v = e.getValue();
            sb.append(e.getKey()).append('\t');
            for (int i = 0; i < 24; i++) sb.append(i == 0 ? "" : ",").append(v[i]);
            sb.append('\t');
            for (int i = 0; i < 24; i++) sb.append(i == 0 ? "" : ",").append(v[24 + i]);
            sb.append('\n');
        }
        sp.edit().putString(K_HAB, sb.toString()).apply();
    }

    private static void habit(SharedPreferences sp, String path, long now) {
        if (path.isEmpty() || path.indexOf('\t') >= 0 || path.indexOf('\n') >= 0) return;
        if (path.equals(android.os.Environment.getExternalStorageDirectory().getAbsolutePath())) return;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        long day = localDay(now);
        Map<String, long[]> m = readHabits(sp);
        long[] v = m.remove(path);
        if (v == null) v = new long[48];
        if (v[24 + h] != day) {
            v[h]++;
            v[24 + h] = day;
        }
        m.put(path, v);
        while (m.size() > MAX_HABITS) {
            Iterator<String> it = m.keySet().iterator();
            it.next();
            it.remove();
        }
        writeHabits(sp, m);
    }

    private static String habitPick(SharedPreferences sp, long now) {
        Map<String, long[]> m = readHabits(sp);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        long today = localDay(now);
        String best = null;
        long bestN = 0;
        for (Map.Entry<String, long[]> e : m.entrySet()) {
            long[] v = e.getValue();
            boolean seenToday = false;
            for (int i = 0; i < 24; i++) if (v[24 + i] == today) seenToday = true;
            if (seenToday || !new File(e.getKey()).isDirectory()) continue;
            long n = v[(h + 23) % 24] + v[h] + v[(h + 1) % 24];
            if (n >= 3 && n > bestN) {
                best = e.getKey();
                bestN = n;
            }
        }
        return best;
    }

    static void setEnabled(Context c, boolean on) {
        SharedPreferences.Editor e = p(c).edit().putBoolean(K_ON, on);
        if (on) {
            e.remove(K_OFF);
            for (String k : p(c).getAll().keySet()) if (k.startsWith("rej_")) e.remove(k);
        }
        e.apply();
    }

    private static void reject(SharedPreferences sp, String cat) {
        int n = sp.getInt("rej_" + cat, 0) + 1;
        SharedPreferences.Editor e = sp.edit().putInt("rej_" + cat, n);
        if (n >= 2) {
            Set<String> off = new LinkedHashSet<>(sp.getStringSet(K_OFF, new LinkedHashSet<String>()));
            off.add(cat);
            e.putStringSet(K_OFF, off);
        }
        e.apply();
    }

    private static void accept(SharedPreferences sp, String cat) {
        sp.edit().remove("rej_" + cat).apply();
    }

    private static final class Tip {
        String id;
        String cat;
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
                    .setPositiveButton(t.yes, (d, w) -> {
                        accept(sp, t.cat);
                        t.action.run();
                    })
                    .setNeutralButton(R.string.as_later, (d, w) -> reject(sp, t.cat))
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
        SharedPreferences sp = p(c);
        int guard = SmartGuard.pending(c);
        if (guard > 0 && !off.contains("guard")) {
            Tip t = new Tip();
            t.id = "guard";
            t.cat = "guard";
            t.title = R.string.as_guard_t;
            t.message = c.getString(R.string.as_guard_m, guard);
            t.yes = R.string.as_guard_y;
            t.action = () -> h.openSmart("guard");
            return t;
        }
        if (deleted >= 8 && !off.contains("cleanup")) {
            Tip t = new Tip();
            t.id = "cleanup";
            t.cat = "cleanup";
            t.title = R.string.as_cleanup_t;
            t.message = c.getString(R.string.as_cleanup_m, deleted);
            t.yes = R.string.as_cleanup_y;
            t.action = () -> h.openSmart("clean");
            return t;
        }
        if (extracted >= 2 && !off.contains("archives")) {
            Tip t = new Tip();
            t.id = "archives";
            t.cat = "archives";
            t.title = R.string.as_arc_t;
            t.message = c.getString(R.string.as_arc_m, extracted);
            t.yes = R.string.as_arc_y;
            t.action = () -> h.openCategory(Cats.ARC);
            return t;
        }
        if (!off.contains("organize") && now - sp.getLong(K_ORG_AT, 0) > 3 * DAY) {
            int loose = SmartPlan.looseCount(SmartPlan.downloads());
            if (loose >= 25) {
                sp.edit().putLong(K_ORG_AT, now).apply();
                Tip t = new Tip();
                t.id = "organize";
                t.cat = "organize";
                t.title = R.string.as_org_t;
                t.message = c.getString(R.string.as_org_m, loose);
                t.yes = R.string.as_org_y;
                t.action = () -> h.openSmart("organize");
                return t;
            }
        }
        if (!off.contains("habit")) {
            final String hp = habitPick(sp, now);
            if (hp != null) {
                Tip t = new Tip();
                t.id = "habit";
                t.cat = "habit";
                t.title = R.string.as_habit_t;
                t.message = c.getString(R.string.as_habit_m, new File(hp).getName());
                t.yes = R.string.as_habit_y;
                t.action = () -> h.openFolder(hp);
                return t;
            }
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : visits.entrySet()) {
            File dir = new File(e.getKey());
            if (e.getValue() >= 4 && e.getValue() > bestN && dir.isDirectory() && !h.isFavorite(e.getKey())) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        if (best != null && !off.contains("fav")) {
            final String path = best;
            Tip t = new Tip();
            t.id = "fav:" + path;
            t.cat = "fav";
            t.title = R.string.as_fav_t;
            t.message = c.getString(R.string.as_fav_m, new File(path).getName(), bestN);
            t.yes = R.string.as_fav_y;
            t.action = () -> h.addFavorite(path);
            return t;
        }
        return null;
    }
}
