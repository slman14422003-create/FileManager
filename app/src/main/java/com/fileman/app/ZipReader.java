package com.fileman.app;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Self-contained ZIP reader. Unlike {@link java.util.zip.ZipFile} it
 * <ul>
 *   <li>reads ZIP64 archives (entries and archives above 4 GB, more than 65535 entries),</li>
 *   <li>detects the file-name encoding (UTF-8, Windows-1256 / CP720 Arabic, CP437) per archive,</li>
 *   <li>never needs the whole archive in memory: only the central directory is parsed,</li>
 *   <li>verifies the CRC-32 of every entry it streams, so corrupt data is reported instead of saved.</li>
 * </ul>
 * Encrypted entries and methods other than STORED / DEFLATE / DEFLATE64-less are reported with a {@link ZipException}.
 */
final class ZipReader implements Closeable {
    static final int STORED = 0, DEFLATED = 8;

    static final class Entry {
        String name = "";        // decoded, '/' separated
        long size, packed, offset, crc, time = -1;
        int method, flags, dosTime;
        int aes, aesVersion, aesMethod;   // WinZip AES: strength 1..3, AE version, real compression method
        boolean dir;
        long dataStart = -1;     // filled lazily

        boolean encrypted() {
            return (flags & 1) != 0;
        }
    }

    static final class NeedPassword extends ZipException {
        NeedPassword() {
            super("encrypted");
        }
    }

    static final class WrongPassword extends ZipException {
        WrongPassword() {
            super("encrypted: wrong password");
        }
    }

    private byte[] password;
    private char[] passwordChars;

    void setPassword(String p) {
        if (p == null) {
            password = null;
            passwordChars = null;
        } else {
            password = p.getBytes(StandardCharsets.UTF_8);
            passwordChars = p.toCharArray();
        }
    }

    private final RandomAccessFile raf;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byName = new HashMap<>();
    private Charset charset = StandardCharsets.UTF_8;
    private final long length;

    private ZipReader(RandomAccessFile raf) throws IOException {
        this.raf = raf;
        this.length = raf.length();
    }

