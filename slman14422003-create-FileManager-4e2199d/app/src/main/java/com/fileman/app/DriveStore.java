package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Local data of the Drive feature, kept apart from the rest of the app: its own private preferences file and a
 * backup index in the no-backup folder. No access token is ever stored here (tokens live in memory only). The bridge address is kept as is and the bridge
 * secret is kept encrypted with a key that never leaves the Android Keystore.
 */
final class DriveStore {
    private DriveStore() {
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("drive_prefs", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return p(c).getBoolean("signed", false);
    }

    static void setSignedIn(Context c, boolean v) {
        p(c).edit().putBoolean("signed", v).apply();
    }

    private static final String KEY_ALIAS = "drive_bridge_key";

    private static SecretKey bridgeKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        }
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    private static String seal(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, bridgeKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }

    private static String unseal(String sealed) throws Exception {
        byte[] all = Base64.decode(sealed, Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, bridgeKey(), new GCMParameterSpec(128, all, 0, 12));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    static String bridgeUrl(Context c) {
        return p(c).getString("bridge_url", "");
    }

    static String bridgeSecret(Context c) {
        String s = p(c).getString("bridge_key", "");
        if (s.isEmpty()) return "";
        try {
            return unseal(s);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean bridgeSet(Context c) {
        return !bridgeUrl(c).isEmpty() && !p(c).getString("bridge_key", "").isEmpty();
    }

    /** Saves the bridge address and secret. False when the secret could not be encrypted (nothing is saved then). */
    static boolean setBridge(Context c, String url, String secret) {
        try {
            String sealed = seal(secret);
            p(c).edit().putString("bridge_url", url).putString("bridge_key", sealed).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void setAccount(Context c, String name, String email) {
        p(c).edit().putString("name", name == null ? "" : name).putString("email", email == null ? "" : email).apply();
    }

    static String name(Context c) {
        return p(c).getString("name", "");
    }

    static String email(Context c) {
        return p(c).getString("email", "");
    }

    static void setQuota(Context c, long used, long limit) {
        p(c).edit().putLong("used", used).putLong("limit", limit).apply();
    }

    static long used(Context c) {
        return p(c).getLong("used", -1);
    }

    static long limit(Context c) {
        return p(c).getLong("limit", 0);
    }

    static List<String> folders(Context c) {
        List<String> out = new ArrayList<>();
        String s = p(c).getString("folders", "");
        for (String x : s.split("\n")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    static void setFolders(Context c, List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String s : list) b.append(s).append('\n');
        p(c).edit().putString("folders", b.toString()).apply();
    }

    private static File indexFile(Context c) {
        return new File(c.getApplicationContext().getNoBackupFilesDir(), "drive_index.json");
    }

    /** Local path to "size:modified|driveId" of what was already uploaded. */
    static Map<String, String> index(Context c) {
        Map<String, String> m = new HashMap<>();
        File f = indexFile(c);
        if (!f.isFile() || f.length() > 64L * 1024 * 1024) return m;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.optString(k));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    static void saveIndex(Context c, Map<String, String> m) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
            File f = indexFile(c);
            File tmp = new File(f.getParentFile(), "drive_index.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {
        }
    }

    /** Forgets everything about the account (sign-out). */
    static void clear(Context c) {
        p(c).edit().clear().apply();
        indexFile(c).delete();
    }
}
