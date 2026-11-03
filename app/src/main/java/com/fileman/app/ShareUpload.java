package com.fileman.app;

import android.content.Context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ShareUpload {
    private final Context app;
    private final ShareClient client;
    private final ConcurrentLinkedQueue<ShareItem> queue = new ConcurrentLinkedQueue<>();
    private final Set<ShareClient.Conn> open = ConcurrentHashMap.newKeySet();

    final AtomicLong done = new AtomicLong();
    final AtomicInteger filesDone = new AtomicInteger();
    final AtomicInteger filesFailed = new AtomicInteger();
    final Map<Integer, String> active = new ConcurrentHashMap<>();
    final long total;
    final int totalFiles;
    volatile boolean cancelled;
    volatile boolean finished;

    ShareUpload(Context c, ShareClient client, List<ShareItem> items) {
        this.app = c.getApplicationContext();
        this.client = client;
        long t = 0;
        int n = 0;
        for (ShareItem i : items) {
            i.id = ++n;
            t += Math.max(0, i.size);
            queue.add(i);
        }
        this.total = t;
        this.totalFiles = items.size();
    }

    void start(final int streams, final Runnable onFinish) {
        new Thread(() -> {
            java.util.List<Thread> ws = new java.util.ArrayList<>();
            int n = Math.max(1, Math.min(streams, 8));
            for (int i = 0; i < n; i++) {
                Thread w = new Thread(this::worker);
                ws.add(w);
                w.start();
            }
            for (Thread w : ws) {
                try {
                    w.join();
                } catch (InterruptedException ignored) {
                }
            }
            finished = true;
            if (onFinish != null) onFinish.run();
        }).start();
    }

    void cancel() {
        cancelled = true;
        for (ShareClient.Conn c : open) c.close();
    }

    private void worker() {
        ShareClient.Conn c = null;
        byte[] arr = new byte[ShareProto.BUF];
        ShareItem it;
        while (!cancelled && (it = queue.poll()) != null) {
            long sentNow = 0;
            ShareItem.Src src = null;
            try {
                if (c == null) {
                    c = client.open();
                    open.add(c);
                }
                src = it.open(app);
                active.put(it.id, it.rel);
                c.out.writeByte(ShareProto.C_PUT);
                c.out.writeUTF(it.rel);
                c.out.writeLong(it.size);
                long pos = 0;
                ByteBuffer bb = ByteBuffer.wrap(arr);
                while (pos < it.size) {
                    bb.clear();
                    bb.limit((int) Math.min(arr.length, it.size - pos));
                    int r = src.ch.read(bb, pos);
                    if (r <= 0) throw new IOException("short read");
                    c.out.write(arr, 0, r);
                    pos += r;
                    sentNow += r;
                    done.addAndGet(r);
                }
                c.out.flush();
                if (c.in.readUnsignedByte() != ShareProto.OK) throw new IOException("rejected");
                filesDone.incrementAndGet();
            } catch (IOException e) {
                done.addAndGet(-sentNow);
                done.addAndGet(Math.max(0, it.size));
                filesFailed.incrementAndGet();
                if (c != null) {
                    open.remove(c);
                    c.close();
                    c = null;
                }
            } finally {
                active.remove(it.id);
                if (src != null) src.close();
            }
        }
        if (c != null) {
            open.remove(c);
            c.close();
        }
    }
}
