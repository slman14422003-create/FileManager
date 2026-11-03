package com.fileman.app;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class ShareServer {
    interface Listener {
        void onPeers();

        void onReceived(File f);
    }

    private final Context app;
    private final byte[] token;
    private final String deviceName;
    private final File receiveDir;
    private final List<ShareItem> items = new ArrayList<>();
    private final Set<SocketChannel> conns = ConcurrentHashMap.newKeySet();
    private final Map<String, String> peerNames = new HashMap<>();
    private final Map<String, Integer> peerConns = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocketChannel server;
    private volatile boolean running;
    private volatile int version = 1;
    private long totalBytes;

    final AtomicLong sent = new AtomicLong();
    final AtomicLong received = new AtomicLong();
    volatile Listener listener;

    ShareServer(Context c, String deviceName, String tokenHex, File receiveDir) {
        this.app = c.getApplicationContext();
        this.deviceName = deviceName;
        this.token = ShareProto.unhex(tokenHex);
        this.receiveDir = receiveDir;
    }

    int start() throws IOException {
        server = ServerSocketChannel.open();
        server.socket().setReuseAddress(true);
        server.bind(new InetSocketAddress(0));
        running = true;
        pool.execute(this::acceptLoop);
        return server.socket().getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final SocketChannel ch = server.accept();
                conns.add(ch);
                pool.execute(() -> serve(ch));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (SocketChannel c : conns) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
        conns.clear();
        pool.shutdownNow();
    }

    synchronized int addItems(List<ShareItem> add) {
        int n = 0;
        for (ShareItem i : add) {
            if (items.size() >= ShareProto.MAX_ITEMS) break;
            i.id = items.size() + 1;
            items.add(i);
            totalBytes += Math.max(0, i.size);
            n++;
        }
        if (n > 0) version++;
        return n;
    }

    synchronized int count() {
        return items.size();
    }

    synchronized long total() {
        return totalBytes;
    }

    synchronized List<String> peers() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : peerConns.entrySet()) {
            if (e.getValue() > 0) {
                String n = peerNames.get(e.getKey());
                out.add((n == null || n.isEmpty() ? "" : n + " · ") + e.getKey());
            }
        }
        return out;
    }

    private synchronized ShareItem item(int id) {
        return id >= 1 && id <= items.size() ? items.get(id - 1) : null;
    }

    private synchronized void peerIn(String ip, String name) {
        Integer n = peerConns.get(ip);
        peerConns.put(ip, n == null ? 1 : n + 1);
        if (name != null && !name.isEmpty()) peerNames.put(ip, name);
    }

    private synchronized void peerOut(String ip) {
        Integer n = peerConns.get(ip);
        if (n != null) peerConns.put(ip, Math.max(0, n - 1));
    }

    private void notifyPeers() {
        Listener l = listener;
        if (l != null) l.onPeers();
    }

    private void serve(SocketChannel ch) {
        Socket s = ch.socket();
        String ip = "";
        boolean counted = false;
        try {
            s.setTcpNoDelay(true);
            try {
                s.setSendBufferSize(ShareProto.BUF * 2);
                s.setReceiveBufferSize(ShareProto.BUF * 2);
            } catch (Exception ignored) {
            }
            s.setSoTimeout(300000);
            ip = s.getInetAddress() == null ? "" : s.getInetAddress().getHostAddress();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8192));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
            if (in.readInt() != ShareProto.MAGIC) return;
            in.readUnsignedByte();
            byte[] got = new byte[16];
            in.readFully(got);
            String peerName = in.readUTF();
            if (!MessageDigest.isEqual(got, token)) {
                out.writeByte(ShareProto.DENIED);
                out.flush();
                return;
            }
            out.writeByte(ShareProto.OK);
            out.writeUTF(deviceName);
            out.flush();
            peerIn(ip, peerName);
            counted = true;
            notifyPeers();
            ShareItem.Src cache = null;
            int cacheId = -1;
            try {
                while (running) {
                    int cmd = in.read();
                    if (cmd < 0 || cmd == ShareProto.C_BYE) break;
                    if (cmd == ShareProto.C_PING) {
                        out.writeByte(ShareProto.OK);
                        out.flush();
                    } else if (cmd == ShareProto.C_LIST) {
                        sendList(out);
                    } else if (cmd == ShareProto.C_GET) {
                        int id = in.readInt();
                        long off = in.readLong();
                        long len = in.readLong();
                        ShareItem it = item(id);
                        if (it == null) {
                            out.writeByte(ShareProto.NOT_FOUND);
                            out.flush();
                            continue;
                        }
                        if (cache == null || cacheId != id) {
                            if (cache != null) cache.close();
                            cache = null;
                            cacheId = -1;
                            try {
                                cache = it.open(app);
                                cacheId = id;
                            } catch (IOException e) {
                                out.writeByte(ShareProto.ERR);
                                out.flush();
                                continue;
                            }
                        }
                        long size = it.size;
                        if (off < 0 || off > size) off = size;
                        if (len < 0 || off + len > size) len = size - off;
                        out.writeByte(ShareProto.OK);
                        out.writeLong(len);
                        out.flush();
                        pump(cache, off, len, ch);
                    } else if (cmd == ShareProto.C_BATCH) {
                        int n = in.readInt();
                        int[] ids = new int[Math.max(0, Math.min(n, 100000))];
                        for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
                        for (int id : ids) {
                            ShareItem it = item(id);
                            ShareItem.Src src = null;
                            if (it != null) {
                                try {
                                    src = it.open(app);
                                } catch (IOException ignored) {
                                }
                            }
                            if (src == null) {
                                out.writeByte(ShareProto.NOT_FOUND);
                                out.flush();
                                continue;
                            }
                            try {
                                out.writeByte(ShareProto.OK);
                                out.writeLong(it.size);
                                out.flush();
                                pump(src, 0, it.size, ch);
                            } finally {
                                src.close();
                            }
                        }
                    } else if (cmd == ShareProto.C_PUT) {
                        receive(in, out);
                    } else {
                        break;
                    }
                }
            } finally {
                if (cache != null) cache.close();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
            conns.remove(ch);
            if (counted) {
                peerOut(ip);
                notifyPeers();
            }
        }
    }

    private void sendList(DataOutputStream out) throws IOException {
        List<ShareItem> snap;
        int ver;
        synchronized (this) {
            snap = new ArrayList<>(items);
            ver = version;
        }
        out.writeByte(ShareProto.OK);
        out.writeInt(ver);
        out.writeInt(snap.size());
        for (ShareItem i : snap) {
            out.writeInt(i.id);
            out.writeUTF(i.rel);
            out.writeLong(i.size);
            out.writeLong(i.mtime);
        }
        out.flush();
    }

    private void pump(ShareItem.Src src, long off, long len, SocketChannel ch) throws IOException {
        long pos = off;
        long left = len;
        while (left > 0) {
            long n = src.ch.transferTo(pos, Math.min(left, 8L << 20), ch);
            if (n <= 0) throw new IOException("short read");
            pos += n;
            left -= n;
            sent.addAndGet(n);
        }
    }

    private void receive(DataInputStream in, DataOutputStream out) throws IOException {
        String rel = ShareProto.cleanRel(in.readUTF());
        long size = in.readLong();
        File dest = null;
        File part = null;
        boolean ok = false;
        if (rel != null && size >= 0 && receiveDir != null) {
            File target = new File(receiveDir, rel);
            File parent = target.getParentFile();
            boolean inside = false;
            try {
                inside = parent != null && (parent.isDirectory() || parent.mkdirs())
                        && (parent.getCanonicalPath() + File.separator).startsWith(receiveDir.getCanonicalPath() + File.separator);
            } catch (IOException ignored) {
            }
            if (inside && size > receiveDir.getUsableSpace()) inside = false;
            if (inside) {
                dest = uniqueFile(target);
                part = new File(dest.getParentFile(), dest.getName() + ".fmpart");
            }
        }
        byte[] buf = new byte[ShareProto.BUF];
        FileOutputStream fo = null;
        try {
            if (part != null) fo = new FileOutputStream(part);
            long left = size;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) throw new IOException("eof");
                if (fo != null) fo.write(buf, 0, r);
                left -= r;
                received.addAndGet(r);
            }
            if (fo != null) {
                fo.close();
                fo = null;
                ok = part.renameTo(dest);
            }
        } finally {
            if (fo != null) {
                try {
                    fo.close();
                } catch (IOException ignored) {
                }
            }
            if (!ok && part != null) {
                part.delete();
            }
        }
        out.writeByte(ok ? ShareProto.OK : ShareProto.ERR);
        out.flush();
        if (ok) {
            Listener l = listener;
            if (l != null) l.onReceived(dest);
        }
    }

    static File uniqueFile(File f) {
        if (!f.exists()) return f;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        File g = f;
        while (g.exists()) g = new File(f.getParentFile(), stem + " (" + (i++) + ")" + ext);
        return g;
    }
}
