package com.fileman.app;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

public final class DisplaySize {
    private DisplaySize() {
    }

    public static final int LEVELS = 5;
    private static final String KEY = "display_size";
    private static final float[] SCALE = {1f, 1.08f, 1.17f, 1.26f, 1.35f};

    public static int level(Context c) {
        try {
            return Math.max(0, Math.min(LEVELS - 1, c.getSharedPreferences("fm", Context.MODE_PRIVATE).getInt("p_" + KEY, 0)));
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setLevel(Context c, int level) {
        Store.setIntPref(c, KEY, Math.max(0, Math.min(LEVELS - 1, level)));
    }

    public static float scale(Context c) {
        return SCALE[level(c)];
    }

    public static Context wrap(Context base) {
        float s = scale(base);
        if (s == 1f) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.densityDpi = Math.round(Resources.getSystem().getConfiguration().densityDpi * s);
        return base.createConfigurationContext(cfg);
    }
}
