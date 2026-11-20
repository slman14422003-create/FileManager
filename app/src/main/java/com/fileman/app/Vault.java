package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.FileObserver;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted vault for protected documents. Every document gets its own random data key; that key is wrapped by a
 * non-exportable Android Keystore key. Destroying a document deletes the Keystore key first (the data becomes
 * mathematically unreadable, even for root, because the key never leaves the secure hardware/keystore daemon) and then
 * overwrites and deletes the encrypted file, its index entry and every decrypted temporary copy.
 */
final class Vault {
    private Vault() {
    }

    static final class Item {
        final String id;
        final String name;
        final long size;
        final long added;

        Item(String id, String name, long size, long added) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.added = added;
        }
    }

    private static final String PREFS = "vault";
    private static final String K_ITEMS = "items";
    private static final String K_FAILS = "fails";
    private static final String K_LIMIT = "limit";
    private static final String ALIAS = "fmvault_";
    private static final String KS = "AndroidKeyStore";
    private static final byte[] MAGIC = {'F', 'V', '1', 0};
    private static final byte[] MAGIC2 = {'F', 'V', '2', 0};
    private static final int CHUNK = 64 * 1024;
    private static final int TAG_BITS = 128;
    private static final long MAX_FILE = 1536L * 1024 * 1024;
    private static final SecureRandom RNG = new SecureRandom();

    private static final AtomicInteger active = new AtomicInteger();
    private static volatile long selfUntil;
    private static FileObserver watcher;

    // ------------------------------------------------------------------ settings

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "vault");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static int attemptLimit(Context c) {
        return sp(c).getInt(K_LIMIT, 5);
    }

    static void setAttemptLimit(Context c, int n) {
        sp(c).edit().putInt(K_LIMIT, Math.max(3, Math.min(20, n))).apply();
    }

    /** Counts a failed unlock; returns attempts left, and destroys everything when none are left. */
    static synchronized int recordFailure(Context c) {
        SharedPreferences s = sp(c);
        int n = s.getInt(K_FAILS, 0) + 1;
        int left = attemptLimit(c) - n;
        if (left <= 0) {
            destroyAll(c, "attempts");
            return 0;
        }
        s.edit().putInt(K_FAILS, n).commit();
        return left;
    }

    static void resetFailures(Context c) {
        sp(c).edit().putInt(K_FAILS, 0).apply();
    }

    // ------------------------------------------------------------------ index

    private static synchronized List<Item> readIndex(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(K_ITEMS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item(o.getString("id"), o.getString("name"), o.optLong("size"), o.optLong("added")));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static synchronized void writeIndex(Context c, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("name", it.name);
                o.put("size", it.size);
                o.put("added", it.added);
                a.put(o);
            }
            sp(c).edit().putString(K_ITEMS, a.toString()).commit();
        } catch (Throwable ignored) {
        }
    }

    static List<Item> list(Context c) {
        List<Item> l = readIndex(c);
        java.util.Collections.sort(l, (a, b) -> Long.compare(b.added, a.added));
        return l;
    }

    static boolean isEmpty(Context c) {
        return readIndex(c).isEmpty();
    }

    // ------------------------------------------------------------------ keystore

    private static SecretKey createKey(String alias) throws Exception {
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                g.init(b.setIsStrongBoxBacked(true).build());
                return g.generateKey();
            } catch (Throwable strongBoxMissing) {
                b = new KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256);
            }
        }
        g.init(b.build());
        return g.generateKey();
    }

    private static SecretKey loadKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        return (SecretKey) ks.getKey(alias, null);
    }

    private static void deleteKey(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ protect / read

    private static void begin() {
        active.incrementAndGet();
        selfUntil = System.currentTimeMillis() + 3000;
    }

    private static void end() {
        selfUntil = System.currentTimeMillis() + 3000;
        active.decrementAndGet();
    }

    /** Second wrapping layer: the vault master key (known only while the vault is open) around the chip-wrapped data key. */
    private static byte[] outer(byte[] vk, byte[] data, boolean enc) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec k = new SecretKeySpec(vk, "AES");
        if (enc) {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(data);
            byte[] r = new byte[12 + ct.length];
            System.arraycopy(iv, 0, r, 0, 12);
            System.arraycopy(ct, 0, r, 12, ct.length);
            return r;
        }
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, data, 0, 12));
        return c.doFinal(data, 12, data.length - 12);
    }

    private static byte[] nonce(byte[] base, int counter) {
        ByteBuffer bb = ByteBuffer.allocate(12);
        bb.put(base, 0, 8);
        bb.putInt(counter);
        return bb.array();
    }

    private static byte[] aad(int counter, boolean last) {
        return ByteBuffer.allocate(5).putInt(counter).put((byte) (last ? 1 : 0)).array();
    }

    private static int fill(InputStream in, byte[] buf) throws java.io.IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    /** Encrypts src into the vault and removes the original for good. Returns null when it could not be done. */
    static Item protect(Context c, File src) {
        if (src == null || !src.isFile() || src.length() > MAX_FILE) return null;
        begin();
        String id = null;
        File tmp = null;
        try {
            final byte[] vk = VaultKey.current();
            dir(c);
            byte[] rid = new byte[12];
            RNG.nextBytes(rid);
            id = hex(rid);
            String alias = ALIAS + id;
            SecretKey wrap = createKey(alias);
            byte[] dataKey = new byte[32];
            RNG.nextBytes(dataKey);
            byte[] base = new byte[8];
            RNG.nextBytes(base);

            Cipher wc = Cipher.getInstance("AES/GCM/NoPadding");
            wc.init(Cipher.ENCRYPT_MODE, wrap);
            byte[] wiv = wc.getIV();
            byte[] wrapped = outer(vk, wc.doFinal(dataKey), true);

            tmp = new File(dir(c), id + ".v.tmp");
            SecretKeySpec dk = new SecretKeySpec(dataKey, "AES");
            long total = 0;
            try (InputStream in = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(fos, CHUNK))) {
                out.write(MAGIC2);
                out.writeByte(wiv.length);
                out.write(wiv);
                out.writeShort(wrapped.length);
                out.write(wrapped);
                out.write(base);
                byte[] cur = new byte[CHUNK];
                byte[] next = new byte[CHUNK];
                int curN = fill(in, cur);
                int counter = 0;
                while (true) {
                    int nextN = curN < CHUNK ? 0 : fill(in, next);
                    boolean last = nextN == 0;
                    Cipher ec = Cipher.getInstance("AES/GCM/NoPadding");
                    ec.init(Cipher.ENCRYPT_MODE, dk, new GCMParameterSpec(TAG_BITS, nonce(base, counter)));
                    ec.updateAAD(aad(counter, last));
                    byte[] ct = ec.doFinal(cur, 0, curN);
                    out.writeInt(ct.length);
                    out.write(ct);
                    total += curN;
                    if (last) break;
                    byte[] t = cur;
                    cur = next;
                    next = t;
                    curN = nextN;
                    counter++;
                }
                out.flush();
                fos.getFD().sync();
            }
            java.util.Arrays.fill(dataKey, (byte) 0);
            File fin = new File(dir(c), id + ".v");
            if (!tmp.renameTo(fin)) throw new java.io.IOException("rename");
            String name = src.getName();
            List<Item> all = readIndex(c);
            Item it = new Item(id, name, total, System.currentTimeMillis());
            all.add(it);
            writeIndex(c, all);
            shred(src);
            if (src.exists()) src.delete();
            scrub(c, src);
            forget(c, src);
            return it;
        } catch (Throwable t) {
            if (tmp != null) shred(tmp);
            if (id != null) deleteKey(ALIAS + id);
            return null;
        } finally {
            end();
        }
    }

    /** Decrypts an item into dest (which must not exist). Fails with an exception when the key is gone or data was altered. */
    static void decryptTo(Context c, String id, File dest) throws Exception {
        final byte[] vk = VaultKey.current();
        begin();
        try {
            File f = new File(dir(c), id + ".v");
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f), CHUNK))) {
                byte[] m = new byte[4];
                in.readFully(m);
                boolean v2 = java.util.Arrays.equals(m, MAGIC2);
                if (!v2 && !java.util.Arrays.equals(m, MAGIC)) throw new java.io.IOException("format");
                byte[] wiv = new byte[in.readUnsignedByte()];
                in.readFully(wiv);
                byte[] wrapped = new byte[in.readUnsignedShort()];
                in.readFully(wrapped);
                byte[] base = new byte[8];
                in.readFully(base);
                if (v2) wrapped = outer(vk, wrapped, false);
                SecretKey wrap = loadKey(ALIAS + id);
                if (wrap == null) throw new java.io.IOException("key");
                Cipher wc = Cipher.getInstance("AES/GCM/NoPadding");
                wc.init(Cipher.DECRYPT_MODE, wrap, new GCMParameterSpec(TAG_BITS, wiv));
                byte[] dataKey = wc.doFinal(wrapped);
                SecretKeySpec dk = new SecretKeySpec(dataKey, "AES");
                java.util.Arrays.fill(dataKey, (byte) 0);
                boolean done = false;
                try (OutputStream out = new FileOutputStream(dest)) {
                    int counter = 0;
                    while (!done) {
                        int len = in.readInt();
                        if (len < TAG_BITS / 8 || len > CHUNK + 64) throw new java.io.IOException("chunk");
                        byte[] ct = new byte[len];
                        in.readFully(ct);
                        boolean last = probeEnd(in);
                        Cipher dc = Cipher.getInstance("AES/GCM/NoPadding");
                        dc.init(Cipher.DECRYPT_MODE, dk, new GCMParameterSpec(TAG_BITS, nonce(base, counter)));
                        dc.updateAAD(aad(counter, last));
                        byte[] plain = dc.doFinal(ct);
                        out.write(plain);
                        java.util.Arrays.fill(plain, (byte) 0);
                        done = last;
                        counter++;
                    }
                }
            }
        } catch (Exception e) {
            shred(dest);
            throw e;
        } finally {
            end();
        }
    }

    /** True when the stream has no more bytes (works on buffered streams that report 0 available between refills). */
    private static boolean probeEnd(DataInputStream in) {
        try {
            in.mark(1);
            int b = in.read();
            if (b < 0) return true;
            in.reset();
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ temporary plaintext copies

    /** Removes the traces a deleted original leaves in the media database and the media scanner. */
    static void scrub(Context c, File f) {
        String p = f.getAbsolutePath();
        try {
            c.getContentResolver().delete(android.provider.MediaStore.Files.getContentUri("external"),
                    android.provider.MediaStore.MediaColumns.DATA + "=?", new String[]{p});
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                for (String vol : android.provider.MediaStore.getExternalVolumeNames(c)) {
                    try {
                        c.getContentResolver().delete(android.provider.MediaStore.Files.getContentUri(vol),
                                android.provider.MediaStore.MediaColumns.DATA + "=?", new String[]{p});
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            android.media.MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{p}, null, null);
        } catch (Throwable ignored) {
        }
    }

    /** Drops every app-side trace of a protected original: search index (names and text), favorites, pins, resume spot. */
    private static void forget(Context c, File f) {
        Context app = c.getApplicationContext();
        String p = f.getAbsolutePath();
        try {
            SmartIndex.forget(app, p);
        } catch (Throwable ignored) {
        }
        try {
            java.util.Set<String> fav = Store.favorites(app);
            if (fav.remove(p)) Store.setFavorites(app, fav);
            java.util.Set<String> pin = Store.pins(app);
            if (pin.remove(p)) Store.setPins(app, pin);
            Store.setResume(app, p, 0);
        } catch (Throwable ignored) {
        }
    }

    /** Deletes decrypted copies that were left open longer than the given time. */
    static void clearOldTemps(Context c, long olderThanMs) {
        try {
            File[] ds = tempRoot(c).listFiles();
            if (ds == null) return;
            long now = System.currentTimeMillis();
            begin();
            try {
                for (File d : ds) {
                    if (!d.getName().startsWith("vault-") || now - d.lastModified() < olderThanMs) continue;
                    File[] fs = d.listFiles();
                    if (fs != null) for (File f : fs) shred(f);
                    d.delete();
                    ConvertCache.clear(c);
                }
            } finally {
                end();
            }
        } catch (Throwable ignored) {
        }
    }

    private static File tempRoot(Context c) {
        return new File(c.getApplicationContext().getCacheDir(), "zipview");
    }

    /** Decrypts an item to a short-lived copy (inside the folder other viewers are allowed to read) and returns it. */
    static File openTemp(Context c, Item it) throws Exception {
        byte[] r = new byte[6];
        RNG.nextBytes(r);
        File d = new File(tempRoot(c), "vault-" + hex(r));
        if (!d.mkdirs()) throw new java.io.IOException("tmp");
        File out = new File(d, safeName(it.name));
        decryptTo(c, it.id, out);
        out.setReadable(false, false);
        out.setReadable(true, true);   // only this app can read the decrypted copy
        d.setReadable(false, false);
        d.setReadable(true, true);
        d.setExecutable(false, false);
        d.setExecutable(true, true);
        return out;
    }

    static void clearTemps(Context c) {
        try {
            File[] ds = tempRoot(c).listFiles();
            if (ds == null) return;
            begin();
            try {
                for (File d : ds) {
                    if (!d.getName().startsWith("vault-")) continue;
                    File[] fs = d.listFiles();
                    if (fs != null) for (File f : fs) shred(f);
                    d.delete();
                    ConvertCache.clear(c);
                }
            } finally {
                end();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ destroy

    /** Crypto-erases one item: key first, then the file, then the index entry. */
    static void destroy(Context c, String id) {
        deleteKey(ALIAS + id);
        begin();
        try {
            shred(new File(dir(c), id + ".v"));
            shred(new File(dir(c), id + ".v.tmp"));
        } finally {
            end();
        }
        List<Item> all = readIndex(c);
        List<Item> keep = new ArrayList<>();
        for (Item it : all) if (!it.id.equals(id)) keep.add(it);
        writeIndex(c, keep);
    }

    /** Break-in response: every protected document and every decrypted copy is wiped, keys included. */
    static synchronized void destroyAll(Context c, String why) {
        Context app = c.getApplicationContext();
        List<Item> all = readIndex(app);
        for (Item it : all) deleteKey(ALIAS + it.id);   // instant: nothing can be decrypted from here on
        begin();
        try {
            File[] fs = dir(app).listFiles();
            if (fs != null) for (File f : fs) shred(f);
        } finally {
            end();
        }
        clearTemps(app);
        VaultKey.reset(app);
        sp(app).edit().putString(K_ITEMS, "[]").putInt(K_FAILS, 0).commit();
        if (!all.isEmpty()) {
            GuardCore.log(app, "vault wiped (" + why + "): " + all.size());
            GuardCore.notifyVaultWiped(app, all.size());
        }
    }

    /** Overwrites a file with random data and zeros, flushes it to storage, truncates and deletes it. */
    static void shred(File f) {
        shred(f, false);
    }

    /** With {@code full} every file gets three passes whatever its size. */
    static void shred(File f, boolean full) {
        try {
            if (f == null || !f.exists()) return;
            if (f.isFile() && !Wipe.isLink(f)) {
                long len = f.length();
                try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                    int passes = !full && len > 256L * 1024 * 1024 ? 1 : 3;
                    byte[] buf = new byte[256 * 1024];
                    for (int p = 0; p < passes; p++) {
                        raf.seek(0);
                        long left = len;
                        if (p == 1) java.util.Arrays.fill(buf, (byte) 0);
                        while (left > 0) {
                            if (p != 1) RNG.nextBytes(buf);
                            int n = (int) Math.min(buf.length, left);
                            raf.write(buf, 0, n);
                            left -= n;
                        }
                        raf.getFD().sync();
                    }
                    raf.setLength(0);
                    raf.getFD().sync();
                }
                // rename first so the original name leaves no trace in the directory entry either
                File parent = f.getParentFile();
                if (parent != null) {
                    File z = new File(parent, ".z" + Long.toHexString(RNG.nextLong() & Long.MAX_VALUE));
                    if (f.renameTo(z)) f = z;
                }
            }
            f.delete();
        } catch (Throwable t) {
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ break-in watch

    /**
     * Watches the vault folder. Only this app opens those files (through {@link #begin()}); any other process touching
     * them (a root shell copying the encrypted files, for example) is a break-in attempt and wipes the vault.
     */
    @SuppressWarnings("deprecation")
    static synchronized void watch(final Context c) {
        final Context app = c.getApplicationContext();
        if (watcher != null) return;
        try {
            String path = dir(app).getAbsolutePath();
            watcher = new FileObserver(path, FileObserver.OPEN | FileObserver.ACCESS | FileObserver.MODIFY
                    | FileObserver.MOVED_FROM | FileObserver.DELETE | FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String p) {
                    if (p == null) return;
                    if (active.get() > 0 || System.currentTimeMillis() < selfUntil) return;
                    if (isEmpty(app)) return;
                    destroyAll(app, "foreign access");
                }
            };
            watcher.startWatching();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String safeName(String n) {
        String s = n == null ? "file" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "file";
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }
}
