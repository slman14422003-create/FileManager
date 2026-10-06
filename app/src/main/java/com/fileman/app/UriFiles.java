package com.fileman.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Turns the link another app hands over (file://, content://, document or tree URIs) into a real
 * {@link File} the built-in viewers can open. When no real path exists the content is copied once into
 * the cache folder ({@code cache/incoming}), which is cleaned automatically.
 */
final class UriFiles {
    private UriFiles() {
    }

    private static final String EXTERNAL_DOCS = "com.android.externalstorage.documents";
    private static final String DOWNLOAD_DOCS = "com.android.providers.downloads.documents";
    private static final long KEEP_MS = 6L * 60 * 60 * 1000;

    /** The real file or folder behind the link, or null when there is none (the caller then copies). */
    static File resolve(Context c, Uri u) {
        if (u == null) return null;
        String scheme = u.getScheme();
        if ("file".equals(scheme)) {
            String p = u.getPath();
            return p == null ? null : new File(p);
        }
        if (!"content".equals(scheme)) return null;
        String auth = u.getAuthority();
        try {
            if (DocumentsContract.isDocumentUri(c, u)) {
                String id = DocumentsContract.getDocumentId(u);
                if (EXTERNAL_DOCS.equals(auth)) {
                    File f = fromExternalDocId(id);
                    if (f != null) return f;
                } else if (DOWNLOAD_DOCS.equals(auth) && id != null && id.startsWith("raw:")) {
                    File f = new File(id.substring(4));
                    if (onStorage(f)) return f;
                }
            } else if (EXTERNAL_DOCS.equals(auth) && DocumentsContract.isTreeUri(u)) {
                File f = fromExternalDocId(DocumentsContract.getTreeDocumentId(u));
                if (f != null) return f;
            }
        } catch (Exception ignored) {
        }
        // MediaStore and many other providers expose the real path in the legacy _data column
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(u, new String[]{"_data"}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String p = cur.getString(0);
                if (p != null && p.startsWith("/")) {
                    File f = new File(p);
                    if (onStorage(f)) return f;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }

    private static File fromExternalDocId(String id) {
        if (id == null) return null;
        int i = id.indexOf(':');
        if (i <= 0) return null;
        String vol = id.substring(0, i);
        String rel = id.substring(i + 1);
        File base = "primary".equalsIgnoreCase(vol) ? Environment.getExternalStorageDirectory()
                : new File("/storage/" + vol);
        File f = rel.isEmpty() ? base : new File(base, rel);
        return onStorage(f) ? f : null;
    }

    /** Paths reported by other apps are trusted only when they point into shared storage. */
    private static boolean onStorage(File f) {
        try {
            String p = f.getCanonicalPath();
            String ext = Environment.getExternalStorageDirectory().getCanonicalPath();
            return p.equals(ext) || p.startsWith(ext + File.separator) || p.startsWith("/storage/");
        } catch (IOException e) {
            return false;
        }
    }

    /** Copies the content behind the link into the cache and returns the copy (named like the original). */
    static File copyToCache(Context c, Uri u) throws IOException {
        File root = new File(c.getCacheDir(), "incoming");
        File dir = new File(root, Long.toString(System.nanoTime()));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cache");
        String name = clean(Cats.displayName(c.getContentResolver(), u));
        if (Cats.extOf(name).isEmpty()) {
            String type = null;
            try {
                type = c.getContentResolver().getType(u);
            } catch (Exception ignored) {
            }
            String x = type == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
            if (x != null && !x.isEmpty()) name = name + "." + x;
        }
        File out = new File(dir, name);
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("no stream");
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
        return out;
    }

    private static String clean(String name) {
        if (name == null) return "file";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.length() > 120) {
            String x = Cats.extOf(s);
            s = s.substring(0, 100) + (x.isEmpty() || x.length() > 10 ? "" : "." + x);
        }
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "file";
        return s;
    }

    /** Deletes copies older than a few hours; called whenever a link is opened. */
    static void cleanIncoming(Context c) {
        File root = new File(c.getCacheDir(), "incoming");
        File[] kids = root.listFiles();
        if (kids == null) return;
        long now = System.currentTimeMillis();
        for (File k : kids) if (now - k.lastModified() > KEEP_MS) deleteTree(k);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static boolean isDirMime(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("resource/folder") || t.equals("vnd.android.document/directory")
                || t.equals("inode/directory") || t.equals("application/x-directory");
    }
}
