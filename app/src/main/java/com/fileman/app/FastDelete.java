package com.fileman.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Parallel recursive delete: one syscall per file, folders handled by a small work-stealing pool. */
final class FastDelete {
    interface Cancel {
        boolean get();
    }

    interface Progress {
        void tick(long done, String name);
    }

    static final class Result {
        long deleted;
        int failed;
        String firstFail;
        boolean cancelled;
    }

    private static final class Ctx {
        final Cancel cancel;
        final Progress prog;
        final AtomicLong count = new AtomicLong();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicReference<String> first = new AtomicReference<>();

        Ctx(Cancel c, Progress p) {
            cancel = c;
            prog = p;
        }

        void ok(String name) {
            long n = count.incrementAndGet();
            if ((n & 31) == 0 && prog != null) prog.tick(n, name);
        }

        void fail(String name) {
            failed.incrementAndGet();
            first.compareAndSet(null, name);
        }
    }

    private static final class Job extends RecursiveAction {
        private final File f;
        private final Ctx c;

        Job(File f, Ctx c) {
            this.f = f;
            this.c = c;
        }

        @Override
        protected void compute() {
            if (c.cancel.get()) return;
            if (f.delete()) {
                c.ok(f.getName());
                return;
            }
            String p = f.getPath();
            if (!FsCache.isRealDir(p)) {
                if (FsCache.lexists(p)) c.fail(f.getName());
                return;
            }
            String[] names = f.list();
            List<Job> subs = null;
            if (names != null) {
                for (String nm : names) {
                    if (c.cancel.get()) return;
                    File k = new File(f, nm);
                    if (k.delete()) {
                        c.ok(nm);
                    } else if (FsCache.isRealDir(k.getPath())) {
                        if (subs == null) subs = new ArrayList<>();
                        subs.add(new Job(k, c));
                    } else if (FsCache.lexists(k.getPath())) {
                        c.fail(nm);
                    }
                }
            }
            if (subs != null) invokeAll(subs);
            if (c.cancel.get()) return;
            if (f.delete()) c.ok(f.getName());
            else if (FsCache.lexists(p)) c.fail(f.getName());
        }
    }

    static Result run(List<File> roots, Cancel cancel, Progress prog) {
        Ctx c = new Ctx(cancel, prog);
        Result r = new Result();
        ForkJoinPool pool = new ForkJoinPool(4);
        try {
            for (File f : roots) {
                if (cancel.get()) break;
                pool.invoke(new Job(f, c));
            }
        } finally {
            pool.shutdown();
        }
        r.deleted = c.count.get();
        r.failed = c.failed.get();
        r.firstFail = c.first.get();
        r.cancelled = cancel.get();
        return r;
    }
}
