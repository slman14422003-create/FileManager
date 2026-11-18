package com.fileman.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Disk cache of converted Office pages, keyed by path + size + lastModified (plus the language and colours the page
 * was built with), so an unchanged file opens at once. Password protected files are never cached.
 */
final class ConvertCache {
    private ConvertCache() {
    }

    private static final String DIR = "docconv";
    private static final String VERSION = "v3";
    private static final long MAX_BYTES = 48L * 1024 * 1024;
    private static final int LITE_MIN = 300_000;
    private static final String PIXEL = "data:image/gif;base64,R0lGODlhAQABAAAAACw=";
    private static final Pattern IMG_SRC = Pattern.compile("src=\"data:[^\"]{2000,}\"");
    private static final Pattern CSS_URL = Pattern.compile("url\\((['\"]?)data:[^)]{2000,}\\)");

    static String key(File f, String ext, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((VERSION + "|" + f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified() + "|" + ext + "|" + salt)
                    .getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString((f.getAbsolutePath() + f.length() + f.lastModified()).hashCode());
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The cached page, or null. Blocking: call off the main thread. */
    static String get(Context c, String key) {
        File f = new File(dir(c), key + ".html");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length() + 16, 1 << 28))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    static void put(Context c, String key, final String html) {
        try {
            File f = new File(dir(c), key + ".html");
            SafeWrite.atomic(f, html.length() * 2L, out -> out.write(html.getBytes(StandardCharsets.UTF_8)));
            trim(c);
        } catch (Throwable ignored) {
        }
    }

    private static void trim(Context c) {
        File[] all = dir(c).listFiles();
        if (all == null) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= MAX_BYTES) return;
        final java.util.HashMap<File, Long> mods = new java.util.HashMap<>();
        final java.util.HashMap<File, Long> lens = new java.util.HashMap<>();
        for (File f : all) {
            mods.put(f, f.lastModified());
            lens.put(f, f.length());
        }
        Arrays.sort(all, (a, b) -> Long.compare(mods.get(a), mods.get(b)));
        for (File f : all) {
            if (total <= MAX_BYTES * 3 / 4) break;
            long len = lens.get(f);
            if (f.delete()) total -= len;
        }
    }

    /** The page without its big embedded pictures (text and layout only), or null when that would change nothing. */
    static String lite(String html) {
        if (html == null || html.length() < LITE_MIN) return null;
        String a = IMG_SRC.matcher(html).replaceAll("src=\"" + PIXEL + "\"");
        a = CSS_URL.matcher(a).replaceAll("url(" + PIXEL + ")");
        return a.length() < html.length() * 0.8 ? a : null;
    }
}
