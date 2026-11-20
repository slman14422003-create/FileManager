package com.fileman.app;

import android.content.Context;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Looks for signs that the phone is rooted or that something is hooking into the app while the vault is open. */
final class Hostile {
    private static final String[] SU = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sd/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su", "/system/app/Superuser.apk",
            "/data/adb/magisk", "/sbin/.magisk", "/data/adb/ksu"};

    private Hostile() {
    }

    /** Active tampering: a debugger or an injection framework inside this process. The vault refuses to stay open. */
    static boolean hooking() {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String l;
                int n = 0;
                while ((l = r.readLine()) != null && n++ < 20000) {
                    String s = l.toLowerCase(java.util.Locale.ROOT);
                    if (s.contains("frida") || s.contains("gum-js") || s.contains("xposed") || s.contains("substrate")
                            || s.contains("lsposed")) {
                        return true;
                    }
                }
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", 27042), 60);
                return true;
            } catch (Exception closed) {
                // nothing listening: good
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** The phone has root: someone with access to it could read memory while the vault is open. */
    static boolean rooted(Context c) {
        try {
            for (String p : SU) if (new File(p).exists()) return true;
            String tags = Build.TAGS;
            return tags != null && tags.contains("test-keys");
        } catch (Throwable t) {
            return false;
        }
    }
}
