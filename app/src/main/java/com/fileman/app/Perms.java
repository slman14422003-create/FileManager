package com.fileman.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;

/** One place for every permission the app needs: all-files access and installing APKs. */
public final class Perms {
    public static final int REQ_STORAGE = 4021;

    private Perms() {
    }

    // ------------------------------------------------------------------ storage

    public static boolean hasAllFiles(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Opens the right system screen (or dialog) to grant file access. On Android 10 and older the
     * runtime dialog is used; once the user has chosen "don't ask again" the system shows nothing, so
     * the app settings page is opened instead.
     */
    public static void requestAllFiles(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName()));
                a.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (Exception ignored) {
            }
            openAppSettings(a);
        } else {
            boolean askedBefore = Store.flag(a, "storage_asked");
            boolean canAsk = ActivityCompat.shouldShowRequestPermissionRationale(a,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    || ActivityCompat.shouldShowRequestPermissionRationale(a,
                    Manifest.permission.READ_EXTERNAL_STORAGE);
            if (askedBefore && !canAsk) {
                openAppSettings(a);   // permanently denied: only the settings page can grant it now
                return;
            }
            Store.setFlag(a, "storage_asked", true);
            ActivityCompat.requestPermissions(a, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    /**
     * Explains why the app needs file access and offers to grant it. Shown once, the first time the
     * home screen opens without the permission; the home screen keeps a permanent banner afterwards.
     */
    public static void promptFirstRun(final Activity a) {
        if (hasAllFiles(a) || Store.flag(a, "perm_intro")) return;
        Store.setFlag(a, "perm_intro", true);
        new Dlg(a)
                .setTitle(R.string.perm_intro_title)
                .setMessage(R.string.perm_intro_body)
                .setPositiveButton(R.string.perm_allow, (d, w) -> requestAllFiles(a))
                .setNegativeButton(R.string.perm_later, null)
                .show();
    }

    // ------------------------------------------------------------------ media (Android 13+)

    public static boolean hasMedia(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;   // covered by storage access
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestMedia(Activity a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        boolean askedBefore = Store.flag(a, "media_asked");
        boolean canAsk = ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.READ_MEDIA_IMAGES)
                || ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.READ_MEDIA_VIDEO)
                || ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.READ_MEDIA_AUDIO);
        if (askedBefore && !canAsk) {
            openAppSettings(a);   // permanently denied: only the settings page can grant it now
            return;
        }
        Store.setFlag(a, "media_asked", true);
        ActivityCompat.requestPermissions(a, new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO}, REQ_STORAGE);
    }

    /** Asks for the first permission that is still missing (files, then media, then installing). True if one was missing. */
    public static boolean fixNext(Activity a) {
        if (!hasAllFiles(a)) {
            requestAllFiles(a);
            return true;
        }
        if (!hasMedia(a)) {
            requestMedia(a);
            return true;
        }
        if (!canInstall(a)) {
            requestInstall(a);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ install unknown apps

    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    public static void requestInstall(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
                return;
            } catch (Exception ignored) {
            }
        }
        openAppSettings(a);
    }

    public static void openAppSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    /**
     * Installs an APK file. When the "install unknown apps" permission is missing, explains why and
     * sends the user to the exact system screen instead of silently failing.
     */
    public static void installApk(final Activity a, final File apk) {
        if (!canInstall(a)) {
            new Dlg(a)
                    .setTitle(R.string.perm_install_title)
                    .setMessage(R.string.perm_install_dialog)
                    .setPositiveButton(R.string.perm_open_settings, (d, w) -> requestInstall(a))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (!Safe.mayExpose(a, apk)) {
            android.widget.Toast.makeText(a, R.string.fm_private_blocked, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(a, R.string.install_failed, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ default app for opening files

    /** Representative MIME type and extension for each kind of file the "open by default" screen lists. */
    public static final String[][] DEFAULT_TYPES = {
            {"application/pdf", "pdf"},
            {"image/jpeg", "jpg"},
            {"video/mp4", "mp4"},
            {"audio/mpeg", "mp3"},
            {"text/plain", "txt"},
            {"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"},
            {"application/zip", "zip"},
            {"application/vnd.android.package-archive", "apk"},
    };

    /** A link that points nowhere: only used to make Android show its "open with" list for a type. */
    private static Uri probeUri(Context c, String ext) {
        File f = new File(new File(c.getCacheDir(), "defaults"), "probe." + ext);
        return FileProvider.getUriForFile(c, c.getPackageName() + ".files", f);
    }

    static boolean isProbe(Context c, Uri u) {
        String p = u.getPath();
        return u.getAuthority() != null && u.getAuthority().equals(c.getPackageName() + ".files")
                && p != null && p.contains("/defaults/probe.");
    }

    private static Intent probeIntent(Context c, String mime, String ext) {
        Uri u = probeUri(c, ext);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, mime);
        i.setClipData(android.content.ClipData.newRawUri("", u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /** Package that Android would open this type with right now, or null when it would ask. */
    static String defaultHandler(Context c, String mime, String ext) {
        try {
            android.content.pm.ResolveInfo ri = c.getPackageManager()
                    .resolveActivity(probeIntent(c, mime, ext), PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return null;
            String pkg = ri.activityInfo.packageName;
            return "android".equals(pkg) ? null : pkg;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isDefaultFor(Context c, String mime, String ext) {
        return c.getPackageName().equals(defaultHandler(c, mime, ext));
    }

    /**
     * Android does not let an app make itself the default for files, so this fires a harmless test link
     * of the given type: the system lists the apps able to open it and the user picks this app and
     * "Always". When another app is already the default, the page where its defaults are cleared opens.
     */
    public static void chooseDefault(final Activity a, String mime, String ext) {
        final String other = defaultHandler(a, mime, ext);
        if (other != null && !a.getPackageName().equals(other)) {
            new Dlg(a)
                    .setTitle(R.string.def_other_title)
                    .setMessage(R.string.def_other_msg)
                    .setPositiveButton(R.string.def_open_other, (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + other)));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        try {
            a.startActivity(probeIntent(a, mime, ext));
        } catch (Exception e) {
            android.widget.Toast.makeText(a, R.string.fm_no_app, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** The system "default apps" page (falls back to this app's own settings page). */
    public static void openDefaultAppsSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (Exception e) {
            openAppSettings(a);
        }
    }
}
