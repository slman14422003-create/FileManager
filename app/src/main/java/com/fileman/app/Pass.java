package com.fileman.app;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password stretching (PBKDF2) shared by the app lock and the vault. */
final class Pass {
    private static final int ITER = 120_000;
    private static final SecureRandom RNG = new SecureRandom();

    private Pass() {
    }

    static byte[] salt() {
        byte[] s = new byte[16];
        RNG.nextBytes(s);
        return s;
    }

    static byte[] derive(char[] pw, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pw, salt, ITER, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    static byte[] unb64(String s) {
        return s == null ? null : android.util.Base64.decode(s, android.util.Base64.NO_WRAP);
    }
}
