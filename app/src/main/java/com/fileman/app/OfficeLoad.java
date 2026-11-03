package com.fileman.app;

import android.content.Context;

import java.io.File;

/**
 * Turns an Office document into the HTML page of the viewer, opening password protected files on the way: new
 * (docx / xlsx / pptx) and old (doc / xls / ppt) formats alike. Blocking; call it off the main thread.
 */
final class OfficeLoad {
    private OfficeLoad() {
    }

    /** Password Office itself uses for files that are only "read-only recommended" or have a locked structure. */
    private static final String DEFAULT_PASSWORD = "VelvetSweatshop";

    static final class Result {
        String html;
        /** 0 = loaded, 1 = ask for a password, 2 = the password given was wrong. */
        int lock;
        int err;
    }

    static Result load(Context c, File file, String ext, String password) {
        Result r = new Result();
        File plain = null;
        try {
            File src = file;
            boolean modern = OfficeCrypto.isEncrypted(file);
            boolean legacy = !modern && OfficeCrypto.legacyLocked(file, ext);
            boolean odf = !modern && !legacy && isOdf(ext) && OdfCrypto.isEncrypted(file);
            if (modern || legacy || odf) {
                File dir = new File(c.getCacheDir(), "lock");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                plain = File.createTempFile("doc", "." + ext, dir);
                boolean ok = false;
                try {
                    decrypt(file, ext, password == null ? DEFAULT_PASSWORD : password, plain, modern, odf);
                    ok = true;
                } catch (OfficeCrypto.BadPassword e) {
                    if (password != null) throw e;
                }
                if (!ok) {
                    r.lock = 1;
                    return r;
                }
                src = plain;
            }
            r.html = DocHtml.convert(src, ext);
        } catch (OfficeCrypto.BadPassword e) {
            r.lock = 2;
        } catch (OfficeCrypto.Unsupported e) {
            r.err = R.string.lock_unsupported;
        } catch (OutOfMemoryError e) {
            r.err = R.string.v_too_large;
        } catch (Throwable e) {
            r.err = R.string.v_doc_failed;
        } finally {
            if (plain != null) //noinspection ResultOfMethodCallIgnored
                plain.delete();
        }
        return r;
    }

    private static boolean isOdf(String ext) {
        return ext.equals("odt") || ext.equals("ods") || ext.equals("odp");
    }

    private static void decrypt(File file, String ext, String pw, File out, boolean modern, boolean odf)
            throws java.io.IOException {
        if (modern) OfficeCrypto.decrypt(file, pw, out);
        else if (odf) OdfCrypto.decrypt(file, pw, out);
        else LegacyCrypto.decrypt(file, ext, pw, out);
    }
}
