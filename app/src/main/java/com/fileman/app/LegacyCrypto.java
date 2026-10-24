package com.fileman.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Password protected legacy Office files (doc, xls, ppt): the RC4 and RC4 CryptoAPI schemes. The file is decrypted
 * into a plain OLE2 copy that the normal readers ({@link LegacyDoc}, {@link LegacyXls}, {@link LegacyPpt}) open.
 */
final class LegacyCrypto {
    private LegacyCrypto() {
    }

    // ------------------------------------------------------------------ small helpers

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static MessageDigest md(String alg) throws IOException {
        try {
            return MessageDigest.getInstance(alg);
        } catch (Exception e) {
            throw new OfficeCrypto.Unsupported(alg);
        }
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static boolean same(byte[] a, byte[] b, int n) {
        if (a.length < n || b.length < n) return false;
        int d = 0;
        for (int i = 0; i < n; i++) d |= a[i] ^ b[i];
        return d == 0;
    }

    private static byte[] range(byte[] b, int from, int len) throws IOException {
        if (from < 0 || len < 0 || from + len > b.length) throw new IOException("short data");
        return Arrays.copyOfRange(b, from, from + len);
    }

    // ------------------------------------------------------------------ RC4

    private static final class Rc4 {
        private final int[] s = new int[256];
        private int i, j;

        Rc4(byte[] key) {
            for (int k = 0; k < 256; k++) s[k] = k;
            int jj = 0;
            for (int k = 0; k < 256; k++) {
                jj = (jj + s[k] + (key[k % key.length] & 0xFF)) & 0xFF;
                int t = s[k];
                s[k] = s[jj];
                s[jj] = t;
            }
        }

        int next() {
            i = (i + 1) & 0xFF;
            j = (j + s[i]) & 0xFF;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            return s[(s[i] + s[j]) & 0xFF];
        }

        void skip(long n) {
            while (n-- > 0) next();
        }

        void xor(byte[] b, int off, int len) {
            for (int k = 0; k < len; k++) b[off + k] ^= (byte) next();
        }
    }

    // ------------------------------------------------------------------ key schemes

    /** A password-checked key source: one RC4 key per block number. */
    private abstract static class Scheme {
        abstract Rc4 block(int n);
    }

    private static byte[] utf16(String pw, int maxChars) {
        String p = pw == null ? "" : pw;
        if (p.length() > maxChars) p = p.substring(0, maxChars);
        return p.getBytes(StandardCharsets.UTF_16LE);
    }

    /** Office 97-2003 RC4 (version 1.1): salt, encrypted verifier and verifier hash, 16 bytes each. */
    private static Scheme rc4Binary(byte[] salt, byte[] encVerifier, byte[] encHash, String pw) throws IOException {
        final MessageDigest md5 = md("MD5");
        byte[] h0 = md5.digest(utf16(pw, 15));
        md5.reset();
        byte[] seed = cat(Arrays.copyOf(h0, 5), salt);
        for (int k = 0; k < 16; k++) md5.update(seed);
        final byte[] h1 = Arrays.copyOf(md5.digest(), 5);
        Scheme sc = new Scheme() {
            @Override
            Rc4 block(int n) {
                try {
                    MessageDigest m = MessageDigest.getInstance("MD5");
                    return new Rc4(Arrays.copyOf(m.digest(cat(h1, le32(n))), 16));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        Rc4 r = sc.block(0);
        byte[] v = encVerifier.clone();
        byte[] h = encHash.clone();
        r.xor(v, 0, 16);
        r.xor(h, 0, 16);
        if (!same(md("MD5").digest(v), h, 16)) throw new OfficeCrypto.BadPassword();
        return sc;
    }

    /**
     * RC4 CryptoAPI (versions 2.2 / 3.2 / 4.2). {@code info} starts at the version fields: major, minor, flags,
     * header size, header, verifier.
     */
    private static Scheme cryptoApi(byte[] info, final String pw) throws IOException {
        if (info.length < 16) throw new IOException("short header");
        int flags = (int) u32(info, 4);
        int hdrSize = (int) u32(info, 8);
        if (hdrSize < 32 || 12 + hdrSize > info.length) throw new IOException("bad header");
        int algId = (int) u32(info, 20);
        int hashId = (int) u32(info, 24);
        int keyBits = (int) u32(info, 28);
        if ((algId != 0 && algId != 0x6801) || (hashId != 0 && hashId != 0x8004)) {
            throw new OfficeCrypto.Unsupported("algorithm " + algId);
        }
        if (keyBits == 0) keyBits = 40;
        if (keyBits % 8 != 0 || keyBits < 40 || keyBits > 128) throw new OfficeCrypto.Unsupported("key " + keyBits);
        int p = 12 + hdrSize;
        int saltSize = (int) u32(info, p);
        if (saltSize != 16) throw new IOException("bad salt");
        byte[] salt = range(info, p + 4, 16);
        byte[] encV = range(info, p + 20, 16);
        int hashSize = (int) u32(info, p + 36);
        if (hashSize < 20) hashSize = 20;
        byte[] encH = range(info, p + 40, Math.min(hashSize, info.length - (p + 40)));
        if (flags < 0) throw new IOException();

        final MessageDigest sha = md("SHA-1");
        sha.update(salt);
        final byte[] h0 = sha.digest(utf16(pw, 255));
        final int keyLen = keyBits / 8;
        // a 40-bit key is used as 5 bytes by some writers and padded to 16 by others: accept whichever verifies
        int[] lens = keyLen == 5 ? new int[]{5, 16} : new int[]{keyLen};
        for (final int len : lens) {
            Scheme sc = new Scheme() {
                @Override
                Rc4 block(int n) {
                    try {
                        byte[] k = MessageDigest.getInstance("SHA-1").digest(cat(h0, le32(n)));
                        byte[] key = new byte[len];
                        System.arraycopy(k, 0, key, 0, Math.min(len, keyLen));
                        return new Rc4(key);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            Rc4 r = sc.block(0);
            byte[] v = encV.clone();
            byte[] h = encH.clone();
            r.xor(v, 0, 16);
            r.xor(h, 0, h.length);
            if (same(md("SHA-1").digest(v), h, 20)) return sc;
        }
        throw new OfficeCrypto.BadPassword();
    }

    // ------------------------------------------------------------------ entry point

    /** Writes a plain copy of the protected legacy file {@code in} (extension {@code ext}) to {@code out}. */
    static void decrypt(File in, String ext, String password, File out) throws IOException {
        Map<String, byte[]> streams = new LinkedHashMap<>();
        try (Ole2 o = new Ole2(in)) {
            if (ext.equals("doc") || ext.equals("dot")) {
                doc(o, password, streams);
            } else if (ext.equals("xls") || ext.equals("xlt")) {
                xls(o, password, streams);
            } else if (ext.equals("ppt") || ext.equals("pps") || ext.equals("pot")) {
                ppt(o, password, streams);
            } else {
                throw new OfficeCrypto.Unsupported(ext);
            }
        }
        try {
            Ole2Writer.write(out, streams);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
    }

    // ------------------------------------------------------------------ block cursor

    /** Walks a stream in fixed-size blocks, re-keying at each block boundary. */
    private static final class Cursor {
        private final Scheme sc;
        private final int blockSize;
        private int block, inBlock;
        private Rc4 rc;

        Cursor(Scheme sc, int blockSize, long offset) {
            this.sc = sc;
            this.blockSize = blockSize;
            block = (int) (offset / blockSize);
            inBlock = (int) (offset % blockSize);
            rc = sc.block(block);
            rc.skip(inBlock);
        }

        /** Consumes n bytes of key stream; decrypts data[off..off+n) in place when decrypt is true. */
        void step(byte[] data, int off, int n, boolean decrypt) {
            while (n > 0) {
                int k = Math.min(n, blockSize - inBlock);
                if (decrypt) rc.xor(data, off, k);
                else rc.skip(k);
                off += k;
                n -= k;
                inBlock += k;
                if (inBlock == blockSize) {
                    block++;
                    inBlock = 0;
                    rc = sc.block(block);
                }
            }
        }
    }

    // ------------------------------------------------------------------ xls

    private static final Set<Integer> XLS_PLAIN = new HashSet<>(Arrays.asList(
            0x0809, 0x002F, 0x0194, 0x0195, 0x00E1, 0x0196, 0x0138));

    private static void xls(Ole2 o, String pw, Map<String, byte[]> streams) throws IOException {
        String name = o.stream("Workbook") != null ? "Workbook" : "Book";
        byte[] wb = o.stream(name);
        if (wb == null) throw new IOException("no workbook");
        int p = 0;
        int fp = -1;
        for (int n = 0; n < 60 && p + 4 <= wb.length; n++) {
            int id = u16(wb, p);
            int len = u16(wb, p + 2);
            if (id == 0x002F) {
                fp = p;
                break;
            }
            if (id == 0x000A) break;
            p += 4 + len;
        }
        if (fp < 0) throw new IOException("no FilePass");
        int fpLen = u16(wb, fp + 2);
        int d = fp + 4;
        if (d + fpLen > wb.length) throw new IOException("bad FilePass");
        int type = u16(wb, d);
        if (type == 0) throw new OfficeCrypto.Unsupported("xor");
        int major = u16(wb, d + 2), minor = u16(wb, d + 4);
        Scheme sc;
        if (major == 1 && minor == 1) {
            sc = rc4Binary(range(wb, d + 6, 16), range(wb, d + 22, 16), range(wb, d + 38, 16), pw);
        } else if (minor == 2 && major >= 2 && major <= 4) {
            sc = cryptoApi(range(wb, d + 2, fpLen - 2), pw);
        } else {
            throw new OfficeCrypto.Unsupported("xls " + major + "." + minor);
        }
        int after = d + fpLen;
        byte[] best = null;
        for (int variant = 0; variant < 4; variant++) {
            byte[] t = wb.clone();
            xlsApply(t, after, sc, (variant & 1) == 0, (variant & 2) == 0);
            if (xlsPlausible(t)) {
                best = t;
                break;
            }
            if (variant == 0) best = t;
        }
        put16(best, fp, 0x0FFF);
        streams.put("Workbook", best);
    }

    /**
     * Decrypts the record data after FilePass. Record headers are never encrypted; {@code plainStep} says whether the
     * key stream also advances over the records that are stored in the clear, {@code headerStep} over record headers.
     */
    private static void xlsApply(byte[] t, int after, Scheme sc, boolean headerStep, boolean plainStep) {
        Cursor c = new Cursor(sc, 1024, after);
        int p = after;
        while (p + 4 <= t.length) {
            int id = u16(t, p);
            int len = u16(t, p + 2);
            if (headerStep) c.step(t, p, 4, false);
            int body = p + 4;
            if (body + len > t.length) len = t.length - body;
            if (XLS_PLAIN.contains(id)) {
                if (plainStep) c.step(t, body, len, false);
            } else if (id == 0x0085 && len >= 4) {      // BoundSheet8: the first four bytes (stream position) are clear
                c.step(t, body, 4, false);
                c.step(t, body + 4, len - 4, true);
            } else {
                c.step(t, body, len, true);
            }
            p = body + len;
        }
    }

    /** Date1904 and Precision hold a single flag: they read 0 or 1 only when the data decrypted correctly. */
    private static boolean xlsPlausible(byte[] t) {
        int p = 0;
        int checked = 0;
        for (int n = 0; n < 400 && p + 4 <= t.length; n++) {
            int id = u16(t, p);
            int len = u16(t, p + 2);
            if (p + 4 + len > t.length) break;
            if ((id == 0x0022 || id == 0x000E) && len == 2) {
                if (u16(t, p + 4) > 1) return false;
                checked++;
            }
            if (id == 0x0085) break;
            p += 4 + len;
        }
        return checked > 0;
    }

    // ------------------------------------------------------------------ doc

    private static void doc(Ole2 o, String pw, Map<String, byte[]> streams) throws IOException {
        byte[] wd = o.stream("WordDocument");
        if (wd == null || wd.length < 0x1A8 || u16(wd, 0) != 0xA5EC) throw new IOException("not a Word file");
        int flags = u16(wd, 0x0A);
        if ((flags & 0x8000) != 0) throw new OfficeCrypto.Unsupported("xor");
        String tn = (flags & 0x0200) != 0 ? "1Table" : "0Table";
        byte[] table = o.stream(tn);
        if (table == null) throw new IOException("no table stream");
        int hdr = (int) u32(wd, 0x0E);
        if (hdr < 4 || hdr > table.length) throw new IOException("bad encryption header");
        int major = u16(table, 0), minor = u16(table, 2);
        Scheme sc;
        if (major == 1 && minor == 1) {
            sc = rc4Binary(range(table, 4, 16), range(table, 20, 16), range(table, 36, 16), pw);
        } else if (minor == 2 && major >= 2 && major <= 4) {
            sc = cryptoApi(range(table, 0, hdr), pw);
        } else {
            throw new OfficeCrypto.Unsupported("doc " + major + "." + minor);
        }
        // the first 68 bytes of the main stream are never encrypted
        byte[] bestWd = null;
        for (int v = 0; v < 2; v++) {
            byte[] t = wd.clone();
            new Cursor(sc, 512, v == 0 ? 68 : 0).step(t, 68, t.length - 68, true);
            if (docWdPlausible(t, table.length)) {
                bestWd = t;
                break;
            }
            if (v == 0) bestWd = t;
        }
        long fcClx = u32(bestWd, 0x1A2);
        byte[] bestTb = null;
        for (int v = 0; v < 2; v++) {
            byte[] t = table.clone();
            new Cursor(sc, 512, v == 0 ? hdr : 0).step(t, hdr, t.length - hdr, true);
            if (fcClx >= 0 && fcClx < t.length && ((t[(int) fcClx] & 0xFF) == 1 || (t[(int) fcClx] & 0xFF) == 2)) {
                bestTb = t;
                break;
            }
            if (v == 0) bestTb = t;
        }
        put16(bestWd, 0x0A, flags & ~0x0100);
        bestWd[0x0E] = bestWd[0x0F] = bestWd[0x10] = bestWd[0x11] = 0;
        streams.put("WordDocument", bestWd);
        streams.put(tn, bestTb);
    }

    private static boolean docWdPlausible(byte[] wd, int tableLen) {
        long ccpText = u32(wd, 0x4C);
        long fcClx = u32(wd, 0x1A2);
        long lcbClx = u32(wd, 0x1A6);
        return ccpText > 0 && ccpText < (1L << 28) && lcbClx > 0 && fcClx + lcbClx <= tableLen;
    }

    // ------------------------------------------------------------------ ppt

    private static void ppt(Ole2 o, String pw, Map<String, byte[]> streams) throws IOException {
        byte[] d = o.stream("PowerPoint Document");
        byte[] cu = o.stream("Current User");
        if (d == null || cu == null || cu.length < 20) throw new IOException("not a PowerPoint file");
        if (u32(cu, 12) != 0xF3D1C4DFL) throw new IOException("not encrypted");
        long edit = u32(cu, 16);
        long cryptId = -1;
        java.util.List<long[]> objs = new java.util.ArrayList<>();       // {persistId, offset}, newest edit first
        Set<Long> seen = new HashSet<>();
        for (int guard = 0; guard < 4000 && edit > 0 && edit + 8 + 28 <= d.length && seen.add(edit); guard++) {
            int e = (int) edit;
            if (u16(d, e + 2) != 0x0FF5) break;
            long len = u32(d, e + 4);
            int body = e + 8;
            long last = u32(d, body + 8);
            long dir = u32(d, body + 12);
            if (guard == 0 && len >= 32 && body + 32 <= d.length) cryptId = u32(d, body + 28);
            if (dir > 0 && dir + 8 <= d.length && u16(d, (int) dir + 2) == 0x1772) {
                int dp = (int) dir + 8;
                int dend = (int) Math.min(d.length, dir + 8 + u32(d, (int) dir + 4));
                while (dp + 4 <= dend) {
                    long head = u32(d, dp);
                    long id = head & 0xFFFFF;
                    int count = (int) (head >>> 20);
                    dp += 4;
                    for (int k = 0; k < count && dp + 4 <= dend; k++, dp += 4) {
                        objs.add(new long[]{id + k, u32(d, dp)});
                    }
                }
            }
            edit = last;
        }
        if (cryptId < 0) throw new IOException("no encryption record");
        long cryptOff = -1;
        for (long[] ob : objs) {
            if (ob[0] == cryptId) {
                cryptOff = ob[1];
                break;
            }
        }
        if (cryptOff < 0 || cryptOff + 8 > d.length || u16(d, (int) cryptOff + 2) != 0x2F14) {
            throw new IOException("no encryption record");
        }
        int clen = (int) Math.min(u32(d, (int) cryptOff + 4), d.length - cryptOff - 8);
        byte[] info = range(d, (int) cryptOff + 8, clen);
        int major = u16(info, 0), minor = u16(info, 2);
        if (!(minor == 2 && major >= 2 && major <= 4)) throw new OfficeCrypto.Unsupported("ppt " + major + "." + minor);
        Scheme sc = cryptoApi(info, pw);

        byte[] t = d.clone();
        Set<Long> done = new HashSet<>();
        for (long[] ob : objs) {
            if (ob[1] == cryptOff || ob[0] == cryptId || !done.add(ob[1])) continue;
            if (ob[1] + 8 > t.length) continue;
            int off = (int) ob[1];
            Rc4 r = sc.block((int) ob[0]);
            r.xor(t, off, 8);
            long len = u32(t, off + 4);
            if (len < 0 || off + 8 + len > t.length) {
                // not a valid object: leave it as it was
                System.arraycopy(d, off, t, off, 8);
                continue;
            }
            r.xor(t, off + 8, (int) len);
        }
        streams.put("PowerPoint Document", t);
        streams.put("Current User", cu);
    }
}
