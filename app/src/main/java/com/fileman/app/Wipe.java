package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deletes files and folder trees in parallel. Symbolic links are removed, never followed. One failure
 * does not stop the rest: everything that could not be removed is reported at the end.
 */
final class Wipe {
    private Wipe() {
    }

    interface Progress {
        /** Called at most a few times per second from a worker thread. */
        void tick(int deleted, int rootsDone, int rootsTotal, String lastName);
    }

    static final class Result {
        int deleted;
        boolean cancelled;
        final List<String> failed = new ArrayList<>();
    }

    private static final int FILE_BATCH = 64;
    private static final int MAX_FAILED = 40;
    private static final long TICK_GAP_MS = 120;

    static boolean isLink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Throwable t) {
            return false;
        }
    }


    private static final String PREFS = "wipe_pending";
    private static final String KEY = "paths";

    /** Items renamed out of sight, waiting to be removed for real; {@code direct} could not be renamed. */
    static final class Stash {
        final List<File> hidden = new ArrayList<>();
        final List<File> direct = new ArrayList<>();
    }

    private static synchronized void remember(Context c, String path, boolean add) {
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY, new HashSet<String>()));
        boolean changed = add ? set.add(path) : set.remove(path);
        if (changed) sp.edit().putStringSet(KEY, set).commit();
    }

    /**
     * Renames every root to a hidden sibling in the same folder. A rename is instant whatever the size, so
     * the item disappears at once; the slow part (freeing the data) happens later in {@link #delete}.
     */
    static Stash stash(Context c, List<File> roots) {
        Stash st = new Stash();
        for (File f : roots) {
            File parent = f.getParentFile();
            if (parent == null || isLink(f)) {
                st.direct.add(f);
                continue;
            }
            File hid = new File(parent, ".fmdel-" + Long.toHexString(System.nanoTime()) + Integer.toHexString(st.hidden.size()));
            remember(c, hid.getAbsolutePath(), true);
            boolean ok;
            try {
                ok = f.renameTo(hid);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) {
                st.hidden.add(hid);
            } else {
                remember(c, hid.getAbsolutePath(), false);
                st.direct.add(f);
            }
        }
        return st;
    }

    /** Called once a stashed item is really gone. */
    static void settled(Context c, List<File> hidden) {
        for (File h : hidden) if (!exists(h)) remember(c, h.getAbsolutePath(), false);
    }

    /** Finishes anything a previous run left half-way (app closed or killed while purging). */
    static void resume(final Context c) {
        final Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final Set<String> set = new HashSet<>(sp.getStringSet(KEY, new HashSet<String>()));
        if (set.isEmpty()) return;
        Thread t = new Thread(() -> {
            List<File> left = new ArrayList<>();
            for (String path : set) {
                File f = new File(path);
                if (exists(f)) left.add(f);
                else remember(app, path, false);
            }
            if (!left.isEmpty()) {
                delete(left, new AtomicBoolean(), null);
                settled(app, left);
            }
        }, "wipe-resume");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static Result delete(List<File> roots, AtomicBoolean cancel, Progress progress) {
        final Result res = new Result();
        final AtomicInteger deleted = new AtomicInteger();
        final AtomicInteger rootsDone = new AtomicInteger();
        final AtomicLong lastTick = new AtomicLong();
        final ConcurrentLinkedQueue<String> failed = new ConcurrentLinkedQueue<>();
        final int total = roots.size();
        int threads = Math.max(4, Math.min(12, Runtime.getRuntime().availableProcessors() * 2));
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            final Ctx ctx = new Ctx(cancel, deleted, rootsDone, total, lastTick, failed, progress);
            List<RootTask> tasks = new ArrayList<>();
            for (File r : roots) tasks.add(new RootTask(ctx, r));
            // every top-level item runs in its own task, so many big files / folders are removed side by side
            pool.invoke(new RecursiveAction() {
                @Override
                protected void compute() {
                    invokeAll(tasks);
                }
            });
        } catch (Throwable t) {
            failed.add(String.valueOf(t.getMessage()));
        } finally {
            pool.shutdown();
        }
        res.deleted = deleted.get();
        res.cancelled = cancel != null && cancel.get();
        for (String s : failed) {
            if (res.failed.size() >= MAX_FAILED) break;
            res.failed.add(s);
        }
        if (progress != null) progress.tick(res.deleted, rootsDone.get(), total, null);
        return res;
    }

    private static final class Ctx {
        final AtomicBoolean cancel;
        final AtomicInteger deleted, rootsDone;
        final int total;
        final AtomicLong lastTick;
        final ConcurrentLinkedQueue<String> failed;
        final Progress progress;

        Ctx(AtomicBoolean cancel, AtomicInteger deleted, AtomicInteger rootsDone, int total, AtomicLong lastTick,
            ConcurrentLinkedQueue<String> failed, Progress progress) {
            this.cancel = cancel;
            this.deleted = deleted;
            this.rootsDone = rootsDone;
            this.total = total;
            this.lastTick = lastTick;
            this.failed = failed;
            this.progress = progress;
        }

        boolean stop() {
            return cancel != null && cancel.get();
        }

        void fail(File f) {
            if (failed.size() < MAX_FAILED) failed.add(f.getName());
        }

        void tick(String name) {
            if (progress == null) return;
            long now = System.nanoTime() / 1_000_000L;
            long last = lastTick.get();
            if (now - last < TICK_GAP_MS || !lastTick.compareAndSet(last, now)) return;
            progress.tick(deleted.get(), rootsDone.get(), total, name);
        }
    }

    private static boolean removeOne(File f, Ctx c) {
        boolean ok = f.delete();
        if (!ok && f.exists()) {
            try {
                Files.deleteIfExists(f.toPath());
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
        } else if (!ok) {
            ok = true;   // already gone
        }
        if (ok) {
            c.deleted.incrementAndGet();
            c.tick(f.getName());
        } else {
            c.fail(f);
        }
        return ok;
    }

    private static final class RootTask extends RecursiveAction {
        private final Ctx c;
        private final File root;

        RootTask(Ctx c, File root) {
            this.c = c;
            this.root = root;
        }

        @Override
        protected void compute() {
            if (!c.stop()) {
                if (root.delete()) {
                    c.deleted.incrementAndGet();
                } else if (root.isDirectory() && !isLink(root)) {
                    new DirTask(c, root).invoke();
                } else {
                    removeOne(root, c);
                }
            }
            c.rootsDone.incrementAndGet();
            c.tick(root.getName());
        }
    }

    private static final class FilesTask extends RecursiveAction {
        private final Ctx c;
        private final List<File> files;

        FilesTask(Ctx c, List<File> files) {
            this.c = c;
            this.files = files;
        }

        @Override
        protected void compute() {
            List<RecursiveAction> dirs = null;
            for (File f : files) {
                if (c.stop()) return;
                if (f.delete()) {
                    c.deleted.incrementAndGet();
                    c.tick(f.getName());
                } else if (f.isDirectory() && !isLink(f)) {
                    if (dirs == null) dirs = new ArrayList<>();
                    dirs.add(new DirTask(c, f));
                } else {
                    removeOne(f, c);
                }
            }
            if (dirs != null) invokeAll(dirs);
        }
    }

    private static final class DirTask extends RecursiveAction {
        private final Ctx c;
        private final File dir;

        DirTask(Ctx c, File dir) {
            this.c = c;
            this.dir = dir;
        }

        @Override
        protected void compute() {
            File[] kids = dir.listFiles();
            if (kids != null && kids.length > 0) {
                List<RecursiveAction> sub = new ArrayList<>();
                List<File> batch = new ArrayList<>(FILE_BATCH);
                for (File k : kids) {
                    if (c.stop()) return;
                    batch.add(k);
                    if (batch.size() >= FILE_BATCH) {
                        sub.add(new FilesTask(c, batch));
                        batch = new ArrayList<>(FILE_BATCH);
                    }
                }
                if (!batch.isEmpty()) sub.add(new FilesTask(c, batch));
                invokeAll(sub);
            }
            if (c.stop()) return;
            removeOne(dir, c);   // the folder itself, once everything inside is gone
        }
    }

    /** True when {@code f} still exists after a delete (used to keep rows the user could not remove). */
    static boolean exists(File f) {
        try {
            return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
        } catch (Throwable t) {
            return f.exists();
        }
    }
}
