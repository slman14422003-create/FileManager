package com.fileman.app;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The ONLY place in the app that opens a network connection (used by the update check).
 * Everything is HTTPS, limited to GitHub's own hosts, redirects are followed by hand and every hop
 * is checked again. Nothing from the device's files is ever sent: all requests are plain GETs.
 */
final class Net {
    private Net() {
    }

    private static final String[] HOSTS = {
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
    };
    private static final int MAX_REDIRECTS = 5;

    static boolean allowed(URL u) {
        if (u == null || !"https".equals(u.getProtocol())) return false;
        if (u.getUserInfo() != null) return false;
        int port = u.getPort();
        if (port != -1 && port != 443) return false;
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
        for (String h : HOSTS) if (h.equals(host)) return true;
        return false;
    }

    static boolean allowed(String url) {
        try {
            return allowed(new URL(url));
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens a GET connection whose response has already been received (final, non-redirect hop). */
    static HttpURLConnection get(String url, String accept) throws IOException {
        String cur = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = new URL(cur);
            if (!allowed(u)) throw new IOException("blocked host");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("User-Agent", "FileManager-Updater");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("bad redirect");
                cur = new URL(u, loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }
}
