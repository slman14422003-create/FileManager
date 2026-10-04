package com.fileman.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Package installer engine (beta): reads an APK or a split bundle (APKS / XAPK / APKM), reports what
 * it contains and how it compares with the installed copy, and installs it through the system
 * {@link PackageInstaller} session API. No network and no shell: the system still confirms the
 * install, except for updates of apps this app installed itself (Android 12+).
 */
final class PkgInstaller {
    private PkgInstaller() {
    }

    /** Broadcast action the system calls back with the install result. */
    static String action(Context c) {
        return c.getPackageName() + ".INSTALL_RESULT";
    }

    static boolean isPackageExt(String ext) {
        return ext.equals("apk") || ext.equals("apks") || ext.equals("xapk") || ext.equals("apkm");
    }

    static final class Perm {
        final String name;
        final boolean sensitive;

        Perm(String name, boolean sensitive) {
            this.name = name;
            this.sensitive = sensitive;
        }
    }

    /** What the package file contains, plus how it relates to the copy installed now (if any). */
    static final class Info {
        File source;
        boolean bundle;
        List<String> entries = new ArrayList<>();   // APK entries to install (bundles only)
        boolean hasObb;
        long size;

        String label = "";
        String pkg = "";
        String versionName = "";
        long versionCode;
        int minSdk, targetSdk;
        Drawable icon;
        Set<String> certs = new LinkedHashSet<>();
        final List<Perm> perms = new ArrayList<>();
        int sensitiveCount;

        boolean installed;
        String installedName = "";
        long installedCode;
        boolean sameCert;

        String certShort() {
            if (certs.isEmpty()) return "—";
            String h = certs.iterator().next();
            return h.length() > 32 ? h.substring(0, 32) : h;
        }
    }

    // ------------------------------------------------------------------ inspecting

