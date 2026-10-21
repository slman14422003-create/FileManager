package com.fileman.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class SmartScan {
    private SmartScan() {
    }

    static final int K_DUP = 0, K_APK = 1, K_STALE = 2, K_JUNK = 3, K_EMPTY = 4;
    static final long DAY = 24L * 60 * 60 * 1000;
    static final long MIN_DUP = 16 * 1024;
    static final long BIG = 50L * 1024 * 1024;
    static final long STALE = 180L * DAY;
    private static final int BUDGET = 250000;
    private static final int MAX_ITEMS = 3000;

    private static final Set<String> KEEP_DIRS = new HashSet<>(Arrays.asList("camera", "download", "documents",
            "pictures", "music", "movies", "dcim", "screenshots", "recordings", "alarms", "notifications",
            "ringtones", "podcasts", "audiobooks", "android"));
    private static final String[] CHAT = {"whatsapp", "telegram", "messenger", "viber", "signal"};

    static final class Item {
        final File f;
        final int kind;
        final long size;
        final String note;
        boolean checked;

        Item(File f, int kind, long size, String note, boolean checked) {
            this.f = f;
            this.kind = kind;
            this.size = size;
            this.note = note;
            this.checked = checked;
        }
    }

    static final class Result {
        final List<Item> items = new ArrayList<>();
        boolean partial;
    }

    private static final class Ctx {
        final BooleanSupplier stop;
        final PackageManager pm;
        final long now = System.currentTimeMillis();
        final Map<Long, List<File>> chat = new HashMap<>();
        final List<Item> out = new ArrayList<>();
        int budget = BUDGET;
        int apks;

        Ctx(Context c, BooleanSupplier stop) {
            this.stop = stop;
            this.pm = c.getPackageManager();
        }
    }

    static Result scan(Context c, File root, BooleanSupplier stop) {
        Ctx x = new Ctx(c, stop);
        walk(x, root, 0, false);
        Result r = new Result();
        r.partial = x.budget <= 0;
        r.items.addAll(x.out);
        resolveDupes(x, r);
        Collections.sort(r.items, (a, b) -> a.kind != b.kind ? Integer.compare(a.kind, b.kind) : Long.compare(b.size, a.size));
        if (r.items.size() > MAX_ITEMS) r.items.subList(MAX_ITEMS, r.items.size()).clear();
        return r;
    }

    private static boolean isChat(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String k : CHAT) if (p.contains("/" + k) || p.contains("." + k) || p.contains("com." + k)) return true;
        return false;
    }

    private static void walk(Ctx x, File dir, int depth, boolean android) {
        if (x.stop.getAsBoolean() || x.budget <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (x.stop.getAsBoolean() || x.budget <= 0) return;
            String n = f.getName();
            if (n.startsWith(".")) {
                if (!android && n.equals(".thumbnails") && f.isDirectory()) thumbs(x, f);
                continue;
            }
            if (depth == 0 && n.equals("Android")) {
                File m = new File(f, "media");
                if (m.isDirectory()) walk(x, m, 1, true);
                continue;
            }
            x.budget--;
            if (f.isDirectory()) {
                if (Cats.isLink(f)) continue;
                walk(x, f, depth + 1, android);
                if (!android && depth >= 1 && !KEEP_DIRS.contains(n.toLowerCase(Locale.ROOT))) {
                    String[] kids = f.list();
                    if (kids != null && kids.length == 0) x.out.add(new Item(f, K_EMPTY, 0, "", true));
                }
            } else {
                visit(x, f, android);
            }
        }
    }

    private static void thumbs(Ctx x, File dir) {
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (x.stop.getAsBoolean()) return;
            if (f.isFile() && f.length() > 0) x.out.add(new Item(f, K_JUNK, f.length(), "", true));
        }
    }

    private static boolean isTemp(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("thumbs.db") || n.equals("desktop.ini") || n.startsWith("~$")) return true;
        String ext = Cats.extOf(n);
        return ext.equals("tmp") || ext.equals("temp") || ext.equals("bak") || ext.equals("chk") || ext.equals("dmp");
    }

    private static void visit(Ctx x, File f, boolean android) {
        long len = f.length();
        String name = f.getName();
        String path = f.getAbsolutePath();
        if (!android) {
            if (len == 0) {
                x.out.add(new Item(f, K_JUNK, 0, "", true));
                return;
            }
            if (isTemp(name)) {
                x.out.add(new Item(f, K_JUNK, len, "", true));
                return;
            }
        }
        String ext = Cats.extOf(name);
        if (!android && ext.equals("apk") && x.apks < 300) {
            x.apks++;
            String note = installedNote(x.pm, f);
            if (note != null) {
                x.out.add(new Item(f, K_APK, len, note, true));
                return;
            }
        }
        if (len >= MIN_DUP && isChat(path)) {
            List<File> l = x.chat.get(len);
            if (l == null) {
                l = new ArrayList<>(2);
                x.chat.put(len, l);
            }
            l.add(f);
        }
        if (!android && len >= BIG && x.now - lastUsed(f, x.now) >= STALE) {
            x.out.add(new Item(f, K_STALE, len, Fmt.ago(lastUsed(f, x.now)), false));
        }
    }

    private static long lastUsed(File f, long now) {
        long t = f.lastModified();
        try {
            long a = android.system.Os.stat(f.getPath()).st_atime * 1000L;
            if (a > t && a <= now) t = a;
        } catch (Throwable ignored) {
        }
        return t;
    }

    private static long code(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static String installedNote(PackageManager pm, File f) {
        try {
            PackageInfo arch = pm.getPackageArchiveInfo(f.getPath(), 0);
            if (arch == null || arch.packageName == null) return null;
            PackageInfo inst = pm.getPackageInfo(arch.packageName, 0);
            if (code(inst) >= code(arch)) return arch.packageName;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void resolveDupes(Ctx x, Result r) {
        for (Map.Entry<Long, List<File>> e : x.chat.entrySet()) {
            if (x.stop.getAsBoolean()) return;
            List<File> same = e.getValue();
            if (same.size() < 2) continue;
            Map<String, List<File>> byPartial = new HashMap<>();
            for (File f : same) {
                String h = partial(f, e.getKey(), x.stop);
                if (h != null) add(byPartial, h, f);
            }
            for (List<File> pl : byPartial.values()) {
                if (pl.size() < 2) continue;
                Map<String, List<File>> full = new HashMap<>();
                for (File f : pl) {
                    String h = full(f, x.stop);
                    if (h != null) add(full, h, f);
                }
                for (List<File> g : full.values()) {
                    if (g.size() < 2) continue;
                    Collections.sort(g, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                    File keep = g.get(0);
                    for (int i = 1; i < g.size(); i++) {
                        r.items.add(new Item(g.get(i), K_DUP, e.getKey(), keep.getName(), true));
                    }
                }
            }
        }
    }

    private static void add(Map<String, List<File>> m, String k, File f) {
        List<File> l = m.get(k);
        if (l == null) {
            l = new ArrayList<>(2);
            m.put(k, l);
        }
        l.add(f);
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    static String partial(File f, long size, BooleanSupplier stop) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int chunk = (int) Math.min(65536, size);
            byte[] buf = new byte[chunk];
            raf.readFully(buf);
            md.update(buf);
            if (size > chunk) {
                raf.seek(size - chunk);
                raf.readFully(buf);
                md.update(buf);
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    static String full(File f, BooleanSupplier stop) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 17];
            int r;
            while ((r = in.read(buf)) > 0) {
                if (stop.getAsBoolean()) return null;
                md.update(buf, 0, r);
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
