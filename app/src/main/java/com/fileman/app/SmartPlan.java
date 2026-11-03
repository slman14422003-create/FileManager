package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

final class SmartPlan {
    private SmartPlan() {
    }

    static final class Move {
        final File from;
        final File to;
        final String group;
        boolean checked = true;

        Move(File from, File to, String group) {
            this.from = from;
            this.to = to;
            this.group = group;
        }
    }

    private static final String PREFS = "smart_plan";
    private static final String K_JOURNAL = "journal";
    private static final long FRESH_MS = 3 * 60 * 1000L;

    private static final Pattern WA = Pattern.compile("(?i)^(IMG|VID|AUD|PTT|DOC|STK)-\\d{8}-WA\\d+");
    private static final Pattern TG = Pattern.compile(
            "(?i)^(photo|video|audio|file|voice|animation)_\\d{4}-\\d{2}-\\d{2}|^(photo|video|audio|file)_\\d+@\\d{2}-\\d{2}-\\d{4}");
    private static final Pattern DIGITS = Pattern.compile("^[\\d\\s_\\-.()]+$");
    private static final Pattern GENERIC = Pattern.compile(
            "(?i)^(img|vid|pxl|dsc|dscn|dscf|mvimg|image|photo|picture|video|scan|scanned|download|document|doc|file|untitled|unnamed|"
                    + "whatsapp image|whatsapp video|whatsapp audio|whatsapp document|screenshot|screen shot|audio|recording|voice)"
                    + "[\\s_\\-.]*[\\d\\s_\\-.()atpm]*$");

    static File downloads() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    static int looseCount(File dir) {
        File[] arr = dir.listFiles();
        if (arr == null) return 0;
        int n = 0;
        for (File f : arr) if (f.isFile() && !f.getName().startsWith(".")) n++;
        return n;
    }

    private static String folderFor(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        if (WA.matcher(name).find() || low.contains("whatsapp")) return "WhatsApp";
        if (TG.matcher(name).find() || low.startsWith("telegram")) return "Telegram";
        if (low.contains("screenshot") || low.startsWith("screen_shot")) return "Screenshots";
        switch (Cats.typeOfExt(Cats.extOf(name))) {
            case Cats.T_IMG:
                return "Images";
            case Cats.T_VID:
                return "Videos";
            case Cats.T_AUD:
                return "Audio";
            case Cats.T_ARC:
                return "Archives";
            case Cats.T_APK:
                return "Apps";
            case Cats.T_PDF:
            case Cats.T_DOC:
            case Cats.T_TXT:
                return "Documents";
            case Cats.T_CONTACT:
                return "Contacts";
            default:
                return null;
        }
    }

    private static boolean partialName(String name) {
        String e = Cats.extOf(name);
        return e.equals("crdownload") || e.equals("part") || e.equals("download") || e.equals("tmp")
                || e.equals("temp") || e.equals("opdownload") || e.equals("partial");
    }

