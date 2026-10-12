package com.fileman.app;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.LruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Directory snapshots kept in memory and in temp files so folders open instantly and are re-checked in the background. */
final class FsCache {
    static final int F_DIR = 1, F_LINK = 2;
    static final int KIDS_UNKNOWN = -2;
    private static final int VERSION = 4;
    private static final int MEM_NODES = 400_000;
    private static final int PERSIST_MIN = 60;
    private static final int PARALLEL_MIN = 2500;
    private static final long DISK_MAX = 96L << 20;
    private static final long FRESH_MS = 2000;

    static final class Snap {
        final String path;
        final long dirMod;
        final long at;
        final long sig;
        final String[] name;
        final byte[] flag;
        final long[] size;
        final long[] mod;
        final int[] kids;

        Snap(String path, long dirMod, long at, String[] name, byte[] flag, long[] size, long[] mod, int[] kids) {
            this.path = path;
            this.dirMod = dirMod;
            this.at = at;
            this.name = name;
            this.flag = flag;
            this.size = size;
            this.mod = mod;
            this.kids = kids;
            long h = 1125899906842597L;
            for (int i = 0; i < name.length; i++) {
                h = 31 * h + name[i].hashCode();
                h = 31 * h + size[i];
                h = 31 * h + mod[i];
                h = 31 * h + flag[i];
            }
            this.sig = h;
        }

        int count() {
            return name.length;
        }

        boolean fresh() {
            return System.currentTimeMillis() - at < FRESH_MS;
        }
    }

    private static volatile FsCache inst;

    static FsCache get(Context c) {
        if (inst == null) {
            synchronized (FsCache.class) {
                if (inst == null) inst = new FsCache(c.getApplicationContext());
            }
        }
        return inst;
    }

