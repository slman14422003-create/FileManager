package com.fileman.app;

import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Crash-safe writing: a temp file next to the target, then an atomic rename, with a safe fallback. */
final class SafeWrite {
    private SafeWrite() {
    }

    interface Body {
        void write(OutputStream out) throws IOException;
    }

    /** Raised for a full disk so callers can show the right message. */
    static final class NoSpace extends IOException {
        NoSpace() {
            super("ENOSPC");
        }
    }

    static void atomic(File target, long estimate, Body body) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        if (dir == null || !dir.isDirectory()) throw new FileNotFoundException("ENOENT");
        if (estimate > 0 && dir.getUsableSpace() < estimate + (1L << 20)) throw new NoSpace();
        File tmp = null;
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        for (int i = 0; i < 20 && tmp == null; i++) {
            File cand = new File(dir, "." + target.getName() + "." + Long.toHexString(rnd.nextLong()) + ".tmp");
            if (cand.createNewFile()) tmp = cand;   // exclusive: never reuse or follow a name somebody else planted
        }
        if (tmp == null) throw new IOException("cannot create temp file");
        boolean ok = false;
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 64 * 1024)) {
                body.write(bos);
                bos.flush();
                try {
                    fos.getFD().sync();
                } catch (IOException ignored) {
                }
            }
            if (!rename(tmp, target)) {
                // fallback: the temp file is complete, copy it over the target (not atomic, but nothing is lost)
                try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(target, false)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }
            }
            ok = true;
        } finally {
            if (!ok || tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static boolean rename(File from, File to) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    return true;
                } catch (Throwable t) {
                    // not supported on this file system: try the plain rename below
                }
            }
            return from.renameTo(to);
        } catch (Throwable t) {
            return false;
        }
    }

    static void copyAtomic(final File src, File dst) throws IOException {
        atomic(dst, src.length(), out -> {
            try (InputStream in = new FileInputStream(src)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        });
    }

    /** Streams bytes [from, to) of a file into the output. */
    static void copyRange(File src, long from, long to, OutputStream out) throws IOException {
        if (to <= from) return;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(src, "r")) {
            raf.seek(from);
            byte[] buf = new byte[64 * 1024];
            long left = to - from;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    /** Maps a failure to a user message: locked file, full storage, revoked permission, file gone. */
    static int describe(Throwable t) {
        if (t instanceof NoSpace) return R.string.err_disk_full;
        if (t instanceof SecurityException) return R.string.err_permission;
        String m = t == null || t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        if (m.contains("enospc") || m.contains("no space") || m.contains("quota")) return R.string.err_disk_full;
        if (m.contains("ebusy") || m.contains("etxtbsy") || m.contains("text file busy") || m.contains("in use")
                || m.contains("being used")) return R.string.err_locked;
        if (m.contains("eacces") || m.contains("eperm") || m.contains("permission") || m.contains("read-only")
                || m.contains("erofs")) return R.string.err_permission;
        if (m.contains("enoent") || m.contains("no such file")) return R.string.err_gone;
        return R.string.fm_save_failed;
    }
}