    static Info inspect(Context c, File f) throws IOException {
        Info in = new Info();
        in.source = f;
        in.size = f.length();
        in.bundle = !Cats.extOf(f.getName()).equals("apk");
        File apk = f;
        if (in.bundle) {
            scanBundle(f, in);
            if (in.entries.isEmpty()) throw new IOException("no apk inside");
            File dir = new File(c.getCacheDir(), "inst");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            apk = new File(dir, "base.apk");
            try (ZipFile z = new ZipFile(f)) {
                ZipEntry ze = z.getEntry(pickBase(in.entries));
                if (ze == null) throw new IOException("base missing");
                copy(z.getInputStream(ze), new FileOutputStream(apk), null);
            }
        }
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), sigFlags() | PackageManager.GET_PERMISSIONS);
        if (pi == null || pi.applicationInfo == null) throw new IOException("not an apk");
        pi.applicationInfo.sourceDir = apk.getAbsolutePath();
        pi.applicationInfo.publicSourceDir = apk.getAbsolutePath();

        in.pkg = pi.packageName;
        in.versionName = pi.versionName == null ? "" : pi.versionName;
        in.versionCode = codeOf(pi);
        in.minSdk = pi.applicationInfo.minSdkVersion;
        in.targetSdk = pi.applicationInfo.targetSdkVersion;
        try {
            CharSequence l = pi.applicationInfo.loadLabel(pm);
            in.label = l == null ? in.pkg : l.toString();
            in.icon = pi.applicationInfo.loadIcon(pm);
        } catch (Throwable t) {
            in.label = in.pkg;
        }
        in.certs = certs(pi);

        if (pi.requestedPermissions != null) {
            List<Perm> sensitive = new ArrayList<>();
            List<Perm> normal = new ArrayList<>();
            for (String p : pi.requestedPermissions) {
                boolean danger = false;
                try {
                    PermissionInfo info = pm.getPermissionInfo(p, 0);
                    danger = (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                            == PermissionInfo.PROTECTION_DANGEROUS;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                (danger ? sensitive : normal).add(new Perm(shortPerm(p), danger));
            }
            in.sensitiveCount = sensitive.size();
            in.perms.addAll(sensitive);
            in.perms.addAll(normal);
        }

        try {
            PackageInfo old = pm.getPackageInfo(in.pkg, sigFlags());
            in.installed = true;
            in.installedName = old.versionName == null ? "" : old.versionName;
            in.installedCode = codeOf(old);
            Set<String> oc = certs(old);
            in.sameCert = !oc.isEmpty() && !in.certs.isEmpty() && !Collections.disjoint(oc, in.certs);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return in;
    }

    private static int sigFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    static long codeOf(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static String shortPerm(String p) {
        int i = p.lastIndexOf('.');
        return i >= 0 && i < p.length() - 1 ? p.substring(i + 1) : p;
    }

    @SuppressWarnings("deprecation")
    static Set<String> certs(PackageInfo pi) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = pi.signingInfo;
                if (si != null) {
                    sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
                }
            } else {
                sigs = pi.signatures;
            }
            if (sigs != null) for (Signature s : sigs) out.add(sha256(s.toByteArray()));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
        return sb.toString();
    }

    // ------------------------------------------------------------------ bundles

    /**
     * Decides which APKs of a bundle go into one install: bundletool's splits/ folder, or the APKs at
     * the top level of an XAPK / APKM, or a single standalone / universal APK as the last resort.
     */
    private static void scanBundle(File f, Info in) throws IOException {
        List<String> splits = new ArrayList<>();
        List<String> root = new ArrayList<>();
        List<String> standalone = new ArrayList<>();
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            int n = 0;
            while (en.hasMoreElements() && n++ < 3000) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.contains("..")) continue;   // never trust relative parts
                String low = name.toLowerCase(Locale.ROOT);
                if (low.endsWith(".obb")) in.hasObb = true;
                if (!low.endsWith(".apk")) continue;
                if (low.startsWith("splits/")) splits.add(name);
                else if (low.startsWith("standalones/")) standalone.add(name);
                else if (!low.contains("/")) root.add(name);
            }
        }
        if (!splits.isEmpty()) in.entries.addAll(splits);
        else if (!root.isEmpty()) in.entries.addAll(root);
        else if (!standalone.isEmpty()) {
            String pick = standalone.get(0);
            for (String s : standalone) {
                if (s.toLowerCase(Locale.ROOT).contains("universal")) pick = s;
            }
            in.entries.add(pick);
        }
    }

    private static String pickBase(List<String> names) {
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.endsWith("/base.apk") || l.equals("base.apk") || l.contains("base-master")) return n;
        }
        String best = names.get(0);
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (!l.contains("split") && !l.contains("config") && n.length() < best.length()) best = n;
        }
        return best;
    }

    // ------------------------------------------------------------------ installing

    interface Progress {
        void on(long done, long total);
    }

    /** Streams the package into a new install session and commits it. Call off the UI thread. */
    static void install(Context c, Info in, Progress pr) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (in.pkg != null && !in.pkg.isEmpty()) p.setAppPackageName(in.pkg);
        if (Build.VERSION.SDK_INT >= 31) {
            // updates of apps this app installed need no tap; everything else still asks
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        final int id = installer.createSession(p);
        PackageInstaller.Session s = installer.openSession(id);
        try {
            long total = 0;
            if (!in.bundle) {
                total = in.source.length();
            } else {
                try (ZipFile z = new ZipFile(in.source)) {
                    for (String name : in.entries) {
                        ZipEntry ze = z.getEntry(name);
                        if (ze != null && ze.getSize() > 0) total += ze.getSize();
                    }
                }
            }
            final long[] done = {0};
            final long fTotal = Math.max(1, total);
            Progress step = (d, t) -> {
                done[0] = d;
                if (pr != null) pr.on(d, fTotal);
            };
            if (!in.bundle) {
                writeEntry(s, "base.apk", new FileInputStream(in.source), in.source.length(), done, step);
            } else {
                try (ZipFile z = new ZipFile(in.source)) {
                    for (String name : in.entries) {
                        ZipEntry ze = z.getEntry(name);
                        if (ze == null) continue;
                        String safe = name.replace('/', '_').replaceAll("[^A-Za-z0-9._-]", "_");
                        writeEntry(s, safe, z.getInputStream(ze), ze.getSize(), done, step);
                    }
                }
            }
            Intent cb = new Intent(action(c)).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(c, id, cb, flags);
            s.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                s.abandon();
            } catch (Throwable ignored) {
            }
            throw e;
        } finally {
            s.close();
        }
    }

    private static void writeEntry(PackageInstaller.Session s, String name, InputStream is, long length,
                                   long[] done, Progress step) throws IOException {
        try (InputStream in = is; OutputStream out = s.openWrite(name, 0, length > 0 ? length : -1)) {
            byte[] buf = new byte[1 << 16];
            int r;
            long last = 0;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                done[0] += r;
                if (done[0] - last > (256 << 10)) {
                    last = done[0];
                    step.on(done[0], 0);
                }
            }
            s.fsync(out);
            step.on(done[0], 0);
        }
    }

    static void copy(InputStream in, OutputStream out, Progress pr) throws IOException {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[1 << 16];
            int r;
            long n = 0;
            while ((r = i.read(buf)) > 0) {
                o.write(buf, 0, r);
                n += r;
                if (pr != null) pr.on(n, 0);
            }
        }
    }
}
