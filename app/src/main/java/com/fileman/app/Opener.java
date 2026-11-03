package com.fileman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Opens a file inside the app whenever there is a built-in viewer for it: images, video, audio, PDF,
 * Word / Excel / PowerPoint (docx, xlsx, pptx), OpenDocument, CSV, RTF, HTML, SVG, contact files (vcf) and text files.
 * APKs install; everything else is handed to another app.
 */
public final class Opener {
    private Opener() {
    }

    /** Opens a regular file with the right viewer. */
    public static void open(Activity a, File f) {
        open(a, f, true);
    }

    /** {@code allowSibling=false} when another app already chose this app (no bouncing back to Gallery / Music). */
    public static void open(Activity a, File f, boolean allowSibling) {
        String ext = Cats.extOf(f.getName());
        if (isArchiveExt(ext)) {
            openArchive(a, f);
            return;
        }
        int kind = Cats.viewKind(ext);
        if (kind == Cats.V_NONE && Cats.isTextName(f.getName())) kind = Cats.V_TEXT;
        if (kind == Cats.V_NONE && !ext.equals("zip") && !ext.equals("jar")) kind = sniffKind(f);
        switch (kind) {
            case Cats.V_APK:
                a.startActivity(new Intent(a, InstallActivity.class).setData(Uri.fromFile(f)));
                break;
            case Cats.V_TEXT:
                start(a, FileEditActivity.class, f);
                break;
            case Cats.V_IMAGE:
                if (allowSibling && viaSibling(a, f, Semo.ROLE_GALLERY)) break;
                start(a, ImageViewActivity.class, f);
                break;
            case Cats.V_MEDIA:
                String mt = Cats.mimeOf(f);
                if (allowSibling && viaSibling(a, f, mt != null && mt.startsWith("audio/") ? Semo.ROLE_MUSIC : Semo.ROLE_GALLERY)) break;
                start(a, MediaActivity.class, f);
                break;
            case Cats.V_PDF:
                start(a, PdfViewActivity.class, f);
                break;
            case Cats.V_DOC:
                start(a, DocViewActivity.class, f);
                break;
            case Cats.V_CONTACT:
                start(a, ContactsActivity.class, f);
                break;
            case Cats.V_LEGACY:
                legacy(a, f);
                break;
            default:
                external(a, f, ext.equals("zip") || ext.equals("jar"));
                break;
        }
    }

    /**
     * Looks at the first bytes of a file whose name tells nothing (no extension, or an unknown one) and picks a
     * built-in viewer when the content is clearly a PDF, an image, audio / video, an archive or plain text.
     */
    static int sniffKind(File f) {
        byte[] b = new byte[4096];
        int n;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            n = in.read(b);
        } catch (Exception e) {
            return Cats.V_NONE;
        }
        if (n < 4) return Cats.V_NONE;
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return Cats.V_PDF;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Cats.V_IMAGE;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Cats.V_IMAGE;
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return Cats.V_IMAGE;
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            if (b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Cats.V_IMAGE;
            if (b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return Cats.V_MEDIA;
            return Cats.V_NONE;
        }
        if (n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return Cats.V_MEDIA;
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return Cats.V_MEDIA;
        if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return Cats.V_MEDIA;
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return Cats.V_MEDIA;
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) return Cats.V_MEDIA;
        if (b[0] == 'P' && b[1] == 'K' && (b[2] == 3 || b[2] == 5)) return Cats.V_NONE;   // archives go to the archive browser
        return looksLikeText(b, n) ? Cats.V_TEXT : Cats.V_NONE;
    }

