package com.fileman.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

final class SaveTarget {
    static final String ASK = "ask", PUBLIC = "downloads", FOLDER = "folder";

    private SaveTarget() {
    }

    static String mode(Context c) {
        String m = Store.strPref(c, "save_mode", ASK);
        return PUBLIC.equals(m) || FOLDER.equals(m) ? m : ASK;
    }

    static void setMode(Context c, String m) {
        Store.setStrPref(c, "save_mode", m);
    }

    static Uri tree(Context c) {
        String s = Store.strPref(c, "save_tree", null);
        if (s == null || s.isEmpty()) return null;
        try {
            Uri u = Uri.parse(s);
            for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(u) && p.isWritePermission()) return u;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static void setTree(Context c, Uri u) {
        Store.setStrPref(c, "save_tree", u == null ? "" : u.toString());
    }

    static String treeLabel(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int i = id.indexOf(':');
            String vol = i < 0 ? id : id.substring(0, i);
            String rest = i < 0 ? "" : id.substring(i + 1);
            String root = "primary".equals(vol) ? "" : vol + ":";
            return rest.isEmpty() ? (root.isEmpty() ? "/" : root) : root + "/" + rest;
        } catch (Exception e) {
            return tree.toString();
        }
    }

    static String label(Context c) {
        String m = mode(c);
        if (FOLDER.equals(m)) {
            Uri t = tree(c);
            if (t != null) return treeLabel(t);
        }
        if (PUBLIC.equals(m)) return c.getString(R.string.dl_loc_public);
        return c.getString(R.string.dl_loc_ask);
    }

    static Intent systemPicker(Context c) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            List<ResolveInfo> all = c.getPackageManager().queryIntentActivities(i, 0);
            String best = null;
            for (ResolveInfo ri : all) {
                String p = ri.activityInfo.packageName;
                if (p.equals(c.getPackageName())) continue;
                if (p.contains("documentsui")) {
                    best = p;
                    break;
                }
                if (best == null) best = p;
            }
            if (best != null) i.setPackage(best);
        } catch (Throwable ignored) {
        }
        return i;
    }

    static boolean keep(Context c, Uri tree) {
        try {
            c.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            setTree(c, tree);
            setMode(c, FOLDER);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String relative(File dir) {
        if (dir == null) return null;
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String p = dir.getAbsolutePath();
        if (!p.startsWith(root)) return null;
        String rel = p.substring(root.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        return rel;
    }

    static boolean publicSlot(File dir) {
        String rel = relative(dir);
        if (rel == null) return false;
        return rel.equals("Download") || rel.startsWith("Download/") || rel.equals("Documents") || rel.startsWith("Documents/");
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String m = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }

    static String copyToTree(Context c, InputStream in, String name, Uri tree) throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri out = DocumentsContract.createDocument(cr, parent, mimeOf(name), name);
        if (out == null) return null;
        try (OutputStream os = cr.openOutputStream(out)) {
            if (os == null) {
                DocumentsContract.deleteDocument(cr, out);
                return null;
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            try {
                DocumentsContract.deleteDocument(cr, out);
            } catch (Exception ignored) {
            }
            throw e;
        }
        return name;
    }
}
