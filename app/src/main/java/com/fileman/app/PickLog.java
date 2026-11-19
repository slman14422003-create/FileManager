package com.fileman.app;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records what other apps ask of the picker and of the documents provider (who, which call, what came out),
 * in the app's own external files folder (Android/data/<package>/files) (cache folder when that is not writable). It is the way to see
 * why another app reports a failed save when the file manager believes it handed the file over.
 */
final class PickLog {
    private static final String NAME = "FileManager-picker-log.txt";
    private static final long MAX = 120 * 1024;

    private PickLog() {
    }

    static synchronized void add(Context c, String what, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            String line = what == null ? "" : what.replaceAll("[\\p{Cntrl}]", "_");
            if (line.length() > 600) line = line.substring(0, 600);
            pw.println(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + line);
            if (e != null) e.printStackTrace(pw);
            pw.flush();
            File dir = android.os.Build.VERSION.SDK_INT >= 30 ? c.getExternalFilesDir(null) : null;   // app-private: other apps cannot read the log
            File f = new File(dir, NAME);
            if (dir == null || !(dir.isDirectory() && dir.canWrite())) f = new File(c.getCacheDir(), NAME);
            if (f.exists() && f.length() > MAX) //noinspection ResultOfMethodCallIgnored
                f.delete();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(sw.toString());
            }
        } catch (Throwable ignored) {
        }
    }
}
