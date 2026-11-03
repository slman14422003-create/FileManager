package com.fileman.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

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

    static boolean wifiEnabled(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm == null || wm.isWifiEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && wifiNetwork(cm) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static void openWifi(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 29) a.startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            else a.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        } catch (Throwable ignored) {
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    static void openTether(Activity a) {
        try {
            a.startActivity(new Intent("android.settings.TETHER_SETTINGS"));
            return;
        } catch (Throwable ignored) {
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    static int hotspotReasonText(int r) {
        switch (r) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return R.string.sx_hs_r1;
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return R.string.sx_hs_r3;
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return R.string.sx_hs_r4;
            default:
                return R.string.sx_hs_r2;
        }
    }

    static int p2pReasonText(int r) {
        if (r == WifiP2pManager.P2P_UNSUPPORTED) return R.string.sx_p2p_unsupported;
        if (r == WifiP2pManager.BUSY) return R.string.sx_p2p_busy;
        return R.string.sx_p2p_fail;
    }

    /** Wi-Fi Direct group owner: the other device joins it like a normal Wi-Fi network using the name and password. */
    static final class P2p {
        interface Cb {
            void started();

            void failed(int reason);
        }

        private final Handler main = new Handler(Looper.getMainLooper());
        private WifiP2pManager mgr;
        private WifiP2pManager.Channel ch;
        private volatile boolean stopped;
        String ssid = "";
        String pass = "";
        volatile boolean active;

        @SuppressLint("MissingPermission")
        void start(Context c, final Cb cb) {
            stopped = false;
            active = false;
            Context app = c.getApplicationContext();
            mgr = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
            if (mgr == null) {
                cb.failed(WifiP2pManager.P2P_UNSUPPORTED);
                return;
            }
            try {
                ch = mgr.initialize(app, Looper.getMainLooper(), null);
            } catch (Throwable t) {
                ch = null;
            }
            if (ch == null) {
                cb.failed(WifiP2pManager.ERROR);
                return;
            }
            try {
                mgr.removeGroup(ch, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        create(cb);
                    }

                    @Override
                    public void onFailure(int reason) {
                        create(cb);
                    }
                });
            } catch (Throwable t) {
                create(cb);
            }
        }

        @SuppressLint("MissingPermission")
        private void create(final Cb cb) {
            if (stopped || mgr == null || ch == null) return;
            WifiP2pManager.ActionListener l = new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    read(cb, 0);
                }

                @Override
                public void onFailure(int reason) {
                    active = false;
                    if (!stopped) cb.failed(reason);
                }
            };
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                            .setNetworkName("DIRECT-fm-" + ShareProto.newToken().substring(0, 4))
                            .setPassphrase(ShareProto.newToken().substring(0, 12))
                            .enablePersistentMode(false)
                            .build();
                    mgr.createGroup(ch, cfg, l);
                } else {
                    mgr.createGroup(ch, l);
                }
            } catch (Throwable t) {
                if (!stopped) cb.failed(WifiP2pManager.ERROR);
            }
        }

        @SuppressLint("MissingPermission")
        private void read(final Cb cb, final int n) {
            if (stopped || mgr == null || ch == null) return;
            try {
                mgr.requestGroupInfo(ch, group -> {
                    if (stopped) return;
                    if (group != null && group.getNetworkName() != null && group.getPassphrase() != null) {
                        ssid = group.getNetworkName();
                        pass = group.getPassphrase();
                        active = true;
                        cb.started();
                    } else if (n < 15) {
                        main.postDelayed(() -> read(cb, n + 1), 400);
                    } else {
                        cb.failed(WifiP2pManager.ERROR);
                    }
                });
            } catch (Throwable t) {
                cb.failed(WifiP2pManager.ERROR);
            }
        }

        @SuppressLint("MissingPermission")
        void stop() {
            stopped = true;
            active = false;
            final WifiP2pManager m = mgr;
            final WifiP2pManager.Channel c = ch;
            mgr = null;
            ch = null;
            if (m == null || c == null) return;
            try {
                m.removeGroup(c, null);
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= 27) {
                main.postDelayed(() -> {
                    try {
                        c.close();
                    } catch (Throwable ignored) {
                    }
                }, 1500);
            }
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
