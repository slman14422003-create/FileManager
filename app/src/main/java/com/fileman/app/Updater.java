package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer APK in the GitHub Releases of the app's own repository, downloads it and
 * verifies it (size, SHA-256 from the release notes, same signing certificate) before installing.
 */
public final class Updater {
    private Updater() {
    }

    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    public static final class Info {
        public String tag = "";
        public String name = "";
        public String body = "";
        public String htmlUrl = "";
        public long publishedAt;
        public boolean prerelease;
        public String assetUrl = "";
        public String assetName = "";
        public long assetSize;
        /** versionCode written by the build workflow in the release notes (0 when absent). */
        public long versionCode;
        public String sha256 = "";
        public boolean newer;
    }

    public static final class ApiException extends IOException {
        public final int code;

        ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    public interface Progress {
        void onBytes(long done, long total);
    }

    // ------------------------------------------------------------------ installed version

    public static String installedName(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName == null ? "" : p.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    public static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ version comparison

    static long[] parseVersion(String v) {
        if (v == null) return new long[0];
        Matcher m = Pattern.compile("\\d+").matcher(v);
        long[] tmp = new long[16];
        int n = 0;
        while (m.find() && n < tmp.length) {
            try {
                tmp[n++] = Long.parseLong(m.group());
            } catch (NumberFormatException e) {
                tmp[n++] = 0;
            }
        }
        long[] out = new long[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    static int compareVersions(String a, String b) {
        long[] x = parseVersion(a);
        long[] y = parseVersion(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long p = i < x.length ? x[i] : 0;
            long q = i < y.length ? y[i] : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ HTTP

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "FileManager-Updater");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return c;
    }

    private static String getText(String url) throws IOException {
        HttpURLConnection c = open(url, "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new ApiException(code, "HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 4 * 1024 * 1024) throw new IOException("response too large");
                }
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ release lookup

    private static long parseTime(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONObject pickApk(JSONArray assets) {
        if (assets == null) return null;
        JSONObject fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name").toLowerCase(Locale.US);
            if (!n.endsWith(".apk") || !"uploaded".equals(a.optString("state", "uploaded"))) continue;
            if (n.contains("debug")) {
                if (fallback == null) fallback = a;
                continue;
            }
            return a;
        }
        return fallback;
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Returns the newest release that carries an APK, with {@link Info#newer} telling whether it is
     * newer than the installed app, or null when the repository has no installable release.
     */
    public static Info check(Context c, String owner, String repo, boolean includePre) throws Exception {
        String json = getText("https://api.github.com/repos/" + owner + "/" + repo + "/releases?per_page=20");
        JSONArray rels = new JSONArray(json);
        for (int i = 0; i < rels.length(); i++) {
            JSONObject r = rels.getJSONObject(i);
            if (r.optBoolean("draft")) continue;
            if (r.optBoolean("prerelease") && !includePre) continue;
            JSONObject apk = pickApk(r.optJSONArray("assets"));
            if (apk == null) continue;
            Info in = new Info();
            in.tag = r.optString("tag_name");
            in.name = r.isNull("name") ? "" : r.optString("name");
            in.body = r.isNull("body") ? "" : r.optString("body");
            in.htmlUrl = r.optString("html_url");
            in.publishedAt = parseTime(r.isNull("published_at") ? r.optString("created_at") : r.optString("published_at"));
            in.prerelease = r.optBoolean("prerelease");
            in.assetUrl = apk.optString("browser_download_url");
            in.assetName = apk.optString("name");
            in.assetSize = apk.optLong("size");
            String code = find(in.body, "versionCode\\s*[:=]\\s*(\\d+)");
            if (!code.isEmpty()) {
                try {
                    in.versionCode = Long.parseLong(code);
                } catch (NumberFormatException ignored) {
                }
            }
            in.sha256 = find(in.body, "SHA-?256[^0-9a-fA-F]{0,12}([0-9a-fA-F]{64})").toLowerCase(Locale.US);
            if (in.versionCode > 0) in.newer = in.versionCode > installedCode(c);
            else in.newer = compareVersions(in.tag, installedName(c)) > 0;
            return in;
        }
        return null;
    }

    /** Release notes without the machine-readable footer lines added by the build workflow. */
    public static String cleanNotes(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            String t = line.trim().toLowerCase(Locale.US);
            if (t.contains("versioncode") || t.contains("sha-256") || t.contains("sha256")) continue;
            sb.append(line).append('\n');
        }
        String s = sb.toString().trim();
        return s.length() > 1200 ? s.substring(0, 1200) + "…" : s;
    }

    // ------------------------------------------------------------------ download + verification

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    /** Thrown when the downloaded file fails a check; the message is a string resource id. */
    public static final class VerifyException extends IOException {
        public final int resId;

        VerifyException(int resId) {
            super("verify");
            this.resId = resId;
        }
    }

    /**
     * Downloads the APK of a release into the cache folder and verifies it. Call off the UI thread.
     * {@code cancel[0]} set to true aborts the download.
     */
    public static File download(Context c, Info in, Progress progress, boolean[] cancel) throws Exception {
        File dir = new File(c.getCacheDir(), "apk");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) o.delete();
        File apk = new File(dir, "update.apk");
        HttpURLConnection conn = open(in.assetUrl, "application/octet-stream");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new ApiException(code, "HTTP " + code);
            long total = conn.getContentLengthLong();
            if (total <= 0) total = in.assetSize;
            try (InputStream is = conn.getInputStream(); OutputStream os = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                long lastReport = 0;
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (cancel != null && cancel[0]) {
                        os.close();
                        apk.delete();
                        throw new IOException("cancelled");
                    }
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (progress != null && now - lastReport > 150) {
                        lastReport = now;
                        progress.onBytes(done, total);
                    }
                }
                if (progress != null) progress.onBytes(done, total);
            }
        } finally {
            conn.disconnect();
        }
        if (in.assetSize > 0 && apk.length() != in.assetSize) {
            apk.delete();
            throw new VerifyException(R.string.upd_size_bad);
        }
        if (!in.sha256.isEmpty() && !sha256Hex(apk).equalsIgnoreCase(in.sha256)) {
            apk.delete();
            throw new VerifyException(R.string.upd_hash_bad);
        }
        if (!sameSigner(c, apk)) {
            apk.delete();
            throw new VerifyException(R.string.upd_sig_bad);
        }
        return apk;
    }

    // ------------------------------------------------------------------ signing certificate check

    private static String hex(Signature sig) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signersOf(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
        }
        return pi.signatures;
    }

