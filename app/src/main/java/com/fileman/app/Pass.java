package com.fileman.app;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.widget.EditText;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password handling shared by the app lock and the vault. Version 2 stretches the password harder and then mixes in a
 * key that lives only inside the device's secure chip, so a copy of the files can never be brute-forced elsewhere.
 */
final class Pass {
    private static final SecureRandom RNG = new SecureRandom();
    private static final String KS = "AndroidKeyStore";
    private static final String PEPPER = "fm_pepper";
    static final int MIN_LEN = 8;
    private static final String[] COMMON = {"password", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123",
            "11111111", "00000000", "abcd1234", "iloveyou", "admin123", "letmein1", "87654321", "password1"};

    private Pass() {
    }

    static byte[] salt() {
        byte[] s = new byte[16];
        RNG.nextBytes(s);
        return s;
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt, String alg, int iter) {
        PBEKeySpec spec = new PBEKeySpec(pw, salt, iter, 256);
        try {
            return SecretKeyFactory.getInstance(alg).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Version 1 (older installs): plain PBKDF2. Kept only to read what was stored before. */
    static byte[] derive(char[] pw, byte[] salt) {
        return pbkdf2(pw, salt, "PBKDF2WithHmacSHA1", 120_000);
    }

    /** Version 2: heavier stretching, then bound to this device's secure chip. */
    static byte[] derive2(char[] pw, byte[] salt) {
        boolean sha2 = Build.VERSION.SDK_INT >= 26;
        byte[] d = pbkdf2(pw, salt, sha2 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1", sha2 ? 400_000 : 250_000);
        try {
            return pepper(d);
        } finally {
            Arrays.fill(d, (byte) 0);
        }
    }

    private static byte[] pepper(byte[] d) {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (!ks.containsAlias(PEPPER)) {
                KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KS);
                g.init(new KeyGenParameterSpec.Builder(PEPPER, KeyProperties.PURPOSE_SIGN).build());
                g.generateKey();
            }
            SecretKey k = (SecretKey) ks.getKey(PEPPER, null);
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(k);
            return m.doFinal(d);
        } catch (Exception e) {
            throw new IllegalStateException("pepper", e);
        }
    }

    static byte[] derive(int ver, char[] pw, byte[] salt) {
        return ver >= 2 ? derive2(pw, salt) : derive(pw, salt);
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

    /** Copies what was typed into a char array and clears the field, so the password does not linger as a String. */
    static char[] take(EditText e) {
        int n = e.getText().length();
        char[] c = new char[n];
        e.getText().getChars(0, n, c, 0);
        e.getText().clear();
        return c;
    }

    /** 0 = good, otherwise the string resource that says why the password is refused. */
    static int weak(char[] pw) {
        if (pw.length < MIN_LEN) return R.string.vk_short;
        boolean allSame = true;
        for (char ch : pw) if (ch != pw[0]) allSame = false;
        if (allSame) return R.string.vk_common;
        String low = new String(pw).toLowerCase(java.util.Locale.ROOT);
        for (String s : COMMON) if (low.equals(s)) return R.string.vk_common;
        boolean seq = true;
        for (int i = 1; i < pw.length; i++) if (pw[i] != pw[i - 1] + 1) seq = false;
        if (seq) return R.string.vk_common;
        return 0;
    }

    static void wipe(char[] c) {
        if (c != null) Arrays.fill(c, '\0');
    }
}
