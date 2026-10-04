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
}
