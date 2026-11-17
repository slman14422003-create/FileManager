package com.fileman.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.CancellationSignal;
import android.widget.Toast;

/**
 * Optional lock in front of installing and uninstalling (installer beta 5): fingerprint / face or the screen
 * lock code. One successful check covers the next minute, so a batch of installs asks only once.
 */
final class AuthGate {
    private static final long WINDOW_MS = 60_000L;
    private static long okUntil;

    private AuthGate() {
    }

    static boolean isSecure(Context c) {
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isDeviceSecure();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean enabled(Context c) {
        return Store.instBool(c, "lock", false) && Build.VERSION.SDK_INT >= 28 && isSecure(c);
    }

    /** Runs ok now when no lock is needed, otherwise after the person has proved who they are. */
    static void require(final Activity a, final Runnable ok) {
        if (!enabled(a) || System.currentTimeMillis() < okUntil) {
            ok.run();
            return;
        }
        Prompt.show(a, ok);
    }

    private static final class Prompt {
        static void show(final Activity a, final Runnable ok) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b =
                        new android.hardware.biometrics.BiometricPrompt.Builder(a)
                                .setTitle(a.getString(R.string.sec_auth_title))
                                .setSubtitle(a.getString(R.string.sec_auth_sub));
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                    | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else {
                    b.setNegativeButton(a.getString(R.string.cancel), a.getMainExecutor(), (d, w) -> { });
                }
                b.build().authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(
                                    android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                                okUntil = System.currentTimeMillis() + WINDOW_MS;
                                ok.run();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                // 11 = nothing enrolled, 12 = no hardware: the lock cannot be asked for on this phone
                                if (code == 11 || code == 12) {
                                    ok.run();
                                } else if (code != 10 && code != 13) {   // user cancelled / negative button
                                    Toast.makeText(a, String.valueOf(msg), Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
            } catch (Throwable t) {
                ok.run();
            }
        }
    }
}
