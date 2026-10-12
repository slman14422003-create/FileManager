package com.fileman.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pre-install safety check (installer beta 5). Looks at what the package asks for and how it is signed and
 * named, and turns it into a level (low risk / needs attention / high risk) with a plain explanation of each
 * finding. It never blocks by itself except for packages the user put on the block list.
 */
final class SecurityScan {
    static final int SAFE = 0, CAUTION = 1, HIGH = 2;

    static final class Finding {
        final int level;      // 0 informational / good, 1 caution, 2 high
        final int titleRes, bodyRes, arg;

        Finding(int level, int titleRes, int bodyRes, int arg) {
            this.level = level;
            this.titleRes = titleRes;
            this.bodyRes = bodyRes;
            this.arg = arg;
        }
    }

    static final class Result {
        int level = SAFE;
        int score;
        boolean blocked, trusted;
        final List<Finding> findings = new ArrayList<>();
    }

    private static final Pattern DISGUISED = Pattern.compile(
            "(?i).*\\.(pdf|docx?|xlsx?|pptx?|jpe?g|png|gif|webp|mp3|mp4|mkv|txt|zip|rar)\\.(apk|apks|xapk|apkm)$");

    private SecurityScan() {
    }

    private static boolean has(Set<String> p, String... names) {
        for (String n : names) if (p.contains(n)) return true;
        return false;
    }

    static Result scan(Context c, PkgInstaller.Info in) {
        Result r = new Result();
        Set<String> p = new HashSet<>();
        for (String s : in.perms) {
            p.add(s.startsWith("android.permission.") ? s.substring("android.permission.".length()) : s);
        }
        List<Finding> f = r.findings;

        if (Store.blockedPkgs(c).contains(in.pkg)) {
            r.blocked = true;
            f.add(new Finding(2, R.string.sec_f_blocked, R.string.sec_f_blocked_b, 0));
            r.score += 5;
        }
        File name = in.original != null ? in.original : in.source;
        if (name != null && DISGUISED.matcher(name.getName()).matches()) {
            f.add(new Finding(2, R.string.sec_f_name, R.string.sec_f_name_b, 0));
            r.score += 3;
        }
        if (in.installed && !in.sameCert) {
            f.add(new Finding(2, R.string.sec_f_key, R.string.sec_f_key_b, 0));
            r.score += 3;
        }
        if (has(p, "BIND_ACCESSIBILITY_SERVICE")) {
            f.add(new Finding(2, R.string.sec_f_access, R.string.sec_f_access_b, 0));
            r.score += 3;
        }
        if (has(p, "BIND_DEVICE_ADMIN")) {
            f.add(new Finding(2, R.string.sec_f_admin, R.string.sec_f_admin_b, 0));
            r.score += 3;
        }
        if (has(p, "SYSTEM_ALERT_WINDOW")) {
            f.add(new Finding(1, R.string.sec_f_overlay, R.string.sec_f_overlay_b, 0));
            r.score += 2;
        }
        if (has(p, "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH")) {
            f.add(new Finding(1, R.string.sec_f_sms, R.string.sec_f_sms_b, 0));
            r.score += 2;
        }
        if (has(p, "BIND_NOTIFICATION_LISTENER_SERVICE")) {
            f.add(new Finding(1, R.string.sec_f_notif, R.string.sec_f_notif_b, 0));
            r.score += 2;
        }
        if (has(p, "REQUEST_INSTALL_PACKAGES", "REQUEST_DELETE_PACKAGES", "INSTALL_PACKAGES")) {
            f.add(new Finding(1, R.string.sec_f_installer, R.string.sec_f_installer_b, 0));
            r.score += 2;
        }
        if (has(p, "RECORD_AUDIO") && has(p, "CAMERA") && has(p, "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION")) {
            f.add(new Finding(1, R.string.sec_f_spy, R.string.sec_f_spy_b, 0));
            r.score += 2;
        }
        if (has(p, "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS", "ANSWER_PHONE_CALLS")) {
            f.add(new Finding(1, R.string.sec_f_calls, R.string.sec_f_calls_b, 0));
            r.score += 1;
        }
        if (has(p, "READ_CONTACTS", "WRITE_CONTACTS")) {
            f.add(new Finding(1, R.string.sec_f_contacts, R.string.sec_f_contacts_b, 0));
            r.score += 1;
        }
        if (has(p, "MANAGE_EXTERNAL_STORAGE")) {
            f.add(new Finding(1, R.string.sec_f_files, R.string.sec_f_files_b, 0));
            r.score += 1;
        }
        if (has(p, "BIND_VPN_SERVICE")) {
            f.add(new Finding(1, R.string.sec_f_vpn, R.string.sec_f_vpn_b, 0));
            r.score += 1;
        }
        if (has(p, "PACKAGE_USAGE_STATS", "QUERY_ALL_PACKAGES")) {
            f.add(new Finding(1, R.string.sec_f_usage, R.string.sec_f_usage_b, 0));
            r.score += 1;
        }
        if (in.targetSdk > 0 && in.targetSdk < 23) {
            f.add(new Finding(2, R.string.sec_f_old, R.string.sec_f_old_b, in.targetSdk));
            r.score += 3;
        } else if (in.targetSdk > 0 && in.targetSdk < 28) {
            f.add(new Finding(1, R.string.sec_f_old, R.string.sec_f_old_b, in.targetSdk));
            r.score += 1;
        }
        if (in.debuggable) {
            f.add(new Finding(1, R.string.sec_f_debug, R.string.sec_f_debug_b, 0));
            r.score += 2;
        }
        Set<String> trusted = Store.trustedCerts(c);
        for (String cert : in.certs) {
            if (trusted.contains(cert)) {
                r.trusted = true;
                break;
            }
        }
        if (r.trusted) {
            f.add(new Finding(0, R.string.sec_f_trusted, R.string.sec_f_trusted_b, 0));
            r.score = Math.max(0, r.score - 2);
        }
        r.level = r.blocked || r.score >= 3 ? HIGH : r.score >= 1 ? CAUTION : SAFE;
        // worst findings first
        final Integer[] idx = new Integer[f.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        final List<Finding> src = new ArrayList<>(f);
        Arrays.sort(idx, (a, b) -> Integer.compare(src.get(b).level, src.get(a).level));
        f.clear();
        for (Integer i : idx) f.add(src.get(i));
        return r;
    }
}
