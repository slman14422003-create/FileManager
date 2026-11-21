package com.fileman.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;
import java.util.Locale;

final class QuickShare {
    private static final String GMS = "com.google.android.gms";
    private static final String GMS_SHEET = "com.google.android.gms.nearby.sharing.ShareSheetActivity";
    private static final String SAMSUNG = "com.samsung.android.app.sharelive";

    private QuickShare() {
    }

    static ComponentName find(Context c, Intent send) {
        PackageManager pm = c.getPackageManager();
        ComponentName samsung = null;
        try {
            List<ResolveInfo> all = pm.queryIntentActivities(send, 0);
            for (ResolveInfo ri : all) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || !ai.exported) continue;
                String n = ai.name == null ? "" : ai.name.toLowerCase(Locale.ROOT);
                if (GMS.equals(ai.packageName) && (n.contains("nearby") || n.contains("sharing"))) {
                    return new ComponentName(ai.packageName, ai.name);
                }
                if (SAMSUNG.equals(ai.packageName) && samsung == null) samsung = new ComponentName(ai.packageName, ai.name);
            }
        } catch (Throwable ignored) {
        }
        if (samsung != null) return samsung;
        try {
            Intent probe = new Intent(send).setComponent(new ComponentName(GMS, GMS_SHEET));
            if (pm.resolveActivity(probe, 0) != null) return probe.getComponent();
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean launch(Context c, Intent send) {
        ComponentName cn = find(c, send);
        if (cn == null) return false;
        try {
            Intent in = new Intent(send).setComponent(cn);
            c.startActivity(in);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean available(Context c) {
        try {
            Intent probe = new Intent(Intent.ACTION_SEND).setType("*/*");
            return find(c, probe) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
