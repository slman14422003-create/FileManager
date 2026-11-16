package com.fileman.app;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Writes a PDF made of full-page JPEG images, optionally encrypted with the PDF standard security handler
 * (AES-128, revision 4). No Android classes are used, so it can be tested on any JVM.
 * <p>
 * Usage: {@code new PdfBuilder(out, userPassword, restrict)}, {@link #addJpegPage} for every page, then {@link #finish()}.
 * A user password makes readers ask for it; {@code restrict} allows reading and printing but forbids copying and editing.
 */
final class PdfBuilder implements Closeable {
    private static final byte[] PAD = {
            (byte) 0x28, (byte) 0xBF, (byte) 0x4E, (byte) 0x5E, (byte) 0x4E, (byte) 0x75, (byte) 0x8A, (byte) 0x41,
            (byte) 0x64, (byte) 0x00, (byte) 0x4E, (byte) 0x56, (byte) 0xFF, (byte) 0xFA, (byte) 0x01, (byte) 0x08,
            (byte) 0x2E, (byte) 0x2E, (byte) 0x00, (byte) 0xB6, (byte) 0xD0, (byte) 0x68, (byte) 0x3E, (byte) 0x80,
            (byte) 0x2F, (byte) 0x0C, (byte) 0xA9, (byte) 0xFE, (byte) 0x64, (byte) 0x53, (byte) 0x69, (byte) 0x7A};

    private static final int OBJ_CATALOG = 1, OBJ_PAGES = 2, OBJ_ENCRYPT = 3, FIRST_PAGE_OBJ = 4;

    private final OutputStream raw;
    private long pos = 0;
    private final List<Long> offsets = new ArrayList<>();   // offsets.get(n) = byte offset of object n (index 0 unused)
    private int pages = 0;
    private boolean finished = false;

    private final boolean encrypted;
    private byte[] fileKey;
    private byte[] oEntry, uEntry;
    private int permissions;
    private byte[] docId;
    private final SecureRandom rnd = new SecureRandom();

    PdfBuilder(OutputStream out, String userPassword, boolean restrict) throws IOException {
        raw = out;
        boolean hasUser = userPassword != null && userPassword.length() > 0;
        encrypted = hasUser || restrict;
        for (int i = 0; i < FIRST_PAGE_OBJ; i++) offsets.add(0L);
        docId = new byte[16];
        rnd.nextBytes(docId);
        if (encrypted) setupEncryption(hasUser ? userPassword : "", restrict);
        writeRaw("%PDF-1.6\n%".getBytes(StandardCharsets.ISO_8859_1));
        writeRaw(new byte[]{(byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
    }

    /** Adds a page of {@code widthPt} x {@code heightPt} points showing the JPEG, which is {@code wPx} x {@code hPx} pixels. */
    void addJpegPage(float widthPt, float heightPt, byte[] jpeg, int wPx, int hPx) throws IOException {
        int pageObj = FIRST_PAGE_OBJ + 3 * pages;
        int contentObj = pageObj + 1;
        int imageObj = pageObj + 2;
        pages++;

        String w = num(widthPt), h = num(heightPt);
        byte[] content = ("q " + w + " 0 0 " + h + " 0 0 cm /Im0 Do Q").getBytes(StandardCharsets.ISO_8859_1);

        beginObject(imageObj);
        byte[] img = encrypted ? encryptData(jpeg, imageObj) : jpeg;
        writeRaw(("<< /Type /XObject /Subtype /Image /Width " + wPx + " /Height " + hPx
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + img.length
                + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        writeRaw(img);
        writeRaw("\nendstream\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

        beginObject(contentObj);
        byte[] cs = encrypted ? encryptData(content, contentObj) : content;
        writeRaw(("<< /Length " + cs.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        writeRaw(cs);
        writeRaw("\nendstream\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));

        beginObject(pageObj);
        writeRaw(("<< /Type /Page /Parent " + OBJ_PAGES + " 0 R /MediaBox [0 0 " + w + " " + h
                + "] /Resources << /XObject << /Im0 " + imageObj + " 0 R >> >> /Contents " + contentObj
                + " 0 R >>\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    int pageCount() {
        return pages;
    }

    void finish() throws IOException {
        if (finished) return;
        finished = true;
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) kids.append(FIRST_PAGE_OBJ + 3 * i).append(" 0 R ");
        beginObject(OBJ_PAGES);
        writeRaw(("<< /Type /Pages /Count " + pages + " /Kids [" + kids + "] >>\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
        beginObject(OBJ_CATALOG);
        writeRaw(("<< /Type /Catalog /Pages " + OBJ_PAGES + " 0 R >>\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
        if (encrypted) {
            beginObject(OBJ_ENCRYPT);
            writeRaw(("<< /Filter /Standard /V 4 /R 4 /Length 128 /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV2 /Length 16 >> >>"
                    + " /StmF /StdCF /StrF /StdCF /O <" + hex(oEntry) + "> /U <" + hex(uEntry) + "> /P " + permissions
                    + " >>\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        long xref = pos;
        int size = offsets.size();
        StringBuilder x = new StringBuilder();
        x.append("xref\n0 ").append(size).append("\n0000000000 65535 f \n");
        for (int i = 1; i < size; i++) {
            long off = offsets.get(i);
            if (off == 0 && !(i == OBJ_ENCRYPT && encrypted)) {
                x.append("0000000000 65535 f \n");   // object 3 is unused without encryption
            } else {
                x.append(String.format(Locale.ROOT, "%010d 00000 n \n", off));
            }
        }
        x.append("trailer\n<< /Size ").append(size).append(" /Root ").append(OBJ_CATALOG).append(" 0 R");
        if (encrypted) x.append(" /Encrypt ").append(OBJ_ENCRYPT).append(" 0 R");
        x.append(" /ID [<").append(hex(docId)).append("> <").append(hex(docId)).append(">] >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        writeRaw(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        raw.flush();
    }

    @Override
    public void close() throws IOException {
        raw.close();
    }

    // ------------------------------------------------------------------ writing

    private void beginObject(int n) throws IOException {
        while (offsets.size() <= n) offsets.add(0L);
        offsets.set(n, pos);
        writeRaw((n + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private void writeRaw(byte[] b) throws IOException {
        raw.write(b);
        pos += b.length;
    }

    private static String num(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format(Locale.ROOT, "%02X", v & 0xFF));
        return s.toString();
    }

    // ------------------------------------------------------------------ encryption (standard handler, AESV2)

    private static byte[] padPassword(String pw) {
        byte[] b;
        boolean latin = true;
        for (int i = 0; i < pw.length(); i++) {
            if (pw.charAt(i) > 255) {
                latin = false;
                break;
            }
        }
        b = pw.getBytes(latin ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        byte[] out = new byte[32];
        int n = Math.min(32, b.length);
        System.arraycopy(b, 0, out, 0, n);
        System.arraycopy(PAD, 0, out, n, 32 - n);
        return out;
    }

    private static byte[] md5(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] rc4(byte[] key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[data.length];
        int a = 0, b = 0;
        for (int k = 0; k < data.length; k++) {
            a = (a + 1) & 0xFF;
            b = (b + s[a]) & 0xFF;
            int t = s[a];
            s[a] = s[b];
            s[b] = t;
            out[k] = (byte) (data[k] ^ s[(s[a] + s[b]) & 0xFF]);
        }
        return out;
    }

    private static byte[] xorKey(byte[] key, int v) {
        byte[] k = new byte[key.length];
        for (int i = 0; i < key.length; i++) k[i] = (byte) (key[i] ^ v);
        return k;
    }

    private void setupEncryption(String userPw, boolean restrict) {
        String ownerPw = userPw;
        if (restrict || userPw.isEmpty()) {
            if (restrict) {
                byte[] r = new byte[12];
                rnd.nextBytes(r);
                ownerPw = hex(r);   // nobody knows it: editing and copying stay forbidden
            }
        }
        // permissions: print (+ high quality) and accessibility only, or everything
        permissions = restrict ? (0xFFFFF0C0 | 4 | 512 | 2048) : -4;

        // O entry (algorithm 3)
        byte[] h = md5(padPassword(ownerPw));
        for (int i = 0; i < 50; i++) h = md5(h);
        byte[] ownerKey = new byte[16];
        System.arraycopy(h, 0, ownerKey, 0, 16);
        byte[] o = rc4(ownerKey, padPassword(userPw));
        for (int i = 1; i <= 19; i++) o = rc4(xorKey(ownerKey, i), o);
        oEntry = o;

        // file key (algorithm 2)
        byte[] p = new byte[]{(byte) permissions, (byte) (permissions >> 8), (byte) (permissions >> 16), (byte) (permissions >> 24)};
        byte[] k = md5(padPassword(userPw), oEntry, p, docId);
        for (int i = 0; i < 50; i++) {
            byte[] first = new byte[16];
            System.arraycopy(k, 0, first, 0, 16);
            k = md5(first);
        }
        fileKey = new byte[16];
        System.arraycopy(k, 0, fileKey, 0, 16);

        // U entry (algorithm 5)
        byte[] u = rc4(fileKey, md5(PAD, docId));
        for (int i = 1; i <= 19; i++) u = rc4(xorKey(fileKey, i), u);
        uEntry = new byte[32];
        System.arraycopy(u, 0, uEntry, 0, 16);
    }

    private byte[] encryptData(byte[] data, int objNum) throws IOException {
        try {
            byte[] suffix = {(byte) objNum, (byte) (objNum >> 8), (byte) (objNum >> 16), 0, 0, 's', 'A', 'l', 'T'};
            byte[] objKey = md5(fileKey, suffix);   // 16 bytes: the maximum for AES-128
            byte[] iv = new byte[16];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(objKey, "AES"), new IvParameterSpec(iv));
            byte[] enc = c.doFinal(data);
            byte[] out = new byte[16 + enc.length];
            System.arraycopy(iv, 0, out, 0, 16);
            System.arraycopy(enc, 0, out, 16, enc.length);
            return out;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
