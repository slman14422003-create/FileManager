package com.fileman.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Saves a copy of a file (for example one received from another app and kept in the cache) into the public Download folder. */
final class SaveCopy {
    private SaveCopy() {
    }

    static boolean toDownloads(Context c, File src) {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (Perms.hasAllFiles(c) && dl != null && (dl.isDirectory() || dl.mkdirs()) && dl.canWrite()) {
                File out = ExternalDocs.uniqueIn(dl, src.getName(), false);
                try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                return true;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                String n = src.getName();
                int dot = n.lastIndexOf('.');
                String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase(Locale.ROOT));
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, n);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) return false;
                try (InputStream in = new FileInputStream(src); OutputStream os = c.getContentResolver().openOutputStream(u)) {
                    if (os == null) return false;
                    copy(in, os);
                }
                return true;
            }
        } catch (Exception e) {
            PickLog.add(c, "save copy FAILED " + src, e);
        }
        return false;
    }

    /** Streams the content behind a link straight into the public Download folder (no cache copy). Returns the saved name or null. */
    static String uriToDownloads(Context c, Uri src) {
        try {
            String n = UriFiles.clean(Cats.displayName(c.getContentResolver(), src));
            if (Cats.extOf(n).isEmpty()) {
                String t = c.getContentResolver().getType(src);
                String x = t == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(t);
                if (x != null && !x.isEmpty()) n = n + "." + x;
            }
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (Perms.hasAllFiles(c) && dl != null && (dl.isDirectory() || dl.mkdirs()) && dl.canWrite()) {
                File out = ExternalDocs.uniqueIn(dl, n, false);
                try (InputStream in = c.getContentResolver().openInputStream(src); OutputStream os = new FileOutputStream(out)) {
                    if (in == null) return null;
                    copy(in, os);
                }
                return out.getName();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                int dot = n.lastIndexOf('.');
                String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase(Locale.ROOT));
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, n);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) return null;
                try (InputStream in = c.getContentResolver().openInputStream(src); OutputStream os = c.getContentResolver().openOutputStream(u)) {
                    if (in == null || os == null) {
                        c.getContentResolver().delete(u, null, null);
                        return null;
                    }
                    copy(in, os);
                } catch (Exception e) {
                    c.getContentResolver().delete(u, null, null);
                    throw e;
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                c.getContentResolver().update(u, done, null, null);
                return n;
            }
        } catch (Exception e) {
            PickLog.add(c, "save uri FAILED " + src, e);
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream os) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }
}
