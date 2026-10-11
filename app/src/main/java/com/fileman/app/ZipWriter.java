package com.fileman.app;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Streaming ZIP writer with ZIP64 support. {@link java.util.zip.ZipOutputStream} on Android cannot store an
 * entry above 4 GB and re-reads nothing, so big videos and backups failed to compress. This writer:
 * <ul>
 *   <li>uses data descriptors, so files are read once and never need their CRC in advance,</li>
 *   <li>switches an entry (and the archive) to ZIP64 only when needed, keeping small archives compatible with every tool,</li>
 *   <li>stores already-compressed formats (video, images, archives...) with level 0 instead of wasting CPU,</li>
 *   <li>names are written as UTF-8 with the language-encoding flag, so Arabic names survive on every platform.</li>
 * </ul>
 */
final class ZipWriter implements Closeable {
    /** Progress / cancellation hook. */
    interface Sink {
        void bytes(long n);

        boolean cancelled();
    }

    private static final long LIMIT = 0xFFFFFFFFL;

    private static final class Rec {
        byte[] name;
        long crc, size, packed, offset, dosTime;
        boolean zip64Entry, dir;
        int method;
    }

    private final CountOut out;
    private final List<Rec> recs = new ArrayList<>();
    private final Sink sink;
    private final byte[] buf = new byte[1 << 17];
    private int level = Deflater.DEFAULT_COMPRESSION;
    private boolean closed;

    ZipWriter(File dest, Sink sink) throws IOException {
        this.out = new CountOut(new BufferedOutputStream(new FileOutputStream(dest), 1 << 17));
        this.sink = sink;
    }

    /** Compression level 0..9 for the entries added next (-1 = default). */
    void setLevel(int level) {
        this.level = level;
    }

