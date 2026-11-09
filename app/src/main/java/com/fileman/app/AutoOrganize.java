package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Plans a tidy-up of the whole shared storage. Nothing is moved here: {@link #plan} only returns the moves
 * so the screen can show them, and {@link SmartPlan#apply} performs the ones the user keeps (with undo).
 * Protected on purpose: the Android folder (system and app data), camera and system sound folders, hidden
 * items, projects and app folders (marker files), folders already organized, fresh and partial downloads.
 */
final class AutoOrganize {
    private AutoOrganize() {
    }

    private static final int MAX_MOVES = 4000;
    private static final long FRESH_MS = 3 * 60 * 1000L;
    private static final long GAP_MS = 10 * 60 * 1000L;
    private static final String PREFS = "auto_org";
    private static final String K_OFF = "off";
    private static final String K_LAST = "last";
    private static final String K_DEEP = "deep";

    private static final Set<String> SYSTEM_TOP = new HashSet<>(Arrays.asList(
            "Android", "DCIM", "Alarms", "Notifications", "Ringtones", "Podcasts", "Audiobooks", "LOST.DIR"));
    private static final Set<String> STANDARD = new HashSet<>(Arrays.asList(
            "Download", "Documents", "Pictures", "Movies", "Music"));
    private static final Set<String> MARKERS = new HashSet<>(Arrays.asList(
            ".nomedia", ".git", "build.gradle", "build.gradle.kts", "settings.gradle", "package.json", "pom.xml",
            "pubspec.yaml", "AndroidManifest.xml"));
    private static final Set<String> CATEGORY = new HashSet<>(Arrays.asList(
            "Images", "Videos", "Audio", "Archives", "Apps", "Documents", "Contacts", "WhatsApp", "Telegram", "Screenshots"));

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ settings

    static boolean autoDownloads(Context c) {
        return !sp(c).getBoolean(K_OFF, false);
    }

    static void setAutoDownloads(Context c, boolean on) {
        sp(c).edit().putBoolean(K_OFF, !on).apply();
    }

    static boolean deep(Context c) {
        return sp(c).getBoolean(K_DEEP, false);
    }

    static void setDeep(Context c, boolean on) {
        sp(c).edit().putBoolean(K_DEEP, on).apply();
    }

    /** Sorts new downloads by type in the background (at most once every ten minutes, never fresh or partial files). */
    static void sortDownloadsIfDue(Context c) {
        final Context app = c.getApplicationContext();
        if (!autoDownloads(app) || !Perms.hasAllFiles(app)) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = sp(app);
        if (now - p.getLong(K_LAST, 0L) < GAP_MS) return;
        p.edit().putLong(K_LAST, now).apply();
        Thread t = new Thread(() -> {
            try {
                List<SmartPlan.Move> moves = SmartPlan.organize(SmartPlan.downloads(), () -> false);
                if (!moves.isEmpty()) SmartPlan.apply(app, moves);
            } catch (Throwable ignored) {
            }
        }, "auto-sort");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ------------------------------------------------------------------ plan

    static List<SmartPlan.Move> plan(Context c, boolean deep, BooleanSupplier stop) {
        List<SmartPlan.Move> out = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        collect(root, 0, deep, out, new HashSet<String>(), System.currentTimeMillis(), root.getAbsolutePath(), stop);
        Collections.sort(out, (a, b) -> a.group.compareTo(b.group));
        return out;
    }

    private static void collect(File dir, int depth, boolean deep, List<SmartPlan.Move> out, Set<String> taken,
                                long now, String root, BooleanSupplier stop) {
        if (stop.getAsBoolean() || out.size() >= MAX_MOVES) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        List<File> files = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        for (File f : kids) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) dirs.add(f);
            } else if (f.isFile()) {
                files.add(f);
            }
        }
        boolean here = depth == 0 || (depth == 1 && STANDARD.contains(dir.getName())) || (deep && isMess(files));
        if (here) place(dir, files, out, taken, now, root, stop);
        if (depth >= (deep ? 3 : 1)) return;
        for (File d : dirs) {
            if (stop.getAsBoolean() || out.size() >= MAX_MOVES) return;
            String n = d.getName();
            boolean standardTop = depth == 0 && STANDARD.contains(n);
            if (depth == 0) {
                if (SYSTEM_TOP.contains(n)) continue;
                if (!deep && !standardTop) continue;
            } else if (CATEGORY.contains(n) || SmartIndex.topics().contains(n)) {
                continue;
            }
            if (!standardTop && isUnit(d)) continue;
            collect(d, depth + 1, deep, out, taken, now, root, stop);
        }
    }

    private static void place(File dir, List<File> files, List<SmartPlan.Move> out, Set<String> taken, long now,
                              String root, BooleanSupplier stop) {
        for (File f : files) {
            if (stop.getAsBoolean() || out.size() >= MAX_MOVES) return;
            String n = f.getName();
            if (n.indexOf('\t') >= 0 || n.indexOf('\n') >= 0) continue;
            if (now - f.lastModified() < FRESH_MS || SmartPlan.partialName(n)) continue;
            String rel = rel(f);
            if (rel == null || nativeKind(dir, rel)) continue;
            File folder = target(dir, rel);
            File to = SmartPlan.unique(new File(folder, n), taken);
            taken.add(to.getAbsolutePath());
            String label = folder.getAbsolutePath();
            label = label.startsWith(root) ? label.substring(root.length()) : label;
            while (label.startsWith("/")) label = label.substring(1);
            out.add(new SmartPlan.Move(f, to, label.isEmpty() ? rel : label));
        }
    }

    /** Folder name for a file: its kind, and for documents the topic read from the name (invoices, contracts...). */
    private static String rel(File f) {
        String base = SmartPlan.folderFor(f.getName());
        if (base == null) return null;
        if (base.equals("Documents")) {
            String topic = SmartIndex.topicFolder(f.getName());
            if (topic != null) return "Documents/" + topic;
        }
        return base;
    }

    /** Photos stay in Pictures, videos in Movies and music in Music: those folders already mean that kind. */
    private static boolean nativeKind(File dir, String rel) {
        String d = dir.getName();
        return (d.equals("Pictures") && rel.equals("Images")) || (d.equals("Movies") && rel.equals("Videos"))
                || (d.equals("Music") && rel.equals("Audio"));
    }

    private static File target(File dir, String rel) {
        File t = dir;
        for (String seg : rel.split("/")) {
            if (seg.equalsIgnoreCase(t.getName())) continue;
            t = new File(t, seg);
        }
        return t;
    }

    /** A folder of loose files of several kinds: worth sorting in place (deep mode only). */
    private static boolean isMess(List<File> files) {
        if (files.size() < 8) return false;
        Set<String> kinds = new HashSet<>();
        for (File f : files) {
            String k = SmartPlan.folderFor(f.getName());
            if (k != null) kinds.add(k);
        }
        return kinds.size() >= 3;
    }

    /** Projects and app folders are one unit: never reshuffled. */
    private static boolean isUnit(File d) {
        for (String m : MARKERS) if (new File(d, m).exists()) return true;
        return false;
    }
}
