package com.fileman.app;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * The ONLY network code of the Google Drive feature (including the Apps Script bridge that hands out the access token) (the update check keeps its own, separate Net.java).
 * Every request is HTTPS on port 443 to Google's own hosts; redirects are never followed; the access token
 * is attached only to the Drive API hosts and never to thumbnail hosts; response sizes are capped.
 */
final class DriveHttp {
    private DriveHttp() {
    }

    static final class Resp {
        final int code;
        final String body;
        final String location;
        final String range;

        Resp(int code, String body, String location, String range) {
            this.code = code;
            this.body = body;
            this.location = location;
            this.range = range;
        }
    }

    interface Progress {
        void on(long done, long total);
    }

    private static final int MAX_JSON = 4 * 1024 * 1024;
    private static final int MAX_THUMB = 2 * 1024 * 1024;

    private static boolean apiHost(String h) {
        return h.equals("www.googleapis.com") || h.equals("oauth2.googleapis.com");
    }

    private static boolean thumbHost(String h) {
        return h.endsWith(".googleusercontent.com");
    }

    /** True for an https URL on one of the Drive API hosts (used to validate the upload session address). */
    static boolean apiUrl(String url) {
        try {
            URL u = new URL(url);
            return "https".equals(u.getProtocol()) && u.getUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && apiHost(u.getHost().toLowerCase(Locale.US));
        } catch (Exception e) {
            return false;
        }
    }

    /** Percent-encodes a query value (RFC 3986 unreserved characters stay as they are). */
    static String enc(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length + 16);
        for (byte x : b) {
            int c = x & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    private static HttpsURLConnection open(String url, String method, String token) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || u.getUserInfo() != null
                || (u.getPort() != -1 && u.getPort() != 443)) {
            throw new IOException("blocked url");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
        boolean api = apiHost(host);
        if (!api && !thumbHost(host)) throw new IOException("blocked host");
        HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        if (api && token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "FileManager-Drive");
        return c;
    }

