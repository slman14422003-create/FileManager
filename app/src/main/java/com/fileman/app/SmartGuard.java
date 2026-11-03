package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class SmartGuard {
    private SmartGuard() {
    }

    static final class Alert {
        final File f;
        final int level;
        final String reason;

        Alert(File f, int level, String reason) {
            this.f = f;
            this.level = level;
            this.reason = reason;
        }
    }

    private static final String PREFS = "smart_guard";
    private static final String K_SEEN = "seen";
    private static final String K_LAST = "last";
    private static final String K_COUNT = "count";
    private static final long RECENT = 30L * 24 * 60 * 60 * 1000;
    private static final long CHECK_GAP = 6L * 60 * 60 * 1000;
    private static final int MAX_APKS = 12;
    private static final int MAX_ALERTS = 50;

    private static final Pattern DISGUISED = Pattern.compile(
            "(?i).*\\.(pdf|docx?|xlsx?|pptx?|jpe?g|png|gif|webp|mp3|mp4|mkv|txt|zip|rar)\\.(apk|exe|bat|cmd|scr|vbs|msi|ps1|hta|jar|sh)$");
    private static final Set<String> HARD = new HashSet<>(Arrays.asList("apk", "exe", "scr", "bat", "cmd", "vbs",
            "msi", "ps1", "hta"));
    private static final Set<String> SOFT = new HashSet<>(Arrays.asList("jar", "sh", "dex", "lnk", "com"));

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<File> roots() {
        List<File> r = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        r.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        r.add(new File(ext, "Bluetooth"));
        r.add(new File(ext, "WhatsApp/Media/WhatsApp Documents"));
        r.add(new File(ext, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"));
        r.add(new File(ext, "Telegram/Telegram Documents"));
        r.add(new File(ext, "Documents"));
        return r;
    }

    static String key(File f) {
        return f.getAbsolutePath() + "@" + f.lastModified();
    }

    static List<Alert> scan(Context c, BooleanSupplier stop) {
        List<Alert> out = new ArrayList<>();
        int[] apks = {0};
        long now = System.currentTimeMillis();
        for (File root : roots()) walk(c, root, 0, now, out, apks, stop);
        Collections.sort(out, (a, b) -> a.level != b.level ? Integer.compare(b.level, a.level)
                : Long.compare(b.f.lastModified(), a.f.lastModified()));
        if (out.size() > MAX_ALERTS) return new ArrayList<>(out.subList(0, MAX_ALERTS));
        return out;
    }

    private static void walk(Context c, File dir, int depth, long now, List<Alert> out, int[] apks, BooleanSupplier stop) {
        if (stop.getAsBoolean() || depth > 2 || out.size() >= MAX_ALERTS) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (stop.getAsBoolean() || out.size() >= MAX_ALERTS) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) walk(c, f, depth + 1, now, out, apks, stop);
                continue;
            }
            if (now - f.lastModified() > RECENT || f.length() > 400L * 1024 * 1024) continue;
            Alert a = check(c, f, apks);
            if (a != null) out.add(a);
        }
    }

    private static Alert check(Context c, File f, int[] apks) {
        String name = f.getName();
        String ext = Cats.extOf(name);
        if (DISGUISED.matcher(name).matches()) {
            return new Alert(f, SecurityScan.HIGH, c.getString(R.string.sg_r_disguised));
        }
        if (ext.equals("apk") || ext.equals("apks") || ext.equals("xapk") || ext.equals("apkm")) {
            if (apks[0] >= MAX_APKS) return null;
            apks[0]++;
            try {
                PkgInstaller.Info info = PkgInstaller.inspect(c, f);
                SecurityScan.Result r = SecurityScan.scan(c, info);
                if (r.level < SecurityScan.CAUTION) return null;
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (SecurityScan.Finding fd : r.findings) {
                    if (fd.level < SecurityScan.CAUTION) continue;
                    if (n > 0) sb.append(" \u00b7 ");
                    sb.append(c.getString(fd.titleRes));
                    if (++n >= 3) break;
                }
                String reason = sb.length() == 0 ? c.getString(R.string.sg_r_apk) : sb.toString();
                return new Alert(f, r.level, reason);
            } catch (Throwable e) {
                return null;
            }
        }
        if (ext.equals("zip")) {
            try (ZipFile z = new ZipFile(f)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                int seen = 0;
                String found = null;
                int level = 0;
                while (en.hasMoreElements() && seen++ < 3000) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String x = Cats.extOf(e.getName().toLowerCase(Locale.ROOT));
                    int lv = HARD.contains(x) ? SecurityScan.HIGH : SOFT.contains(x) ? SecurityScan.CAUTION : 0;
                    if (lv > level) {
                        level = lv;
                        found = e.getName();
                    }
                }
                if (found != null) {
                    int slash = found.lastIndexOf('/');
                    String leaf = slash >= 0 ? found.substring(slash + 1) : found;
                    return new Alert(f, level, c.getString(R.string.sg_r_archive, leaf));
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static int pending(Context c) {
        return sp(c).getInt(K_COUNT, 0);
    }

    static void markSeen(Context c, List<Alert> alerts) {
        SharedPreferences sp = sp(c);
        Set<String> seen = new HashSet<>(sp.getStringSet(K_SEEN, new HashSet<String>()));
        for (Alert a : alerts) seen.add(key(a.f));
        if (seen.size() > 400) seen.clear();
        sp.edit().putStringSet(K_SEEN, seen).putInt(K_COUNT, 0).apply();
    }

    static void checkAsync(final Context c) {
        final Context app = c.getApplicationContext();
        if (!Perms.hasAllFiles(app)) return;
        final SharedPreferences sp = sp(app);
        long now = System.currentTimeMillis();
        if (now - sp.getLong(K_LAST, 0) < CHECK_GAP) return;
        sp.edit().putLong(K_LAST, now).apply();
        Thread t = new Thread(() -> {
            try {
                List<Alert> all = scan(app, () -> false);
                Set<String> seen = sp.getStringSet(K_SEEN, new HashSet<String>());
                int fresh = 0;
                for (Alert a : all) if (!seen.contains(key(a.f))) fresh++;
                sp.edit().putInt(K_COUNT, fresh).apply();
            } catch (Throwable ignored) {
            }
        }, "smart-guard");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
