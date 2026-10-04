package com.fileman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/** Opens a file the way the file manager does: APKs install, text files edit, the rest goes to other apps. */
public final class Opener {
    private Opener() {
    }

    /** Opens a regular file. */
    public static void open(Activity a, File f) {
        String ext = Cats.extOf(f.getName());
        int t = Cats.typeOfExt(ext);
        if (t == Cats.T_APK) {
            Perms.installApk(a, f);
        } else if (t == Cats.T_TXT) {
            Intent i = new Intent(a, FileEditActivity.class);
            i.putExtra("path", f.getAbsolutePath());
            a.startActivity(i);
        } else {
            external(a, f, ext.equals("zip") || ext.equals("jar"));
        }
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
}
