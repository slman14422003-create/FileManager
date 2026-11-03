package com.fileman.app;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader for OLE2 / Compound File Binary containers, the wrapper of the old binary Office formats
 * (.doc, .xls, .ppt). Reads versions 3 and 4 (512 / 4096-byte sectors), the FAT, the mini-FAT and the directory,
 * and returns the named streams. Read-only; sizes are capped so a damaged file cannot exhaust memory.
 */
final class Ole2 implements Closeable {
    static final long MAX_STREAM = 120L * 1024 * 1024;

    private static final int FREE = -1, END = -2;

    private final RandomAccessFile raf;
    private final int secSize, miniSize = 64;
    private final long miniCutoff;
    private int[] fat;
    private int[] miniFat;
    private byte[] miniStream;
    private final List<Dir> dirs = new ArrayList<>();

    private static final class Dir {
        String name;
        int type, left, right, child, start;
        long size;
    }

    static boolean isOle2(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[8];
            if (in.read(b) != 8) return false;
            return (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && b[2] == 0x11 && (b[3] & 0xFF) == 0xE0
                    && (b[4] & 0xFF) == 0xA1 && (b[5] & 0xFF) == 0xB1 && b[6] == 0x1A && (b[7] & 0xFF) == 0xE1;
        } catch (IOException e) {
            return false;
        }
    }

    Ole2(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        try {
            byte[] h = new byte[512];
            raf.readFully(h);
            if (!(isOle2(f))) throw new IOException("not an OLE2 file");
            int shift = le16(h, 30);
            if (shift != 9 && shift != 12) throw new IOException("bad sector size");
            secSize = 1 << shift;
            int dirStart = le32(h, 48);
            miniCutoff = le32(h, 56) & 0xFFFFFFFFL;
            int miniFatStart = le32(h, 60);
            int miniFatCount = le32(h, 64);
            int difStart = le32(h, 68);
            int difCount = le32(h, 72);
            int fatCount = le32(h, 44);
            if (fatCount < 0 || fatCount > 1 << 20) throw new IOException("bad FAT");

            // FAT sector list: 109 in the header, the rest through the DIFAT chain
            List<Integer> fatSecs = new ArrayList<>();
            for (int i = 0; i < 109 && fatSecs.size() < fatCount; i++) {
                int s = le32(h, 76 + i * 4);
                if (s >= 0) fatSecs.add(s);
            }
            int dif = difStart;
            for (int i = 0; i < difCount && dif >= 0 && fatSecs.size() < fatCount; i++) {
                byte[] d = sector(dif);
                int per = secSize / 4 - 1;
                for (int k = 0; k < per && fatSecs.size() < fatCount; k++) {
                    int s = le32(d, k * 4);
                    if (s >= 0) fatSecs.add(s);
                }
                dif = le32(d, per * 4);
            }
            fat = new int[fatSecs.size() * (secSize / 4)];
            int p = 0;
            for (int s : fatSecs) {
                byte[] d = sector(s);
                for (int k = 0; k < secSize / 4; k++) fat[p++] = le32(d, k * 4);
            }
            // directory
            byte[] dirData = chain(dirStart, -1, false);
            for (int o = 0; o + 128 <= dirData.length; o += 128) {
                Dir d = new Dir();
                int nl = le16(dirData, o + 64);
                d.name = nl >= 2 ? new String(dirData, o, Math.min(nl - 2, 62), StandardCharsets.UTF_16LE) : "";
                d.type = dirData[o + 66] & 0xFF;
                d.left = le32(dirData, o + 68);
                d.right = le32(dirData, o + 72);
                d.child = le32(dirData, o + 76);
                d.start = le32(dirData, o + 116);
                d.size = (le32(dirData, o + 120) & 0xFFFFFFFFL) | (secSize == 4096 ? ((long) le32(dirData, o + 124) << 32) : 0);
                dirs.add(d);
            }
            if (dirs.isEmpty()) throw new IOException("empty directory");
            // mini stream
            if (miniFatCount > 0 && miniFatStart >= 0) {
                byte[] mf = chain(miniFatStart, -1, false);
                miniFat = new int[mf.length / 4];
                for (int i = 0; i < miniFat.length; i++) miniFat[i] = le32(mf, i * 4);
                Dir root = dirs.get(0);
                miniStream = chain(root.start, root.size, false);
            }
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private byte[] sector(int n) throws IOException {
        byte[] b = new byte[secSize];
        long pos = (long) (n + 1) * secSize;
        if (pos + secSize > raf.length() + secSize) throw new IOException("sector beyond end");
        raf.seek(pos);
        int r = raf.read(b);
        if (r < 0) throw new IOException("sector beyond end");
        return b;
    }

    /** Follows a sector chain; {@code size < 0} means "all of it". */
    private byte[] chain(int start, long size, boolean mini) throws IOException {
        if (size > MAX_STREAM) throw new IOException("stream too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, Math.min(size < 0 ? secSize : size, 1 << 24)));
        int guard = 0;
        int s = start;
        int unit = mini ? miniSize : secSize;
        while (s >= 0 && guard++ < 20_000_000) {
            if (mini) {
                int off = s * miniSize;
                if (miniStream == null || off + miniSize > miniStream.length) throw new IOException("bad mini sector");
                out.write(miniStream, off, miniSize);
                s = s < miniFat.length ? miniFat[s] : END;
            } else {
                if (s >= fat.length && fat.length > 0) throw new IOException("bad sector index");
                out.write(sector(s));
                s = fat.length > 0 ? fat[s] : END;
            }
            if (out.size() > MAX_STREAM) throw new IOException("stream too large");
            if (size >= 0 && out.size() >= size) break;
        }
        byte[] all = out.toByteArray();
        if (size >= 0 && all.length > size) return java.util.Arrays.copyOf(all, (int) size);
        return all;
    }

    /** Names of the streams directly under the root. */
    List<String> streamNames() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < dirs.size(); i++) if (dirs.get(i).type == 2) out.add(dirs.get(i).name);
        return out;
    }

    /** A stream of the root storage by (case-insensitive) name, or null. */
    byte[] stream(String name) throws IOException {
        for (int i = 1; i < dirs.size(); i++) {
            Dir d = dirs.get(i);
            if (d.type != 2 || !d.name.equalsIgnoreCase(name)) continue;
            boolean mini = d.size < miniCutoff;
            return chain(d.start, d.size, mini);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
