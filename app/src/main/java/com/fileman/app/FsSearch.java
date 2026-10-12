package com.fileman.app;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/** Multi-threaded name search over the cached folder snapshots; results stream out while it runs. */
final class FsSearch {
    interface Sink {
        void hits(List<String> paths, boolean done);
    }

    interface Alive {
        boolean get();
    }

    private static final int THREADS = 4;

    private static final class Pat {
        final List<String> plain = new ArrayList<>();
        final List<Pattern> globs = new ArrayList<>();

        Pat(String q) {
            for (String t : q.trim().split("\\s+")) {
                if (t.isEmpty()) continue;
                if (t.indexOf('*') >= 0 || t.indexOf('?') >= 0) {
                    StringBuilder sb = new StringBuilder();
                    for (char c : t.toCharArray()) {
                        if (c == '*') sb.append(".*");
                        else if (c == '?') sb.append('.');
                        else sb.append(Pattern.quote(String.valueOf(c)));
                    }
                    globs.add(Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } else {
                    plain.add(t.toLowerCase(Locale.ROOT));
                }
            }
        }

        boolean empty() {
            return plain.isEmpty() && globs.isEmpty();
        }

        boolean ok(String name) {
            for (int i = 0; i < plain.size(); i++) if (!has(name, plain.get(i))) return false;
            for (int i = 0; i < globs.size(); i++) if (!globs.get(i).matcher(name).matches()) return false;
            return true;
        }

        private static boolean has(String s, String needle) {
            int n = needle.length();
            int last = s.length() - n;
            for (int i = 0; i <= last; i++) if (s.regionMatches(true, i, needle, 0, n)) return true;
            return false;
        }
    }

    static void run(final FsCache cache, File root, String query, final boolean hidden, final int max,
                    final Alive alive, final Sink sink) {
        final Pat pat = new Pat(query);
        if (pat.empty()) {
            sink.hits(new ArrayList<String>(), true);
            return;
        }
        final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger pending = new AtomicInteger(1);
        final List<String> hits = Collections.synchronizedList(new ArrayList<String>());
        final AtomicBoolean stop = new AtomicBoolean();
        queue.add(root.getAbsolutePath());
        Thread[] ts = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            ts[i] = new Thread(() -> {
                while (!stop.get()) {
                    String d = queue.poll();
                    if (d == null) {
                        if (pending.get() == 0) return;
                        LockSupport.parkNanos(300_000);
                        continue;
                    }
                    try {
                        visit(cache, d, pat, hidden, max, hits, queue, pending, stop);
                    } catch (Throwable ignored) {
                    } finally {
                        pending.decrementAndGet();
                    }
                }
            }, "fs-search-" + i);
            ts[i].setDaemon(true);
            ts[i].setPriority(Thread.NORM_PRIORITY - 1);
            ts[i].start();
        }
        int sent = 0;
        try {
            while (true) {
                if (!alive.get()) stop.set(true);
                boolean running = false;
                for (Thread t : ts) {
                    if (t.isAlive()) {
                        running = true;
                        break;
                    }
                }
                int n = hits.size();
                if (running && n != sent && alive.get()) {
                    sent = n;
                    sink.hits(copy(hits), false);
                }
                if (!running) break;
                Thread.sleep(120);
            }
        } catch (InterruptedException e) {
            stop.set(true);
            Thread.currentThread().interrupt();
        }
        if (alive.get()) sink.hits(copy(hits), true);
    }

    private static List<String> copy(List<String> l) {
        synchronized (l) {
            return new ArrayList<>(l);
        }
    }

    private static void visit(FsCache cache, String d, Pat pat, boolean hidden, int max, List<String> hits,
                              ConcurrentLinkedQueue<String> queue, AtomicInteger pending, AtomicBoolean stop) {
        FsCache.Snap s = cache.ensure(d);
        if (s == null) return;
        int n = s.count();
        for (int i = 0; i < n; i++) {
            if (stop.get()) return;
            String nm = s.name[i];
            if (!hidden && nm.startsWith(".")) continue;
            if (pat.ok(nm)) {
                if (hits.size() >= max) {
                    stop.set(true);
                    return;
                }
                hits.add(FsCache.child(d, nm));
            }
            int f = s.flag[i];
            if ((f & FsCache.F_DIR) != 0 && (f & FsCache.F_LINK) == 0) {
                pending.incrementAndGet();
                queue.add(FsCache.child(d, nm));
            }
        }
    }
}
