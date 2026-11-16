package com.fileman.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal writer for OLE2 / Compound File containers (version 4, 4096-byte sectors, no mini stream): puts the given
 * streams directly under the root. Used to hand a decrypted copy of a legacy Office file to the readers of this app.
 */
final class Ole2Writer {
    private static final int SS = 4096;
    private static final int FREESECT = -1, ENDCHAIN = -2, FATSECT = -3;

    private Ole2Writer() {
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static int sectorsFor(long len) {
        return (int) ((len + SS - 1) / SS);
    }

    static void write(File out, Map<String, byte[]> streams) throws IOException {
        List<String> names = new ArrayList<>(streams.keySet());
        int entries = names.size() + 1;
        int dirSectors = sectorsFor((long) entries * 128);
        int[] start = new int[names.size()];
        int next = 0;
        for (int i = 0; i < names.size(); i++) {
            byte[] d = streams.get(names.get(i));
            int n = sectorsFor(d.length);
            start[i] = n == 0 ? ENDCHAIN : next;
            next += n;
        }
        int dataSectors = next;
        int dirStart = next;
        int fatSectors = 0;
        while (fatSectors * (SS / 4) < dataSectors + dirSectors + fatSectors) fatSectors++;
        if (fatSectors > 109) throw new IOException("file too large");
        int fatStart = dataSectors + dirSectors;
        int total = fatStart + fatSectors;

        int[] fat = new int[fatSectors * (SS / 4)];
        java.util.Arrays.fill(fat, FREESECT);
        for (int i = 0; i < names.size(); i++) {
            int n = sectorsFor(streams.get(names.get(i)).length);
            for (int k = 0; k < n; k++) fat[start[i] + k] = k == n - 1 ? ENDCHAIN : start[i] + k + 1;
        }
        for (int k = 0; k < dirSectors; k++) fat[dirStart + k] = k == dirSectors - 1 ? ENDCHAIN : dirStart + k + 1;
        for (int k = 0; k < fatSectors; k++) fat[fatStart + k] = FATSECT;

        byte[] h = new byte[SS];
        byte[] sig = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(sig, 0, h, 0, 8);
        put16(h, 24, 0x003E);
        put16(h, 26, 4);
        put16(h, 28, 0xFFFE);
        put16(h, 30, 12);
        put16(h, 32, 6);
        put32(h, 40, dirSectors);
        put32(h, 44, fatSectors);
        put32(h, 48, dirStart);
        put32(h, 56, 0);
        put32(h, 60, ENDCHAIN);
        put32(h, 64, 0);
        put32(h, 68, ENDCHAIN);
        put32(h, 72, 0);
        for (int i = 0; i < 109; i++) put32(h, 76 + i * 4, i < fatSectors ? fatStart + i : FREESECT);

        byte[] dir = new byte[dirSectors * SS];
        for (int o = 0; o < dir.length; o += 128) {
            put32(dir, o + 68, FREESECT);
            put32(dir, o + 72, FREESECT);
            put32(dir, o + 76, FREESECT);
        }
        writeEntry(dir, 0, "Root Entry", 5, FREESECT, names.isEmpty() ? FREESECT : 1, ENDCHAIN, 0);
        for (int i = 0; i < names.size(); i++) {
            int right = i + 1 < names.size() ? i + 2 : FREESECT;
            writeEntry(dir, i + 1, names.get(i), 2, FREESECT, FREESECT, start[i], streams.get(names.get(i)).length);
            put32(dir, (i + 1) * 128 + 72, right);
        }

        byte[] fatBytes = new byte[fatSectors * SS];
        for (int i = 0; i < fat.length; i++) put32(fatBytes, i * 4, fat[i]);

        try (OutputStream os = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            os.write(h);
            byte[] pad = new byte[SS];
            for (String n : names) {
                byte[] d = streams.get(n);
                os.write(d);
                int rem = d.length % SS;
                if (rem != 0) os.write(pad, 0, SS - rem);
            }
            os.write(dir);
            os.write(fatBytes);
        }
        if (total < 0) throw new IOException();
    }

    private static void writeEntry(byte[] dir, int index, String name, int type, int left, int child, int start, long size) {
        int o = index * 128;
        byte[] nb = name.getBytes(StandardCharsets.UTF_16LE);
        int len = Math.min(nb.length, 62);
        System.arraycopy(nb, 0, dir, o, len);
        put16(dir, o + 64, len + 2);
        dir[o + 66] = (byte) type;
        dir[o + 67] = 1;
        put32(dir, o + 68, left);
        put32(dir, o + 76, child);
        put32(dir, o + 116, start);
        put32(dir, o + 120, (int) size);
        put32(dir, o + 124, (int) (size >>> 32));
    }
}