    /** Extensions whose data is already compressed: deflating them again costs time and gains nothing. */
    static boolean alreadyCompressed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        if (i < 0) return false;
        switch (n.substring(i + 1)) {
            case "zip": case "jar": case "apk": case "apks": case "xapk": case "apkm": case "7z": case "rar":
            case "gz": case "tgz": case "bz2": case "xz": case "zst": case "lz4": case "cab": case "iso":
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "heic": case "heif": case "avif":
            case "mp3": case "m4a": case "aac": case "ogg": case "opus": case "flac": case "wma":
            case "mp4": case "mkv": case "webm": case "avi": case "mov": case "m4v": case "3gp": case "wmv": case "flv":
            case "docx": case "xlsx": case "pptx": case "odt": case "ods": case "odp": case "epub": case "cbz":
                return true;
            default:
                return false;
        }
    }

    void addDirectory(String entry, long mtime) throws IOException {
        Rec r = new Rec();
        r.name = (entry.endsWith("/") ? entry : entry + "/").getBytes(StandardCharsets.UTF_8);
        r.dir = true;
        r.dosTime = dos(mtime);
        r.offset = out.count;
        r.method = 0;
        writeLocal(r, false);
        recs.add(r);
    }

    /** Adds a file; throws {@link Cancelled} if the sink asks to stop. */
    void addFile(String entry, File f) throws IOException {
        Rec r = new Rec();
        r.name = entry.getBytes(StandardCharsets.UTF_8);
        r.dosTime = dos(f.lastModified());
        r.offset = out.count;
        long len = f.length();
        r.zip64Entry = len >= LIMIT - (8L << 20);   // headroom: deflate may expand incompressible data slightly
        boolean store = alreadyCompressed(f.getName());
        r.method = 8;
        Deflater def = new Deflater(store ? 0 : level, true);
        writeLocal(r, true);
        CRC32 crc = new CRC32();
        long start = out.count;
        long total = 0;
        try (InputStream in = new FileInputStream(f)) {
            byte[] chunk = new byte[1 << 16];
            byte[] outBuf = new byte[1 << 16];
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (sink != null && sink.cancelled()) throw new Cancelled();
                crc.update(chunk, 0, n);
                total += n;
                def.setInput(chunk, 0, n);
                while (!def.needsInput()) {
                    int d = def.deflate(outBuf);
                    if (d > 0) out.write(outBuf, 0, d);
                }
                if (sink != null) sink.bytes(n);
            }
            def.finish();
            while (!def.finished()) {
                int d = def.deflate(outBuf);
                if (d > 0) out.write(outBuf, 0, d);
            }
        } finally {
            def.end();
        }
        r.crc = crc.getValue();
        r.size = total;
        r.packed = out.count - start;
        if (!r.zip64Entry && (r.size >= LIMIT || r.packed >= LIMIT)) {
            throw new IOException("file grew beyond the size announced (zip64 needed): " + f.getName());
        }
        // data descriptor
        putInt(0x08074b50L);
        putInt(r.crc);
        if (r.zip64Entry) {
            putLong(r.packed);
            putLong(r.size);
        } else {
            putInt(r.packed);
            putInt(r.size);
        }
        recs.add(r);
    }

    /** Thrown when {@link Sink#cancelled()} becomes true. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    private void writeLocal(Rec r, boolean descriptor) throws IOException {
        int flags = 0x800 | (descriptor ? 0x08 : 0);
        putInt(0x04034b50L);
        putShort(r.zip64Entry ? 45 : 20);
        putShort(flags);
        putShort(r.method);
        putInt(r.dosTime);
        putInt(0);                                   // crc: in the data descriptor
        if (r.zip64Entry) {
            putInt(LIMIT);
            putInt(LIMIT);
        } else {
            putInt(0);
            putInt(0);
        }
        putShort(r.name.length);
        putShort(r.zip64Entry ? 20 : 0);
        out.write(r.name);
        if (r.zip64Entry) {
            putShort(0x0001);
            putShort(16);
            putLong(0);
            putLong(0);
        }
    }

    private static long dos(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t <= 0 ? System.currentTimeMillis() : t);
        int y = c.get(Calendar.YEAR);
        if (y < 1980) return (1L << 21) | (1L << 16);     // 1980-01-01
        long date = ((y - 1980) << 9) | ((c.get(Calendar.MONTH) + 1) << 5) | c.get(Calendar.DAY_OF_MONTH);
        long time = (c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5) | (c.get(Calendar.SECOND) / 2);
        return (date << 16) | time;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            long cdStart = out.count;
            for (Rec r : recs) {
                boolean bigSize = r.size >= LIMIT || r.packed >= LIMIT;
                boolean bigOff = r.offset >= LIMIT;
                int extraLen = 0;
                if (bigSize) extraLen += 16;
                if (bigOff) extraLen += 8;
                if (extraLen > 0) extraLen += 4;
                boolean z64 = bigSize || bigOff || r.zip64Entry;
                putInt(0x02014b50L);
                putShort(z64 ? 0x032D : 0x0314);       // made by Unix, version 4.5 / 2.0
                putShort(z64 ? 45 : 20);
                putShort(0x800 | (r.dir ? 0 : 0x08));
                putShort(r.method);
                putInt(r.dosTime);
                putInt(r.crc);
                putInt(bigSize ? LIMIT : r.packed);
                putInt(bigSize ? LIMIT : r.size);
                putShort(r.name.length);
                putShort(extraLen);
                putShort(0);
                putShort(0);
                putShort(0);
                putInt(r.dir ? 0x41ED0010L : 0x81A40000L);   // unix rwx permissions in the high 16 bits
                putInt(bigOff ? LIMIT : r.offset);
                out.write(r.name);
                if (extraLen > 0) {
                    putShort(0x0001);
                    putShort(extraLen - 4);
                    if (bigSize) {
                        putLong(r.size);
                        putLong(r.packed);
                    }
                    if (bigOff) putLong(r.offset);
                }
            }
            long cdSize = out.count - cdStart;
            long n = recs.size();
            boolean need64 = n >= 0xFFFF || cdStart >= LIMIT || cdSize >= LIMIT;
            if (need64) {
                long z64Pos = out.count;
                putInt(0x06064b50L);
                putLong(44);
                putShort(0x032D);
                putShort(45);
                putInt(0);
                putInt(0);
                putLong(n);
                putLong(n);
                putLong(cdSize);
                putLong(cdStart);
                putInt(0x07064b50L);
                putInt(0);
                putLong(z64Pos);
                putInt(1);
            }
            putInt(0x06054b50L);
            putShort(0);
            putShort(0);
            putShort(need64 ? 0xFFFF : (int) n);
            putShort(need64 ? 0xFFFF : (int) n);
            putInt(need64 ? LIMIT : cdSize);
            putInt(need64 ? LIMIT : cdStart);
            putShort(0);
            out.flush();
        } finally {
            out.close();
        }
    }

    // ------------------------------------------------------------------ little-endian helpers

    private void putShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private void putInt(long v) throws IOException {
        byte[] b = {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
        out.write(b, 0, 4);
    }

    private void putLong(long v) throws IOException {
        putInt(v & 0xFFFFFFFFL);
        putInt((v >>> 32) & 0xFFFFFFFFL);
    }

    private static final class CountOut extends OutputStream {
        private final OutputStream o;
        long count;

        CountOut(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            o.flush();
        }

        @Override
        public void close() throws IOException {
            o.close();
        }
    }
}