    private final File disk;
    private final LruCache<String, Snap> mem = new LruCache<String, Snap>(MEM_NODES) {
        @Override
        protected int sizeOf(String key, Snap s) {
            return Math.max(1, s.count());
        }
    };
    private final ConcurrentHashMap<String, Long> persisted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Snap> pendingWrite = new ConcurrentHashMap<>();
    private final AtomicBoolean writing = new AtomicBoolean();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger warmToken = new AtomicInteger();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> low(r, "fs-cache-write"));
    private final ExecutorService warmer = Executors.newSingleThreadExecutor(r -> low(r, "fs-cache-warm"));

    private static Thread low(Runnable r, String n) {
        Thread t = new Thread(r, n);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

    private FsCache(Context app) {
        disk = new File(app.getCacheDir(), "fsindex");
        //noinspection ResultOfMethodCallIgnored
        disk.mkdirs();
    }

    // ------------------------------------------------------------------ stat helpers

    static String child(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** One lstat (plus one stat for links): out = {flags, size, mtime millis}. False when the entry is gone. */
    static boolean stat(String path, long[] out) {
        try {
            StructStat s = Os.lstat(path);
            int fl = 0;
            if (OsConstants.S_ISLNK(s.st_mode)) {
                fl |= F_LINK;
                try {
                    s = Os.stat(path);
                } catch (ErrnoException ignored) {
                }
            }
            if (OsConstants.S_ISDIR(s.st_mode)) fl |= F_DIR;
            out[0] = fl;
            out[1] = s.st_size;
            out[2] = s.st_mtime * 1000L;
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    static boolean isRealDir(String path) {
        try {
            return OsConstants.S_ISDIR(Os.lstat(path).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    static boolean isLink(String path) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(path).st_mode);
        } catch (ErrnoException e) {
            return true;
        }
    }

    static boolean lexists(String path) {
        try {
            Os.lstat(path);
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    static long dirMod(String path) {
        long[] o = new long[3];
        return stat(path, o) ? o[2] : -1;
    }

    // ------------------------------------------------------------------ scanning

    /** Lists one folder from scratch. Null when it cannot be read. Child folder counts carry over from prev. */
    Snap scan(File dir, Snap prev) {
        final String p = dir.getAbsolutePath();
        final String[] names = dir.list();
        if (names == null) return null;
        final long dm = dirMod(p);
        final int n = names.length;
        final byte[] fl = new byte[n];
        final long[] sz = new long[n];
        final long[] md = new long[n];
        final boolean[] ok = new boolean[n];
        if (n >= PARALLEL_MIN) {
            IntStream.range(0, n).parallel().forEach(i -> fill(p, names[i], i, fl, sz, md, ok));
        } else {
            for (int i = 0; i < n; i++) fill(p, names[i], i, fl, sz, md, ok);
        }
        int good = 0;
        for (boolean b : ok) if (b) good++;
        String[] nm = names;
        byte[] f2 = fl;
        long[] s2 = sz;
        long[] m2 = md;
        if (good != n) {
            nm = new String[good];
            f2 = new byte[good];
            s2 = new long[good];
            m2 = new long[good];
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (!ok[i]) continue;
                nm[k] = names[i];
                f2[k] = fl[i];
                s2[k] = sz[i];
                m2[k] = md[i];
                k++;
            }
        }
        int[] kd = new int[good];
        Arrays.fill(kd, KIDS_UNKNOWN);
        if (prev != null && prev.count() > 0) {
            Map<String, Integer> idx = new HashMap<>(prev.count() * 2);
            for (int j = 0; j < prev.count(); j++) idx.put(prev.name[j], j);
            for (int i = 0; i < good; i++) {
                if ((f2[i] & F_DIR) == 0) continue;
                Integer j = idx.get(nm[i]);
                if (j != null && prev.mod[j] == m2[i]) kd[i] = prev.kids[j];
            }
        }
        return new Snap(p, dm, System.currentTimeMillis(), nm, f2, s2, m2, kd);
    }

    private static void fill(String p, String name, int i, byte[] fl, long[] sz, long[] md, boolean[] ok) {
        long[] o = new long[3];
        if (stat(child(p, name), o)) {
            fl[i] = (byte) o[0];
            sz[i] = o[1];
            md[i] = o[2];
            ok[i] = true;
        }
    }

    // ------------------------------------------------------------------ memory + disk

    Snap peek(String path) {
        Snap s = mem.get(path);
        if (s != null) return s;
        s = readDisk(path);
        if (s != null) {
            mem.put(path, s);
            persisted.put(path, s.sig);
        }
        return s;
    }

    void put(Snap s) {
        mem.put(s.path, s);
        if (s.count() >= PERSIST_MIN) queueWrite(s);
    }

    /** Snapshot that is known to match the folder's current listing (rescans when the folder changed). */
    Snap ensure(String path) {
        long dm = dirMod(path);
        Snap s = peek(path);
        if (s != null && dm >= 0 && s.dirMod == dm && s.at - dm > 2000) return s;
        Snap n = scan(new File(path), s);
        if (n != null) put(n);
        return n;
    }

    int countKids(String path) {
        Snap s = mem.get(path);
        if (s != null) return s.count();
        String[] l = new File(path).list();
        return l == null ? -1 : l.length;
    }

    void flush(Snap s) {
        persisted.remove(s.path);
        if (s.count() >= PERSIST_MIN) queueWrite(s);
    }

    // ------------------------------------------------------------------ invalidation

    void invalidateDir(File d) {
        String p = d.getAbsolutePath();
        mem.remove(p);
        persisted.remove(p);
        pendingWrite.remove(p);
        //noinspection ResultOfMethodCallIgnored
        diskFile(p).delete();
    }

    void invalidateTree(File f) {
        String p = f.getAbsolutePath();
        String pre = p.endsWith("/") ? p : p + "/";
        ArrayList<String> keys = new ArrayList<>(mem.snapshot().keySet());
        keys.addAll(persisted.keySet());
        for (String k : keys) if (k.equals(p) || k.startsWith(pre)) invalidateDir(new File(k));
        invalidateDir(f);
    }

    /** Something under f was created, removed, renamed or rewritten. */
    void changed(File f) {
        invalidateTree(f);
        File par = f.getParentFile();
        if (par != null) invalidateDir(par);
    }

    // ------------------------------------------------------------------ background warm-up

    /** Quietly caches the sub-folders of dir so opening them is instant. */
    void warm(final File dir, final boolean hidden, final int maxDirs) {
        final int token = warmToken.incrementAndGet();
        warmer.execute(() -> {
            Snap s = peek(dir.getAbsolutePath());
            if (s == null) return;
            int done = 0;
            for (int i = 0; i < s.count(); i++) {
                if (token != warmToken.get() || done >= maxDirs) return;
                int f = s.flag[i];
                if ((f & F_DIR) == 0 || (f & F_LINK) != 0) continue;
                if (!hidden && s.name[i].startsWith(".")) continue;
                try {
                    ensure(child(s.path, s.name[i]));
                } catch (Throwable ignored) {
                }
                done++;
            }
        });
    }

    void stopWarm() {
        warmToken.incrementAndGet();
    }

    // ------------------------------------------------------------------ temp files

    private File diskFile(String path) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < path.length(); i++) {
            h ^= path.charAt(i);
            h *= 0x100000001b3L;
        }
        return new File(disk, Long.toHexString(h));
    }

    private void queueWrite(Snap s) {
        Long done = persisted.get(s.path);
        if (done != null && done == s.sig) return;
        pendingWrite.put(s.path, s);
        if (writing.compareAndSet(false, true)) writer.execute(this::drain);
    }

    private void drain() {
        try {
            while (true) {
                Iterator<Map.Entry<String, Snap>> it = pendingWrite.entrySet().iterator();
                if (!it.hasNext()) break;
                Map.Entry<String, Snap> e = it.next();
                Snap s = e.getValue();
                pendingWrite.remove(e.getKey(), s);
                writeDisk(s);
            }
        } finally {
            writing.set(false);
            if (!pendingWrite.isEmpty() && writing.compareAndSet(false, true)) writer.execute(this::drain);
        }
    }

    private void writeDisk(Snap s) {
        File f = diskFile(s.path);
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024))) {
            o.writeInt(VERSION);
            o.writeUTF(s.path);
            o.writeLong(s.dirMod);
            o.writeLong(s.at);
            int n = s.count();
            o.writeInt(n);
            for (int i = 0; i < n; i++) {
                o.writeUTF(s.name[i]);
                o.writeByte(s.flag[i]);
                o.writeLong(s.size[i]);
                o.writeLong(s.mod[i]);
                o.writeInt(s.kids[i]);
            }
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (tmp.renameTo(f)) persisted.put(s.path, s.sig);
        else //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        if (writes.incrementAndGet() % 100 == 0) prune();
    }

    private Snap readDisk(String path) {
        File f = diskFile(path);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 64 * 1024))) {
            if (in.readInt() != VERSION) throw new java.io.IOException();
            if (!path.equals(in.readUTF())) return null;
            long dm = in.readLong();
            long at = in.readLong();
            int n = in.readInt();
            if (n < 0 || n > 5_000_000) throw new java.io.IOException();
            String[] nm = new String[n];
            byte[] fl = new byte[n];
            long[] sz = new long[n];
            long[] md = new long[n];
            int[] kd = new int[n];
            for (int i = 0; i < n; i++) {
                nm[i] = in.readUTF();
                fl[i] = in.readByte();
                sz[i] = in.readLong();
                md[i] = in.readLong();
                kd[i] = in.readInt();
            }
            return new Snap(path, dm, at, nm, fl, sz, md, kd);
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    private void prune() {
        File[] all = disk.listFiles();
        if (all == null) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= DISK_MAX) return;
        Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long target = DISK_MAX * 7 / 10;
        for (File f : all) {
            if (total <= target) break;
            long l = f.length();
            if (f.delete()) total -= l;
        }
        persisted.clear();
    }
}
