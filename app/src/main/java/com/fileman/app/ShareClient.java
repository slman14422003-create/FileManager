package com.fileman.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

final class ShareClient {
    static final class Conn implements Closeable {
        Socket s;
        DataInputStream in;
        DataOutputStream out;
        String ip = "";
        String peer = "";

        @Override
        public void close() {
            try {
                if (s != null) s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final SocketFactory factory;
    private final int port;
    private final byte[] token;
    private final String myName;
    private volatile String host = "";
    volatile int listVersion;

    ShareClient(SocketFactory factory, int port, String tokenHex, String myName) {
        this.factory = factory;
        this.port = port;
        this.token = ShareProto.unhex(tokenHex);
        this.myName = myName;
    }

    String host() {
        return host;
    }

    Conn open(String ip) throws IOException {
        Socket s = factory.createSocket();
        Conn c = new Conn();
        c.s = s;
        c.ip = ip;
        try {
            s.setTcpNoDelay(true);
            try {
                s.setReceiveBufferSize(ShareProto.BUF * 2);
                s.setSendBufferSize(ShareProto.BUF * 2);
            } catch (Exception ignored) {
            }
            s.connect(new InetSocketAddress(ip, port), 4000);
            s.setSoTimeout(30000);
            c.in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8192));
            c.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
            c.out.writeInt(ShareProto.MAGIC);
            c.out.writeByte(ShareProto.VERSION);
            c.out.write(token);
            c.out.writeUTF(myName);
            c.out.flush();
            if (c.in.readUnsignedByte() != ShareProto.OK) throw new IOException("denied");
            c.peer = c.in.readUTF();
            return c;
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    Conn open() throws IOException {
        return open(host);
    }

    Conn connectAny(List<String> ips) throws IOException {
        if (ips.isEmpty()) throw new IOException("no address");
        final ExecutorService ex = Executors.newFixedThreadPool(Math.min(ips.size(), 8));
        final CompletionService<Conn> cs = new ExecutorCompletionService<>(ex);
        final int n = ips.size();
        for (final String ip : ips) {
            cs.submit((Callable<Conn>) () -> open(ip));
        }
        Conn win = null;
        int seen = 0;
        while (seen < n && win == null) {
            seen++;
            try {
                win = cs.take().get();
            } catch (Exception ignored) {
            }
        }
        final int rest = n - seen;
        ex.shutdown();
        if (rest > 0) {
            new Thread(() -> {
                for (int i = 0; i < rest; i++) {
                    try {
                        cs.take().get().close();
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }
        if (win == null) throw new IOException("unreachable");
        host = win.ip;
        return win;
    }

    List<ShareItem> list(Conn c) throws IOException {
        c.out.writeByte(ShareProto.C_LIST);
        c.out.flush();
        if (c.in.readUnsignedByte() != ShareProto.OK) throw new IOException("list");
        listVersion = c.in.readInt();
        int n = c.in.readInt();
        List<ShareItem> out = new ArrayList<>(Math.min(n, 100000));
        for (int i = 0; i < n; i++) {
            ShareItem it = new ShareItem();
            it.id = c.in.readInt();
            it.rel = c.in.readUTF();
            it.size = c.in.readLong();
            it.mtime = c.in.readLong();
            out.add(it);
        }
        return out;
    }

    void ping(Conn c) throws IOException {
        c.out.writeByte(ShareProto.C_PING);
        c.out.flush();
        if (c.in.readUnsignedByte() != ShareProto.OK) throw new IOException("ping");
    }
}