    private static byte[] readCapped(InputStream in, int max) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (bo.size() + n > max) throw new IOException("response too large");
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    /** A JSON request (PATCH goes out as POST with the override header, because the platform client lacks PATCH). */
    static Resp json(String method, String url, String token, String body) throws IOException {
        boolean patch = "PATCH".equals(method);
        HttpsURLConnection c = open(url, patch ? "POST" : method, token);
        try {
            if (patch) c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            } else if (patch || "POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
            }
            int code = c.getResponseCode();
            byte[] data = readCapped(code >= 400 ? c.getErrorStream() : c.getInputStream(), MAX_JSON);
            return new Resp(code, new String(data, StandardCharsets.UTF_8), c.getHeaderField("Location"), null);
        } finally {
            c.disconnect();
        }
    }

    /** Streams a Drive download into a local file. */
    static void download(String url, String token, File out, Progress p, AtomicBoolean cancel) throws IOException {
        HttpsURLConnection c = open(url, "GET", token);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("http " + code);
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) throw new IOException("cancelled");
                    o.write(buf, 0, n);
                    done += n;
                    if (p != null) p.on(done, total);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Opens a resumable upload session and returns its address. */
    static String startResumable(String method, String url, String token, String meta, String mime, long size)
            throws IOException {
        boolean patch = "PATCH".equals(method);
        HttpsURLConnection c = open(url, patch ? "POST" : method, token);
        try {
            if (patch) c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            byte[] b = meta.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("X-Upload-Content-Type", mime);
            c.setRequestProperty("X-Upload-Content-Length", Long.toString(size));
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                byte[] err = readCapped(c.getErrorStream(), 64 * 1024);
                throw new DriveApi.DriveError(code, new String(err, StandardCharsets.UTF_8));
            }
            String loc = c.getHeaderField("Location");
            if (loc == null || !apiUrl(loc)) throw new IOException("bad upload session");
            return loc;
        } finally {
            c.disconnect();
        }
    }

    /** Sends one piece of an upload session. 308 means "send the next piece", 200 / 201 means finished. */
    static Resp putChunk(String session, byte[] buf, int len, long offset, long total) throws IOException {
        HttpsURLConnection c = open(session, "PUT", null);
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(len);
            c.setRequestProperty("Content-Range", len == 0 ? "bytes */" + total
                    : "bytes " + offset + "-" + (offset + len - 1) + "/" + total);
            try (OutputStream o = c.getOutputStream()) {
                o.write(buf, 0, len);
            }
            int code = c.getResponseCode();
            byte[] data = readCapped(code >= 400 ? c.getErrorStream() : c.getInputStream(), MAX_JSON);
            return new Resp(code, new String(data, StandardCharsets.UTF_8), null, c.getHeaderField("Range"));
        } finally {
            c.disconnect();
        }
    }

    /** Asks the session how much it already has (after a dropped connection). */
    static long queryOffset(String session, long total) throws IOException {
        HttpsURLConnection c = open(session, "PUT", null);
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.setRequestProperty("Content-Range", "bytes */" + total);
            int code = c.getResponseCode();
            if (code == 200 || code == 201) return total;
            String r = c.getHeaderField("Range");
            if (r == null) return 0;
            int dash = r.lastIndexOf('-');
            return dash < 0 ? 0 : Long.parseLong(r.substring(dash + 1).trim()) + 1;
        } catch (NumberFormatException e) {
            return 0;
        } finally {
            c.disconnect();
        }
    }

    /** A thumbnail image (no token is ever sent to these hosts). */
    static byte[] getThumb(String url) throws IOException {
        HttpsURLConnection c = open(url, "GET", null);
        try {
            if (c.getResponseCode() != 200) throw new IOException("thumb");
            return readCapped(c.getInputStream(), MAX_THUMB);
        } finally {
            c.disconnect();
        }
    }

    private static boolean bridgeHost(String h) {
        return h.equals("script.google.com") || h.equals("script.googleusercontent.com");
    }

    /** True for an https address of a published Apps Script web app (the bridge). */
    static boolean bridgeUrl(String url) {
        try {
            URL u = new URL(url);
            return "https".equals(u.getProtocol()) && u.getUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && u.getHost().toLowerCase(Locale.US).equals("script.google.com")
                    && u.getPath().contains("/macros/") && u.getPath().endsWith("/exec");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Asks the person's own Apps Script web app for an access token. The secret goes only to script.google.com
     * (POST body, never in the address); the answer is fetched from the redirect target with a plain GET.
     */
    static String bridge(String url, String secret) throws IOException {
        byte[] form = ("k=" + enc(secret)).getBytes(StandardCharsets.UTF_8);
        String cur = url;
        boolean post = true;
        for (int hop = 0; hop < 4; hop++) {
            URL u = new URL(cur);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
            if (!"https".equals(u.getProtocol()) || u.getUserInfo() != null
                    || (u.getPort() != -1 && u.getPort() != 443) || !bridgeHost(host)) {
                throw new IOException("blocked host");
            }
            boolean send = post && host.equals("script.google.com");
            HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
            try {
                c.setRequestMethod(send ? "POST" : "GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "FileManager-Drive");
                c.setRequestProperty("Accept", "application/json");
                if (send) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    c.setFixedLengthStreamingMode(form.length);
                    try (OutputStream o = c.getOutputStream()) {
                        o.write(form);
                    }
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("bad redirect");
                    cur = new URL(u, loc).toString();
                    post = false;
                    continue;
                }
                if (code != 200) throw new IOException("bridge http " + code);
                return new String(readCapped(c.getInputStream(), 64 * 1024), StandardCharsets.UTF_8);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /** Revokes an access token (this also ends the app's permission for the account). */
    static void revoke(String token) throws IOException {
        HttpsURLConnection c = open("https://oauth2.googleapis.com/revoke", "POST", null);
        try {
            byte[] b = ("token=" + enc(token)).getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }
}
