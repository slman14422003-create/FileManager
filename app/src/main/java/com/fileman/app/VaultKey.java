package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The vault's own password layer. A random master key is kept only wrapped (by the password, and optionally by a
 * fingerprint-bound key in the secure chip). It lives in memory just while the vault is open: leaving the vault
 * wipes it, and nothing inside can be read again until the password or the fingerprint is given.
 */
final class VaultKey {
    private static final String PREFS = "vaultkey";
    private static final String KS = "AndroidKeyStore";
    private static final String BIO_ALIAS = "fmvault_bio";
    private static final SecureRandom RNG = new SecureRandom();
    private static volatile byte[] master;

    private VaultKey() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isSetup(Context c) {
        return sp(c).getString("wrapped", null) != null;
    }

    static boolean isUnlocked() {
        return master != null;
    }

    static byte[] current() {
        byte[] m = master;
        if (m == null) throw new IllegalStateException("locked");
        return m;
    }

    static void lock() {
        byte[] m = master;
        master = null;
        if (m != null) Arrays.fill(m, (byte) 0);
    }

    private static byte[] seal(byte[] key, byte[] data) throws Exception {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(data);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] open(byte[] key, byte[] blob) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, blob, 0, 12));
        return c.doFinal(blob, 12, blob.length - 12);
    }

    /** Creates the vault password (only when none exists yet) and leaves the vault open. */
    static boolean setup(Context c, char[] pw) {
        if (isSetup(c)) return false;
        try {
            byte[] m = new byte[32];
            RNG.nextBytes(m);
            store(c, pw, m);
            master = m;
            Remote.checkin(c);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void store(Context c, char[] pw, byte[] m) throws Exception {
        byte[] salt = Pass.salt();
        byte[] k = Pass.derive2(pw, salt);
        try {
            sp(c).edit().putString("salt", Pass.b64(salt)).putString("wrapped", Pass.b64(seal(k, m)))
                    .putInt("ver", 2).apply();
        } finally {
            Arrays.fill(k, (byte) 0);
        }
    }

    /** Opens the master key with the password without leaving the vault open (null when the password is wrong). */
    private static byte[] tryOpen(Context c, char[] pw) {
        try {
            SharedPreferences p = sp(c);
            byte[] salt = Pass.unb64(p.getString("salt", null));
            byte[] wrapped = Pass.unb64(p.getString("wrapped", null));
            if (salt == null || wrapped == null) return null;
            byte[] k = Pass.derive(p.getInt("ver", 1), pw, salt);
            try {
                return open(k, wrapped);
            } finally {
                Arrays.fill(k, (byte) 0);
            }
        } catch (Exception e) {
            return null;
        }
    }

    static boolean unlock(Context c, char[] pw) {
        byte[] m = tryOpen(c, pw);
        if (m == null) return false;
        master = m;
        try {
            if (sp(c).getInt("ver", 1) < 2) store(c, pw, m);   // older install: upgrade to the device-bound version
        } catch (Exception ignored) {
        }
        Remote.checkin(c);
        return true;
    }

    /** True when the given password is the real vault password (used to refuse a duress password that equals it). */
    static boolean isReal(Context c, char[] pw) {
        byte[] m = tryOpen(c, pw);
        if (m == null) return false;
        Arrays.fill(m, (byte) 0);
        return true;
    }

    // ------------------------------------------------------------------ duress password

    static boolean panicSet(Context c) {
        return sp(c).getString("phash", null) != null;
    }

    static void setPanic(Context c, char[] pw) {
        byte[] salt = Pass.salt();
        sp(c).edit().putString("psalt", Pass.b64(salt)).putString("phash", Pass.b64(Pass.derive2(pw, salt))).apply();
    }

    static void clearPanic(Context c) {
        sp(c).edit().remove("psalt").remove("phash").apply();
    }

    static boolean isPanic(Context c, char[] pw) {
        try {
            SharedPreferences p = sp(c);
            byte[] salt = Pass.unb64(p.getString("psalt", null));
            byte[] hash = Pass.unb64(p.getString("phash", null));
            return salt != null && hash != null && Pass.same(hash, Pass.derive2(pw, salt));
        } catch (Exception e) {
            return false;
        }
    }

    /** Forgets the password layer completely (used when the vault is erased). */
    static void reset(Context c) {
        lock();
        sp(c).edit().clear().apply();
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ fingerprint

    static boolean bioReady(Context c) {
        return Bio.cryptoSupported() && Bio.available(c) && sp(c).getString("bio", null) != null;
    }

    private static SecretKey bioKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(BIO_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        }
        g.init(b.build());
        SecretKey k = g.generateKey();
        if (Build.VERSION.SDK_INT >= 23) {
            // refuse a key that is not kept inside secure hardware
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance(k.getAlgorithm(), KS);
            android.security.keystore.KeyInfo info = (android.security.keystore.KeyInfo) f.getKeySpec(k,
                    android.security.keystore.KeyInfo.class);
            if (!info.isInsideSecureHardware()) {
                KeyStore ks = KeyStore.getInstance(KS);
                ks.load(null);
                ks.deleteEntry(BIO_ALIAS);
                throw new java.security.GeneralSecurityException("software key");
            }
        }
        return k;
    }

    /** Lets a fingerprint open the vault from now on. The vault must be open; the finger is asked once to bind the key. */
    static void bioEnable(final android.app.Activity a, final Runnable done, final Runnable failed) {
        if (!isUnlocked() || !Bio.cryptoSupported()) {
            failed.run();
            return;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, bioKey());
            Bio.authenticate(a, a.getString(R.string.vk_bio_title), a.getString(R.string.cancel), c, new Bio.Result() {
                @Override
                public void ok(Cipher cipher) {
                    try {
                        byte[] ct = cipher.doFinal(current());
                        byte[] iv = cipher.getIV();
                        byte[] blob = new byte[1 + iv.length + ct.length];
                        blob[0] = (byte) iv.length;
                        System.arraycopy(iv, 0, blob, 1, iv.length);
                        System.arraycopy(ct, 0, blob, 1 + iv.length, ct.length);
                        sp(a).edit().putString("bio", Pass.b64(blob)).apply();
                        done.run();
                    } catch (Throwable t) {
                        failed.run();
                    }
                }

                @Override
                public void fail() {
                    failed.run();
                }
            });
        } catch (Throwable t) {
            failed.run();
        }
    }

    static void bioDisable(Context c) {
        sp(c).edit().remove("bio").apply();
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS);
        } catch (Throwable ignored) {
        }
    }

    static void bioUnlock(final android.app.Activity a, final Runnable ok, final Runnable failed) {
        try {
            byte[] blob = Pass.unb64(sp(a).getString("bio", null));
            if (blob == null) {
                failed.run();
                return;
            }
            final int ivLen = blob[0] & 0xFF;
            final byte[] ct = Arrays.copyOfRange(blob, 1 + ivLen, blob.length);
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(BIO_ALIAS, null);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 1, ivLen));
            Bio.authenticate(a, a.getString(R.string.vk_bio_title), a.getString(R.string.cancel), c, new Bio.Result() {
                @Override
                public void ok(Cipher cipher) {
                    try {
                        master = cipher.doFinal(ct);
                        Remote.checkin(a);
                        ok2(ok);
                    } catch (Throwable t) {
                        failed.run();
                    }
                }

                private void ok2(Runnable r) {
                    r.run();
                }

                @Override
                public void fail() {
                    failed.run();
                }
            });
        } catch (Throwable t) {
            bioDisable(a);   // the key was invalidated (a finger was added or removed): fall back to the password
            failed.run();
        }
    }
}
