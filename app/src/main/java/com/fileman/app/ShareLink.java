package com.fileman.app;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

final class ShareLink {
    String ssid = "";
    String pass = "";
    String name = "";
    String token = "";
    int sec = 1;
    int port;
    List<String> ips = new ArrayList<>();

    String encode() {
        Uri.Builder b = new Uri.Builder().scheme("fmshare").authority("link");
        b.appendQueryParameter("v", String.valueOf(ShareProto.VERSION));
        b.appendQueryParameter("t", token);
        b.appendQueryParameter("o", String.valueOf(port));
        b.appendQueryParameter("n", name);
        if (!ssid.isEmpty()) {
            b.appendQueryParameter("s", ssid);
            b.appendQueryParameter("p", pass);
            b.appendQueryParameter("k", String.valueOf(sec));
        }
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) sb.append(',');
            sb.append(ip);
        }
        if (sb.length() > 0) b.appendQueryParameter("i", sb.toString());
        return b.build().toString();
    }

    static ShareLink parse(String text) {
        if (text == null) return null;
        try {
            Uri u = Uri.parse(text.trim());
            if (!"fmshare".equals(u.getScheme())) return null;
            ShareLink l = new ShareLink();
            l.token = String.valueOf(u.getQueryParameter("t"));
            if (ShareProto.unhex(l.token) == null) return null;
            l.port = Integer.parseInt(String.valueOf(u.getQueryParameter("o")));
            if (l.port < 1 || l.port > 65535) return null;
            String n = u.getQueryParameter("n");
            l.name = n == null ? "" : n;
            String s = u.getQueryParameter("s");
            l.ssid = s == null ? "" : s;
            String p = u.getQueryParameter("p");
            l.pass = p == null ? "" : p;
            String k = u.getQueryParameter("k");
            if (k != null) l.sec = Integer.parseInt(k);
            String i = u.getQueryParameter("i");
            if (i != null) {
                for (String ip : i.split(",")) {
                    String t = ip.trim();
                    if (t.matches("\\d{1,3}(\\.\\d{1,3}){3}")) l.ips.add(t);
                }
            }
            return l;
        } catch (Exception e) {
            return null;
        }
    }
}
