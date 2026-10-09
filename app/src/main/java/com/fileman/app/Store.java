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

    // ------------------------------------------------------------------ appearance

    /** "light" (default, white), "dark" or "auto" (follows the system). */
    public static String themeMode(Context c) {
        return sp(c).getString("theme_mode", "light");
    }

    public static void setThemeMode(Context c, String v) {
        sp(c).edit().putString("theme_mode", v == null ? "light" : v).apply();
    }

    /** Id of the colour theme ("ocean" by default); see {@link Appearance}. */
    public static String palette(Context c) {
        return sp(c).getString("theme_palette", "ocean");
    }

    public static void setPalette(Context c, String v) {
        sp(c).edit().putString("theme_palette", v == null ? "ocean" : v).apply();
    }

    // ------------------------------------------------------------------ package installer (beta 2)

    public static boolean instBool(Context c, String key, boolean def) {
        return sp(c).getBoolean("inst_" + key, def);
    }

    public static void setInstBool(Context c, String key, boolean v) {
        sp(c).edit().putBoolean("inst_" + key, v).apply();
    }

    /** Install results, newest first; one line per install, tab-separated (see PkgInstaller.log). */
    public static String instHistory(Context c) {
        return sp(c).getString("inst_history", "");
    }

    public static void setInstHistory(Context c, String v) {
        sp(c).edit().putString("inst_history", v == null ? "" : v).apply();
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

    // ------------------------------------------------------------------ places drawer: recently visited folders

    private static final int MAX_RECENT_PLACES = 20;

    /** Folder paths most recently browsed to, newest first. */
    public static java.util.List<String> recentPlaces(Context c) {
        String raw = sp(c).getString("recent_places", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String p : raw.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Moves the path to the front, dropping any older duplicate and trimming the tail. */
    public static void addRecentPlace(Context c, String path) {
        java.util.List<String> cur = recentPlaces(c);
        cur.remove(path);
        cur.add(0, path);
        while (cur.size() > MAX_RECENT_PLACES) cur.remove(cur.size() - 1);
        sp(c).edit().putString("recent_places", android.text.TextUtils.join("\n", cur)).apply();
    }

    public static void clearRecentPlaces(Context c) {
        sp(c).edit().remove("recent_places").apply();
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
