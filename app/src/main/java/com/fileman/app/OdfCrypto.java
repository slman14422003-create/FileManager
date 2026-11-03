package com.fileman.app;

import android.util.Base64;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Password protected OpenDocument files (odt, ods, odp): PBKDF2 key, Blowfish or AES, deflated content. */
final class OdfCrypto {
    private OdfCrypto() {
    }

    private static final long MAX_ENTRY = 120L * 1024 * 1024;

    private static final class Enc {
        long size;
        String checksumType = "", algorithm = "", startKey = "", derivation = "";
        byte[] checksum, iv, salt;
        int keySize = 16, iterations = 1024;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            int c = n.indexOf(':');
            if ((c < 0 ? n : n.substring(c + 1)).equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String local(String n) {
        int c = n.indexOf(':');
        return c < 0 ? n : n.substring(c + 1);
    }

    private static byte[] b64(String s) {
        return s == null ? null : Base64.decode(s, Base64.DEFAULT);
    }

    private static int num(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, Enc> manifest(ZipFile z) throws Exception {
        Map<String, Enc> out = new HashMap<>();
        ZipEntry me = z.getEntry("META-INF/manifest.xml");
        if (me == null) return out;
        try (InputStream in = z.getInputStream(me)) {
            XmlPullParser p = android.util.Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, null);
            String path = null;
            long size = 0;
            Enc cur = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    switch (local(p.getName())) {
                        case "file-entry":
                            path = attr(p, "full-path");
                            size = num(attr(p, "size"), 0);
                            cur = null;
                            break;
                        case "encryption-data":
                            cur = new Enc();
                            cur.size = size;
                            cur.checksumType = String.valueOf(attr(p, "checksum-type"));
                            cur.checksum = b64(attr(p, "checksum"));
                            if (path != null) out.put(path, cur);
                            break;
                        case "algorithm":
                            if (cur != null) {
                                cur.algorithm = String.valueOf(attr(p, "algorithm-name"));
                                cur.iv = b64(attr(p, "initialisation-vector"));
                            }
                            break;
                        case "start-key-generation":
                            if (cur != null) cur.startKey = String.valueOf(attr(p, "start-key-generation-name"));
                            break;
                        case "key-derivation":
                            if (cur != null) {
                                cur.derivation = String.valueOf(attr(p, "key-derivation-name"));
                                cur.salt = b64(attr(p, "salt"));
                                cur.keySize = num(attr(p, "key-size"), 16);
                                cur.iterations = num(attr(p, "iteration-count"), 1024);
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        return out;
    }

    /** True for an OpenDocument package that has encrypted entries. */
    static boolean isEncrypted(File f) {
        if (f == null || !f.isFile()) return false;
        try (ZipFile z = new ZipFile(f)) {
            return !manifest(z).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] pbkdf2(byte[] pw, byte[] salt, int iterations, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(pw, "HmacSHA1"));
        byte[] out = new byte[len];
        int done = 0;
        for (int block = 1; done < len; block++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            int n = Math.min(t.length, len - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            bo.write(buf, 0, n);
            if (bo.size() > MAX_ENTRY) throw new IOException("entry too large");
        }
        return bo.toByteArray();
    }

    /** Writes the document without protection to {@code out}; throws BadPassword when the password is wrong. */
    static void decrypt(File in, String password, File out) throws IOException {
        try (ZipFile z = new ZipFile(in)) {
            Map<String, Enc> enc = manifest(z);
            try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out))) {
                boolean first = true;
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    byte[] data;
                    try (InputStream is = z.getInputStream(e)) {
                        data = readAll(is);
                    }
                    Enc x = enc.get(e.getName());
                    if (x != null) data = open(data, x, password);
                    ZipEntry ne = new ZipEntry(e.getName());
                    if (first && e.getName().equals("mimetype")) {
                        ne.setMethod(ZipEntry.STORED);
                        ne.setSize(data.length);
                        ne.setCompressedSize(data.length);
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        crc.update(data);
                        ne.setCrc(crc.getValue());
                    }
                    first = false;
                    zo.putNextEntry(ne);
                    zo.write(data);
                    zo.closeEntry();
                }
            }
        } catch (OfficeCrypto.BadPassword | OfficeCrypto.Unsupported e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IOException("odf crypto: " + e.getMessage());
        }
    }

    private static byte[] open(byte[] data, Enc x, String password) throws Exception {
        if (x.salt == null || x.iv == null || !x.derivation.toUpperCase(java.util.Locale.ROOT).contains("PBKDF2")) {
            throw new OfficeCrypto.Unsupported("odf key derivation");
        }
        String alg = x.algorithm.toLowerCase(java.util.Locale.ROOT);
        boolean aes = alg.contains("aes");
        if (!aes && !alg.contains("blowfish")) throw new OfficeCrypto.Unsupported("odf cipher " + x.algorithm);
        boolean sha256Start = x.startKey.toLowerCase(java.util.Locale.ROOT).contains("sha256");
        byte[] start = MessageDigest.getInstance(sha256Start ? "SHA-256" : "SHA-1")
                .digest((password == null ? "" : password).getBytes("UTF-8"));
        byte[] key = pbkdf2(start, x.salt, x.iterations, x.keySize);
        Cipher c = aes ? Cipher.getInstance("AES/CBC/PKCS5Padding") : Cipher.getInstance("Blowfish/CFB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, aes ? "AES" : "Blowfish"), new IvParameterSpec(x.iv));
        byte[] plain;
        try {
            plain = c.doFinal(data);
        } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException e) {
            throw new OfficeCrypto.BadPassword();
        }
        if (x.checksum != null) {
            boolean s256 = x.checksumType.toLowerCase(java.util.Locale.ROOT).contains("256");
            byte[] sum = MessageDigest.getInstance(s256 ? "SHA-256" : "SHA-1")
                    .digest(Arrays.copyOf(plain, Math.min(1024, plain.length)));
            if (!Arrays.equals(sum, x.checksum)) throw new OfficeCrypto.BadPassword();
        }
        return inflate(plain, x.size);
    }

    private static byte[] inflate(byte[] d, long size) throws IOException {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(d);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(64, Math.min(size, 1 << 24)));
            byte[] buf = new byte[32 * 1024];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                bo.write(buf, 0, n);
                if (bo.size() > MAX_ENTRY) throw new IOException("entry too large");
            }
            return bo.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            return d;   // stored without compression
        } finally {
            inf.end();
        }
    }
}
