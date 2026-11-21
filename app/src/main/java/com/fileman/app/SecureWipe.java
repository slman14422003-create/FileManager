package com.fileman.app;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional permanent delete, done in three separate stages over the whole selection:
 * <ol>
 *   <li><b>Corrupt</b> - random data over the start, the end and samples of every file, so it is unusable at once.</li>
 *   <li><b>Overwrite</b> - the chosen number of full passes over every byte (random, zeros, random, ...), each flushed to storage.</li>
 *   <li><b>Delete</b> - truncate to zero, optionally rename to a random name, delete, then clear every app-side trace.</li>
 * </ol>
 * Nothing goes to the in-app trash, so there is no undo.
 */
final class SecureWipe {
    static final int ST_CORRUPT = 0;
    static final int ST_OVERWRITE = 1;
    static final int ST_DELETE = 2;

    private static final SecureRandom RNG = new SecureRandom();

    private SecureWipe() {
    }

    /** What the person chose in the options dialog. */
    static final class Options {
        int passes = 3;
        boolean zeroPass = true;
        boolean rename = true;
        boolean clearTraces = true;
    }

    /** Called from the worker thread. */
    interface Listener {
        void onStage(int stage);

        /** {@code fraction} is the progress (0..1) of the current stage, {@code name} the item being worked on. */
        void onProgress(int stage, float fraction, String name);
    }

    /** Everything that will be erased, found up front so the stages can report real progress. */
    static final class Plan {
        final List<File> files = new ArrayList<>();
        /** Folders, children before parents. */
        final List<File> dirs = new ArrayList<>();
        final List<File> links = new ArrayList<>();
        long bytes;

        int items() {
            return files.size() + dirs.size() + links.size();
        }
    }

    static final class Result {
        int erased;
        int failed;
        boolean cancelled;
    }

    static Plan plan(List<File> roots) {
        Plan p = new Plan();
        for (File r : roots) walk(r, p);
        return p;
    }