    static ZipReader open(java.io.File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            ZipReader z = new ZipReader(r);
            z.readDirectory();
            return z;
        } catch (IOException | RuntimeException e) {
            try {
                r.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** True if the file starts with a ZIP local-header or empty-archive signature. */
    static boolean hasZipSignature(java.io.File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return b[0] == 'P' && b[1] == 'K' && ((b[2] == 3 && b[3] == 4) || (b[2] == 5 && b[3] == 6) || (b[2] == 7 && b[3] == 8));
        } catch (IOException e) {
            return false;
        }
    }

    List<Entry> entries() {
        return entries;
    }

    Charset charset() {
        return charset;
    }

    Entry find(String name) {
        return byName.get(name);
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    // ------------------------------------------------------------------ central directory

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }

    private void readDirectory() throws IOException {
        if (length < 22) throw new ZipException("not a zip");
        // end of central directory record: last 22 bytes + up to 64 KB of comment
        int scan = (int) Math.min(length, 22 + 65535);
        byte[] tail = new byte[scan];
        raf.seek(length - scan);
        raf.readFully(tail);
        int eocd = -1;
        for (int i = scan - 22; i >= 0; i--) {
            if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 && tail[i + 3] == 6) {
                int commentLen = u16(tail, i + 20);
                if (i + 22 + commentLen <= scan) {
                    eocd = i;
                    break;
                }
            }
        }
        if (eocd < 0) throw new ZipException("no central directory");
        long total = u16(tail, eocd + 10);
        long cdSize = u32(tail, eocd + 12);
        long cdOffset = u32(tail, eocd + 16);
        long eocdPos = length - scan + eocd;

        boolean need64 = total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL;
        if (eocdPos >= 20) {
            byte[] loc = new byte[20];
            raf.seek(eocdPos - 20);
            raf.readFully(loc);
            if (u32(loc, 0) == 0x07064b50L) {
                long z64 = u64(loc, 8);
                if (z64 >= 0 && z64 + 56 <= length) {
                    byte[] rec = new byte[56];
                    raf.seek(z64);
                    raf.readFully(rec);
                    if (u32(rec, 0) == 0x06064b50L) {
                        total = u64(rec, 32);
                        cdSize = u64(rec, 40);
                        cdOffset = u64(rec, 48);
                        need64 = false;
                    }
                }
            }
        }
        if (need64) throw new ZipException("zip64 record missing");
        // archives with data in front of them (self-extractors): shift by the gap
        long gap = eocdPos - (cdOffset + cdSize);
        if (gap < 0 || cdSize > Integer.MAX_VALUE * 2L) throw new ZipException("bad central directory");
        long base = gap > 0 && isCdAt(cdOffset + gap) && !isCdAt(cdOffset) ? gap : 0;
        raf.seek(cdOffset + base);

        List<byte[]> rawNames = new ArrayList<>();
        List<Boolean> utf8Flag = new ArrayList<>();
        long remaining = cdSize;
        byte[] hdr = new byte[46];
        java.io.BufferedInputStream bin = new java.io.BufferedInputStream(new RafStream(raf, cdOffset + base, cdSize), 1 << 16);
        long count = 0;
        while (remaining >= 46 && count < 5_000_000L) {
            readFully(bin, hdr, 46);
            if (u32(hdr, 0) != 0x02014b50L) throw new ZipException("bad central entry");
            Entry e = new Entry();
            e.flags = u16(hdr, 8);
            e.method = u16(hdr, 10);
            e.time = dosTime(u16(hdr, 12), u16(hdr, 14));
            e.dosTime = u16(hdr, 12);
            e.crc = u32(hdr, 16);
            e.packed = u32(hdr, 20);
            e.size = u32(hdr, 24);
            int nameLen = u16(hdr, 28), extraLen = u16(hdr, 30), commentLen = u16(hdr, 32);
            e.offset = u32(hdr, 42);
            byte[] name = new byte[nameLen];
            readFully(bin, name, nameLen);
            byte[] extra = new byte[extraLen];
            readFully(bin, extra, extraLen);
            skipFully(bin, commentLen);
            remaining -= 46L + nameLen + extraLen + commentLen;
            // ZIP64 extended information
            int p = 0;
            while (p + 4 <= extraLen) {
                int id = u16(extra, p), sz = u16(extra, p + 2);
                int q = p + 4;
                if (id == 0x0001) {
                    if (e.size == 0xFFFFFFFFL && q + 8 <= p + 4 + sz) {
                        e.size = u64(extra, q);
                        q += 8;
                    }
                    if (e.packed == 0xFFFFFFFFL && q + 8 <= p + 4 + sz) {
                        e.packed = u64(extra, q);
                        q += 8;
                    }
                    if (e.offset == 0xFFFFFFFFL && q + 8 <= p + 4 + sz) {
                        e.offset = u64(extra, q);
                    }
                } else if (id == 0x9901 && sz >= 7) {   // WinZip AES
                    e.aesVersion = u16(extra, q);
                    e.aes = extra[q + 4] & 0xFF;
                    e.aesMethod = u16(extra, q + 5);
                } else if (id == 0x7075 && sz > 5 && extra[q] == 1) {   // Info-ZIP unicode path: always UTF-8
                    e.flags |= 0x800;
                    name = java.util.Arrays.copyOfRange(extra, q + 5, q + sz);
                }
                p += 4 + sz;
            }
            e.offset += base;
            rawNames.add(name);
            utf8Flag.add((e.flags & 0x800) != 0);
            entries.add(e);
            count++;
        }
        charset = detectCharset(rawNames, utf8Flag);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Charset cs = utf8Flag.get(i) ? StandardCharsets.UTF_8 : charset;
            e.name = new String(rawNames.get(i), cs).replace('\\', '/');
            if (e.name.endsWith("/")) e.dir = true;
            if (!byName.containsKey(e.name)) byName.put(e.name, e);
        }
    }

    private boolean isCdAt(long pos) {
        try {
            if (pos < 0 || pos + 4 > length) return false;
            byte[] b = new byte[4];
            raf.seek(pos);
            raf.readFully(b);
            return u32(b, 0) == 0x02014b50L;
        } catch (IOException e) {
            return false;
        }
    }

    private static void readFully(InputStream in, byte[] b, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException("truncated central directory");
            off += r;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException("truncated central directory");
                s = 1;
            }
            n -= s;
        }
    }

    private static long dosTime(int time, int date) {
        if (date == 0) return -1;
        int year = ((date >> 9) & 0x7F) + 1980, mon = (date >> 5) & 0xF, day = date & 0x1F;
        int h = (time >> 11) & 0x1F, mi = (time >> 5) & 0x3F, s = (time & 0x1F) * 2;
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(year, Math.max(0, mon - 1), Math.max(1, day), h, mi, s);
            return c.getTimeInMillis();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ file-name encoding

    private static boolean validUtf8(byte[] b) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Names without the UTF-8 flag: UTF-8 if every name is valid UTF-8, else the Arabic Windows page if it reads as Arabic, else CP437. */
    private static Charset detectCharset(List<byte[]> names, List<Boolean> flags) {
        boolean anyHigh = false, allUtf8 = true;
        for (int i = 0; i < names.size(); i++) {
            if (flags.get(i)) continue;
            byte[] n = names.get(i);
            boolean high = false;
            for (byte x : n) if (x < 0) high = true;
            if (!high) continue;
            anyHigh = true;
            if (!validUtf8(n)) allUtf8 = false;
        }
        if (!anyHigh || allUtf8) return StandardCharsets.UTF_8;
        String[] cands = {"windows-1256", "IBM720", "IBM437"};
        Charset best = null;
        double bestScore = -1;
        for (String cn : cands) {
            Charset cs;
            try {
                cs = Charset.forName(cn);
            } catch (RuntimeException e) {
                continue;
            }
            int high = 0, arabic = 0, bad = 0;
            for (int i = 0; i < names.size() && i < 400; i++) {
                if (flags.get(i)) continue;
                String s = new String(names.get(i), cs);
                for (int k = 0; k < s.length(); k++) {
                    char ch = s.charAt(k);
                    if (ch == '\uFFFD') bad++;
                    if (ch >= 0x0600 && ch <= 0x06FF) arabic++;
                    if (ch >= 0x80) high++;
                }
            }
            double score = high == 0 ? 0 : (double) arabic / high - bad * 0.5;
            if (cn.equals("IBM437")) score = Math.max(score, 0.01);   // last resort, always readable
            if (score > bestScore) {
                bestScore = score;
                best = cs;
            }
        }
        if (best == null || bestScore <= 0.05) {
            try {
                return Charset.forName("IBM437");
            } catch (RuntimeException e) {
                return StandardCharsets.ISO_8859_1;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ reading data

    /** Opens one entry's data, decompressed. The stream throws if the CRC-32 does not match at the end. */
    InputStream open(Entry e) throws IOException {
        if (e.dir) throw new ZipException("directory");
        boolean enc = e.encrypted();
        if (enc) {
            if (e.aes == 0 && (e.flags & 0x40) != 0) throw new ZipException("unsupported method strong encryption");
            if (e.aes != 0 && (e.aes < 1 || e.aes > 3)) throw new ZipException("unsupported method aes");
            if (password == null) throw new NeedPassword();
        }
        int method = e.aes != 0 ? e.aesMethod : e.method;
        if (method != STORED && method != DEFLATED) throw new ZipException("unsupported method " + method);
        if (e.dataStart < 0) {
            byte[] lh = new byte[30];
            raf.seek(e.offset);
            raf.readFully(lh);
            if (u32(lh, 0) != 0x04034b50L) throw new ZipException("bad local header");
            e.dataStart = e.offset + 30 + u16(lh, 26) + u16(lh, 28);
        }
        if (e.dataStart + e.packed > length) throw new ZipException("entry beyond end of file");
        InputStream raw = new java.io.BufferedInputStream(new RafStream(raf, e.dataStart, e.packed), 1 << 16);
        boolean checkCrc = true;
        boolean legacyCrypto = false;
        if (enc) {
            if (e.aes != 0) {
                raw = aesStream(raw, e);
                if (e.aesVersion == 2) checkCrc = false;
            } else {
                raw = new ZipCryptoStream(raw, password, (byte) (e.crc >>> 24), (byte) (e.dosTime >>> 8));
                legacyCrypto = true;
            }
        }
        InputStream data = method == STORED ? raw : new InflateStream(raw);
        return new CheckedStream(data, e.crc, e.size, checkCrc, legacyCrypto);
    }

    private InputStream aesStream(InputStream raw, Entry e) throws IOException {
        int keyLen = e.aes == 1 ? 16 : e.aes == 2 ? 24 : 32;
        int saltLen = keyLen / 2;
        long cipherLen = e.packed - saltLen - 2 - 10;
        if (cipherLen < 0) throw new ZipException("bad aes entry");
        byte[] salt = new byte[saltLen];
        readFully(raw, salt, saltLen);
        byte[] pv = new byte[2];
        readFully(raw, pv, 2);
        byte[] dk;
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            dk = f.generateSecret(new PBEKeySpec(passwordChars, salt, 1000, (2 * keyLen + 2) * 8)).getEncoded();
        } catch (java.security.GeneralSecurityException ex) {
            throw new ZipException("aes unavailable");
        }
        if (dk[2 * keyLen] != pv[0] || dk[2 * keyLen + 1] != pv[1]) throw new WrongPassword();
        byte[] encKey = java.util.Arrays.copyOfRange(dk, 0, keyLen);
        byte[] macKey = java.util.Arrays.copyOfRange(dk, keyLen, 2 * keyLen);
        try {
            return new AesCtrStream(raw, cipherLen, encKey, macKey);
        } catch (java.security.GeneralSecurityException ex) {
            throw new ZipException("aes unavailable");
        }
    }

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            CRC_TABLE[n] = c;
        }
    }

    /** Traditional PKWARE encryption. */
    private static final class ZipCryptoStream extends InputStream {
        private final InputStream in;
        private int k0 = 0x12345678, k1 = 0x23456789, k2 = 0x34567890;

        ZipCryptoStream(InputStream in, byte[] pwd, byte check1, byte check2) throws IOException {
            this.in = in;
            for (byte b : pwd) update(b);
            byte[] hdr = new byte[12];
            readFully(in, hdr, 12);
            byte last = 0;
            for (int i = 0; i < 12; i++) last = decrypt(hdr[i]);
            if (last != check1 && last != check2) throw new WrongPassword();
        }

        private void update(byte b) {
            k0 = CRC_TABLE[(k0 ^ b) & 0xFF] ^ (k0 >>> 8);
            k1 = (k1 + (k0 & 0xFF)) * 134775813 + 1;
            k2 = CRC_TABLE[(k2 ^ (k1 >>> 24)) & 0xFF] ^ (k2 >>> 8);
        }

        private byte decrypt(byte c) {
            int t = (k2 | 2) & 0xFFFF;
            byte p = (byte) (c ^ ((t * (t ^ 1)) >>> 8));
            update(p);
            return p;
        }

        @Override
        public int read() throws IOException {
            int r = in.read();
            return r < 0 ? -1 : (decrypt((byte) r) & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, len);
            for (int i = 0; i < r; i++) b[off + i] = decrypt(b[off + i]);
            return r;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** WinZip AES: counter mode with a little-endian counter, HMAC-SHA1 over the cipher text (first 10 bytes kept). */
    private static final class AesCtrStream extends InputStream {
        private final InputStream in;
        private final long total;
        private long done;
        private final Cipher ecb;
        private final Mac mac;
        private final byte[] counter = new byte[16];
        private final byte[] ks = new byte[4096];
        private final byte[] blocks = new byte[4096];
        private int ksPos = ks.length;
        private boolean verified;

        AesCtrStream(InputStream in, long total, byte[] encKey, byte[] macKey) throws java.security.GeneralSecurityException {
            this.in = in;
            this.total = total;
            ecb = Cipher.getInstance("AES/ECB/NoPadding");
            ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"));
            mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(macKey, "HmacSHA1"));
        }

        private void refill() {
            for (int i = 0; i < blocks.length; i += 16) {
                for (int k = 0; k < 16; k++) {
                    if (++counter[k] != 0) break;
                }
                System.arraycopy(counter, 0, blocks, i, 16);
            }
            try {
                ecb.update(blocks, 0, blocks.length, ks, 0);
            } catch (java.security.GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
            ksPos = 0;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done >= total) {
                verify();
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, total - done));
            if (n < 0) throw new EOFException("truncated aes data");
            mac.update(b, off, n);
            for (int i = 0; i < n; i++) {
                if (ksPos >= ks.length) refill();
                b[off + i] ^= ks[ksPos++];
            }
            done += n;
            return n;
        }

        private void verify() throws IOException {
            if (verified) return;
            verified = true;
            byte[] auth = new byte[10];
            readFully(in, auth, 10);
            byte[] want = mac.doFinal();
            for (int i = 0; i < 10; i++) {
                if (auth[i] != want[i]) throw new ZipException("authentication failed (corrupt archive)");
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Reads a byte range of the file; the file handle is shared, so every read seeks first. */
    private static final class RafStream extends InputStream {
        private final RandomAccessFile raf;
        private long pos;
        private final long end;

        RafStream(RandomAccessFile raf, long start, long len) {
            this.raf = raf;
            this.pos = start;
            this.end = start + len;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= end) return -1;
            int n = (int) Math.min(len, end - pos);
            synchronized (raf) {
                raf.seek(pos);
                int r = raf.read(b, off, n);
                if (r > 0) pos += r;
                return r;
            }
        }

        @Override
        public int available() {
            return (int) Math.min(Integer.MAX_VALUE, end - pos);
        }
    }

    private static final class InflateStream extends InputStream {
        private final InputStream in;
        private final Inflater inf = new Inflater(true);
        private final byte[] buf = new byte[1 << 16];
        private boolean done, dummySent;

        InflateStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            try {
                int n;
                while ((n = inf.inflate(b, off, len)) == 0) {
                    if (inf.finished() || inf.needsDictionary()) {
                        done = true;
                        return -1;
                    }
                    if (inf.needsInput()) {
                        int r = in.read(buf, 0, buf.length);
                        if (r < 0) {
                            // raw deflate may need one extra byte to report its end, as java.util.zip does
                            if (dummySent) throw new EOFException("truncated deflate data");
                            dummySent = true;
                            buf[0] = 0;
                            inf.setInput(buf, 0, 1);
                        } else {
                            inf.setInput(buf, 0, r);
                        }
                    }
                }
                return n;
            } catch (DataFormatException e) {
                throw new ZipException(e.getMessage() == null ? "bad deflate data" : e.getMessage());
            }
        }

        @Override
        public void close() throws IOException {
            inf.end();
            in.close();
        }
    }

    private static final class CheckedStream extends InputStream {
        private final InputStream in;
        private final CRC32 crc = new CRC32();
        private final long expectCrc, expectSize;
        private long seen;
        private boolean checked;

        private final boolean checkCrc, legacyCrypto;

        CheckedStream(InputStream in, long expectCrc, long expectSize, boolean checkCrc, boolean legacyCrypto) {
            this.in = in;
            this.expectCrc = expectCrc;
            this.expectSize = expectSize;
            this.checkCrc = checkCrc;
            this.legacyCrypto = legacyCrypto;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, len);
            if (r > 0) {
                crc.update(b, off, r);
                seen += r;
            } else if (r < 0 && !checked) {
                checked = true;
                if (seen != expectSize) throw new ZipException("size mismatch");
                if (checkCrc && crc.getValue() != expectCrc) {
                    if (legacyCrypto) throw new WrongPassword();
                    throw new ZipException("CRC mismatch (corrupt archive)");
                }
            }
            return r;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
