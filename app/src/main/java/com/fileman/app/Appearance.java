package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * App themes. Two independent choices, both saved in {@link Store}:
 * <ul>
 *   <li><b>mode</b>: day (white, the default), night, or auto (follows the system). Implemented with the
 *   normal resource qualifiers (values / values-night) plus {@link AppCompatDelegate#setDefaultNightMode}.</li>
 *   <li><b>colour theme</b>: a set of accent colours (accent, soft tint and the brand gradient) applied on top of
 *   AppTheme through the Palette.* styles, so every screen, drawable and dialog follows it.</li>
 * </ul>
 */
public final class Appearance {
    private Appearance() {
    }

    public static final String LIGHT = "light", DARK = "dark", AUTO = "auto";

    /** One selectable colour theme. */
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
            new Palette("ocean", R.string.pal_ocean, R.style.Palette_Ocean),
            new Palette("emerald", R.string.pal_emerald, R.style.Palette_Emerald),
            new Palette("sunset", R.string.pal_sunset, R.style.Palette_Sunset),
            new Palette("rose", R.string.pal_rose, R.style.Palette_Rose),
            new Palette("violet", R.string.pal_violet, R.style.Palette_Violet),
            new Palette("graphite", R.string.pal_graphite, R.style.Palette_Graphite),
    };

    public static int paletteIndex(String id) {
        for (int i = 0; i < PALETTES.length; i++) if (PALETTES[i].id.equals(id)) return i;
        return 0;
    }

    public static Palette current(Context c) {
        return PALETTES[paletteIndex(Store.palette(c))];
    }

    /** Pushes the saved mode to AppCompat. Open screens recreate themselves when the effective mode changes. */
    public static void applyMode(Context c) {
        String m = Store.themeMode(c);
        int mode = DARK.equals(m) ? AppCompatDelegate.MODE_NIGHT_YES
                : AUTO.equals(m) ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_NO;
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    public static int modeName(String mode) {
        if (DARK.equals(mode)) return R.string.theme_dark;
        if (AUTO.equals(mode)) return R.string.theme_auto;
        return R.string.theme_light;
    }

    /** True when this context is currently drawn with the night resources. */
    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Recreates every open screen so a new colour theme shows immediately. */
    public static void recreateAll() {
        for (Activity a : App.openActivities()) {
            try {
                a.recreate();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * A context that renders in the given mode with the given colour theme, whatever the app is showing now.
     * Used by the theme previews so each tile shows its own real colours.
     */
    public static Context themed(Context base, boolean night, int paletteIndex) {
        Context app = base.getApplicationContext();
        Configuration cfg = new Configuration(app.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context cc = app.createConfigurationContext(cfg);
        ContextThemeWrapper w = new ContextThemeWrapper(cc, R.style.AppTheme);
        w.getTheme().applyStyle(PALETTES[paletteIndex].style, true);
        return w;
    }
}
