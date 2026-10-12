package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AppCompatDelegate;

public final class Appearance {
    private Appearance() {
    }

    public static final String LIGHT = "light", SAND = "sand", DARK = "dark", AMOLED = "amoled",
            MIDNIGHT = "midnight", GRAY = "gray", AUTO = "auto";

    public static final String[] MODES = {LIGHT, SAND, DARK, AMOLED, MIDNIGHT, GRAY, AUTO};

    public static final String DEFAULT_PALETTE = "mono";

    public static final class Palette {
        public final String id;
        public final int name;
        public final int style;

        Palette(String id, int name, int style) {
            this.id = id;
            this.name = name;
            this.style = style;
        }
    }

    public static final Palette[] PALETTES = {
            new Palette("mono", R.string.pal_mono, R.style.Palette_Mono),
            new Palette("ocean", R.string.pal_ocean, R.style.Palette_Ocean),
            new Palette("emerald", R.string.pal_emerald, R.style.Palette_Emerald),
            new Palette("sunset", R.string.pal_sunset, R.style.Palette_Sunset),
            new Palette("rose", R.string.pal_rose, R.style.Palette_Rose),
            new Palette("violet", R.string.pal_violet, R.style.Palette_Violet),
            new Palette("teal", R.string.pal_teal, R.style.Palette_Teal),
            new Palette("gold", R.string.pal_gold, R.style.Palette_Gold),
            new Palette("crimson", R.string.pal_crimson, R.style.Palette_Crimson),
            new Palette("indigo", R.string.pal_indigo, R.style.Palette_Indigo),
            new Palette("aurora", R.string.pal_aurora, R.style.Palette_Aurora),
            new Palette("fuchsia", R.string.pal_fuchsia, R.style.Palette_Fuchsia),
            new Palette("graphite", R.string.pal_graphite, R.style.Palette_Graphite),
    };

    public static int paletteIndex(String id) {
        for (int i = 0; i < PALETTES.length; i++) if (PALETTES[i].id.equals(id)) return i;
        return 0;
    }

    public static Palette current(Context c) {
        return PALETTES[paletteIndex(Store.palette(c))];
    }

    public static String normalize(String mode) {
        for (String m : MODES) if (m.equals(mode)) return m;
        return LIGHT;
    }

    public static int overlayStyle(String mode) {
        if (GRAY.equals(mode)) return R.style.GrayMode;
        if (AMOLED.equals(mode)) return R.style.AmoledMode;
        if (MIDNIGHT.equals(mode)) return R.style.MidnightMode;
        if (SAND.equals(mode)) return R.style.SandMode;
        return 0;
    }

    public static void applyTo(Resources.Theme theme, Context c) {
        theme.applyStyle(current(c).style, true);
        int overlay = overlayStyle(Store.themeMode(c));
        if (overlay != 0) theme.applyStyle(overlay, true);
    }

    public static void applyMode(Context c) {
        String m = Store.themeMode(c);
        int mode = AUTO.equals(m) ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : nightFor(m) ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO;
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    public static int modeName(String mode) {
        if (SAND.equals(mode)) return R.string.theme_sand;
        if (DARK.equals(mode)) return R.string.theme_dark;
        if (AMOLED.equals(mode)) return R.string.theme_amoled;
        if (MIDNIGHT.equals(mode)) return R.string.theme_midnight;
        if (GRAY.equals(mode)) return R.string.theme_gray;
        if (AUTO.equals(mode)) return R.string.theme_auto;
        return R.string.theme_light;
    }

    public static boolean nightFor(String mode) {
        if (DARK.equals(mode) || AMOLED.equals(mode) || MIDNIGHT.equals(mode) || GRAY.equals(mode)) return true;
        if (AUTO.equals(mode)) {
            return (Resources.getSystem().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void recreateAll() {
        for (Activity a : App.openActivities()) {
            try {
                a.recreate();
            } catch (Throwable ignored) {
            }
        }
    }

    public static Context themed(Context base, String mode, int paletteIndex) {
        boolean night = nightFor(AUTO.equals(mode) ? DARK : mode);
        Context app = base.getApplicationContext();
        Configuration cfg = new Configuration(app.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context cc = app.createConfigurationContext(cfg);
        ContextThemeWrapper w = new ContextThemeWrapper(cc, R.style.AppTheme);
        w.getTheme().applyStyle(PALETTES[paletteIndex].style, true);
        int overlay = overlayStyle(mode);
        if (overlay != 0) w.getTheme().applyStyle(overlay, true);
        return w;
    }
}
