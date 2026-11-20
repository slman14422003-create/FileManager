package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;

import javax.crypto.Cipher;

/** Fingerprint prompts: the platform prompt on Android 9+, the older fingerprint service before that. */
final class Bio {
    interface Result {
        void ok(Cipher cipher);

        void fail();
    }

    private Bio() {
    }

    @SuppressWarnings("deprecation")
    static boolean available(Context c) {
        try {
            if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return false;
            android.hardware.fingerprint.FingerprintManager fm =
                    (android.hardware.fingerprint.FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fingerprint only (no key involved). */
    static void confirm(Activity a, CharSequence title, CharSequence cancel, Result r) {
        authenticate(a, title, cancel, null, r);
    }

    /** Fingerprint bound to a key: the cipher only works after the finger is accepted. Needs Android 9+. */
    static boolean cryptoSupported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    static void authenticate(final Activity a, CharSequence title, CharSequence cancel, final Cipher cipher,
                             final Result r) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder pb =
                        new android.hardware.biometrics.BiometricPrompt.Builder(a)
                                .setTitle(title)
                                .setNegativeButton(cancel, a.getMainExecutor(), (d, w) -> r.fail());
                if (Build.VERSION.SDK_INT >= 29) pb.setConfirmationRequired(true);
                if (Build.VERSION.SDK_INT >= 30) {
                    pb.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
                }
                android.hardware.biometrics.BiometricPrompt p = pb.build();
                android.hardware.biometrics.BiometricPrompt.AuthenticationCallback cb =
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(
                                    android.hardware.biometrics.BiometricPrompt.AuthenticationResult res) {
                                Cipher c = null;
                                if (res.getCryptoObject() != null) c = res.getCryptoObject().getCipher();
                                r.ok(c);
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                r.fail();
                            }
                        };
                if (cipher != null) {
                    p.authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),
                            new CancellationSignal(), a.getMainExecutor(), cb);
                } else {
                    p.authenticate(new CancellationSignal(), a.getMainExecutor(), cb);
                }
                return;
            } catch (Throwable t) {
                r.fail();
                return;
            }
        }
        legacy(a, r);
    }

    @SuppressWarnings("deprecation")
    private static void legacy(Activity a, final Result r) {
        try {
            android.hardware.fingerprint.FingerprintManager fm =
                    (android.hardware.fingerprint.FingerprintManager) a.getSystemService(Context.FINGERPRINT_SERVICE);
            fm.authenticate(null, new CancellationSignal(), 0,
                    new android.hardware.fingerprint.FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.fingerprint.FingerprintManager.AuthenticationResult res) {
                            r.ok(null);
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            r.fail();
                        }
                    }, null);
        } catch (Throwable t) {
            r.fail();
        }
    }
}
