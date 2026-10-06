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
 * Word / Excel / PowerPoint (docx, xlsx, pptx), OpenDocument, CSV, RTF, HTML, SVG and text files.
 * APKs install; everything else is handed to another app.
 */
public final class Opener {
    private Opener() {
    }

    /** Opens a regular file with the right viewer. */
    public static void open(Activity a, File f) {
        String ext = Cats.extOf(f.getName());
        if (isArchiveExt(ext)) {
            openArchive(a, f);
            return;
        }
        switch (Cats.viewKind(ext)) {
            case Cats.V_APK:
                a.startActivity(new Intent(a, InstallActivity.class).setData(Uri.fromFile(f)));
                break;
            case Cats.V_TEXT:
                start(a, FileEditActivity.class, f);
                break;
            case Cats.V_IMAGE:
                start(a, ImageViewActivity.class, f);
                break;
            case Cats.V_MEDIA:
                start(a, MediaActivity.class, f);
                break;
            case Cats.V_PDF:
                start(a, PdfViewActivity.class, f);
                break;
            case Cats.V_DOC:
                start(a, DocViewActivity.class, f);
                break;
            case Cats.V_LEGACY:
                legacy(a, f);
                break;
            default:
                external(a, f, ext.equals("zip") || ext.equals("jar"));
                break;
        }
    }

    /** Extensions the built-in archive browser can read. */
    public static boolean isArchiveExt(String ext) {
        return ext.equals("zip") || ext.equals("jar") || ext.equals("cbz") || ext.equals("war");
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
        new Dlg(a).setTitle(f.getName()).setItems(items, (d, which) -> {
            if (which == 0) external(a, f, true);
            else share(a, f);
        }).show();
    }

    /** The "more" menu with viewer-specific entries listed first. */
    public static void moreMenu(final Activity a, final File f, final String[] extra, final Runnable[] actions) {
        final String[] items = new String[extra.length + 2];
        System.arraycopy(extra, 0, items, 0, extra.length);
        items[extra.length] = a.getString(R.string.fm_open_with);
        items[extra.length + 1] = a.getString(R.string.share);
        new Dlg(a).setTitle(f.getName()).setItems(items, (d, which) -> {
            if (which < extra.length) actions[which].run();
            else if (which == extra.length) external(a, f, true);
            else share(a, f);
        }).show();
    }
}
