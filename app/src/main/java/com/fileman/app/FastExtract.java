package com.fileman.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unpacks a zip with several threads. Zip entries are independent, so many files (or a few big compressed
 * ones) are inflated side by side instead of one after the other. Folders are created once up front.
 */
final class FastExtract {
    private FastExtract() {
    }

    interface Host {
        boolean cancelled();

        void name(String name);

        void bytes(long n);
    }

    /** Returns the number of files written. Throws the same exceptions the one-by-one path throws. */
    static int zip(final Arc arc, final File dest, final long room, final Host host) throws IOException {
        final List<Arc.Item> files = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        Set<String> parents = new HashSet<>();
        for (Arc.Item it : arc.items) {
            if (it.dir) continue;
            String n = clean(it.name);
            if (n == null) throw new IOException("unsafe entry name");
            files.add(it);
            names.add(n);
            int s = n.lastIndexOf('/');
            if (s > 0) parents.add(n.substring(0, s));
        }
        for (String p : parents) {
            File d = new File(dest, p);
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new IOException(d.getName());
        }
        final int count = files.size();
        if (count == 0) return 0;
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicLong used = new AtomicLong();
        final AtomicReference<Throwable> fail = new AtomicReference<>();
        final ZipWriter.Sink sink = new ZipWriter.Sink() {
            @Override
            public void bytes(long n) {
                host.bytes(n);
            }

            @Override
            public boolean cancelled() {
                return host.cancelled() || fail.get() != null;
            }
        };
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        if (count < 4) threads = 1;
        final Thread[] pool = new Thread[threads];
        Runnable work = () -> {
            try {
                int i;
                while (fail.get() == null && !host.cancelled() && (i = next.getAndIncrement()) < count) {
                    Arc.Item it = files.get(i);
                    File out = new File(dest, names.get(i));
                    host.name(it.name);
                    try (InputStream in = arc.stream(it)) {
                        long left = Math.max(0L, room - used.get());
                        used.addAndGet(Arc.copy(in, out, left, sink));
                    }
                    if (it.time > 0) //noinspection ResultOfMethodCallIgnored
                        out.setLastModified(it.time);
                    done.incrementAndGet();
                }
            } catch (Throwable e) {
                fail.compareAndSet(null, e);
            }
        };
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(work, "unzip-" + t);
            pool[t].start();
        }
        for (Thread t : pool) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail.compareAndSet(null, e);
            }
        }
        if (host.cancelled()) throw new ZipWriter.Cancelled();
        Throwable f = fail.get();
        if (f instanceof IOException) throw (IOException) f;
        if (f instanceof RuntimeException) throw (RuntimeException) f;
        if (f != null) throw new IOException(String.valueOf(f.getMessage()));
        return done.get();
    }

    /** The entry name as a safe relative path, or null when it could leave the destination folder. */
    static String clean(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty() || n.indexOf('\0') >= 0) return null;
        for (String seg : n.split("/")) if (seg.equals("..")) return null;
        return n;
    }
}
