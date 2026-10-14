package com.fileman.app;

import android.content.Context;

import org.json.JSONObject;

/**
 * Drive access through the person's own Google Apps Script web app (the bridge). The script runs in the person's
 * account and hands out a short-lived access token when it is called with the secret the person chose; no Google
 * Cloud project and no key inside the app are needed. The token is kept in memory only and is asked for again,
 * silently, when it expires.
 */
final class DriveAuth {
    private DriveAuth() {
    }

    private static final long TOKEN_LIFE_MS = 50L * 60 * 1000;
    private static final Object LOCK = new Object();

    private static volatile String token;
    private static volatile long tokenUntil;

    interface Done {
        void ok();

        void fail();
    }

    private static void fetch(Context c) throws Exception {
        String url = DriveStore.bridgeUrl(c);
        String key = DriveStore.bridgeSecret(c);
        if (url.isEmpty() || key.isEmpty() || !DriveHttp.bridgeUrl(url)) throw new IllegalStateException("consent");
        JSONObject o;
        try {
            o = new JSONObject(DriveHttp.bridge(url, key));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("consent");
        }
        String t = o.optString("token", "");
        if (!o.optBoolean("ok", false) || t.isEmpty()) throw new IllegalStateException("consent");
        token = t;
        tokenUntil = System.currentTimeMillis() + TOKEN_LIFE_MS;
        DriveStore.setSignedIn(c, true);
    }

    /** Tries the saved bridge on a worker thread and reports back on that thread. */
    static void signIn(final Context c, final Done cb) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                invalidate();
                fetch(app);
                cb.ok();
            } catch (Exception e) {
                cb.fail();
            }
        }, "drive-signin").start();
    }

    /** A valid access token. Blocks, so call it from a worker thread only. */
    static String token(Context c) throws Exception {
        String t = token;
        if (t != null && System.currentTimeMillis() < tokenUntil) return t;
        synchronized (LOCK) {
            t = token;
            if (t != null && System.currentTimeMillis() < tokenUntil) return t;
            fetch(c.getApplicationContext());
            return token;
        }
    }

    static void invalidate() {
        token = null;
        tokenUntil = 0;
    }

    /** Forgets the bridge and the account on this device. The script itself is removed from script.google.com. */
    static void signOut(Context c) {
        invalidate();
        DriveStore.clear(c);
    }
}
