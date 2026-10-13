package com.fileman.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Keeps the stack trace of every crash in Download/FileManager-crash.txt (cache folder when that is not writable). */
final class CrashLog {
    private static final String NAME = "FileManager-crash.txt";
    private static final long MAX = 200 * 1024;

    private CrashLog() {
    }

    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            write(app, t, e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static void write(Context c, Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            pw.println("thread: " + (t == null ? "?" : t.getName()));
            pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + " api " + Build.VERSION.SDK_INT);
            pw.println("version: " + BuildConfig.VERSION_NAME);
            e.printStackTrace(pw);
            pw.println("----");
            pw.flush();
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, NAME);
            if (dir == null || !(dir.isDirectory() && dir.canWrite())) f = new File(c.getCacheDir(), NAME);
            boolean append = f.exists() && f.length() < MAX;
            try (FileWriter w = new FileWriter(f, append)) {
                w.write(sw.toString());
            }
        } catch (Throwable ignored) {
        }
    }
}
