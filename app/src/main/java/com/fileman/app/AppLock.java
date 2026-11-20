package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;

/** App-wide lock: a password and optionally a fingerprint, asked again after the app has been left. */
final class AppLock {
    private static final String PREFS = "applock";
    private static final long MIN_AWAY = 2500L;
    private static volatile boolean unlocked;
    private static volatile long leftAt;

    private AppLock() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getString("hash", null) != null;
    }

    static void setPassword(Context c, char[] pw) {
        byte[] salt = Pass.salt();
        sp(c).edit().putString("salt", Pass.b64(salt)).putString("hash", Pass.b64(Pass.derive(pw, salt))).apply();
        unlocked = true;
    }

    static boolean verify(Context c, char[] pw) {
        SharedPreferences p = sp(c);
        byte[] salt = Pass.unb64(p.getString("salt", null));
        byte[] hash = Pass.unb64(p.getString("hash", null));
        if (salt == null || hash == null) return false;
        return Pass.same(hash, Pass.derive(pw, salt));
    }

    static void disable(Context c) {
        sp(c).edit().clear().apply();
    }

    static boolean bio(Context c) {
        return enabled(c) && sp(c).getBoolean("bio", false) && Bio.available(c);
    }

    static void setBio(Context c, boolean on) {
        sp(c).edit().putBoolean("bio", on).apply();
    }

    /** Seconds the app may stay in the background before it asks again (0 = as soon as it was left). */
    static int timeoutSec(Context c) {
        return sp(c).getInt("timeout", 0);
    }

    static void setTimeoutSec(Context c, int s) {
        sp(c).edit().putInt("timeout", s).apply();
    }

    static boolean locked(Context c) {
        return enabled(c) && !unlocked;
    }

    static void markUnlocked() {
        unlocked = true;
    }

    static void left() {
        leftAt = System.currentTimeMillis();
    }

    /** Called when the first screen of the app comes back to the front. */
    static void returned(Context c) {
        if (!enabled(c) || !unlocked) return;
        long away = System.currentTimeMillis() - leftAt;
        if (away >= Math.max(MIN_AWAY, timeoutSec(c) * 1000L)) unlocked = false;
    }
}