    /** True when the downloaded APK is signed with the same certificate as the installed app. */
    @SuppressWarnings("deprecation")
    static boolean sameSigner(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo theirs = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flags);
            if (theirs == null || mine == null) return false;
            Signature[] a = signersOf(theirs);
            Signature[] b = signersOf(mine);
            if (a == null || b == null || a.length != 1 || b.length != 1) return false;
            return hex(a[0]).equals(hex(b[0]));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ background check on launch

    /** Splits "owner/repo"; null when the slug is not valid. */
    public static String[] split(String slug) {
        if (slug == null) return null;
        int slash = slug.indexOf('/');
        if (slash <= 0 || slash >= slug.length() - 1 || slug.indexOf('/', slash + 1) >= 0) return null;
        return new String[]{slug.substring(0, slash), slug.substring(slash + 1)};
    }

    /** Silent check (at most every few hours) that offers the update in a dialog when one exists. */
    public static void autoCheck(final Activity a, final ExecutorService io, final Handler ui) {
        if (!Store.autoUpdate(a)) return;
        final long now = System.currentTimeMillis();
        if (now - Store.lastUpdateCheck(a) < AUTO_CHECK_INTERVAL_MS) return;
        final String[] src = split(Store.updateRepo(a));
        if (src == null) return;
        final boolean pre = Store.updatePre(a);
        io.execute(() -> {
            try {
                Info in = check(a, src[0], src[1], pre);
                Store.setLastUpdateCheck(a, now);
                if (in != null && in.newer && !in.tag.equals(Store.skippedVersion(a))) {
                    ui.post(() -> {
                        if (!a.isFinishing() && !a.isDestroyed()) prompt(a, in);
                    });
                }
            } catch (Exception ignored) {
                // offline or no access: stay silent, the manual check reports errors
            }
        });
    }

    private static void prompt(final Activity a, final Info in) {
        String msg = a.getString(R.string.upd_prompt_msg, in.tag, installedName(a));
        String notes = cleanNotes(in.body);
        if (!notes.isEmpty()) msg = msg + "\n\n" + notes;
        new Dlg(a)
                .setTitle(R.string.upd_available)
                .setMessage(msg)
                .setPositiveButton(R.string.upd_update_now, (d, w) -> {
                    Intent i = new Intent(a, UpdateActivity.class);
                    i.putExtra("autostart", true);
                    a.startActivity(i);
                })
                .setNeutralButton(R.string.upd_skip_version, (d, w) -> Store.setSkippedVersion(a, in.tag))
                .setNegativeButton(R.string.install_later, null)
                .show();
    }
}
