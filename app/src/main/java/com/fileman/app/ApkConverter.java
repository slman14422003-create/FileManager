package com.fileman.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;

import com.android.apksig.ApkSigner;
import com.reandroid.apk.APKLogger;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.apkeditor.common.AndroidManifestHelper;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ZipEntryMap;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.container.SpecTypePair;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResValue;
import com.reandroid.arsc.value.ValueType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ApkConverter {
    private ApkConverter() {
    }

    interface Stage {
        void on(int stringRes);
    }

    static final class Result {
        File file;
        boolean signed;
        boolean pairip;
        boolean copied;
    }

    static final class Skipped extends IOException {
        Skipped(String m) {
            super(m);
        }
    }

    private static final String[] ARCHS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"};

    private static final APKLogger QUIET = new APKLogger() {
        @Override
        public void logMessage(String msg) {
        }

        @Override
        public void logError(String msg, Throwable tr) {
        }

        @Override
        public void logVerbose(String msg) {
        }
    };

    static boolean eligible(PkgInstaller.Info in) {
        return in != null && in.bundle && in.entries.size() >= 2 && !in.hasObb;
    }

    static PkgInstaller.Info auto(Context c, PkgInstaller.Info src, Stage st) {
        if (!Store.instBool(c, "auto_convert", true) || !eligible(src)) return src;
        try {
            Result r = fromBundle(c, src, true, st);
            PkgInstaller.Info conv = PkgInstaller.inspect(c, r.file);
            if (conv.installed && !conv.sameCert) {
                r.file.delete();
                src.convertNote = c.getString(R.string.cv_kept_split);
                return src;
            }
            conv.converted = true;
            conv.convertedFile = r.file;
            conv.original = src.original;
            return conv;
        } catch (Skipped e) {
            src.convertNote = c.getString(R.string.cv_skip_pairip);
        } catch (Throwable t) {
            src.convertNote = c.getString(R.string.cv_failed);
        }
        return src;
    }

    static File outputDir(Context c) {
        boolean all = Perms.hasAllFiles(c);
        File base = all ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                : c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File dir = new File(base, all ? "FileManager-APKs" : "apks");
        dir.mkdirs();
        return dir;
    }

    static String safeName(String label, String ver) {
        String n = ((label == null ? "app" : label) + (ver == null || ver.isEmpty() ? "" : "_" + ver))
                .replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return n.isEmpty() ? "app" : n;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        while (f.exists()) f = new File(dir, stem + "_" + (i++) + ext);
        return f;
    }

    private static File workDir(Context c) throws IOException {
        File d = new File(c.getCacheDir(), "conv/" + System.nanoTime());
        if (!d.mkdirs() && !d.isDirectory()) throw new IOException("no work dir");
        return d;
    }

    static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static void cleanWork(Context c) {
        deleteTree(new File(c.getCacheDir(), "conv"));
    }

    private static String leaf(String name) {
        int i = name.lastIndexOf('/');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static void extractEntries(File bundle, List<String> entries, File work) throws IOException {
        try (ZipFile z = new ZipFile(bundle)) {
            int n = 0;
            for (String name : entries) {
                ZipEntry ze = z.getEntry(name);
                if (ze == null || ze.isDirectory()) continue;
                String l = leaf(name);
                if (l.isEmpty() || l.contains("..")) continue;
                File out = new File(work, l);
                if (out.exists()) out = new File(work, (n++) + "_" + l);
                PkgInstaller.copy(z.getInputStream(ze), new FileOutputStream(out), null);
            }
        }
    }

    static Result fromBundle(Context c, PkgInstaller.Info in, boolean auto, Stage st) throws Exception {
        File work = workDir(c);
        try {
            if (st != null) st.on(R.string.cv_stage_extract);
            extractEntries(in.source, in.entries, work);
            try (ApkBundle bundle = new ApkBundle()) {
                bundle.loadApkDirectory(work);
                String name = safeName(in.label.isEmpty() ? in.pkg : in.label, in.versionName) + "_merged.apk";
                return mergeAndSign(c, bundle, work, outputDir(c), name, auto, st);
            }
        } finally {
            deleteTree(work);
        }
    }

    static Result fromInstalled(Context c, String pkg, String label, String ver, Stage st) throws Exception {
        PackageManager pm = c.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        File outDir = outputDir(c);
        String base = safeName(label == null || label.isEmpty() ? pkg : label, ver);
        if (ai.splitSourceDirs == null || ai.splitSourceDirs.length == 0) {
            Result r = new Result();
            r.file = unique(outDir, base + ".apk");
            r.copied = true;
            PkgInstaller.copy(new FileInputStream(ai.sourceDir), new FileOutputStream(r.file), null);
            return r;
        }
        File work = workDir(c);
        try {
            if (st != null) st.on(R.string.cv_stage_extract);
            copyTo(ai.sourceDir, work, "base.apk");
            int i = 0;
            for (String s : ai.splitSourceDirs) copyTo(s, work, (i++) + "_" + new File(s).getName());
            try (ApkBundle bundle = new ApkBundle()) {
                bundle.loadApkDirectory(work);
                return mergeAndSign(c, bundle, work, outDir, base + "_merged.apk", false, st);
            }
        } finally {
            deleteTree(work);
        }
    }

    private static void copyTo(String src, File dir, String name) throws IOException {
        PkgInstaller.copy(new FileInputStream(src), new FileOutputStream(new File(dir, name)), null);
    }

    private static boolean hasPairip(ApkBundle bundle) {
        for (ApkModule m : bundle.getApkModuleList()) {
            for (String a : ARCHS) {
                if (m.containsFile("lib/" + a + "/libpairipcore.so")) return true;
            }
        }
        return false;
    }

    private static Result mergeAndSign(Context c, ApkBundle bundle, File work, File outDir, String name,
                                       boolean auto, Stage st) throws Exception {
        Result r = new Result();
        boolean sign = true;
        if (hasPairip(bundle)) {
            if (auto) throw new Skipped("pairip");
            r.pairip = true;
            sign = false;
        }
        if (st != null) st.on(R.string.cv_stage_merge);
        try (ApkModule merged = bundle.mergeModules(true)) {
            sanitize(merged);
            if (st != null) st.on(R.string.cv_stage_save);
            File mergedApk = new File(work, "merged_" + System.currentTimeMillis() + ".apk");
            merged.writeApk(mergedApk);
            File dest = unique(outDir, name);
            if (sign) {
                if (st != null) st.on(R.string.cv_stage_sign);
                signDebug(c, mergedApk, dest);
                r.signed = true;
            } else {
                PkgInstaller.copy(new FileInputStream(mergedApk), new FileOutputStream(dest), null);
            }
            r.file = dest;
        }
        return r;
    }

    private static void signDebug(Context c, File in, File out) throws Exception {
        char[] pw = "android".toCharArray();
        KeyStore ks = KeyStore.getInstance("BKS");
        try (InputStream is = c.getAssets().open("debug23.keystore")) {
            ks.load(is, pw);
        }
        String alias = ks.aliases().nextElement();
        new ApkSigner.Builder(Collections.singletonList(new ApkSigner.SignerConfig.Builder("CERT",
                ((KeyStore.PrivateKeyEntry) ks.getEntry(alias, new KeyStore.PasswordProtection(pw))).getPrivateKey(),
                Collections.singletonList((X509Certificate) ks.getCertificate(alias))).build()))
                .setInputApk(in)
                .setOutputApk(out)
                .setCreatedBy("Android Gradle 8.0.2")
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }

    static String debugCertSha(Context c) {
        try {
            char[] pw = "android".toCharArray();
            KeyStore ks = KeyStore.getInstance("BKS");
            try (InputStream is = c.getAssets().open("debug23.keystore")) {
                ks.load(is, pw);
            }
            String alias = ks.aliases().nextElement();
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(ks.getCertificate(alias).getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void sanitize(ApkModule merged) throws IOException {
        if (!merged.hasAndroidManifest()) return;
        AndroidManifestBlock manifest = merged.getAndroidManifest();
        AndroidManifestHelper.removeAttributeFromManifestById(manifest, AndroidManifest.ID_requiredSplitTypes, QUIET);
        AndroidManifestHelper.removeAttributeFromManifestById(manifest, AndroidManifest.ID_splitTypes, QUIET);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest, AndroidManifest.NAME_splitTypes, QUIET);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest, AndroidManifest.NAME_requiredSplitTypes, QUIET);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest, AndroidManifest.NAME_splitTypes, QUIET);
        AndroidManifestHelper.removeAttributeFromManifestAndApplication(manifest,
                AndroidManifest.ID_extractNativeLibs, QUIET, AndroidManifest.NAME_extractNativeLibs);
        AndroidManifestHelper.removeAttributeFromManifestAndApplication(manifest,
                AndroidManifest.ID_isSplitRequired, QUIET, AndroidManifest.NAME_isSplitRequired);

        ResXmlElement application = manifest.getApplicationElement();
        List<ResXmlElement> splitMeta = AndroidManifestHelper.listSplitRequired(application);
        boolean splitsRemoved = false;
        for (ResXmlElement meta : splitMeta) {
            if (!splitsRemoved) {
                boolean result = false;
                ResXmlAttribute nameAttribute = meta.searchAttributeByResourceId(AndroidManifest.ID_name);
                if (nameAttribute != null && "com.android.vending.splits".equals(nameAttribute.getValueAsString())) {
                    ResXmlAttribute valueAttribute = meta.searchAttributeByResourceId(AndroidManifest.ID_value);
                    if (valueAttribute == null) {
                        valueAttribute = meta.searchAttributeByResourceId(AndroidManifest.ID_resource);
                    }
                    if (valueAttribute != null && valueAttribute.getValueType() == ValueType.REFERENCE
                            && merged.hasTableBlock()) {
                        TableBlock tableBlock = merged.getTableBlock();
                        ResourceEntry resourceEntry = tableBlock.getResource(valueAttribute.getData());
                        if (resourceEntry != null) {
                            ZipEntryMap zipEntryMap = merged.getZipEntryMap();
                            for (Entry entry : resourceEntry) {
                                if (entry == null) continue;
                                ResValue resValue = entry.getResValue();
                                if (resValue == null) continue;
                                zipEntryMap.remove(resValue.getValueAsString());
                                entry.setNull(true);
                                SpecTypePair pair = entry.getTypeBlock().getParentSpecTypePair();
                                pair.removeNullEntries(entry.getId());
                            }
                            result = true;
                        }
                    }
                }
                splitsRemoved = result;
            }
            application.remove(meta);
        }
        manifest.refresh();
    }
}
