package com.fileman.app;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * One reader for every archive the app can open.
 * <ul>
 *   <li>Built in (no libraries): ZIP and its relatives (zip, jar, war, aar, cbz, epub, odt, docx...) including ZIP64 and
 *       archives above 4 GB, TAR, TAR.GZ / TGZ, and single GZ files.</li>
 *   <li>Through {@code ArcExtra} (commons-compress / junrar, loaded only if present): 7Z, RAR, BZ2 / TAR.BZ2, XZ / TAR.XZ.</li>
 * </ul>
 * Zip entries can be opened at random; the other formats are streamed in one pass with {@link #walk}.
 */
final class Arc implements Closeable {
    static final class Item {
        String name = "";            // '/' separated, folders end with '/'
        boolean dir;
        long size = -1, packed = -1, time = -1, crc = -1;
        String method = "";
        boolean encrypted;
        Object ref;                  // format specific handle
    }

    interface Want {
        boolean test(Item it);
    }

    /** Receives the data of one entry; {@code in} is valid only during the call and must not be closed. */
    interface Visitor {
        void file(Item it, InputStream in) throws IOException;
    }

    /** Implemented by the optional library-backed formats (7z, rar, bz2, xz). */
    interface Backend extends Closeable {
        List<Item> list() throws IOException;

        void walk(Want w, Visitor v) throws IOException;
    }

    /** The archive needs a password, or uses a method this build cannot decode. */
    static final class Unsupported extends IOException {
        final boolean encrypted;

        Unsupported(String m, boolean encrypted) {
            super(m);
            this.encrypted = encrypted;
        }
    }

    static final int MAX_ENTRIES = 300000;

    final File file;
    final String format;
    final List<Item> items = new ArrayList<>();
    java.nio.charset.Charset charset = StandardCharsets.UTF_8;

    private ZipReader zip;
    private Backend backend;
    private final int kind;          // K_*

    private static final int K_ZIP = 1, K_TAR = 2, K_TGZ = 3, K_GZ = 4, K_EXTRA = 5, K_TX = 6, K_Z = 7;
    private int sub;                 // 20 = bz2, 21 = xz (for K_TX / K_Z)

    private Arc(File f, String format, int kind) {
        this.file = f;
        this.format = format;
        this.kind = kind;
    }

    // ------------------------------------------------------------------ recognising

    private static final String[] ZIP_EXT = {"zip", "jar", "war", "aar", "cbz", "epub", "kmz", "xpi", "odt", "ods", "odp", "docx", "xlsx", "pptx", "apk"};

    /** True for file names the archive browser can open (by extension). */
    static boolean browsable(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        for (String z : new String[]{"zip", "jar", "war", "aar", "cbz", "tar", "tgz", "gz", "7z", "rar", "bz2", "xz", "tbz2", "txz", "cbr", "cb7"}) {
            if (n.endsWith("." + z)) return true;
        }
        return false;
    }

    private static int magic(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8];
            int r = 0, n;
            while (r < 8 && (n = in.read(b, r, 8 - r)) > 0) r += n;
            if (r >= 4 && b[0] == 'P' && b[1] == 'K' && (b[2] == 3 || b[2] == 5 || b[2] == 7)) return K_ZIP;
            if (r >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) return K_GZ;
            if (r >= 3 && b[0] == 'B' && b[1] == 'Z' && b[2] == 'h') return 20;
            if (r >= 6 && (b[0] & 0xFF) == 0xFD && b[1] == '7' && b[2] == 'z' && b[3] == 'X' && b[4] == 'Z') return 21;
            if (r >= 6 && b[0] == '7' && b[1] == 'z' && (b[2] & 0xFF) == 0xBC && (b[3] & 0xFF) == 0xAF) return 22;
            if (r >= 4 && b[0] == 'R' && b[1] == 'a' && b[2] == 'r' && b[3] == '!') return 23;
        } catch (IOException ignored) {
        }
        return 0;
    }

    private static boolean looksLikeTar(byte[] h, int n) {
        if (n < 512) return false;
        if (h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r') return true;
        // old v7 tar: valid header checksum
        long sum = 0;
        for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? 32 : (h[i] & 0xFF);
        long stored = octal(h, 148, 8);
        return stored > 0 && stored == sum;
    }

    private static byte[] peek(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int r = 0, k;
        while (r < n && (k = in.read(b, r, n - r)) > 0) r += k;
        return r == n ? b : java.util.Arrays.copyOf(b, r);
    }

    // ------------------------------------------------------------------ opening

    static Arc open(File f) throws IOException {
        String name = f.getName().toLowerCase(Locale.ROOT);
        int m = magic(f);
        if (m == 0) {   // wrong or missing signature: trust the extension
            for (String z : ZIP_EXT) if (name.endsWith("." + z)) m = K_ZIP;
            if (m == 0 && name.endsWith(".tar")) m = K_TAR;
            if (m == 0 && (name.endsWith(".gz") || name.endsWith(".tgz"))) m = K_GZ;
            if (m == 0 && name.endsWith(".7z")) m = 22;
            if (m == 0 && (name.endsWith(".rar") || name.endsWith(".cbr"))) m = 23;
            if (m == 0 && (name.endsWith(".bz2") || name.endsWith(".tbz2"))) m = 20;
            if (m == 0 && (name.endsWith(".xz") || name.endsWith(".txz"))) m = 21;
            if (m == 0) {   // a bare tar has "ustar" at 257
                try (InputStream in = new FileInputStream(f)) {
                    byte[] h = peek(in, 512);
                    if (looksLikeTar(h, h.length)) m = K_TAR;
                }
            }
        }
        switch (m) {
            case K_ZIP: {
                Arc a = new Arc(f, "ZIP", K_ZIP);
                a.zip = ZipReader.open(f);
                a.charset = a.zip.charset();
                for (ZipReader.Entry e : a.zip.entries()) {
                    String n = clean(e.name);
                    if (n == null) continue;
                    Item it = new Item();
                    it.name = n;
                    it.dir = e.dir;
                    it.size = e.size;
                    it.packed = e.packed;
                    it.time = e.time;
                    it.crc = e.crc;
                    it.encrypted = e.encrypted();
                    it.method = e.method == 0 ? "Store" : e.method == 8 ? "Deflate" : "#" + e.method;
                    it.ref = e;
                    a.items.add(it);
                    if (a.items.size() >= MAX_ENTRIES) break;
                }
                return a;
            }
            case K_TAR: {
                Arc a = new Arc(f, "TAR", K_TAR);
                a.scanTar();
                return a;
            }
            case K_GZ: {
                try (InputStream in = new GZIPInputStream(new FileInputStream(f), 1 << 16)) {
                    byte[] h = peek(in, 512);
                    if (looksLikeTar(h, h.length)) {
                        Arc a = new Arc(f, "TAR.GZ", K_TGZ);
                        a.scanTar();
                        return a;
                    }
                }
                Arc a = new Arc(f, "GZ", K_GZ);
                String n = f.getName();
                Item it = new Item();
                it.name = n.toLowerCase(Locale.ROOT).endsWith(".gz") && n.length() > 3 ? n.substring(0, n.length() - 3) : n + ".out";
                it.packed = f.length();
                it.time = f.lastModified();
                it.method = "Deflate";
                a.items.add(it);
                return a;
            }
            case 20:
            case 21: {
                InputStream raw = extraStream(f, m);
                if (raw == null) throw new Unsupported("format needs the extra archive module", false);
                try (InputStream in = raw) {
                    byte[] h = peek(in, 512);
                    if (looksLikeTar(h, h.length)) {
                        Arc a = new Arc(f, m == 20 ? "TAR.BZ2" : "TAR.XZ", K_TX);
                        a.sub = m;
                        a.scanTar();
                        return a;
                    }
                }
                Arc a = new Arc(f, m == 20 ? "BZ2" : "XZ", K_Z);
                a.sub = m;
                String n = f.getName();
                int dot = n.lastIndexOf('.');
                Item it = new Item();
                it.name = dot > 0 ? n.substring(0, dot) : n + ".out";
                it.packed = f.length();
                it.time = f.lastModified();
                it.method = m == 20 ? "BZip2" : "LZMA2";
                a.items.add(it);
                return a;
            }
            default: {
                Backend b = extra(f, m);
                if (b == null) throw new Unsupported("format needs the extra archive module", false);
                String fmt = m == 22 ? "7Z" : "RAR";
                Arc a = new Arc(f, fmt, K_EXTRA);
                a.backend = b;
                try {
                    for (Item it : b.list()) {
                        String n = clean(it.name);
                        if (n == null) continue;
                        it.name = n;
                        a.items.add(it);
                        if (a.items.size() >= MAX_ENTRIES) break;
                    }
                } catch (IOException | RuntimeException e) {
                    try {
                        b.close();
                    } catch (IOException ignored) {
                    }
                    throw e;
                }
                return a;
            }
        }
    }

    /** Loads the optional library-backed formats by name, so the app still builds and runs without them. */
    private static Backend extra(File f, int kind) throws IOException {
        try {
            Class<?> c = Class.forName("com.fileman.app.ArcExtra");
            return (Backend) c.getMethod("open", File.class, int.class).invoke(null, f, kind);
        } catch (ClassNotFoundException | NoClassDefFoundError | NoSuchMethodException | IllegalAccessException e) {
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof NoClassDefFoundError) return null;
            throw new IOException(String.valueOf(t));
        }
    }

    /** Decompressing stream for bz2 / xz files, or null when the extra module is not in the build. */
    private static InputStream extraStream(File f, int kind) throws IOException {
        try {
            Class<?> c = Class.forName("com.fileman.app.ArcExtra");
            return (InputStream) c.getMethod("stream", File.class, int.class).invoke(null, f, kind);
        } catch (ClassNotFoundException | NoClassDefFoundError | NoSuchMethodException | IllegalAccessException e) {
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof NoClassDefFoundError) return null;
            throw new IOException(String.valueOf(t));
        }
    }

    /** Normalises an entry name; null for names that must never be used (parent-relative or empty). */
    static String clean(String raw) {
        String n = raw.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        while (n.startsWith("./")) n = n.substring(2);
        if (n.isEmpty()) return null;
        for (String part : n.split("/")) if (part.equals("..")) return null;
        return n;
    }

    boolean randomAccess() {
        return kind == K_ZIP;
    }

    @Override
    public void close() throws IOException {
        if (zip != null) zip.close();
        if (backend != null) backend.close();
    }

    // ------------------------------------------------------------------ reading

    /** Opens one entry. Zip only; use {@link #walk} for the other formats. */
    InputStream stream(Item it) throws IOException {
        if (kind != K_ZIP) throw new IOException("sequential archive");
        ZipReader.Entry e = (ZipReader.Entry) it.ref;
        try {
            return zip.open(e);
        } catch (ZipException ex) {
            String m = String.valueOf(ex.getMessage());
            if (m.contains("encrypted")) throw new Unsupported(m, true);
            if (m.contains("unsupported method")) throw new Unsupported(m, false);
            throw ex;
        }
    }

    /** Visits every wanted file entry once, in archive order. Works for every format. */
    void walk(Want want, Visitor v) throws IOException {
        switch (kind) {
            case K_ZIP:
                for (Item it : items) {
                    if (it.dir || !want.test(it)) continue;
                    try (InputStream in = stream(it)) {
                        v.file(it, in);
                    }
                }
                break;
            case K_TAR:
            case K_TGZ:
            case K_TX:
                try (InputStream raw = tarStream()) {
                    TarWalker w = new TarWalker(raw);
                    Item it;
                    while ((it = w.next()) != null) {
                        if (it.dir || it.ref != null || !want.test(it)) {
                            w.skipData();
                            continue;
                        }
                        v.file(it, w.data());
                    }
                }
                break;
            case K_GZ:
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16), 1 << 16)) {
                    Item it = items.get(0);
                    if (want.test(it)) v.file(it, in);
                }
                break;
            case K_Z:
                try (InputStream in = extraStream(file, sub)) {
                    if (in == null) throw new Unsupported("format needs the extra archive module", false);
                    Item it = items.get(0);
                    if (want.test(it)) v.file(it, in);
                }
                break;
            default:
                backend.walk(want, v);
        }
    }

    /** Copies one entry to a file; stops with an IOException if it would exceed {@code limit} bytes. */
    void copyTo(Item item, File out, long limit, ZipWriter.Sink sink) throws IOException {
        final boolean[] found = {false};
        final String path = item.name;
        walk(it -> !found[0] && it.name.equals(path), (it, in) -> {
            found[0] = true;
            copy(in, out, limit, sink);
        });
        if (!found[0]) throw new IOException("missing entry");
    }

    static long copy(InputStream in, File out, long limit, ZipWriter.Sink sink) throws IOException {
        long total = 0;
        boolean ok = false;
        try (OutputStream os = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 17)) {
            byte[] buf = new byte[1 << 17];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (sink != null && sink.cancelled()) throw new ZipWriter.Cancelled();
                total += r;
                if (total > limit) throw new IOException("not enough space");
                os.write(buf, 0, r);
                if (sink != null) sink.bytes(r);
            }
            ok = true;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
        return total;
    }

    // ------------------------------------------------------------------ TAR

    private InputStream tarStream() throws IOException {
        if (kind == K_TX) {
            InputStream x = extraStream(file, sub);
            if (x == null) throw new Unsupported("format needs the extra archive module", false);
            return x;
        }
        InputStream fin = new BufferedInputStream(new FileInputStream(file), 1 << 16);
        return kind == K_TGZ ? new GZIPInputStream(fin, 1 << 16) : fin;
    }

    private void scanTar() throws IOException {
        try (InputStream raw = tarStream()) {
            TarWalker w = new TarWalker(raw);
            Item it;
            while ((it = w.next()) != null) {
                if (it.ref == null) items.add(it);
                w.skipData();
                if (items.size() >= MAX_ENTRIES) break;
            }
        }
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {   // GNU base-256 for huge values
            long v = b[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        int i = off, end = off + len;
        while (i < end && (b[i] == ' ' || b[i] == 0)) i++;
        for (; i < end && b[i] >= '0' && b[i] <= '7'; i++) v = (v << 3) | (b[i] - '0');
        return v;
    }

    private static String cstr(byte[] b, int off, int len) {
        int e = off;
        while (e < off + len && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.UTF_8);
    }

    /** Reads tar headers one after another (ustar, GNU long names, pax). Links and devices are reported but flagged unusable. */
    private static final class TarWalker {
        private final InputStream in;
        private long remaining, padding;
        private final byte[] hdr = new byte[512];

        TarWalker(InputStream in) {
            this.in = in;
        }

        Item next() throws IOException {
            skipData();
            String longName = null;
            long paxSize = -1, paxTime = -1;
            String paxPath = null;
            for (; ; ) {
                int r = readFull(hdr, 512);
                if (r < 512) return null;
                boolean zero = true;
                for (byte x : hdr) if (x != 0) {
                    zero = false;
                    break;
                }
                if (zero) return null;
                char type = (char) (hdr[156] & 0xFF);
                long size = octal(hdr, 124, 12);
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    if (size > 1 << 20) throw new IOException("bad tar header");
                    byte[] data = new byte[(int) size];
                    if (readFull(data, (int) size) < size) throw new EOFException();
                    skipExact(pad(size));
                    if (type == 'L') longName = cstr(data, 0, data.length);
                    else if (type == 'x') {
                        int p = 0;
                        while (p < data.length) {
                            int sp = p;
                            while (sp < data.length && data[sp] != ' ') sp++;
                            int len;
                            try {
                                len = Integer.parseInt(new String(data, p, sp - p, StandardCharsets.US_ASCII));
                            } catch (NumberFormatException e) {
                                break;
                            }
                            if (len <= 0 || p + len > data.length) break;
                            String rec = new String(data, sp + 1, p + len - sp - 2, StandardCharsets.UTF_8);
                            int eq = rec.indexOf('=');
                            if (eq > 0) {
                                String k = rec.substring(0, eq), val = rec.substring(eq + 1);
                                if (k.equals("path")) paxPath = val;
                                else if (k.equals("size")) paxSize = parseLong(val);
                                else if (k.equals("mtime")) paxTime = (long) (Double.parseDouble(val.isEmpty() ? "0" : val) * 1000);
                            }
                            p += len;
                        }
                    }
                    continue;
                }
                String name = longName != null ? longName : cstr(hdr, 0, 100);
                if (longName == null && hdr[257] == 'u' && hdr[258] == 's') {
                    String prefix = cstr(hdr, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                if (paxPath != null) name = paxPath;
                if (paxSize >= 0) size = paxSize;
                Item it = new Item();
                it.dir = type == '5' || name.endsWith("/");
                it.name = it.dir && !name.endsWith("/") ? name + "/" : name;
                it.size = it.dir ? 0 : size;
                it.packed = it.size;
                it.time = (paxTime >= 0 ? paxTime : octal(hdr, 136, 12) * 1000);
                it.method = "Tar";
                if (type == '1' || type == '2' || type == '3' || type == '4' || type == '6') {
                    it.ref = Boolean.TRUE;      // links, devices, fifos: never extracted
                    it.size = 0;
                }
                remaining = it.ref != null ? 0 : (it.dir ? 0 : size);
                padding = it.ref != null || it.dir ? 0 : pad(size);
                if (it.ref != null && size > 0) skipExact(size + pad(size));
                return it;
            }
        }

        InputStream data() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] b = new byte[1];
                    int r = read(b, 0, 1);
                    return r < 0 ? -1 : (b[0] & 0xFF);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int r = in.read(b, off, (int) Math.min(len, remaining));
                    if (r < 0) throw new EOFException("truncated tar");
                    remaining -= r;
                    return r;
                }
            };
        }

        void skipData() throws IOException {
            skipExact(remaining + padding);
            remaining = 0;
            padding = 0;
        }

        private static long pad(long size) {
            return (512 - (size % 512)) % 512;
        }

        private static long parseLong(String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private int readFull(byte[] b, int n) throws IOException {
            int r = 0, k;
            while (r < n && (k = in.read(b, r, n - r)) > 0) r += k;
            return r;
        }

        private void skipExact(long n) throws IOException {
            byte[] sink = null;
            while (n > 0) {
                long s = in.skip(n);
                if (s <= 0) {
                    if (sink == null) sink = new byte[1 << 14];
                    int r = in.read(sink, 0, (int) Math.min(sink.length, n));
                    if (r < 0) throw new EOFException("truncated tar");
                    s = r;
                }
                n -= s;
            }
        }
    }
}
