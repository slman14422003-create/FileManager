package com.fileman.app;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * File-theft guard. Hidden decoy files ("canaries") are planted in the folders thieves scan first. This app never
 * opens them, and on Android 11+ only an app holding "all files access" can open a file in a hidden shared-storage
 * folder, so an open/read event on a decoy means another app (or process) is sweeping the user's files. The guard then
 * names the apps that could have done it, raises a heads-up notification and kills their processes.
 */
final class GuardCore {
    private GuardCore() {
    }

    static final class Suspect {
        final String pkg;
        final String label;
        final boolean foreground;

        Suspect(String pkg, String label, boolean foreground) {
            this.pkg = pkg;
            this.label = label;
            this.foreground = foreground;
        }
    }

    private static final String PREFS = "guard";
    private static final String CH_ALERT = "guard_alert";
    private static final int ID_ALERT = 7100;
    private static final int ID_WIPED = 7101;
    private static final Pattern PKG = Pattern.compile("[A-Za-z0-9_.]{1,200}");
    private static final String[] BASES = {"", "Download", "Documents", "DCIM", "Pictures"};
    private static final String[] DECOYS = {"passwords.txt", "private_backup.zip"};
    private static final SecureRandom RNG = new SecureRandom();

    private static final List<FileObserver> observers = new ArrayList<>();
    private static final ExecutorService worker = Bg.single("fm-bg");
    private static final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
    private static volatile long lastTrip;
    private static boolean running;

    // ------------------------------------------------------------------ settings

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean pauseOnSaver(Context c) {
        return sp(c).getBoolean("saver_pause", false);
    }

    static void setPauseOnSaver(Context c, boolean v) {
        sp(c).edit().putBoolean("saver_pause", v).apply();
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("on", true);
    }

