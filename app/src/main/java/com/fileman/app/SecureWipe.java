package com.fileman.app;

import android.content.Context;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional permanent delete: every file is overwritten (random, zeros, random), flushed to storage, truncated,
 * renamed and only then deleted. Folders are emptied the same way. Nothing goes to the in-app trash, so there is no undo.
 */
final class SecureWipe {
    private SecureWipe() {
    }

    /** Returns {erased files, items that could not be removed}. */
    static int[] run(Context c, List<File> roots) {
        Context app = c.getApplicationContext();
        int[] cnt = {0, 0};
        Set<String> paths = new HashSet<>();
        for (File r : roots) walk(app, r, cnt, paths);
        try {
            SmartIndex.forgetAll(app, paths);
        } catch (Throwable ignored) {
        }
        try {
            Set<String> fav = Store.favorites(app);
            if (fav.removeAll(paths)) Store.setFavorites(app, fav);
            Set<String> pin = Store.pins(app);
            if (pin.removeAll(paths)) Store.setPins(app, pin);
            for (String p : paths) Store.setResume(app, p, 0);
        } catch (Throwable ignored) {
        }
        ConvertCache.clear(app);
        return cnt;
    }

    private static void walk(Context app, File f, int[] cnt, Set<String> paths) {
        if (Wipe.isLink(f)) {
            if (f.delete()) cnt[0]++;
            else cnt[1]++;
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) walk(app, k, cnt, paths);
            if (!f.delete() && Wipe.exists(f)) cnt[1]++;
            return;
        }
        paths.add(f.getAbsolutePath());
        Vault.shred(f, true);
        if (Wipe.exists(f)) {
            cnt[1]++;
            return;
        }
        Vault.scrub(app, f);
        cnt[0]++;
    }
}
