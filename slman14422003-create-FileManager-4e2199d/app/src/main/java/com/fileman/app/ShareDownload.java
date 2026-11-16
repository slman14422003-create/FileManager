package com.fileman.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ShareDownload {
    private static final class State {
        ShareItem item;
        File dest;
        File part;
        RandomAccessFile raf;
        FileChannel ch;
        AtomicLong left;
    }

    private static final class Task {
        List<ShareItem> batch;
        ShareItem item;
        long off;
        long len;
        int tries;
    }

    private final ShareClient client;
    private final File dir;
    private final List<ShareItem> items;
    private final ConcurrentLinkedQueue<Task> queue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, State> states = new ConcurrentHashMap<>();
    private final Map<Integer, File> dests = new ConcurrentHashMap<>();
    private final Set<ShareClient.Conn> open = ConcurrentHashMap.newKeySet();
    private final Object pauseLock = new Object();

    final AtomicLong done = new AtomicLong();
    final AtomicInteger filesDone = new AtomicInteger();
    final AtomicInteger filesFailed = new AtomicInteger();
    final AtomicInteger filesSkipped = new AtomicInteger();
    final Map<Integer, String> active = new ConcurrentHashMap<>();
    final List<File> results = Collections.synchronizedList(new ArrayList<File>());
    final long total;
    final int totalFiles;
    volatile boolean paused;
    volatile boolean cancelled;
    volatile boolean finished;

    ShareDownload(ShareClient client, File dir, List<ShareItem> items) {
        this.client = client;
        this.dir = dir;
        this.items = items;
        long t = 0;
        for (ShareItem i : items) t += Math.max(0, i.size);
        this.total = t;
        this.totalFiles = items.size();
    }

    void start(final int streams, final boolean skipExisting, final Runnable onFinish) {
        Thread t = new Thread(() -> {
            try {
                plan(skipExisting);
                List<Thread> ws = new ArrayList<>();
                int n = Math.max(1, Math.min(streams, 8));
                for (int i = 0; i < n; i++) {
                    Thread w = new Thread(this::worker, "share-dl-" + i);
                    ws.add(w);
                    w.start();
                }
                for (Thread w : ws) {
                    try {
                        w.join();
                    } catch (InterruptedException ignored) {
                    }
                }
            } catch (Throwable e) {
                filesFailed.incrementAndGet();
            } finally {
                for (State s : states.values()) discard(s);
                states.clear();
                finished = true;
                if (onFinish != null) onFinish.run();
            }
        }, "share-dl");
        t.start();
    }

    void pause(boolean on) {
        paused = on;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    void cancel() {
        cancelled = true;
        pause(false);
        for (ShareClient.Conn c : open) c.close();
    }

    private void awaitResume() {
        if (!paused) return;
        synchronized (pauseLock) {
            while (paused && !cancelled) {
                try {
                    pauseLock.wait(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private File destFor(ShareItem it) throws IOException {
        String rel = ShareProto.cleanRel(it.rel);
        if (rel == null) throw new IOException("bad path");
        File f = new File(dir, rel);
        String base = dir.getCanonicalPath() + File.separator;
        if (!f.getCanonicalPath().startsWith(base)) throw new IOException("path");
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("mkdir");
        return f;
    }

    private void plan(boolean skipExisting) {
        List<ShareItem> batch = new ArrayList<>();
        long batchBytes = 0;
        for (ShareItem it : items) {
            if (cancelled) return;
            File dest;
            try {
                dest = destFor(it);
            } catch (IOException e) {
                filesFailed.incrementAndGet();
                done.addAndGet(Math.max(0, it.size));
                continue;
            }
            if (dest.exists()) {
                if (skipExisting && dest.length() == it.size) {
                    filesSkipped.incrementAndGet();
                    filesDone.incrementAndGet();
                    done.addAndGet(Math.max(0, it.size));
                    continue;
                }
                dest = ShareServer.uniqueFile(dest);
            }
            dests.put(it.id, dest);
            if (it.size <= 0) {
                try {
                    if (dest.createNewFile() || dest.exists()) {
                        filesDone.incrementAndGet();
                        results.add(dest);
                    } else {
                        filesFailed.incrementAndGet();
                    }
                } catch (IOException e) {
                    filesFailed.incrementAndGet();
                }
                continue;
            }
            if (it.size >= ShareProto.BIG) {
                if (!batch.isEmpty()) {
                    queueBatch(batch);
                    batch = new ArrayList<>();
                    batchBytes = 0;
                }
                State st = new State();
                st.item = it;
                st.dest = dest;
                st.part = new File(dest.getParentFile(), dest.getName() + ".fmpart");
                st.left = new AtomicLong(it.size);
                states.put(it.id, st);
                for (long off = 0; off < it.size; off += ShareProto.CHUNK) {
                    Task t = new Task();
                    t.item = it;
                    t.off = off;
                    t.len = Math.min(ShareProto.CHUNK, it.size - off);
                    queue.add(t);
                }
            } else {
                batch.add(it);
                batchBytes += it.size;
                if (batch.size() >= ShareProto.BATCH_FILES || batchBytes >= ShareProto.BATCH_BYTES) {
                    queueBatch(batch);
                    batch = new ArrayList<>();
                    batchBytes = 0;
                }
            }
        }
        if (!batch.isEmpty()) queueBatch(batch);
    }

    private void queueBatch(List<ShareItem> b) {
        Task t = new Task();
        t.batch = b;
        queue.add(t);
    }

    private void worker() {
        ShareClient.Conn c = null;
        byte[] buf = new byte[ShareProto.BUF];
        while (!cancelled) {
            awaitResume();
            Task t = queue.poll();
            if (t == null) break;
            try {
                if (c == null) {
                    c = client.open();
                    open.add(c);
                }
                if (t.batch != null) getBatch(c, t, buf);
                else getChunk(c, t, buf);
            } catch (IOException e) {
                if (c != null) {
                    open.remove(c);
                    c.close();
                    c = null;
                }
                if (cancelled) break;
                if (++t.tries < 4) {
                    queue.add(t);
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    failTask(t);
                }
            }
        }
        if (c != null) {
            open.remove(c);
            try {
                c.out.writeByte(ShareProto.C_BYE);
                c.out.flush();
            } catch (IOException ignored) {
            }
            c.close();
        }
    }

    private void failTask(Task t) {
        if (t.batch != null) {
            for (ShareItem it : t.batch) failFile(it);
        } else {
            State st = states.remove(t.item.id);
            if (st != null) {
                discard(st);
                failFile(t.item);
            }
        }
    }

    private void failFile(ShareItem it) {
        filesFailed.incrementAndGet();
        active.remove(it.id);
    }

    private void discard(State st) {
        try {
            if (st.raf != null) st.raf.close();
        } catch (IOException ignored) {
        }
        st.raf = null;
        st.ch = null;
        st.part.delete();
    }

    private synchronized FileChannel channel(State st) throws IOException {
        if (st.ch == null) {
            st.raf = new RandomAccessFile(st.part, "rw");
            st.raf.setLength(st.item.size);
            st.ch = st.raf.getChannel();
        }
        return st.ch;
    }

    private void getChunk(ShareClient.Conn c, Task t, byte[] buf) throws IOException {
        State st = states.get(t.item.id);
        if (st == null) return;
        FileChannel fc = channel(st);
        active.put(t.item.id, t.item.rel);
        c.out.writeByte(ShareProto.C_GET);
        c.out.writeInt(t.item.id);
        c.out.writeLong(t.off);
        c.out.writeLong(t.len);
        c.out.flush();
        if (c.in.readUnsignedByte() != ShareProto.OK) throw new IOException("status");
        if (c.in.readLong() != t.len) throw new IOException("length");
        long pos = t.off;
        long left = t.len;
        long got = 0;
        try {
            while (left > 0) {
                awaitResume();
                int r = c.in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new IOException("eof");
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, r);
                while (bb.hasRemaining()) pos += fc.write(bb, pos);
                left -= r;
                got += r;
                done.addAndGet(r);
            }
        } catch (IOException e) {
            done.addAndGet(-got);
            throw e;
        }
        if (st.left.addAndGet(-t.len) == 0) finishFile(st);
    }

    private void finishFile(State st) {
        states.remove(st.item.id);
        active.remove(st.item.id);
        try {
            if (st.raf != null) st.raf.close();
        } catch (IOException ignored) {
        }
        if (st.part.renameTo(st.dest)) {
            if (st.item.mtime > 0) {
                st.dest.setLastModified(st.item.mtime);
            }
            filesDone.incrementAndGet();
            results.add(st.dest);
        } else {
            filesFailed.incrementAndGet();
        }
    }

    private void getBatch(ShareClient.Conn c, Task t, byte[] buf) throws IOException {
        List<ShareItem> b = t.batch;
        c.out.writeByte(ShareProto.C_BATCH);
        c.out.writeInt(b.size());
        for (ShareItem it : b) c.out.writeInt(it.id);
        c.out.flush();
        int idx = 0;
        try {
            for (; idx < b.size(); idx++) {
                ShareItem it = b.get(idx);
                int status = c.in.readUnsignedByte();
                if (status != ShareProto.OK) {
                    failFile(it);
                    done.addAndGet(Math.max(0, it.size));
                    continue;
                }
                long size = c.in.readLong();
                File dest = dests.get(it.id);
                if (dest == null) throw new IOException("dest");
                File part = new File(dest.getParentFile(), dest.getName() + ".fmpart");
                active.put(it.id, it.rel);
                long got = 0;
                try (FileOutputStream fo = new FileOutputStream(part)) {
                    long left = size;
                    while (left > 0) {
                        awaitResume();
                        int r = c.in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) throw new IOException("eof");
                        fo.write(buf, 0, r);
                        left -= r;
                        got += r;
                        done.addAndGet(r);
                    }
                } catch (IOException e) {
                    done.addAndGet(-got);
                    part.delete();
                    throw e;
                }
                active.remove(it.id);
                if (part.renameTo(dest)) {
                    if (it.mtime > 0) {
                        dest.setLastModified(it.mtime);
                    }
                    filesDone.incrementAndGet();
                    results.add(dest);
                } else {
                    filesFailed.incrementAndGet();
                }
            }
        } catch (IOException e) {
            t.batch = new ArrayList<>(b.subList(idx, b.size()));
            throw e;
        }
    }
}