    static File unique(File dest, Set<String> taken) {
        if (!dest.exists() && !taken.contains(dest.getAbsolutePath())) return dest;
        String n = dest.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        String ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dest.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists() && !taken.contains(c.getAbsolutePath())) return c;
        }
        return dest;
    }

    static List<Move> organize(File dir, BooleanSupplier stop) {
        List<Move> out = new ArrayList<>();
        File[] arr = dir.listFiles();
        if (arr == null) return out;
        long now = System.currentTimeMillis();
        Set<String> taken = new HashSet<>();
        for (File f : arr) {
            if (stop.getAsBoolean()) return out;
            String n = f.getName();
            if (!f.isFile() || n.startsWith(".") || partialName(n) || now - f.lastModified() < FRESH_MS) continue;
            if (n.indexOf('\t') >= 0 || n.indexOf('\n') >= 0) continue;
            String folder = folderFor(n);
            if (folder == null) continue;
            File to = unique(new File(new File(dir, folder), n), taken);
            taken.add(to.getAbsolutePath());
            out.add(new Move(f, to, folder));
        }
        Collections.sort(out, (a, b) -> a.group.compareTo(b.group));
        return out;
    }

    static boolean isGeneric(String base) {
        return DIGITS.matcher(base).matches() || GENERIC.matcher(base).matches();
    }

    static List<File> renameRoots() {
        List<File> r = new ArrayList<>();
        r.add(downloads());
        r.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        r.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        return r;
    }

    static List<Move> renames(List<File> roots, BooleanSupplier stop) {
        List<Move> out = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (File root : roots) walkRename(root, 0, out, taken, stop);
        return out;
    }

    private static void walkRename(File dir, int depth, List<Move> out, Set<String> taken, BooleanSupplier stop) {
        if (stop.getAsBoolean() || depth > 2 || out.size() >= 500) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (stop.getAsBoolean() || out.size() >= 500) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) walkRename(f, depth + 1, out, taken, stop);
                continue;
            }
            int dot = n.lastIndexOf('.');
            if (dot <= 0 || n.indexOf('\t') >= 0 || n.indexOf('\n') >= 0) continue;
            String base = n.substring(0, dot);
            if (!isGeneric(base)) continue;
            String ext = n.substring(dot);
            String name = propose(f, ext);
            if (name == null || name.equals(n)) continue;
            File to = unique(new File(f.getParentFile(), name), taken);
            taken.add(to.getAbsolutePath());
            out.add(new Move(f, to, f.getParentFile().getName()));
        }
    }

    private static String propose(File f, String dotExt) {
        String ext = Cats.extOf(f.getName());
        int type = Cats.typeOfExt(ext);
        String title = SmartText.titleOf(f);
        if (title != null) return title + dotExt;
        long when = f.lastModified();
        if (type == Cats.T_IMG && (ext.equals("jpg") || ext.equals("jpeg"))) {
            try {
                String d = new ExifInterface(f.getAbsolutePath()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                if (d != null) {
                    Date p = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(d);
                    if (p != null) when = p.getTime();
                }
            } catch (Throwable ignored) {
            }
        }
        String prefix;
        switch (type) {
            case Cats.T_IMG:
                prefix = "Photo";
                break;
            case Cats.T_VID:
                prefix = "Video";
                break;
            case Cats.T_AUD:
                prefix = "Audio";
                break;
            case Cats.T_PDF:
            case Cats.T_DOC:
            case Cats.T_TXT:
                prefix = "Document";
                break;
            case Cats.T_ARC:
                prefix = "Archive";
                break;
            case Cats.T_APK:
                prefix = "App";
                break;
            default:
                prefix = "File";
        }
        return prefix + " " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date(when)) + dotExt;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int apply(Context c, List<Move> moves) {
        StringBuilder journal = new StringBuilder();
        List<String> paths = new ArrayList<>();
        int ok = 0;
        for (Move m : moves) {
            if (!m.checked || !m.from.exists()) continue;
            File parent = m.to.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) continue;
            File to = m.to.exists() ? unique(m.to, new HashSet<String>()) : m.to;
            if (m.from.renameTo(to)) {
                ok++;
                journal.append(m.from.getAbsolutePath()).append('\t').append(to.getAbsolutePath()).append('\n');
                paths.add(m.from.getAbsolutePath());
                paths.add(to.getAbsolutePath());
            }
        }
        if (ok > 0) sp(c).edit().putString(K_JOURNAL, journal.toString()).apply();
        scan(c, paths);
        return ok;
    }

    static int journalSize(Context c) {
        String j = sp(c).getString(K_JOURNAL, "");
        if (j.isEmpty()) return 0;
        return j.split("\n").length;
    }

    static int undo(Context c) {
        String j = sp(c).getString(K_JOURNAL, "");
        if (j.isEmpty()) return 0;
        String[] lines = j.split("\n");
        List<String> paths = new ArrayList<>();
        int ok = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] p = lines[i].split("\t");
            if (p.length != 2) continue;
            File from = new File(p[0]);
            File to = new File(p[1]);
            if (!to.exists() || from.exists()) continue;
            File parent = from.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (to.renameTo(from)) {
                ok++;
                paths.add(from.getAbsolutePath());
                paths.add(to.getAbsolutePath());
                File old = to.getParentFile();
                String[] kids = old == null ? null : old.list();
                if (old != null && kids != null && kids.length == 0 && !old.equals(parent)) old.delete();
            }
        }
        sp(c).edit().remove(K_JOURNAL).apply();
        scan(c, paths);
        return ok;
    }

    static void scan(Context c, List<String> paths) {
        if (paths.isEmpty()) return;
        try {
            MediaScannerConnection.scanFile(c.getApplicationContext(), paths.toArray(new String[0]), null, null);
        } catch (Throwable ignored) {
        }
    }
}
