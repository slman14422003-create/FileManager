package com.fileman.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** Tiny preference store shared by every screen. */
public final class Store {
    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("fm", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ language

    /** "system", "ar" or "en". */
    public static String language(Context c) {
        return sp(c).getString("lang", "system");
    }

    public static void setLanguage(Context c, String v) {
        sp(c).edit().putString("lang", v == null ? "system" : v).apply();
    }

    // ------------------------------------------------------------------ browsing options

    public static boolean showHidden(Context c) {
        return sp(c).getBoolean("hidden", false);
    }

    public static void setShowHidden(Context c, boolean on) {
        sp(c).edit().putBoolean("hidden", on).apply();
    }

    // ------------------------------------------------------------------ favorites (quick access)

    public static Set<String> favorites(Context c) {
        return new LinkedHashSet<>(sp(c).getStringSet("fav", new LinkedHashSet<String>()));
    }

    public static void setFavorites(Context c, Set<String> s) {
        sp(c).edit().putStringSet("fav", new LinkedHashSet<>(s)).apply();
    }

    // ------------------------------------------------------------------ one-time flags

    public static boolean flag(Context c, String key) {
        return sp(c).getBoolean("flag_" + key, false);
    }

    public static void setFlag(Context c, String key, boolean v) {
        sp(c).edit().putBoolean("flag_" + key, v).apply();
    }

    // ------------------------------------------------------------------ updates

    /** "owner/repo" the app updates itself from (the build's own repository unless changed by the user). */
    public static String updateRepo(Context c) {
        String v = sp(c).getString("upd_repo", "");
        return v.isEmpty() ? BuildConfig.UPDATE_REPO : v;
    }

    public static void setUpdateRepo(Context c, String v) {
        sp(c).edit().putString("upd_repo", v == null ? "" : v).apply();
    }

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean("upd_auto", true);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_auto", on).apply();
    }

    public static boolean updatePre(Context c) {
        return sp(c).getBoolean("upd_pre", false);
    }

    public static void setUpdatePre(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_pre", on).apply();
    }

    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong("upd_last", 0);
    }

    public static void setLastUpdateCheck(Context c, long t) {
        sp(c).edit().putLong("upd_last", t).apply();
    }

    public static String skippedVersion(Context c) {
        return sp(c).getString("upd_skip", "");
    }

    public static void setSkippedVersion(Context c, String tag) {
        sp(c).edit().putString("upd_skip", tag == null ? "" : tag).apply();
    }

    // ------------------------------------------------------------------ viewers: resume position, reading options

    /** Last playback position / page of a file (0 when unknown). */
    public static long resume(Context c, String path) {
        return sp(c).getLong("res_" + path.hashCode(), 0);
    }

    public static void setResume(Context c, String path, long v) {
        SharedPreferences.Editor e = sp(c).edit();
        if (v <= 0) e.remove("res_" + path.hashCode());
        else e.putLong("res_" + path.hashCode(), v);
        e.apply();
    }

    public static int intPref(Context c, String key, int def) {
        return sp(c).getInt("p_" + key, def);
    }

    public static void setIntPref(Context c, String key, int v) {
        sp(c).edit().putInt("p_" + key, v).apply();
    }
}
