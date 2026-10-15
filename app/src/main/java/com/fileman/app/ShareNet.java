package com.fileman.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.SocketFactory;

final class ShareNet {
    private ShareNet() {
    }

    static final class Hotspot {
        interface Cb {
            void started();

            void failed(int reason);
        }

        private WifiManager.LocalOnlyHotspotReservation res;
        String ssid = "";
        String pass = "";
        int sec = 1;
        volatile boolean active;

        @SuppressLint("MissingPermission")
        void start(Context c, final Cb cb) {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                cb.failed(-1);
                return;
            }
            try {
                wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                    @Override
                    public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                        res = r;
                        read(r);
                        active = true;
                        cb.started();
                    }

                    @Override
                    public void onStopped() {
                        active = false;
                    }

                    @Override
                    public void onFailed(int reason) {
                        active = false;
                        cb.failed(reason);
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (Throwable t) {
                cb.failed(-1);
            }
        }

        @SuppressWarnings("deprecation")
        private void read(WifiManager.LocalOnlyHotspotReservation r) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    SoftApConfiguration sc = r.getSoftApConfiguration();
                    String s = sc.getSsid();
                    String p = sc.getPassphrase();
                    ssid = s == null ? "" : s;
                    pass = p == null ? "" : p;
                    sec = sc.getSecurityType();
                } else {
                    WifiConfiguration wc = r.getWifiConfiguration();
                    ssid = strip(wc.SSID);
                    pass = strip(wc.preSharedKey);
                    sec = 1;
                }
            } catch (Throwable ignored) {
            }
        }

        void stop() {
            try {
                if (res != null) res.close();
            } catch (Throwable ignored) {
            }
            res = null;
            active = false;
        }
    }

    private static String strip(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    static List<String> localIps() {
        List<String> pref = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") || n.startsWith("tun")
                        || n.startsWith("dummy") || n.startsWith("clat")) continue;
                boolean ap = n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap") || n.equals("wlan1")
                        || n.startsWith("rndis") || n.startsWith("p2p") || n.startsWith("eth");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    (ap ? pref : other).add(ip);
                }
            }
        } catch (Throwable ignored) {
        }
        pref.addAll(other);
        return pref;
    }

    @SuppressWarnings("deprecation")
    static Network wifiNetwork(ConnectivityManager cm) {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static List<String> gateways(ConnectivityManager cm, Network n) {
        List<String> out = new ArrayList<>();
        if (n == null) return out;
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress g = r.getGateway();
                    if (g instanceof Inet4Address && !g.isAnyLocalAddress()) {
                        String ip = g.getHostAddress();
                        if (ip != null && !out.contains(ip)) out.add(ip);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static final class Joiner {
        interface Cb {
            void ok(SocketFactory f, List<String> extraIps);

            void fail();
        }

        private final Handler main = new Handler(Looper.getMainLooper());
        private ConnectivityManager cm;
        private ConnectivityManager.NetworkCallback netCb;
        private volatile boolean stopped;

        @SuppressWarnings("deprecation")
        void join(Context c, final ShareLink l, final Cb done) {
            final Context app = c.getApplicationContext();
            cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (l.ssid.isEmpty()) {
                Network w = wifiNetwork(cm);
                done.ok(w != null ? w.getSocketFactory() : SocketFactory.getDefault(), gateways(cm, w));
                return;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                WifiNetworkSpecifier.Builder sb = new WifiNetworkSpecifier.Builder().setSsid(l.ssid);
                if (!l.pass.isEmpty()) {
                    if (l.sec == 3) sb.setWpa3Passphrase(l.pass);
                    else sb.setWpa2Passphrase(l.pass);
                }
                NetworkRequest req = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(sb.build())
                        .build();
                netCb = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(final Network n) {
                        main.post(() -> {
                            if (!stopped) done.ok(n.getSocketFactory(), gateways(cm, n));
                        });
                    }

                    @Override
                    public void onUnavailable() {
                        main.post(() -> {
                            if (!stopped) done.fail();
                        });
                    }
                };
                try {
                    cm.requestNetwork(req, netCb, 45000);
                } catch (Throwable t) {
                    done.fail();
                }
                return;
            }
            final WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            new Thread(() -> {
                boolean ok = false;
                try {
                    if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
                    WifiConfiguration wc = new WifiConfiguration();
                    wc.SSID = "\"" + l.ssid + "\"";
                    if (l.pass.isEmpty()) wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    else wc.preSharedKey = "\"" + l.pass + "\"";
                    int id = wm.addNetwork(wc);
                    if (id == -1) {
                        List<WifiConfiguration> cfg = wm.getConfiguredNetworks();
                        if (cfg != null) for (WifiConfiguration w : cfg) if (wc.SSID.equals(w.SSID)) id = w.networkId;
                    }
                    if (id != -1) {
                        wm.disconnect();
                        wm.enableNetwork(id, true);
                        wm.reconnect();
                        for (int i = 0; i < 50 && !stopped && !ok; i++) {
                            Thread.sleep(500);
                            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                            if (wi != null && wc.SSID.equals(wi.getSSID()) && wifiNetwork(cm) != null) ok = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
                final boolean fok = ok;
                main.post(() -> {
                    if (stopped) return;
                    if (fok) {
                        Network w = wifiNetwork(cm);
                        done.ok(w != null ? w.getSocketFactory() : SocketFactory.getDefault(), gateways(cm, w));
                    } else {
                        done.fail();
                    }
                });
            }).start();
        }

        void release() {
            stopped = true;
            try {
                if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb);
            } catch (Throwable ignored) {
            }
            netCb = null;
        }
    }

    static final class Locks {
        private PowerManager.WakeLock wl;
        private WifiManager.WifiLock fl;

        @SuppressWarnings("deprecation")
        void acquire(Context c) {
            try {
                Context app = c.getApplicationContext();
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fileman:share");
                    wl.acquire(4 * 60 * 60 * 1000L);
                }
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    fl = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fileman:share");
                    fl.acquire();
                }
            } catch (Throwable ignored) {
            }
        }

        void release() {
            try {
                if (wl != null && wl.isHeld()) wl.release();
            } catch (Throwable ignored) {
            }
            try {
                if (fl != null && fl.isHeld()) fl.release();
            } catch (Throwable ignored) {
            }
            wl = null;
            fl = null;
        }
    }
}
