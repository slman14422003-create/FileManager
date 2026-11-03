package com.fileman.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password protected Word / Excel / PowerPoint files (docx, xlsx, pptx and friends). Such a file is not a ZIP but an OLE2
 * container holding "EncryptionInfo" and "EncryptedPackage"; this class recognises it and, given the password,
 * writes the plain ZIP package to another file. Supports the Standard (Office 2007) and Agile (Office 2010+) schemes.
 * Legacy binary files (doc, xls, ppt) are recognised here, see {@link #legacyLocked}, and opened by {@link LegacyCrypto}.
 */
final class OfficeCrypto {
    private OfficeCrypto() {
    }

    /** The password does not open the file. */
    static final class BadPassword extends IOException {
        BadPassword() {
            super("wrong password");
        }
    }

    /** The file is protected in a way this class cannot open. */
    static final class Unsupported extends IOException {
        Unsupported(String m) {
            super(m);
        }
    }

    private static final int SEGMENT = 4096;
    private static final byte[] BLOCK_VERIFIER_INPUT = {(byte) 0xfe, (byte) 0xa7, (byte) 0xd2, 0x76, 0x3b, 0x4b, (byte) 0x9e, 0x79};
    private static final byte[] BLOCK_VERIFIER_VALUE = {(byte) 0xd7, (byte) 0xaa, 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e};
    private static final byte[] BLOCK_KEY = {0x14, 0x6e, 0x0b, (byte) 0xe7, (byte) 0xab, (byte) 0xac, (byte) 0xd0, (byte) 0xd6};

    // ------------------------------------------------------------------ detection

    /** True for a protected docx / xlsx / pptx (an OLE2 file with the two encryption streams). */
    static boolean isEncrypted(File f) {
        if (f == null || !f.isFile() || f.length() < 1024) return false;
        if (!Ole2.isOle2(f)) return false;
        try (Ole2 o = new Ole2(f)) {
            boolean info = false, pkg = false;
            for (String n : o.streamNames()) {
                if (n.equalsIgnoreCase("EncryptionInfo")) info = true;
                else if (n.equalsIgnoreCase("EncryptedPackage")) pkg = true;
            }
            return info && pkg;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True for a protected legacy doc / xls / ppt (opened through {@link LegacyCrypto}). */
    static boolean legacyLocked(File f, String ext) {
        if (f == null || !f.isFile() || !Ole2.isOle2(f)) return false;
        try (Ole2 o = new Ole2(f)) {
            if (ext.equals("doc") || ext.equals("dot")) {
                byte[] w = o.stream("WordDocument");
                return w != null && w.length > 12 && (w[11] & 0x01) != 0;   // FIB.fEncrypted
            }
            if (ext.equals("xls") || ext.equals("xlt")) {
                byte[] w = o.stream("Workbook");
                if (w == null) w = o.stream("Book");
                return w != null && hasFilePass(w);
            }
            if (ext.equals("ppt") || ext.equals("pps") || ext.equals("pot")) {
                byte[] c = o.stream("Current User");
                return c != null && c.length >= 16 && (c[12] & 0xFF) == 0xDF && (c[13] & 0xFF) == 0xC4
                        && (c[14] & 0xFF) == 0xD1 && (c[15] & 0xFF) == 0xF3;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** BIFF8: the FilePass record (0x002F) sits in the first records of the workbook globals. */
    private static boolean hasFilePass(byte[] w) {
        int p = 0;
        for (int n = 0; n < 40 && p + 4 <= w.length; n++) {
            int id = (w[p] & 0xFF) | ((w[p + 1] & 0xFF) << 8);
            int len = (w[p + 2] & 0xFF) | ((w[p + 3] & 0xFF) << 8);
            if (id == 0x002F) return true;
            if (id == 0x000A) return false;   // EOF of the globals
            p += 4 + len;
        }
        return false;
    }

    // ------------------------------------------------------------------ decryption

    /** Writes the decrypted ZIP package of {@code in} to {@code out}. Throws {@link BadPassword} for a wrong password. */
    static void decrypt(File in, String password, File out) throws IOException {
        byte[] info;
        byte[] pkg;
        try (Ole2 o = new Ole2(in)) {
            info = o.stream("EncryptionInfo");
            pkg = o.stream("EncryptedPackage");
        }
        if (info == null || pkg == null || info.length < 8 || pkg.length < 8) throw new IOException("not an encrypted package");
        int vMajor = le16(info, 0), vMinor = le16(info, 2);
        try {
            if (vMajor == 4 && vMinor == 4) agile(info, pkg, password, out);
            else if ((vMajor == 2 || vMajor == 3 || vMajor == 4) && vMinor == 2) standard(info, pkg, password, out);
            else throw new Unsupported("encryption " + vMajor + "." + vMinor);
        } catch (GeneralSecurityException e) {
            throw new IOException("crypto: " + e.getMessage());
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
    }

    // ---- Agile (version 4.4)

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String element(String xml, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "\\b([^>]*)>").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String javaHash(String alg) throws Unsupported {
        if (alg == null) throw new Unsupported("hash");
        switch (alg.toUpperCase(java.util.Locale.ROOT)) {
            case "SHA1":
                return "SHA-1";
            case "SHA256":
                return "SHA-256";
            case "SHA384":
                return "SHA-384";
            case "SHA512":
                return "SHA-512";
            default:
                throw new Unsupported("hash " + alg);
        }
    }

    private static void agile(byte[] info, byte[] pkg, String password, File out) throws IOException, GeneralSecurityException {
        String xml = new String(info, 8, info.length - 8, StandardCharsets.UTF_8);
        int lt = xml.indexOf('<');
        if (lt > 0) xml = xml.substring(lt);
        String keyData = element(xml, "keyData");
        String ek = element(xml, "encryptedKey");
        if (keyData == null || ek == null) throw new Unsupported("agile descriptor");
        String kdAlg = attr(keyData, "cipherAlgorithm");
        String ekAlg = attr(ek, "cipherAlgorithm");
        if (!"AES".equalsIgnoreCase(kdAlg) || !"AES".equalsIgnoreCase(ekAlg)) throw new Unsupported("cipher " + kdAlg);
        if (!"ChainingModeCBC".equalsIgnoreCase(attr(keyData, "cipherChaining"))
                || !"ChainingModeCBC".equalsIgnoreCase(attr(ek, "cipherChaining"))) throw new Unsupported("chaining");

        String kdHash = javaHash(attr(keyData, "hashAlgorithm"));
        int kdBlock = Integer.parseInt(attr(keyData, "blockSize"));
        byte[] kdSalt = b64(attr(keyData, "saltValue"));

        String ekHash = javaHash(attr(ek, "hashAlgorithm"));
        int spin = Integer.parseInt(attr(ek, "spinCount"));
        int ekBits = Integer.parseInt(attr(ek, "keyBits"));
        int ekBlock = Integer.parseInt(attr(ek, "blockSize"));
        byte[] ekSalt = b64(attr(ek, "saltValue"));
        byte[] encVerInput = b64(attr(ek, "encryptedVerifierHashInput"));
        byte[] encVerValue = b64(attr(ek, "encryptedVerifierHashValue"));
        byte[] encKeyValue = b64(attr(ek, "encryptedKeyValue"));
        if (spin < 0 || spin > 10_000_000) throw new IOException("bad spin count");

        MessageDigest md = MessageDigest.getInstance(ekHash);
        md.update(ekSalt);
        md.update(password.getBytes(StandardCharsets.UTF_16LE));
        byte[] h = md.digest();
        byte[] ib = new byte[4];
        for (int i = 0; i < spin; i++) {
            ib[0] = (byte) i;
            ib[1] = (byte) (i >> 8);
            ib[2] = (byte) (i >> 16);
            ib[3] = (byte) (i >> 24);
            md.update(ib);
            md.update(h);
            h = md.digest();
        }

        byte[] ivEk = fit(ekSalt, ekBlock);
        byte[] verInput = cbc(derive(md, h, BLOCK_VERIFIER_INPUT, ekBits / 8), ivEk, encVerInput);
        byte[] verValue = cbc(derive(md, h, BLOCK_VERIFIER_VALUE, ekBits / 8), ivEk, encVerValue);
        MessageDigest vd = MessageDigest.getInstance(ekHash);
        byte[] want = vd.digest(Arrays.copyOf(verInput, Math.min(verInput.length, b64(attr(ek, "saltValue")).length)));
        if (!Arrays.equals(Arrays.copyOf(verValue, want.length), want)) throw new BadPassword();

        byte[] keyBytes = cbc(derive(md, h, BLOCK_KEY, ekBits / 8), ivEk, encKeyValue);
        int kdBits = Integer.parseInt(attr(keyData, "keyBits"));
        byte[] key = Arrays.copyOf(keyBytes, kdBits / 8);

        long size = le64(pkg, 0);
        if (size < 0 || size > pkg.length) throw new IOException("bad package size");
        MessageDigest sd = MessageDigest.getInstance(kdHash);
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            long written = 0;
            int seg = 0;
            int pos = 8;
            byte[] sb = new byte[4];
            while (written < size && pos < pkg.length) {
                int n = Math.min(SEGMENT, pkg.length - pos);
                n -= n % 16;
                if (n <= 0) break;
                sb[0] = (byte) seg;
                sb[1] = (byte) (seg >> 8);
                sb[2] = (byte) (seg >> 16);
                sb[3] = (byte) (seg >> 24);
                sd.update(kdSalt);
                sd.update(sb);
                byte[] iv = fit(sd.digest(), kdBlock);
                c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
                byte[] plain = c.doFinal(pkg, pos, n);
                int take = (int) Math.min(plain.length, size - written);
                o.write(plain, 0, take);
                written += take;
                pos += n;
                seg++;
            }
        }
        checkZip(out);
    }

    private static byte[] derive(MessageDigest md, byte[] h, byte[] block, int keyLen) {
        md.update(h);
        md.update(block);
        return fit(md.digest(), keyLen);
    }

    // ---- Standard (versions 2.2 / 3.2 / 4.2)

    private static void standard(byte[] info, byte[] pkg, String password, File out) throws IOException, GeneralSecurityException {
        if (info.length < 12 + 32) throw new IOException("short header");
        int hdrSize = le32(info, 8);
        if (hdrSize < 32 || 12 + hdrSize + 8 > info.length) throw new IOException("bad header size");
        int algId = le32(info, 12 + 8);
        int keySize = le32(info, 12 + 16);
        if (algId != 0x660E && algId != 0x660F && algId != 0x6610 && algId != 0) throw new Unsupported("standard cipher 0x" + Integer.toHexString(algId));
        if (algId == 0) throw new Unsupported("standard cipher");
        int keyLen = keySize / 8;
        if (keyLen != 16 && keyLen != 24 && keyLen != 32) throw new Unsupported("key size " + keySize);
        int v = 12 + hdrSize;
        int saltSize = le32(info, v);
        if (saltSize != 16 || v + 4 + 16 + 16 + 4 + 32 > info.length) throw new IOException("bad verifier");
        byte[] salt = Arrays.copyOfRange(info, v + 4, v + 20);
        byte[] encVerifier = Arrays.copyOfRange(info, v + 20, v + 36);
        byte[] encVerifierHash = Arrays.copyOfRange(info, v + 40, v + 72);

        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(salt);
        sha.update(password.getBytes(StandardCharsets.UTF_16LE));
        byte[] h = sha.digest();
        byte[] ib = new byte[4];
        for (int i = 0; i < 50000; i++) {
            ib[0] = (byte) i;
            ib[1] = (byte) (i >> 8);
            ib[2] = (byte) (i >> 16);
            ib[3] = (byte) (i >> 24);
            sha.update(ib);
            sha.update(h);
            h = sha.digest();
        }
        sha.update(h);
        sha.update(new byte[4]);
        byte[] hFinal = sha.digest();
        byte[] b1 = new byte[64];
        byte[] b2 = new byte[64];
        Arrays.fill(b1, (byte) 0x36);
        Arrays.fill(b2, (byte) 0x5C);
        for (int i = 0; i < hFinal.length; i++) {
            b1[i] ^= hFinal[i];
            b2[i] ^= hFinal[i];
        }
        byte[] x1 = sha.digest(b1);
        byte[] x2 = sha.digest(b2);
        byte[] x = new byte[40];
        System.arraycopy(x1, 0, x, 0, 20);
        System.arraycopy(x2, 0, x, 20, 20);
        byte[] key = Arrays.copyOf(x, keyLen);

        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        ecb.init(Cipher.DECRYPT_MODE, ks);
        byte[] verifier = ecb.doFinal(encVerifier);
        byte[] verifierHash = ecb.doFinal(encVerifierHash);
        byte[] want = sha.digest(verifier);
        if (!Arrays.equals(Arrays.copyOf(verifierHash, 20), want)) throw new BadPassword();

        long size = le64(pkg, 0);
        if (size < 0 || size > pkg.length) throw new IOException("bad package size");
        try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            int pos = 8;
            long written = 0;
            while (written < size && pos < pkg.length) {
                int n = Math.min(1 << 20, pkg.length - pos);
                n -= n % 16;
                if (n <= 0) break;
                byte[] plain = ecb.update(pkg, pos, n);
                int take = (int) Math.min(plain.length, size - written);
                o.write(plain, 0, take);
                written += take;
                pos += n;
            }
        }
        checkZip(out);
    }

    // ------------------------------------------------------------------ helpers

    private static void checkZip(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int a = in.read(), b = in.read();
            if (a != 'P' || b != 'K') throw new IOException("decrypted data is not a package");
        }
    }

    private static byte[] cbc(byte[] key, byte[] iv, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    /** Cuts or pads (with 0x36) to exactly n bytes, as the specification demands for keys and IVs. */
    private static byte[] fit(byte[] b, int n) {
        if (b.length == n) return b;
        byte[] r = new byte[n];
        Arrays.fill(r, (byte) 0x36);
        System.arraycopy(b, 0, r, 0, Math.min(n, b.length));
        return r;
    }

    private static byte[] b64(String s) throws IOException {
        if (s == null) throw new IOException("missing field");
        // own decoder: java.util.Base64 needs API 26, the app supports API 24
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4 + 3);
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+') v = 62;
            else if (c == '/') v = 63;
            else continue;   // padding and white space
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static long le64(byte[] b, int o) {
        return (le32(b, o) & 0xFFFFFFFFL) | ((long) le32(b, o + 4) << 32);
    }
}