    private static void walk(File f, Plan p) {
        if (Wipe.isLink(f)) {
            p.links.add(f);
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) walk(k, p);
            p.dirs.add(f);
            return;
        }
        p.files.add(f);
        p.bytes += Math.max(0L, f.length());
    }

    static Result run(Context c, Plan plan, Options o, Listener l, AtomicBoolean cancel) {
        Context app = c.getApplicationContext();
        Result res = new Result();
        Throttle t = new Throttle(l);
        int n = plan.files.size();

        // ---- 1) corrupt
        l.onStage(ST_CORRUPT);
        for (int i = 0; i < n; i++) {
            if (cancel.get()) {
                res.cancelled = true;
                return finish(app, plan, o, res);
            }
            File f = plan.files.get(i);
            t.progress(ST_CORRUPT, i / (float) Math.max(1, n), f.getName(), false);
            corrupt(f);
        }
        t.progress(ST_CORRUPT, 1f, "", true);

        // ---- 2) overwrite
        l.onStage(ST_OVERWRITE);
        int passes = Math.max(1, Math.min(35, o.passes));
        long total = Math.max(1L, plan.bytes * passes);
        long[] done = {0L};
        for (int i = 0; i < n; i++) {
            if (cancel.get()) {
                res.cancelled = true;
                return finish(app, plan, o, res);
            }
            File f = plan.files.get(i);
            t.progress(ST_OVERWRITE, done[0] / (float) total, f.getName(), false);
            overwrite(f, passes, o.zeroPass, done, total, t, cancel);
        }
        if (cancel.get()) {
            res.cancelled = true;
            return finish(app, plan, o, res);
        }
        t.progress(ST_OVERWRITE, 1f, "", true);

        // ---- 3) delete
        l.onStage(ST_DELETE);
        int units = Math.max(1, plan.items());
        int step = 0;
        for (File f : plan.files) {
            t.progress(ST_DELETE, step++ / (float) units, f.getName(), false);
            remove(f, o.rename);
            if (Wipe.exists(f)) {
                res.failed++;
            } else {
                res.erased++;
                Vault.scrub(app, f);
            }
        }
        for (File k : plan.links) {
            t.progress(ST_DELETE, step++ / (float) units, k.getName(), false);
            if (k.delete()) res.erased++;
            else res.failed++;
        }
        for (File d : plan.dirs) {
            t.progress(ST_DELETE, step++ / (float) units, d.getName(), false);
            if (!d.delete() && Wipe.exists(d)) res.failed++;
        }
        t.progress(ST_DELETE, 1f, "", true);
        return finish(app, plan, o, res);
    }

    /** Clears the app-side traces of everything that is gone (also after a stop, for what was already removed). */
    private static Result finish(Context app, Plan plan, Options o, Result res) {
        Set<String> paths = new HashSet<>();
        for (File f : plan.files) if (!Wipe.exists(f)) paths.add(f.getAbsolutePath());
        if (o.clearTraces && !paths.isEmpty()) {
            try {
                SmartIndex.forgetAll(app, paths);
            } catch (Throwable ignored) {
            }
            try {
                Set<String> fav = Store.favorites(app);
                if (fav.removeAll(paths)) Store.setFavorites(app, fav);
                Set<String> pin = Store.pins(app);
                if (pin.removeAll(paths)) Store.setPins(app, pin);
                for (String p : paths) Store.setResume(app, p, 0);
            } catch (Throwable ignored) {
            }
            ConvertCache.clear(app);
        }
        return res;
    }

    // ------------------------------------------------------------------ stage 1

    /** Random data over the first MiB, the last 256 KiB and 16 spread blocks, so no format can be read any more. */
    private static boolean corrupt(File f) {
        try {
            if (!f.canWrite()) f.setWritable(true);
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                long len = raf.length();
                if (len == 0) return true;
                byte[] buf = new byte[64 * 1024];
                long head = Math.min(len, 1L << 20);
                fillRandom(raf, 0, head, buf);
                long tail = Math.min(len - head, 256L * 1024);
                if (tail > 0) fillRandom(raf, len - tail, tail, buf);
                long mid = len - head - tail;
                if (mid > 0) {
                    for (int i = 1; i <= 16; i++) {
                        long pos = head + mid * i / 17;
                        long cnt = Math.min(4096L, len - pos);
                        if (cnt > 0) fillRandom(raf, pos, cnt, buf);
                    }
                }
                raf.getFD().sync();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fillRandom(RandomAccessFile raf, long pos, long count, byte[] buf) throws java.io.IOException {
        raf.seek(pos);
        long left = count;
        while (left > 0) {
            RNG.nextBytes(buf);
            int n = (int) Math.min(buf.length, left);
            raf.write(buf, 0, n);
            left -= n;
        }
    }

    // ------------------------------------------------------------------ stage 2

    /** Pass 1 random, pass 2 zeros (if enabled), pass 3 random, and so on. Every pass is flushed to storage. */
    private static boolean overwrite(File f, int passes, boolean zeroPass, long[] done, long total, Throttle t,
                                     AtomicBoolean cancel) {
        try {
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                long len = raf.length();
                byte[] buf = new byte[256 * 1024];
                for (int p = 0; p < passes; p++) {
                    boolean zeros = zeroPass && p % 3 == 1;
                    if (zeros) java.util.Arrays.fill(buf, (byte) 0);
                    raf.seek(0);
                    long left = len;
                    while (left > 0) {
                        if (cancel.get()) {
                            raf.getFD().sync();
                            return true;
                        }
                        if (!zeros) RNG.nextBytes(buf);
                        int n = (int) Math.min(buf.length, left);
                        raf.write(buf, 0, n);
                        left -= n;
                        done[0] += n;
                        t.progress(ST_OVERWRITE, Math.min(1f, done[0] / (float) total), f.getName(), false);
                    }
                    raf.getFD().sync();
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ stage 3

    private static void remove(File f, boolean rename) {
        try {
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.setLength(0);
                raf.getFD().sync();
            }
        } catch (Throwable ignored) {
        }
        try {
            File target = f;
            File parent = f.getParentFile();
            if (rename && parent != null) {
                File z = new File(parent, ".z" + Long.toHexString(RNG.nextLong() & Long.MAX_VALUE));
                if (f.renameTo(z)) target = z;
            }
            if (!target.delete() && Wipe.exists(target)) {
                Wipe.delete(java.util.Collections.singletonList(target), new AtomicBoolean(), null);
            }
        } catch (Throwable ignored) {
            try {
                f.delete();
            } catch (Throwable ignored2) {
            }
        }
    }

    /** Keeps the progress callbacks to a calm rate (the loops above run thousands of times for big files). */
    private static final class Throttle {
        private final Listener l;
        private long last;

        Throttle(Listener l) {
            this.l = l;
        }

        void progress(int stage, float frac, String name, boolean force) {
            long now = android.os.SystemClock.uptimeMillis();
            if (!force && now - last < 90) return;
            last = now;
            l.onProgress(stage, Math.max(0f, Math.min(1f, frac)), name);
        }
    }
}
