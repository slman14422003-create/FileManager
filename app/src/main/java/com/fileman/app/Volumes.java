package com.fileman.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class Volumes {
    private Volumes() {
    }

    static final class Vol {
        File root;
        String label;
        boolean usb;
        boolean mounted = true;
        boolean readOnly;
    }

    private static final Pattern UUID_DIR = Pattern.compile("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f-]{8,}");

    /** SD cards and USB drives currently attached, whether or not the system reports them as removable. */
    static List<Vol> removable(Context c) {
        List<Vol> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (v.isPrimary() || v.isEmulated()) continue;
                    File dir = dirOf(v);
                    if (dir == null) continue;
                    Vol x = new Vol();
                    x.root = dir;
                    String state = v.getState();
                    x.mounted = Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
                    x.readOnly = Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
                    String d = null;
                    try {
                        d = v.getDescription(c);
                    } catch (Throwable ignored) {
                    }
                    x.usb = d != null && d.toLowerCase(Locale.ROOT).contains("usb");
                    x.label = d;
                    if (seen.add(key(dir))) out.add(x);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File[] dirs = c.getExternalFilesDirs(null);
            if (dirs != null) {
                for (int i = 1; i < dirs.length; i++) {
                    if (dirs[i] == null) continue;
                    String p = dirs[i].getAbsolutePath();
                    int k = p.indexOf("/Android/data");
                    if (k <= 0) continue;
                    File r = new File(p.substring(0, k));
                    if (r.exists() && seen.add(key(r))) {
                        Vol x = new Vol();
                        x.root = r;
                        out.add(x);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        File[] top = new File("/storage").listFiles();
        if (top != null) {
            for (File f : top) {
                String n = f.getName();
                if (n.equals("emulated") || n.equals("self") || !UUID_DIR.matcher(n).matches()) continue;
                if (!f.isDirectory() || !f.canRead() || !seen.add(key(f))) continue;
                Vol x = new Vol();
                x.root = f;
                out.add(x);
            }
        }
        int sd = 0, usb = 0;
        for (Vol v : out) {
            if (v.label == null || v.label.isEmpty()) v.label = null;
            if (v.usb) {
                usb++;
                v.label = c.getString(usb > 1 ? R.string.fm_usb_n : R.string.fm_usb, usb);
            } else if (v.label == null) {
                sd++;
                v.label = c.getString(R.string.fm_sdcard) + (sd > 1 ? " " + sd : "");
            }
        }
        return out;
    }

    static List<File> roots(Context c) {
        List<File> out = new ArrayList<>();
        for (Vol v : removable(c)) if (v.mounted) out.add(v.root);
        return out;
    }

    static String labelOf(Context c, File root) {
        for (Vol v : removable(c)) if (v.root.equals(root)) return v.label;
        return c.getString(R.string.fm_sdcard);
    }

    static boolean isMounted(Context c, File root) {
        return root.exists() && root.canRead() && root.listFiles() != null;
    }

    private static String key(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }

    private static File dirOf(StorageVolume v) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
            return new File((String) StorageVolume.class.getMethod("getPath").invoke(v));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Calls {@code changed} on the main thread whenever a volume is attached, detached or ejected. */
    static BroadcastReceiver watch(final Context c, final Runnable changed) {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addAction(Intent.ACTION_MEDIA_NOFS);
        f.addDataScheme("file");
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                changed.run();
            }
        };
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(r, f);
        return r;
    }
}
