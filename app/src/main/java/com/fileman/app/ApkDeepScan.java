package com.fileman.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ApkDeepScan {
    static final int LAYERS = 5;

    static final class Hit {
        final int level, score, titleRes, bodyRes, arg;
        final String detail;

        Hit(int level, int score, int titleRes, int bodyRes, int arg, String detail) {
            this.level = level;
            this.score = score;
            this.titleRes = titleRes;
            this.bodyRes = bodyRes;
            this.arg = arg;
            this.detail = detail;
        }
    }

    private static final long DEX_TOTAL_CAP = 192L << 20;
    private static final long TIME_CAP_MS = 25_000;
    private static final int CHUNK = 1 << 20;
    private static final int OVERLAP = 256;

    private static final String[] MINER = {"stratum+tcp://", "stratum+ssl://", "coinhive", "coin-hive", "xmrig",
            "cryptonight", "minero.cc", "webminerpool"};
    private static final String[] RAT = {"Lcom/metasploit/stage/", "ahmyth", "AhMyth", "droidjack", "DroidJack",
            "spynote", "SpyNote", "Lcom/android/ahmyth"};
    private static final String[] C2 = {"api.telegram.org/bot", "discord.com/api/webhooks",
            "discordapp.com/api/webhooks", "pastebin.com/raw", "ngrok.io", "serveo.net", "duckdns.org",
            "no-ip.org", "no-ip.biz", "ddns.net", "000webhostapp.com", "hopto.org"};
    private static final String[] TLS = {"ALLOW_ALL_HOSTNAME_VERIFIER", "AllowAllHostnameVerifier"};
    private static final String[] ECB = {"AES/ECB", "DES/ECB", "DESede/ECB", "DES/CBC"};
    private static final String[] FILE_ACCESS = {"setAllowUniversalAccessFromFileURLs", "setAllowFileAccessFromFileURLs"};
    private static final String[] PACKERS = {"libjiagu", "libsecexe", "libsecmain", "libDexHelper", "libprotectClass",
            "libchaosvmp", "libmobisecy", "libnqshield", "libegis", "libddog", "libfdog", "libapkprotect",
            "libbaiduprotect", "libdpt", "libreveny", "libexecmain", "libmegjb", "libtup", "libshell-super"};

    private static final Pattern IP_URL = Pattern.compile("https?://(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?[^\\s\"'<>]{0,40}");
    private static final Pattern HTTP_URL = Pattern.compile("http://[A-Za-z0-9][A-Za-z0-9.\\-]{3,60}");
    private static final Pattern SECRET = Pattern.compile(
            "AKIA[0-9A-Z]{16}|-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----|xox[baprs]-[0-9A-Za-z\\-]{10,}|ghp_[0-9A-Za-z]{36}");
    private static final String[] HTTP_IGNORE = {"schemas.android.com", "www.w3.org", "xmlpull.org", "localhost",
            "ns.adobe.com", "apache.org", "schemas.", "java.sun.com", "purl.org", "www.example", "example.com",
            "www.google.com", "maven.apache", "xml.org", "json.org", "ietf.org", "iana.org", "relaxng.org",
            "www.openssl", "creativecommons", "xmlsoap.org", "java.com", "dublincore"};

    private static final String[][] BRANDS = {
            {"whatsapp", "com.whatsapp"}, {"telegram", "org.telegram"}, {"facebook", "com.facebook"},
            {"instagram", "com.instagram"}, {"youtube", "com.google.android.youtube"},
            {"tiktok", "com.zhiliaoapp"}, {"snapchat", "com.snapchat"}, {"gmail", "com.google.android.gm"},
            {"paypal", "com.paypal"}, {"netflix", "com.netflix"}, {"signal", "org.thoughtcrime"},
            {"messenger", "com.facebook.orca"}, {"chrome", "com.android.chrome"}};

    private ApkDeepScan() {
    }

    private static final class Ctx {
        final List<Hit> hits = new ArrayList<>();
        boolean dexLoad, payloadDex, smsManager, hasSendSms;
        int minSdk;
    }

    static void run(Context c, File apk, PackageInfo pi, PkgInstaller.Info in) {
        Ctx x = new Ctx();
        x.minSdk = in.minSdk;
        x.hasSendSms = in.perms.contains("android.permission.SEND_SMS");
        try {
            layerSignature(apk, pi, x);
        } catch (Throwable ignored) {
        }
        try {
            layerArchive(apk, x);
        } catch (Throwable ignored) {
        }
        try {
            layerManifest(c, pi, in, x);
        } catch (Throwable ignored) {
        }
        try {
            layerPayload(apk, x);
        } catch (Throwable ignored) {
        }
        try {
            layerCode(apk, x);
        } catch (Throwable ignored) {
        }
        finish(x);
        in.deep.addAll(x.hits);
        in.layers = LAYERS;
    }

    private static void add(Ctx x, int level, int score, int title, int body, int arg, String detail) {
        x.hits.add(new Hit(level, score, title, body, arg, detail));
    }

    private static String join(Set<String> s, int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String v : s) {
            if (i++ >= max) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ layer 1: signature

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo si = pi.signingInfo;
            if (si == null) return null;
            return si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        }
        return pi.signatures;
    }

    private static void layerSignature(File apk, PackageInfo pi, Ctx x) throws Exception {
        Signature[] sigs = signers(pi);
        if (sigs != null) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Date now = new Date();
            for (Signature s : sigs) {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(s.toByteArray()));
                String alg = cert.getSigAlgName().toUpperCase(Locale.ROOT);
                String subject = cert.getSubjectX500Principal().getName();
                String lower = subject.toLowerCase(Locale.ROOT);
                if (alg.contains("MD5") || alg.contains("MD2")) {
                    add(x, 2, 3, R.string.sec_d_alg, R.string.sec_d_alg_b, 0, alg);
                } else if (alg.contains("SHA1")) {
                    add(x, 1, 0, R.string.sec_d_alg, R.string.sec_d_alg_b, 0, alg);
                }
                PublicKey key = cert.getPublicKey();
                int bits = 0;
                if (key instanceof RSAPublicKey) bits = ((RSAPublicKey) key).getModulus().bitLength();
                else if (key instanceof ECPublicKey) bits = ((ECPublicKey) key).getParams().getOrder().bitLength();
                boolean ec = key instanceof ECPublicKey;
                if (bits > 0 && ((!ec && bits < 1024) || (ec && bits < 200))) {
                    add(x, 2, 3, R.string.sec_d_keysize, R.string.sec_d_keysize_b, 0, bits + " bit");
                } else if (bits > 0 && !ec && bits < 2048) {
                    add(x, 1, 1, R.string.sec_d_keysize, R.string.sec_d_keysize_b, 0, bits + " bit");
                }
                if (cert.getNotAfter().before(now)) {
                    add(x, 1, 1, R.string.sec_d_expired, R.string.sec_d_expired_b, 0, cert.getNotAfter().toString());
                }
                if (lower.contains("android debug")) {
                    add(x, 1, 2, R.string.sec_d_debugkey, R.string.sec_d_debugkey_b, 0, null);
                } else if (lower.contains("android@android.com") || (lower.contains("o=android") && lower.contains("cn=android"))) {
                    add(x, 2, 3, R.string.sec_d_testkey, R.string.sec_d_testkey_b, 0, null);
                }
            }
        }
        Set<Integer> ids = signingBlockIds(apk);
        boolean v2 = ids.contains(0x7109871a) || ids.contains(0xf05368c0) || ids.contains(0x1b93ad61);
        if (!v2) {
            add(x, 1, 1, R.string.sec_d_v1, R.string.sec_d_v1_b, 0, null);
        }
    }

    private static Set<Integer> signingBlockIds(File f) {
        Set<Integer> ids = new HashSet<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long searchFrom = Math.max(0, len - 65557);
            byte[] tail = new byte[(int) (len - searchFrom)];
            r.seek(searchFrom);
            r.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 5 && tail[i + 3] == 6) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return ids;
            long cdOffset = le32(tail, eocd + 16);
            if (cdOffset < 32 || cdOffset > len) return ids;
            byte[] foot = new byte[24];
            r.seek(cdOffset - 24);
            r.readFully(foot);
            if (!"APK Sig Block 42".equals(new String(foot, 8, 16, StandardCharsets.ISO_8859_1))) return ids;
            long size = le64(foot, 0);
            long start = cdOffset - size - 8;
            if (size < 24 || size > (64L << 20) || start < 0) return ids;
            byte[] block = new byte[(int) (size - 16)];
            r.seek(start + 8);
            r.readFully(block);
            int p = 0;
            while (p + 12 <= block.length) {
                long pairLen = le64(block, p);
                if (pairLen < 4 || p + 8 + pairLen > block.length) break;
                ids.add((int) le32(block, p + 8));
                p += 8 + (int) pairLen;
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long le64(byte[] b, int o) {
        return le32(b, o) | (le32(b, o + 4) << 32);
    }

    // ------------------------------------------------------------------ layer 2: archive integrity

    private static void layerArchive(File apk, Ctx x) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(apk, "r")) {
            byte[] m = new byte[8];
            int n = r.read(m);
            if (n >= 4 && m[0] == 'd' && m[1] == 'e' && m[2] == 'x' && m[3] == '\n') {
                add(x, 2, 4, R.string.sec_d_janus, R.string.sec_d_janus_b, 0, null);
            }
        }
        try (ZipReader z = ZipReader.open(apk)) {
            Set<String> seen = new HashSet<>();
            Set<String> dupes = new LinkedHashSet<>();
            Set<String> bad = new LinkedHashSet<>();
            int encrypted = 0;
            long total = 0;
            boolean bomb = false;
            for (ZipReader.Entry e : z.entries()) {
                String name = e.name;
                if (!seen.add(name)) dupes.add(name);
                if (name.startsWith("/") || name.equals("..") || name.startsWith("../") || name.contains("/../")
                        || name.endsWith("/..")) bad.add(name);
                if (e.encrypted()) encrypted++;
                total += Math.max(0, e.size);
                if (e.size > (256L << 20) && e.packed > 0 && e.size / e.packed > 1000) bomb = true;
            }
            if (!dupes.isEmpty()) add(x, 2, 3, R.string.sec_d_dupe, R.string.sec_d_dupe_b, 0, join(dupes, 3));
            if (!bad.isEmpty()) add(x, 2, 3, R.string.sec_d_trav, R.string.sec_d_trav_b, 0, join(bad, 3));
            if (encrypted > 0) add(x, 1, 2, R.string.sec_d_enc, R.string.sec_d_enc_b, 0, encrypted + "");
            if (bomb || total > (3L << 30)) add(x, 1, 1, R.string.sec_d_bomb, R.string.sec_d_bomb_b, 0, null);
        }
    }

    // ------------------------------------------------------------------ layer 3: manifest and components

    private static void layerManifest(Context c, PackageInfo pi, PkgInstaller.Info in, Ctx x) {
        ApplicationInfo ai = pi.applicationInfo;
        if (ai != null) {
            if ((ai.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0) {
                add(x, 1, 1, R.string.sec_d_testonly, R.string.sec_d_testonly_b, 0, null);
            }
            if ((ai.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
                add(x, 1, 0, R.string.sec_d_backup, R.string.sec_d_backup_b, 0, null);
            }
            if ((ai.flags & (1 << 27)) != 0 && in.targetSdk >= 28) {
                add(x, 1, 0, R.string.sec_d_clear, R.string.sec_d_clear_b, 0, null);
            }
        }
        if (pi.sharedUserId != null && !pi.sharedUserId.isEmpty()) {
            boolean sys = pi.sharedUserId.startsWith("android.uid.");
            add(x, sys ? 2 : 1, sys ? 3 : 1, R.string.sec_d_uid, R.string.sec_d_uid_b, 0, pi.sharedUserId);
        }
        int openServices = 0;
        if (pi.services != null) {
            for (ServiceInfo s : pi.services) {
                if (s.exported && s.permission == null) openServices++;
            }
        }
        if (openServices > 0) add(x, 1, 1, R.string.sec_d_svc, R.string.sec_d_svc_b, openServices, null);
        Set<String> openProviders = new LinkedHashSet<>();
        if (pi.providers != null) {
            for (ProviderInfo p : pi.providers) {
                if (p.exported && p.permission == null && p.readPermission == null && p.writePermission == null
                        && (p.authority == null || !p.authority.endsWith(".androidx-startup"))) {
                    openProviders.add(p.name == null ? "?" : p.name.substring(p.name.lastIndexOf('.') + 1));
                }
            }
        }
        if (!openProviders.isEmpty()) {
            add(x, 1, 2, R.string.sec_d_prov, R.string.sec_d_prov_b, 0, join(openProviders, 3));
        }
        if (pi.activities != null) {
            Set<String> hijack = new LinkedHashSet<>();
            for (ActivityInfo a : pi.activities) {
                String aff = a.taskAffinity;
                if (aff != null && !aff.isEmpty() && !aff.startsWith(in.pkg)
                        && (a.flags & ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0) {
                    hijack.add(aff);
                }
            }
            if (!hijack.isEmpty()) add(x, 2, 2, R.string.sec_d_hijack, R.string.sec_d_hijack_b, 0, join(hijack, 3));
        }
        String pkg = in.pkg == null ? "" : in.pkg;
        if (!in.installed && (pkg.startsWith("com.android.") || pkg.startsWith("android.")
                || pkg.startsWith("com.google.android.gms") || pkg.startsWith("com.google.android.gsf"))) {
            add(x, 1, 2, R.string.sec_d_sys, R.string.sec_d_sys_b, 0, pkg);
        }
        String label = in.label == null ? "" : in.label.toLowerCase(Locale.ROOT);
        for (String[] b : BRANDS) {
            if (label.contains(b[0]) && !pkg.startsWith(b[1])) {
                add(x, 1, 2, R.string.sec_d_mimic, R.string.sec_d_mimic_b, 0, in.label);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ layer 4: hidden payloads and native code

    private static void layerPayload(File apk, Ctx x) throws Exception {
        try (ZipReader z = ZipReader.open(apk)) {
            Set<String> dex = new LinkedHashSet<>();
            Set<String> elf = new LinkedHashSet<>();
            Set<String> packers = new LinkedHashSet<>();
            Set<String> frida = new LinkedHashSet<>();
            int probed = 0;
            for (ZipReader.Entry e : z.entries()) {
                if (e.dir) continue;
                String name = e.name;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("lib/")) {
                    String base = lower.substring(lower.lastIndexOf('/') + 1);
                    if (base.contains("frida") || base.contains("gadget")) frida.add(base);
                    for (String p : PACKERS) if (base.startsWith(p.toLowerCase(Locale.ROOT))) packers.add(base);
                    continue;
                }
                boolean asset = lower.startsWith("assets/") || lower.startsWith("res/raw/") || lower.startsWith("res/xml/");
                if (!asset) continue;
                if (lower.endsWith(".dex") || lower.endsWith(".jar") || lower.endsWith(".odex") || lower.endsWith(".apk")) {
                    dex.add(name);
                    continue;
                }
                if (probed >= 600 || e.size < 64 || e.encrypted()) continue;
                probed++;
                byte[] h = new byte[8];
                int n = 0;
                try (InputStream in = z.open(e)) {
                    n = in.read(h);
                } catch (Exception ignored) {
                }
                if (n >= 4) {
                    if (h[0] == 'd' && h[1] == 'e' && h[2] == 'x' && h[3] == '\n') dex.add(name);
                    else if (h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') elf.add(name);
                }
            }
            if (!dex.isEmpty()) x.payloadDex = true;
            if (!elf.isEmpty()) add(x, 1, 2, R.string.sec_d_elf, R.string.sec_d_elf_b, 0, join(elf, 3));
            if (!packers.isEmpty()) add(x, 1, 1, R.string.sec_d_packer, R.string.sec_d_packer_b, 0, join(packers, 3));
            if (!frida.isEmpty()) add(x, 2, 3, R.string.sec_d_frida, R.string.sec_d_frida_b, 0, join(frida, 3));
            if (x.payloadDex) {
                x.hits.add(new Hit(1, 2, R.string.sec_d_payload, R.string.sec_d_payload_b, 0, join(dex, 3)));
            }
        }
    }

    // ------------------------------------------------------------------ layer 5: code (DEX strings)

    private static final class Found {
        final Set<String> miner = new LinkedHashSet<>(), rat = new LinkedHashSet<>(), c2 = new LinkedHashSet<>(),
                tls = new LinkedHashSet<>(), ecb = new LinkedHashSet<>(), fileAccess = new LinkedHashSet<>(),
                ips = new LinkedHashSet<>(), http = new LinkedHashSet<>(), secrets = new LinkedHashSet<>();
        boolean jsInterface, stetho;
    }

    private static void layerCode(File apk, Ctx x) throws Exception {
        Found f = new Found();
        long start = System.currentTimeMillis();
        long budget = DEX_TOTAL_CAP;
        boolean partial = false;
        try (ZipReader z = ZipReader.open(apk)) {
            for (ZipReader.Entry e : z.entries()) {
                if (e.dir || !e.name.matches("classes\\d*\\.dex")) continue;
                if (budget <= 0 || System.currentTimeMillis() - start > TIME_CAP_MS) {
                    partial = true;
                    break;
                }
                try (InputStream in = z.open(e)) {
                    byte[] buf = new byte[CHUNK + OVERLAP];
                    int carry = 0;
                    while (budget > 0) {
                        int n = 0;
                        while (n < CHUNK) {
                            int r = in.read(buf, carry + n, CHUNK - n);
                            if (r < 0) break;
                            n += r;
                        }
                        if (n <= 0) break;
                        budget -= n;
                        String s = new String(buf, 0, carry + n, StandardCharsets.ISO_8859_1);
                        scanChunk(s, f, x);
                        int total = carry + n;
                        int keep = Math.min(OVERLAP, total);
                        System.arraycopy(buf, total - keep, buf, 0, keep);
                        carry = keep;
                        if (n < CHUNK) break;
                        if (System.currentTimeMillis() - start > TIME_CAP_MS) {
                            partial = true;
                            break;
                        }
                    }
                    if (budget <= 0) partial = true;
                }
            }
        }
        if (!f.miner.isEmpty()) add(x, 2, 3, R.string.sec_d_miner, R.string.sec_d_miner_b, 0, join(f.miner, 3));
        if (!f.rat.isEmpty()) add(x, 2, 4, R.string.sec_d_rat, R.string.sec_d_rat_b, 0, join(f.rat, 3));
        if (!f.c2.isEmpty()) add(x, 1, 1, R.string.sec_d_c2, R.string.sec_d_c2_b, 0, join(f.c2, 4));
        if (!f.ips.isEmpty()) add(x, 1, 1, R.string.sec_d_ip, R.string.sec_d_ip_b, 0, join(f.ips, 3));
        if (!f.tls.isEmpty()) add(x, 1, 1, R.string.sec_d_tls, R.string.sec_d_tls_b, 0, null);
        boolean oldJs = f.jsInterface && x.minSdk > 0 && x.minSdk < 17;
        if (oldJs || !f.fileAccess.isEmpty()) {
            add(x, 1, 1, R.string.sec_d_webview, R.string.sec_d_webview_b, 0,
                    oldJs ? "addJavascriptInterface" : join(f.fileAccess, 2));
        }
        if (!f.ecb.isEmpty()) add(x, 1, 0, R.string.sec_d_ecb, R.string.sec_d_ecb_b, 0, join(f.ecb, 3));
        if (f.stetho) add(x, 1, 1, R.string.sec_d_stetho, R.string.sec_d_stetho_b, 0, null);
        if (!f.secrets.isEmpty()) {
            boolean key = false;
            for (String s : f.secrets) if (s.startsWith("-----")) key = true;
            add(x, 1, key ? 2 : 1, R.string.sec_d_secret, R.string.sec_d_secret_b, 0, join(f.secrets, 2));
        }
        if (!f.http.isEmpty()) add(x, 1, 0, R.string.sec_d_http, R.string.sec_d_http_b, 0, join(f.http, 3));
        if (x.smsManager && x.hasSendSms) add(x, 1, 1, R.string.sec_d_sms, R.string.sec_d_sms_b, 0, null);
        if (partial) add(x, 1, 0, R.string.sec_d_partial, R.string.sec_d_partial_b, 0, null);
    }

    private static void collect(String s, String[] keys, Set<String> out) {
        for (String k : keys) if (s.contains(k)) out.add(k);
    }

    private static boolean privateIp(String url) {
        Matcher m = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}").matcher(url);
        if (!m.find()) return true;
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        return a == 10 || a == 127 || a == 0 || a >= 224 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31)
                || (a == 169 && b == 254) || a > 255 || b > 255;
    }

    private static void scanChunk(String s, Found f, Ctx x) {
        if (s.contains("Ldalvik/system/DexClassLoader;") || s.contains("Ldalvik/system/InMemoryDexClassLoader;")) {
            x.dexLoad = true;
        }
        if (s.contains("Landroid/telephony/SmsManager;")) x.smsManager = true;
        collect(s, MINER, f.miner);
        collect(s, RAT, f.rat);
        collect(s, C2, f.c2);
        collect(s, TLS, f.tls);
        collect(s, ECB, f.ecb);
        collect(s, FILE_ACCESS, f.fileAccess);
        if (s.contains("addJavascriptInterface")) f.jsInterface = true;
        if (s.contains("Lcom/facebook/stetho/")) f.stetho = true;
        Matcher m = IP_URL.matcher(s);
        while (m.find() && f.ips.size() < 6) {
            String u = m.group();
            if (!privateIp(u)) f.ips.add(u);
        }
        m = HTTP_URL.matcher(s);
        while (m.find() && f.http.size() < 6) {
            String u = m.group();
            boolean skip = false;
            for (String ig : HTTP_IGNORE) if (u.contains(ig)) skip = true;
            if (!skip) f.http.add(u);
        }
        m = SECRET.matcher(s);
        while (m.find() && f.secrets.size() < 4) {
            String v = m.group();
            f.secrets.add(v.startsWith("-----") ? v : v.substring(0, Math.min(8, v.length())) + "…");
        }
    }

    private static void finish(Ctx x) {
        if (x.dexLoad) {
            int score = x.payloadDex ? 1 : 0;
            add(x, 1, score, R.string.sec_d_dexload, R.string.sec_d_dexload_b, 0, null);
        }
    }
}
