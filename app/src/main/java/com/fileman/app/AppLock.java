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
        sp(c).edit().putString("salt", Pass.b64(salt)).putString("hash", Pass.b64(Pass.derive2(pw, salt)))
                .putInt("ver", 2).putInt("fails", 0).putLong("until", 0).apply();
        unlocked = true;
    }

    static boolean verify(Context c, char[] pw) {
        SharedPreferences p = sp(c);
        byte[] salt = Pass.unb64(p.getString("salt", null));
        byte[] hash = Pass.unb64(p.getString("hash", null));
        if (salt == null || hash == null) return false;
        int ver = p.getInt("ver", 1);
        boolean ok = Pass.same(hash, Pass.derive(ver, pw, salt));
        if (ok && ver < 2) setPassword(c, pw);   // upgrade an older hash to the device-bound one
        return ok;
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

    static void markUnlocked(Context c) {
        unlocked = true;
        sp(c).edit().putInt("fails", 0).putLong("until", 0).apply();
        Remote.checkin(c);
    }

    // ------------------------------------------------------------------ wrong-password delay (kept on disk, so restarting does not reset it)

    /** Milliseconds still to wait before another try is allowed (0 = free to try). */
    static long waitMs(Context c) {
        long left = sp(c).getLong("until", 0) - System.currentTimeMillis();
        return Math.max(0, left);
    }

    static void failed(Context c) {
        SharedPreferences p = sp(c);
        int n = p.getInt("fails", 0) + 1;
        long until = 0;
        if (n >= 5) {
            long sec = Math.min(3600, 15L << Math.min(n - 5, 8));   // 15 s, 30 s, 1 min ... up to an hour
            until = System.currentTimeMillis() + sec * 1000L;
        }
        p.edit().putInt("fails", n).putLong("until", until).apply();
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
