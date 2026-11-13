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

    private static void copy(InputStream in, OutputStream os) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }
}
