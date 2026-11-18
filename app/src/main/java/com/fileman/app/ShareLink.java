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

    /** A scanned code must never make this device connect to an address outside the local network. */
    static boolean privateV4(String ip) {
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] p = ip.split("\\.");
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            o[i] = Integer.parseInt(p[i]);
            if (o[i] > 255) return false;
        }
        return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168)
                || (o[0] == 169 && o[1] == 254);
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
            l.name = n == null ? "" : n.replaceAll("[\\p{Cntrl}]", "_");
            if (l.name.length() > 64) l.name = l.name.substring(0, 64);
            String s = u.getQueryParameter("s");
            l.ssid = s == null ? "" : s;
            String p = u.getQueryParameter("p");
            l.pass = p == null ? "" : p;
            if (l.ssid.length() > 32 || l.pass.length() > 63) return null;
            String k = u.getQueryParameter("k");
            if (k != null) l.sec = Integer.parseInt(k);
            String i = u.getQueryParameter("i");
            if (i != null) {
                for (String ip : i.split(",")) {
                    String t = ip.trim();
                    if (privateV4(t) && l.ips.size() < 16) l.ips.add(t);
                }
            }
            return l;
        } catch (Exception e) {
            return null;
        }
    }
}
