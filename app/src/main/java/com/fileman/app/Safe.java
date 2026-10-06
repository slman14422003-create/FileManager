package com.fileman.app;

import android.content.Context;

import java.io.File;

/** Guards that keep the app's own private data out of reach of other apps and of the file picker. */
final class Safe {
    private Safe() {
    }

    /**
     * False for anything inside this app's private storage (databases, preferences, files) other
     * than the update download in the cache folder. Symlinks are resolved first.
     */
    static boolean mayExpose(Context c, File f) {
        try {
            String p = f.getCanonicalPath();
            String own = c.getCacheDir().getCanonicalPath() + File.separator + "apk" + File.separator;
            if (p.startsWith(own)) return true;
            String view = c.getCacheDir().getCanonicalPath() + File.separator + "zipview" + File.separator;
            if (p.startsWith(view)) return true;   // files previewed from inside an archive
            String incoming = c.getCacheDir().getCanonicalPath() + File.separator + "incoming" + File.separator;
            if (p.startsWith(incoming)) return true;   // copies of files opened from other apps
            File data = c.getDataDir();
            if (data != null && inside(p, data.getCanonicalPath())) return false;
            File dp = c.createDeviceProtectedStorageContext().getDataDir();
            if (dp != null && inside(p, dp.getCanonicalPath())) return false;
            String pkg = "/" + c.getPackageName();
            if (p.startsWith("/data/data" + pkg) || p.startsWith("/data/user/") && p.contains(pkg + "/")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean inside(String path, String dir) {
        return path.equals(dir) || path.startsWith(dir + File.separator);
    }
}