    /** True when the bytes are text: no NUL, almost no control characters (a UTF-8 or UTF-16 byte order mark is fine). */
    private static boolean looksLikeText(byte[] b, int n) {
        int start = 0;
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) start = 3;
        boolean utf16 = n >= 2 && (((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) || ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF));
        if (utf16) return true;
        int bad = 0;
        for (int i = start; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) return false;
            if (c < 9 || (c > 13 && c < 32 && c != 27)) bad++;
        }
        return bad * 100 <= (n - start);
    }

    /** Extensions the built-in archive browser can read. */
    public static boolean isArchiveExt(String ext) {
        return Arc.browsable("x." + ext);
    }

    /** Opens the archive browser for a zip-like file (whatever its name is). */
    public static void openArchive(Activity a, File f) {
        start(a, ZipBrowseActivity.class, f);
    }

    /** True when the file starts with the ZIP signature (downloads often lose or change the extension). */
    public static boolean looksLikeZip(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return b[0] == 'P' && b[1] == 'K' && (b[2] == 3 || b[2] == 5) && (b[3] == 4 || b[3] == 6);
        } catch (Exception e) {
            return false;
        }
    }

    /** Semo ecosystem: photos and videos open in Gallery, songs in Music, when those apps are installed. */
    private static boolean viaSibling(Activity a, File f, String role) {
        if (Store.intPref(a, "semo_link", 1) == 0) return false;
        if (!Safe.mayExpose(a, f)) return false;
        try {
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            return Semo.view(a, u, Cats.mimeOf(f), role);
        } catch (Exception e) {
            return false;
        }
    }

    private static void start(Activity a, Class<?> cls, File f) {
        Intent i = new Intent(a, cls);
        i.putExtra("path", f.getAbsolutePath());
        a.startActivity(i);
    }

    /** .doc / .xls / .ppt (binary Office formats) cannot be read without a large library. */
    private static void legacy(final Activity a, final File f) {
        new Dlg(a).setTitle(R.string.v_legacy_title).setMessage(R.string.v_legacy_msg)
                .setPositiveButton(R.string.fm_open_with, (d, w) -> external(a, f, true))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Hands a file to another app (with an app chooser when asked). This app never offers itself: when it
     * is the system default for a file type it would otherwise receive its own request again.
     */
    public static void external(Activity a, File f, boolean chooser) {
        if (!Safe.mayExpose(a, f)) {
            Toast.makeText(a, R.string.fm_private_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, Cats.mimeOf(f));
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            PackageManager pm = a.getPackageManager();
            boolean other = false;
            for (ResolveInfo ri : pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (ri.activityInfo != null && !a.getPackageName().equals(ri.activityInfo.packageName)) {
                    other = true;
                    break;
                }
            }
            if (!other) {
                Toast.makeText(a, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!chooser) {
                ResolveInfo def = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
                if (def != null && def.activityInfo != null
                        && !a.getPackageName().equals(def.activityInfo.packageName)
                        && !"android".equals(def.activityInfo.packageName)) {
                    a.startActivity(i);   // the user's own default for this type
                    return;
                }
            }
            Intent ch = Intent.createChooser(i, f.getName());
            ch.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{
                    new ComponentName(a, OpenActivity.class),
                    new ComponentName(a, InstallActivity.class)});
            a.startActivity(ch);
        } catch (Exception ex) {
            Toast.makeText(a, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    public static void share(Activity a, File f) {
        if (!Safe.mayExpose(a, f)) {
            Toast.makeText(a, R.string.fm_private_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(Cats.mimeOf(f));
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, a.getString(R.string.share)));
        } catch (Exception ex) {
            Toast.makeText(a, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** The "more" menu shown by every viewer. */
    public static void moreMenu(final Activity a, final File f) {
        String[] items = {a.getString(R.string.fm_open_with), a.getString(R.string.share)};
        new Dlg(a).setTitle(f.getName()).setItems(items, new int[]{R.drawable.ic_open, R.drawable.ic_share}, (d, which) -> {
            if (which == 0) external(a, f, true);
            else share(a, f);
        }).show();
    }

    /** The "more" menu with viewer-specific entries listed first. */
    public static void moreMenu(final Activity a, final File f, final String[] extra, final Runnable[] actions) {
        moreMenu(a, f, extra, null, actions);
    }

    public static void moreMenu(final Activity a, final File f, final String[] extra, final int[] extraIcons,
                                final Runnable[] actions) {
        final String[] items = new String[extra.length + 2];
        System.arraycopy(extra, 0, items, 0, extra.length);
        items[extra.length] = a.getString(R.string.fm_open_with);
        items[extra.length + 1] = a.getString(R.string.share);
        final int[] icons = new int[items.length];
        if (extraIcons != null) System.arraycopy(extraIcons, 0, icons, 0, Math.min(extraIcons.length, extra.length));
        icons[extra.length] = R.drawable.ic_open;
        icons[extra.length + 1] = R.drawable.ic_share;
        new Dlg(a).setTitle(f.getName()).setItems(items, icons, (d, which) -> {
            if (which < extra.length) actions[which].run();
            else if (which == extra.length) external(a, f, true);
            else share(a, f);
        }).show();
    }
}
