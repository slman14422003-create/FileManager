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
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Package installer engine (beta 2): reads an APK or a split bundle (APKS / XAPK / APKM), reports what
 * it contains and how it compares with the installed copy, checks the install before it starts
 * (Android version, CPU architecture, free space, downgrade, signature), picks only the splits that suit
 * this device, installs through the system {@link PackageInstaller} session API and copies OBB data.
 * No network and no shell: the system still confirms the install, except for updates of apps this app
 * installed itself (Android 12+).
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
        List<String> perms = new ArrayList<>();       // permissions the package asks for
        List<String> abis = new ArrayList<>();        // native-code architectures it ships (empty = none)
        List<String> allEntries = new ArrayList<>();  // every APK of a bundle
        List<String> obbEntries = new ArrayList<>();  // OBB data files of a bundle
        boolean smart;                                // entries is a device-specific subset of allEntries
        long installBytes;                            // bytes that will be written to the install session
        File original;                                // the file the user picked (null for shared streams)
        String fileSha = "";

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

    /** A single APK has AndroidManifest.xml at the top of the archive; bundles hold other APKs instead. */
    static boolean looksLikeApk(File f) {
        try (ZipReader z = ZipReader.open(f)) {
            return z.find("AndroidManifest.xml") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Copies a package handed over by another app (content:// or file://) into the cache. */
    static File fromUri(Context c, android.net.Uri u) throws IOException {
        if ("file".equals(u.getScheme()) && u.getPath() != null) {
            File f = new File(u.getPath());
            if (f.isFile() && f.canRead()) return f;
        }
        File dir = new File(c.getCacheDir(), "inst");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "incoming.pkg");
        long expected = sizeOf(c, u);
        long free = freeBytes();
        if (expected > 0 && free >= 0 && free < expected * 2 + (32L << 20)) throw new NoSpace();
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("no stream");
        copy(in, new FileOutputStream(out), null);
        return out;
    }

    /** Size reported by the provider of a shared package (-1 when unknown). */
    static long sizeOf(Context c, android.net.Uri u) {
        try (android.database.Cursor cur = c.getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Best-effort display name of a shared package (for the title when the file name is generic). */
    static String nameOf(Context c, android.net.Uri u) {
        try (android.database.Cursor cur = c.getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "" : last;
    }

    static Info inspect(Context c, File f) throws IOException {
        Info in = new Info();
        in.source = f;
        in.size = f.length();
        in.bundle = !looksLikeApk(f);
        File apk = f;
        if (in.bundle) {
            scanBundle(f, in);
            if (in.allEntries.isEmpty()) throw new IOException("no apk inside");
            File dir = new File(c.getCacheDir(), "inst");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            apk = new File(dir, "base.apk");
            try (ZipReader z = ZipReader.open(f)) {
                ZipReader.Entry ze = z.find(pickBase(in.entries));
                if (ze == null) throw new IOException("base missing");
                copy(z.open(ze), new FileOutputStream(apk), null);
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
            for (String p : pi.requestedPermissions) if (p != null) in.perms.add(p);
        }
        in.abis = abisOf(apk, in);
        applySelection(c, in, Store.instBool(c, "smart", true));

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


    // ------------------------------------------------------------------ device-aware package selection

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static final Set<String> ABI_TOKENS = new HashSet<>(java.util.Arrays.asList(
            "arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64"));
    private static final String[] DPI_NAMES = {"ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] DPI_VALUES = {120, 160, 213, 240, 320, 480, 640};

    /** The part of a split's file name that says what it is for ("arm64_v8a", "xxhdpi", "en", "master"...). */
    private static String configToken(String entry) {
        String n = entry.substring(entry.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (n.endsWith(".apk")) n = n.substring(0, n.length() - 4);
        if (n.startsWith("split_config.")) return norm(n.substring("split_config.".length()));
        if (n.startsWith("config.")) return norm(n.substring("config.".length()));
        int i = n.lastIndexOf(".config.");
        if (i >= 0) return norm(n.substring(i + ".config.".length()));
        i = n.lastIndexOf('-');                       // bundletool: base-arm64_v8a, base-xxhdpi, base-en
        if (i > 0 && (n.startsWith("base-") || n.startsWith("feature") || n.indexOf('-') == i)) {
            String t = norm(n.substring(i + 1));
            if (ABI_TOKENS.contains(t) || isDpi(t) || isLang(t)) return t;
        }
        return "";
    }

    private static boolean isDpi(String t) {
        for (String d : DPI_NAMES) if (d.equals(t)) return true;
        return false;
    }

    private static boolean isLang(String t) {
        return t.matches("[a-z]{2,3}(_r[a-z]{2})?") && !t.equals("master");
    }

    static List<String> deviceAbis() {
        List<String> out = new ArrayList<>();
        for (String a : Build.SUPPORTED_ABIS) out.add(norm(a));
        return out;
    }

    private static String bestDpi(Context c, List<String> available) {
        int dev = c.getResources().getDisplayMetrics().densityDpi;
        String best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (String a : available) {
            for (int i = 0; i < DPI_NAMES.length; i++) {
                if (!DPI_NAMES[i].equals(a)) continue;
                int diff = Math.abs(DPI_VALUES[i] - dev);
                if (diff < bestDiff || (diff == bestDiff && best != null && DPI_VALUES[i] > dpiValue(best))) {
                    bestDiff = diff;
                    best = a;
                }
            }
        }
        return best;
    }

    private static int dpiValue(String n) {
        for (int i = 0; i < DPI_NAMES.length; i++) if (DPI_NAMES[i].equals(n)) return DPI_VALUES[i];
        return 0;
    }

    /**
     * Chooses which APKs of a bundle go into the install. Smart mode keeps the base and feature splits, the
     * architecture splits this device can run, the one screen-density split that fits best and the language
     * splits for the device's languages; everything else is left out (smaller, faster, same result).
     */
    static void applySelection(Context c, Info in, boolean smart) {
        in.entries = new ArrayList<>();
        in.smart = false;
        if (!in.bundle) {
            in.installBytes = in.size;
            return;
        }
        List<String> keep = new ArrayList<>(in.allEntries);
        if (smart && in.allEntries.size() >= 3) {
            List<String> devAbis = deviceAbis();
            List<String> dpiAvail = new ArrayList<>();
            boolean anyAbi = false, abiHit = false;
            for (String e : in.allEntries) {
                String t = configToken(e);
                if (isDpi(t)) dpiAvail.add(t);
                if (ABI_TOKENS.contains(t)) {
                    anyAbi = true;
                    if (devAbis.contains(t)) abiHit = true;
                }
            }
            String dpi = bestDpi(c, dpiAvail);
            Set<String> langs = new HashSet<>();
            android.os.LocaleList ll = c.getResources().getConfiguration().getLocales();
            for (int i = 0; i < ll.size(); i++) {
                Locale l = ll.get(i);
                langs.add(l.getLanguage().toLowerCase(Locale.ROOT));
                langs.add(norm(l.toLanguageTag()).replace("_", "_r").toLowerCase(Locale.ROOT));
            }
            boolean anyLang = false, langHit = false;
            for (String e : in.allEntries) {
                String t = configToken(e);
                if (isLang(t) && !ABI_TOKENS.contains(t) && !isDpi(t)) {
                    anyLang = true;
                    String base = t.contains("_") ? t.substring(0, t.indexOf('_')) : t;
                    if (langs.contains(t) || langs.contains(base)) langHit = true;
                }
            }
            keep.clear();
            for (String e : in.allEntries) {
                String t = configToken(e);
                if (t.isEmpty() || t.equals("master")) {
                    keep.add(e);
                } else if (ABI_TOKENS.contains(t)) {
                    if (devAbis.contains(t) || !abiHit) keep.add(e);   // nothing matches: let the system decide
                } else if (isDpi(t)) {
                    if (t.equals(dpi)) keep.add(e);
                } else if (isLang(t)) {
                    String base = t.contains("_") ? t.substring(0, t.indexOf('_')) : t;
                    if (langs.contains(t) || langs.contains(base) || (!langHit && t.equals("en"))) keep.add(e);
                } else {
                    keep.add(e);
                }
            }
            // never end up without a base package
            if (keep.isEmpty()) keep.addAll(in.allEntries);
            in.smart = keep.size() < in.allEntries.size();
            if (!anyAbi && !anyLang && dpiAvail.isEmpty()) in.smart = false;
        }
        in.entries.addAll(keep);
        long total = 0;
        try (ZipReader z = ZipReader.open(in.source)) {
            for (String name : in.entries) {
                ZipReader.Entry ze = z.find(name);
                if (ze != null && ze.size > 0) total += ze.size;
            }
        } catch (IOException ignored) {
        }
        in.installBytes = total > 0 ? total : in.size;
        // the base APK carries the app's identity: when the selection changes, keep the cached copy valid
    }

    /** Native-code architectures a package ships: lib/<abi>/ folders of the base APK and architecture splits. */
    private static List<String> abisOf(File apk, Info in) {
        Set<String> out = new LinkedHashSet<>();
        try (ZipReader z = ZipReader.open(apk)) {
            int n = 0;
            for (ZipReader.Entry ent : z.entries()) {
                if (n++ >= 6000) break;
                String name = ent.name;
                if (name.startsWith("lib/")) {
                    int e = name.indexOf('/', 4);
                    if (e > 4) out.add(norm(name.substring(4, e)));
                }
            }
        } catch (IOException ignored) {
        }
        for (String e : in.allEntries) {
            String t = configToken(e);
            if (ABI_TOKENS.contains(t)) out.add(t);
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ checks before installing

    static final int LV_INFO = 0, LV_WARN = 1, LV_ERROR = 2;

    /** One finding of {@link #preflight}. A blocking issue means the system would refuse the install. */
    static final class Issue {
        final int level;
        final boolean blocking;
        final String text;

        Issue(int level, boolean blocking, String text) {
            this.level = level;
            this.blocking = blocking;
            this.text = text;
        }
    }

    static long freeBytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Everything worth knowing before the install starts, most serious first. */
    static List<Issue> preflight(Context c, Info in) {
        List<Issue> out = new ArrayList<>();
        // Android version
        if (in.minSdk > Build.VERSION.SDK_INT) {
            out.add(new Issue(LV_ERROR, true, c.getString(R.string.pk_issue_sdk, in.minSdk, Build.VERSION.SDK_INT)));
        }
        // very old apps are refused by Android 14+ (targetSdk < 23) and Android 15+ (targetSdk < 24)
        int floor = Build.VERSION.SDK_INT >= 35 ? 24 : Build.VERSION.SDK_INT >= 34 ? 23 : 0;
        if (in.targetSdk > 0 && in.targetSdk < floor) {
            out.add(new Issue(LV_ERROR, true, c.getString(R.string.pk_issue_target, in.targetSdk, floor)));
        }
        // CPU architecture
        List<String> dev = deviceAbis();
        if (!in.abis.isEmpty()) {
            boolean hit = false;
            for (String a : in.abis) if (dev.contains(a)) hit = true;
            if (!hit) {
                out.add(new Issue(LV_ERROR, !in.bundle, c.getString(in.bundle ? R.string.pk_issue_abi_split : R.string.pk_issue_abi,
                        Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?", join(in.abis))));
            }
        }
        // signature / version against the installed copy
        if (in.installed && !in.sameCert) {
            out.add(new Issue(LV_ERROR, true, c.getString(R.string.in_cert_line)));
        } else if (in.installed && in.versionCode < in.installedCode) {
            out.add(new Issue(LV_ERROR, true, c.getString(R.string.pk_issue_downgrade, in.installedName, in.versionName)));
        }
        // free space
        long free = freeBytes();
        long need = Math.max(in.installBytes, 1);
        if (free >= 0 && free < need + (32L << 20)) {
            out.add(new Issue(LV_ERROR, true, c.getString(R.string.pk_issue_space_err, Fmt.size(free), Fmt.size(need * 2))));
        } else if (free >= 0 && free < need * 3) {
            out.add(new Issue(LV_WARN, false, c.getString(R.string.pk_issue_space_warn, Fmt.size(free))));
        }
        // OBB data
        if (in.bundle && !in.obbEntries.isEmpty()) {
            out.add(new Issue(Perms.hasAllFiles(c) ? LV_INFO : LV_WARN, false,
                    c.getString(Perms.hasAllFiles(c) ? R.string.pk_issue_obb : R.string.pk_issue_obb_perm, in.obbEntries.size())));
        }
        // bundle without a base package
        if (!hasBase(in)) {
            out.add(new Issue(LV_WARN, false, c.getString(R.string.pk_issue_nobase)));
        }
        // smart selection
        if (in.bundle && in.smart) {
            out.add(new Issue(LV_INFO, false, c.getString(R.string.pk_issue_smart, in.entries.size(), in.allEntries.size())));
        }
        // updating this very app
        if (c.getPackageName().equals(in.pkg)) {
            out.add(new Issue(LV_INFO, false, c.getString(R.string.pk_issue_self)));
        }
        return out;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ permissions of the package

    private static final String[] SENSITIVE_EXTRA = {
            "REQUEST_INSTALL_PACKAGES", "SYSTEM_ALERT_WINDOW", "MANAGE_EXTERNAL_STORAGE", "QUERY_ALL_PACKAGES",
            "WRITE_SETTINGS", "REQUEST_DELETE_PACKAGES", "BIND_ACCESSIBILITY_SERVICE", "BIND_DEVICE_ADMIN",
            "PACKAGE_USAGE_STATS", "READ_LOGS", "INSTALL_PACKAGES", "BIND_NOTIFICATION_LISTENER_SERVICE"};

    /** True for permissions the user must grant at run time or that give an app unusual reach. */
    static boolean sensitive(Context c, String perm) {
        try {
            PermissionInfo pi = c.getPackageManager().getPermissionInfo(perm, 0);
            int base = Build.VERSION.SDK_INT >= 28 ? pi.getProtection()
                    : (pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
            if (base == PermissionInfo.PROTECTION_DANGEROUS) return true;
        } catch (Exception ignored) {
        }
        String shortName = perm.startsWith("android.permission.") ? perm.substring("android.permission.".length()) : perm;
        for (String s : SENSITIVE_EXTRA) if (s.equals(shortName)) return true;
        return false;
    }

    static int sensitiveCount(Context c, Info in) {
        int n = 0;
        for (String p : in.perms) if (sensitive(c, p)) n++;
        return n;
    }

    // ------------------------------------------------------------------ file fingerprint

    /** SHA-256 of the package file (call off the UI thread). */
    static String fileSha256(File f, Progress pr) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int r;
            long n = 0;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
                n += r;
                if (pr != null) pr.on(n, f.length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------------ OBB data (XAPK)

    /** Copies the bundle's OBB files to Android/obb/<package>/. Needs all-files access. Returns how many were copied. */
    static int copyObb(Context c, Info in, Progress pr) throws IOException {
        if (in.obbEntries.isEmpty() || in.pkg.isEmpty()) return 0;
        if (!Perms.hasAllFiles(c)) throw new IOException("no all-files access");
        File dir = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + in.pkg);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        long total = 0;
        int copied = 0;
        try (ZipReader z = ZipReader.open(in.source)) {
            for (String name : in.obbEntries) {
                ZipReader.Entry ze = z.find(name);
                if (ze != null && ze.size > 0) total += ze.size;
            }
            final long fTotal = Math.max(1, total);
            final long[] done = {0};
            for (String name : in.obbEntries) {
                ZipReader.Entry ze = z.find(name);
                if (ze == null) continue;
                String leaf = name.substring(name.lastIndexOf('/') + 1);
                if (leaf.isEmpty() || leaf.contains("..")) continue;
                File out = new File(dir, leaf);
                File part = new File(dir, leaf + ".part");
                try (InputStream is = z.open(ze); OutputStream os = new FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    long last = 0;
                    while ((r = is.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        done[0] += r;
                        if (pr != null && done[0] - last > (512 << 10)) {
                            last = done[0];
                            pr.on(done[0], fTotal);
                        }
                    }
                }
                if (out.exists() && !out.delete()) throw new IOException("cannot replace " + leaf);
                if (!part.renameTo(out)) throw new IOException("cannot move " + leaf);
                copied++;
            }
            if (pr != null) pr.on(fTotal, fTotal);
        }
        return copied;
    }

    // ------------------------------------------------------------------ beta 3: integrity and fingerprint checks

    /**
     * Reads everything that will be installed (the chosen splits of a bundle, or every entry of a single APK) and
     * checks each entry's CRC-32, so a truncated or corrupt download is reported before the install session starts
     * instead of failing half way with a vague system error. Call off the UI thread.
     */
    static void verify(Info in, Progress pr) throws IOException {
        try (ZipReader z = ZipReader.open(in.source)) {
            List<ZipReader.Entry> todo = new ArrayList<>();
            long total = 0;
            if (in.bundle) {
                for (String name : in.entries) {
                    ZipReader.Entry e = z.find(name);
                    if (e != null) todo.add(e);
                }
            } else {
                for (ZipReader.Entry e : z.entries()) if (!e.dir) todo.add(e);
            }
            for (ZipReader.Entry e : todo) total += Math.max(0, e.size);
            final long fTotal = Math.max(1, total);
            long done = 0, last = 0;
            byte[] buf = new byte[1 << 16];
            for (ZipReader.Entry e : todo) {
                try (InputStream is = z.open(e)) {
                    int r;
                    while ((r = is.read(buf)) > 0) {
                        done += r;
                        if (pr != null && done - last > (512 << 10)) {
                            last = done;
                            pr.on(done, fTotal);
                        }
                    }
                }
            }
            if (pr != null) pr.on(fTotal, fTotal);
        }
    }

    /** 1: the clipboard holds a SHA-256 equal to this file's, -1: it holds a different one, 0: no hash on the clipboard. */
    static int hashVsClipboard(Context c, String sha) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return 0;
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(c);
            if (t == null) return 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\b[0-9a-f]{64}\\b").matcher(t);
            if (!m.find()) return 0;
            return m.group().equalsIgnoreCase(sha) ? 1 : -1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** A bundle must carry a base package; without one the system answers with an unhelpful "missing split". */
    static boolean hasBase(Info in) {
        if (!in.bundle) return true;
        for (String n : in.allEntries) {
            String l = n.toLowerCase(Locale.ROOT);
            String t = configToken(n);
            if (l.endsWith("/base.apk") || l.equals("base.apk") || l.contains("base-master") || t.isEmpty() || t.equals("master")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ history

    private static String clean(String v) {
        return v == null ? "" : v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Keeps the last 40 install results (newest first) so the installer can show what happened. */
    static void log(Context c, Info in, boolean ok, String note) {
        try {
            String line = System.currentTimeMillis() + "\t" + (ok ? "1" : "0") + "\t"
                    + clean(in.label.isEmpty() ? in.pkg : in.label) + "\t" + clean(in.pkg) + "\t"
                    + clean(in.versionName) + "\t" + clean(note);
            String old = Store.instHistory(c);
            String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
            StringBuilder sb = new StringBuilder(line);
            for (int i = 0; i < lines.length && i < 39; i++) sb.append('\n').append(lines[i]);
            Store.setInstHistory(c, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Result codes the system reports as text, turned into something a person can act on. */
    static int friendlyMessage(String msg) {
        if (msg == null) return 0;
        String m = msg.toUpperCase(Locale.ROOT);
        if (m.contains("VERSION_DOWNGRADE")) return R.string.pk_fail_downgrade;
        if (m.contains("UPDATE_INCOMPATIBLE") || m.contains("SIGNATURE") || m.contains("NO_CERTIFICATES")) return R.string.pk_fail_sig;
        if (m.contains("NO_MATCHING_ABIS")) return R.string.pk_fail_abi;
        if (m.contains("MISSING_SPLIT")) return R.string.pk_fail_split;
        if (m.contains("OLDER_SDK") || m.contains("DEPRECATED_SDK") || m.contains("NEWER_SDK")) return R.string.pk_fail_sdk;
        if (m.contains("DUPLICATE_PERMISSION") || m.contains("CONFLICTING_PROVIDER")) return R.string.pk_fail_perm;
        if (m.contains("INSUFFICIENT_STORAGE")) return R.string.pk_fail_storage;
        if (m.contains("TIMEOUT")) return R.string.pk_fail_timeout;
        return 0;
    }

    /** The install could not start because the device is nearly full. */
    static final class NoSpace extends IOException {
        NoSpace() {
            super("no space");
        }
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
        try (ZipReader z = ZipReader.open(f)) {
            int n = 0;
            for (ZipReader.Entry e : z.entries()) {
                if (n++ >= 3000) break;
                if (e.dir) continue;
                String name = e.name;
                if (name.contains("..")) continue;   // never trust relative parts
                String low = name.toLowerCase(Locale.ROOT);
                if (low.endsWith(".obb")) {
                    in.hasObb = true;
                    in.obbEntries.add(name);
                }
                if (!low.endsWith(".apk")) continue;
                if (low.startsWith("splits/")) splits.add(name);
                else if (low.startsWith("standalones/")) standalone.add(name);
                else if (!low.contains("/")) root.add(name);
            }
        }
        if (!splits.isEmpty()) in.allEntries.addAll(splits);
        else if (!root.isEmpty()) in.allEntries.addAll(root);
        else if (!standalone.isEmpty()) {
            String pick = standalone.get(0);
            for (String s : standalone) {
                if (s.toLowerCase(Locale.ROOT).contains("universal")) pick = s;
            }
            in.allEntries.add(pick);
        }
        in.entries.addAll(in.allEntries);
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

    /** Cancels install sessions this app left behind (killed mid-install) so they cannot pile up or block a new one. */
    static void abandonStale(Context c, PackageInstaller installer) {
        try {
            for (PackageInstaller.SessionInfo si : installer.getMySessions()) {
                if (!si.isActive()) {
                    try {
                        installer.abandonSession(si.getSessionId());
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Streams the package into a new install session and commits it. Call off the UI thread. */
    static void install(Context c, Info in, Progress pr) throws IOException {
        long free = freeBytes();
        if (free >= 0 && in.installBytes > 0 && free < in.installBytes + (16L << 20)) throw new NoSpace();

        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        abandonStale(c, installer);
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (in.pkg != null && !in.pkg.isEmpty()) p.setAppPackageName(in.pkg);
        if (in.installBytes > 0) p.setSize(in.installBytes);   // lets the system reserve space up front
        if (Build.VERSION.SDK_INT >= 26) p.setInstallReason(PackageManager.INSTALL_REASON_USER);
        if (Build.VERSION.SDK_INT >= 31) {
            // updates of apps this app installed need no tap; everything else still asks
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
            p.setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST);
        }
        if (Build.VERSION.SDK_INT >= 34 && Store.instBool(c, "owner", false)) {
            try {
                p.setRequestUpdateOwnership(true);
            } catch (Throwable ignored) {
            }
        }

        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fileman:install");
                wl.acquire(10 * 60 * 1000L);   // a big bundle must not be cut off by the screen turning off
            }
        } catch (Throwable ignored) {
            wl = null;
        }

        final int id = installer.createSession(p);
        PackageInstaller.Session s = installer.openSession(id);
        try {
            long total = 0;
            if (!in.bundle) {
                total = in.source.length();
            } else {
                try (ZipReader z = ZipReader.open(in.source)) {
                    for (String name : in.entries) {
                        ZipReader.Entry ze = z.find(name);
                        if (ze != null && ze.size > 0) total += ze.size;
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
                try (ZipReader z = ZipReader.open(in.source)) {
                    for (String name : in.entries) {
                        ZipReader.Entry ze = z.find(name);
                        if (ze == null) continue;
                        String safe = name.replace('/', '_').replaceAll("[^A-Za-z0-9._-]", "_");
                        writeEntry(s, safe, z.open(ze), ze.size, done, step);
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
            if (wl != null && wl.isHeld()) {
                try {
                    wl.release();
                } catch (Throwable ignored) {
                }
            }
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