    static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("on", v).apply();
        if (v) GuardService.ensure(c);
        else GuardService.halt(c);
    }

    static boolean autoKill(Context c) {
        return sp(c).getBoolean("kill", true);
    }

    static void setAutoKill(Context c, boolean v) {
        sp(c).edit().putBoolean("kill", v).apply();
    }

    static boolean useRoot(Context c) {
        return sp(c).getBoolean("root", true);
    }

    static void setUseRoot(Context c, boolean v) {
        sp(c).edit().putBoolean("root", v).apply();
    }

    static boolean wipeVaultOnTrip(Context c) {
        return sp(c).getBoolean("vault_trip", false);
    }

    static void setWipeVaultOnTrip(Context c, boolean v) {
        sp(c).edit().putBoolean("vault_trip", v).apply();
    }

    static Set<String> trusted(Context c) {
        return new HashSet<>(sp(c).getStringSet("trusted", new HashSet<String>()));
    }

    static void trust(Context c, String pkg, boolean on) {
        if (pkg == null || !PKG.matcher(pkg).matches()) return;
        Set<String> t = trusted(c);
        if (on) t.add(pkg);
        else t.remove(pkg);
        sp(c).edit().putStringSet("trusted", t).apply();
    }

    // ------------------------------------------------------------------ log

    static synchronized void log(Context c, String line) {
        try {
            SharedPreferences s = sp(c);
            String old = s.getString("log", "");
            String row = System.currentTimeMillis() + "|" + line.replace('\n', ' ').replace('|', '/');
            String[] parts = old.isEmpty() ? new String[0] : old.split("\n");
            StringBuilder sb = new StringBuilder(row);
            for (int i = 0; i < Math.min(parts.length, 29); i++) sb.append('\n').append(parts[i]);
            s.edit().putString("log", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Newest first; each entry is {time millis, text}. */
    static List<String[]> readLog(Context c) {
        List<String[]> out = new ArrayList<>();
        String raw = sp(c).getString("log", "");
        if (raw.isEmpty()) return out;
        for (String l : raw.split("\n")) {
            int i = l.indexOf('|');
            if (i > 0) out.add(new String[]{l.substring(0, i), l.substring(i + 1)});
        }
        return out;
    }

    // ------------------------------------------------------------------ decoys and watching

    static boolean canRun(Context c) {
        if (pauseOnSaver(c)) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isPowerSaveMode()) return false;
            } catch (Throwable ignored) {
            }
        }
        return enabled(c) && Perms.hasAllFiles(c);
    }

    private static List<File> plant() {
        List<File> out = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        for (String b : BASES) {
            try {
                File base = b.isEmpty() ? ext : new File(ext, b);
                if (!base.isDirectory() && !base.mkdirs()) continue;
                File d = new File(base, ".fm_guard");
                if (!d.isDirectory() && !d.mkdirs()) continue;
                File nm = new File(d, ".nomedia");
                if (!nm.exists()) nm.createNewFile();
                for (String n : DECOYS) {
                    File f = new File(d, n);
                    if (!f.exists()) {
                        byte[] junk = new byte[1500 + RNG.nextInt(900)];
                        RNG.nextBytes(junk);
                        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (32 + ((junk[i] & 0xff) % 90));
                        try (FileOutputStream o = new FileOutputStream(f)) {
                            o.write(junk);
                        }
                    }
                    out.add(f);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    static synchronized void start(final Context c) {
        final Context app = c.getApplicationContext();
        Vault.watch(app);
        if (running || !canRun(app)) return;
        List<File> decoys = plant();
        stopWatching();
        for (final File f : decoys) {
            try {
                FileObserver o = new FileObserver(f.getAbsolutePath(), FileObserver.OPEN | FileObserver.ACCESS) {
                    @Override
                    public void onEvent(int event, String p) {
                        trip(app, false);
                    }
                };
                o.startWatching();
                observers.add(o);
            } catch (Throwable ignored) {
            }
        }
        running = !observers.isEmpty();
    }

    static synchronized void stop() {
        stopWatching();
        running = false;
    }

    private static void stopWatching() {
        for (FileObserver o : observers) {
            try {
                o.stopWatching();
            } catch (Throwable ignored) {
            }
        }
        observers.clear();
    }

    static boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ response

    static void trip(final Context c, final boolean test) {
        long now = SystemClock.elapsedRealtime();
        if (!test && now - lastTrip < 5000) return;
        lastTrip = now;
        worker.execute(() -> {
            try {
                respond(c.getApplicationContext(), test);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void respond(Context c, boolean test) {
        List<Suspect> s = identify(c);
        if (test) {
            if (s.isEmpty()) s.add(new Suspect("test.app", c.getString(R.string.g_test_name), false));
        }
        if (s.isEmpty()) {
            log(c, c.getString(R.string.g_log_unknown));
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Suspect x : s) {
            if (names.length() > 0) names.append("، ");
            names.append(x.label);
        }
        boolean kill = !test && autoKill(c);
        log(c, c.getString(kill ? R.string.g_log_killed : R.string.g_log_detected, names.toString()));
        notifyTheft(c, s, kill);
        if (kill) killLoop(c, s);
        if (!test) {
            Vault.clearTemps(c);
            if (wipeVaultOnTrip(c)) Vault.destroyAll(c, "theft guard");
        }
    }

    private static boolean canReadAll(Context c, PackageManager pm, ApplicationInfo ai) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
                if (ao == null) return false;
                int mode = ao.unsafeCheckOpNoThrow("android:manage_external_storage", ai.uid, ai.packageName);
                return mode == AppOpsManager.MODE_ALLOWED;
            }
            return pm.checkPermission("android.permission.READ_EXTERNAL_STORAGE", ai.packageName)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Packages with usage events in the last three minutes, or null when usage access was not granted. */
    private static Map<String, Boolean> recent(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ao == null) return null;
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ao.unsafeCheckOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.getPackageName())
                    : ao.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) return null;
            UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (um == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents ev = um.queryEvents(now - 180000L, now);
            Map<String, Boolean> out = new HashMap<>();
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                boolean fg = t == 1 || t == 19;
                boolean bg = t == 2 || t == 20;
                if (!fg && !bg) continue;
                out.put(e.getPackageName(), fg);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean usageGranted(Context c) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int m = ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName());
            return m == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    static List<Suspect> identify(Context c) {
        List<Suspect> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            Set<String> trusted = trusted(c);
            Map<String, Boolean> rec = recent(c);
            List<Suspect> all = new ArrayList<>();
            List<Suspect> seen = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String p = ai.packageName;
                if (p.equals(c.getPackageName()) || trusted.contains(p)) continue;
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (sys || !canReadAll(c, pm, ai)) continue;
                boolean fg = rec != null && Boolean.TRUE.equals(rec.get(p));
                Suspect s = new Suspect(p, String.valueOf(pm.getApplicationLabel(ai)), fg);
                all.add(s);
                if (rec != null && rec.containsKey(p)) seen.add(s);
            }
            out = seen.isEmpty() ? all : seen;
            if (out.size() > 8) out = new ArrayList<>(out.subList(0, 8));
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ kill

    private static void killLoop(final Context c, final List<Suspect> s) {
        boolean anyFg = false;
        for (Suspect x : s) if (x.foreground) anyFg = true;
        if (anyFg) {
            try {
                Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(h);   // pushes a foreground thief to the background so it can be killed
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < 12; i++) {
            sched.schedule(() -> {
                for (Suspect x : s) kill(c, x.pkg);
            }, i * 1200L, TimeUnit.MILLISECONDS);
        }
    }

    static void kill(Context c, String pkg) {
        if (pkg == null || !PKG.matcher(pkg).matches() || pkg.equals(c.getPackageName())) return;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        } catch (Throwable ignored) {
        }
        if (useRoot(c)) rootStop(pkg);
    }

    /** Hard stop through su when the phone is rooted; silently does nothing otherwise. */
    private static void rootStop(String pkg) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
            final Process fp = p;
            Thread t = new Thread(() -> {
                try {
                    fp.waitFor();
                } catch (Throwable ignored) {
                }
            }, "guard-su");
            t.start();
            t.join(3000);
        } catch (Throwable ignored) {
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ------------------------------------------------------------------ notifications

    private static void channel(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ALERT, c.getString(R.string.g_channel_alert),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
    }

    private static void notifyTheft(Context c, List<Suspect> s, boolean killed) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            channel(c, nm);
            Suspect first = s.get(0);
            StringBuilder names = new StringBuilder();
            for (Suspect x : s) {
                if (names.length() > 0) names.append("، ");
                names.append(x.label);
            }
            String text = c.getString(killed ? R.string.g_notif_text_killed : R.string.g_notif_text, names.toString());
            Intent trust = new Intent(c, GuardService.class).setAction(GuardService.ACT_TRUST)
                    .putExtra(GuardService.EXTRA_PKG, first.pkg);
            PendingIntent pt = PendingIntent.getService(c, 31, trust,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent po = PendingIntent.getActivity(c, 32, new Intent(c, GuardActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_lock_fill)
                    .setContentTitle(c.getString(R.string.g_notif_title))
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setContentIntent(po)
                    .addAction(0, c.getString(R.string.g_notif_trust, first.label), pt)
                    .addAction(0, c.getString(R.string.g_notif_open), po);
            nm.notify(ID_ALERT, b.build());
        } catch (Throwable ignored) {
        }
    }

    static void notifyVaultWiped(Context c, int n) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            channel(c, nm);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_lock_fill)
                    .setContentTitle(c.getString(R.string.v_wiped_title))
                    .setContentText(c.getString(R.string.v_wiped_text, n))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setAutoCancel(true);
            nm.notify(ID_WIPED, b.build());
        } catch (Throwable ignored) {
        }
    }

    static void clearAlert(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID_ALERT);
        } catch (Throwable ignored) {
        }
    }

    /** Distinct labels of the apps that currently hold broad file access and are not trusted (shown in the guard screen). */
    static List<String> watchedApps(Context c) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Suspect s : identifyAllReaders(c)) if (seen.add(s.pkg)) out.add(s.label);
        return out;
    }

    private static List<Suspect> identifyAllReaders(Context c) {
        List<Suspect> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName.equals(c.getPackageName())) continue;
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (sys || !canReadAll(c, pm, ai)) continue;
                out.add(new Suspect(ai.packageName, String.valueOf(pm.getApplicationLabel(ai)), false));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
