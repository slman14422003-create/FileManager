package com.fileman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
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
        switch (Cats.viewKind(ext)) {
            case Cats.V_APK:
                Perms.installApk(a, f);
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

    /** Hands a file to another app (with an app chooser when asked). */
    public static void external(Activity a, File f, boolean chooser) {
        try {
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, Cats.mimeOf(f));
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(chooser ? Intent.createChooser(i, f.getName()) : i);
        } catch (Exception ex) {
            Toast.makeText(a, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    public static void share(Activity a, File f) {
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
}
