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
            final Handler h = new Handler(Looper.getMainLooper());
            final java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
            final Runnable timeout = () -> {
                if (answered.compareAndSet(false, true)) {
                    active = false;
                    cb.failed(-1);
                }
            };
            h.postDelayed(timeout, 15000);
            try {
                wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                    @Override
                    public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                        if (!answered.compareAndSet(false, true)) {
                            try {
                                r.close();
                            } catch (Throwable ignored) {
                            }
                            return;
                        }
                        h.removeCallbacks(timeout);
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
                        if (!answered.compareAndSet(false, true)) return;
                        h.removeCallbacks(timeout);
                        active = false;
                        cb.failed(reason);
                    }
                }, h);
            } catch (Throwable t) {
                if (answered.compareAndSet(false, true)) {
                    h.removeCallbacks(timeout);
                    cb.failed(-1);
                }
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

    static boolean sameSubnet(LinkProperties lp, List<String> ips) {
        if (lp == null || ips == null || ips.isEmpty()) return false;
        try {
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (!(a instanceof Inet4Address)) continue;
                byte[] mine = a.getAddress();
                int pl = Math.max(8, Math.min(30, la.getPrefixLength()));
                for (String ip : ips) {
                    byte[] o = rawV4(ip);
                    if (o != null && prefixEq(mine, o, pl)) return true;
                }
            }
            for (RouteInfo r : lp.getRoutes()) {
                InetAddress g = r.getGateway();
                if (g instanceof Inet4Address && ips.contains(g.getHostAddress())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static byte[] rawV4(String ip) {
        try {
            String[] p = ip.split("\\.");
            if (p.length != 4) return null;
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(p[i]);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean prefixEq(byte[] a, byte[] b, int bits) {
        for (int i = 0; i < 4 && bits > 0; i++) {
            int m = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
            if ((a[i] & m) != (b[i] & m)) return false;
            bits -= 8;
        }
        return true;
    }

    static boolean hasV4(ConnectivityManager cm, Network n) {
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return false;
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static List<String> subnetGuesses(ConnectivityManager cm, Network n) {
        List<String> out = new ArrayList<>();
        if (n == null) return out;
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return out;
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (!(a instanceof Inet4Address)) continue;
                byte[] b = a.getAddress();
                String base = (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + ".";
                String own = a.getHostAddress();
                for (String last : new String[]{"1", "254"}) {
                    String g = base + last;
                    if (!g.equals(own) && !out.contains(g)) out.add(g);
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

            void lost();
        }

        private static final long LIMIT = 60000L;

        private final Handler main = new Handler(Looper.getMainLooper());
        private final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        private ConnectivityManager cm;
        private ConnectivityManager.NetworkCallback netCb;
        private volatile boolean stopped;
        private volatile Network bound;
        private Context app;
        private ShareLink link;
        private Cb cb;
        private long startedAt;
        private Runnable watcher;

        SocketFactory factory() {
            Network n = bound;
            if (n == null && cm != null) n = wifiNetwork(cm);
            return n != null ? n.getSocketFactory() : SocketFactory.getDefault();
        }

        Network network() {
            Network n = bound;
            if (n == null && cm != null) n = wifiNetwork(cm);
            return n;
        }

        List<String> gateways() {
            List<String> out = new ArrayList<>();
            if (cm == null) return out;
            Network n = network();
            out.addAll(ShareNet.gateways(cm, n));
            for (String g : subnetGuesses(cm, n)) if (!out.contains(g)) out.add(g);
            return out;
        }

        private String ssidNow() {
            try {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                android.net.wifi.WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                return wi == null ? "" : strip(wi.getSSID());
            } catch (Throwable t) {
                return "";
            }
        }

        @SuppressWarnings("deprecation")
        private Network findOnAp() {
            try {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    if (sameSubnet(cm.getLinkProperties(n), link.ips)) return n;
                }
                if (!link.ssid.isEmpty() && link.ssid.equals(ssidNow())) return wifiNetwork(cm);
            } catch (Throwable ignored) {
            }
            return null;
        }

        private void settle(final Network n, final int tries) {
            if (stopped) return;
            if (n != null && !hasV4(cm, n) && tries < 14) {
                main.postDelayed(() -> settle(n, tries + 1), 500);
                return;
            }
            bound = n;
            List<String> extra = gateways();
            cb.ok(factory(), extra);
        }

        private void arrive(Network n) {
            if (stopped || !finished.compareAndSet(false, true)) return;
            stopWatcher();
            settle(n, 0);
        }

        private void giveUp() {
            if (stopped || !finished.compareAndSet(false, true)) return;
            stopWatcher();
            cb.fail();
        }

        private void stopWatcher() {
            if (watcher != null) main.removeCallbacks(watcher);
            watcher = null;
        }

        private void startWatcher() {
            stopWatcher();
            watcher = new Runnable() {
                @Override
                public void run() {
                    if (stopped || finished.get()) return;
                    Network n = findOnAp();
                    if (n != null) {
                        arrive(n);
                        return;
                    }
                    if (System.currentTimeMillis() - startedAt > LIMIT) {
                        giveUp();
                        return;
                    }
                    main.postDelayed(this, 600);
                }
            };
            main.postDelayed(watcher, 600);
        }

        boolean force() {
            if (stopped || cm == null) return false;
            Network n = findOnAp();
            if (n == null) n = wifiNetwork(cm);
            if (!finished.compareAndSet(false, true)) return false;
            stopWatcher();
            settle(n, 0);
            return true;
        }

        @SuppressWarnings("deprecation")
        void join(Context c, final ShareLink l, final Cb done) {
            release();
            stopped = false;
            finished.set(false);
            bound = null;
            app = c.getApplicationContext();
            cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            link = l;
            cb = done;
            startedAt = System.currentTimeMillis();
            if (l.ssid.isEmpty()) {
                arrive(wifiNetwork(cm));
                return;
            }
            Network now = findOnAp();
            if (now != null) {
                arrive(now);
                return;
            }
            startWatcher();
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
                        main.post(() -> arrive(n));
                    }

                    @Override
                    public void onUnavailable() {
                        main.post(() -> {
                            if (stopped || finished.get()) return;
                            Network n = findOnAp();
                            if (n != null) arrive(n);
                            else giveUp();
                        });
                    }

                    @Override
                    public void onLost(final Network n) {
                        main.post(() -> {
                            if (!stopped && finished.get() && n.equals(bound)) cb.lost();
                        });
                    }
                };
                try {
                    cm.requestNetwork(req, netCb, (int) LIMIT);
                } catch (Throwable t) {
                    if (findOnAp() == null) giveUp();
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
                        for (int i = 0; i < 100 && !stopped && !finished.get() && !ok; i++) {
                            Thread.sleep(500);
                            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                            if (wi != null && wc.SSID.equals(wi.getSSID()) && wifiNetwork(cm) != null) ok = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
                final boolean fok = ok;
                main.post(() -> {
                    if (stopped || finished.get()) return;
                    if (fok) arrive(wifiNetwork(cm));
                });
            }).start();
        }

        void release() {
            stopped = true;
            stopWatcher();
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
