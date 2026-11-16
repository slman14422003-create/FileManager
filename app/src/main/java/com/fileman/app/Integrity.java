package com.fileman.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Release-build self check: refuses to run when the package was re-signed (certificate differs from the one pinned at
 * build time in BuildConfig.CERT_SHA256), was made debuggable, or has a debugger attached. A client-side check can be
 * patched out by a determined attacker, so it only raises the cost of repackaging; it is not a replacement for keeping
 * secrets off the device.
 */
final class Integrity {
    private Integrity() {
    }

    static boolean tampered(Context c) {
        try {
            if (BuildConfig.DEBUG) return false;
            if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true;
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;
            String want = BuildConfig.CERT_SHA256;
            if (want == null || want.trim().isEmpty()) return false;   // no certificate pinned in this build
            return !want.replace(":", "").trim().equalsIgnoreCase(certSha256(c));
        } catch (Throwable t) {
            return false;   // never lock a genuine install out because the check itself failed
        }
    }

    @SuppressWarnings("deprecation")
    private static String certSha256(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            sigs = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
        } else {
            sigs = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        }
        if (sigs == null || sigs.length != 1) return "multiple-or-none";
        byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString().toLowerCase(Locale.US);
    }

    /** Tells the person why, then ends the process. */
    static void reject(final Context c) {
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> Toast.makeText(c, R.string.integrity_fail, Toast.LENGTH_LONG).show());
        h.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 2500);
    }
}
