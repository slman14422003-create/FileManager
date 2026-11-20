package com.fileman.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** Ways to erase the vault when the phone is not in your hands: a secret SMS, and a dead-man timer. */
final class Remote {
    private static final String PREFS = "remote";
    private static final int JOB = 4711;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private Remote() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ secret SMS

    static boolean smsOn(Context c) {
        return sp(c).getString("sms_hash", null) != null;
    }

    /** Creates a new secret command, stores only its hash and returns the text to keep somewhere safe. */
    static String smsEnable(Context c) {
        StringBuilder sb = new StringBuilder("FMWIPE-");
        for (int i = 0; i < 24; i++) {
            if (i > 0 && i % 6 == 0) sb.append('-');
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        String cmd = sb.toString();
        byte[] salt = Pass.salt();
        sp(c).edit().putString("sms_salt", Pass.b64(salt)).putString("sms_hash", Pass.b64(hash(cmd, salt))).apply();
        return cmd;
    }

    static void smsDisable(Context c) {
        sp(c).edit().remove("sms_salt").remove("sms_hash").apply();
    }

    private static byte[] hash(String cmd, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(cmd.trim().toUpperCase(java.util.Locale.ROOT).getBytes("UTF-8"));
            return md.digest();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static boolean smsMatches(Context c, String body) {
        if (body == null || !smsOn(c)) return false;
        SharedPreferences p = sp(c);
        byte[] salt = Pass.unb64(p.getString("sms_salt", null));
        byte[] want = Pass.unb64(p.getString("sms_hash", null));
        for (String part : body.split("\\s+")) {
            if (part.startsWith("FMWIPE-") && Pass.same(want, hash(part, salt))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ dead-man timer

    static int deadDays(Context c) {
        return sp(c).getInt("dead_days", 0);
    }

    static void setDeadDays(Context c, int days) {
        sp(c).edit().putInt("dead_days", days).putLong("last_in", System.currentTimeMillis()).apply();
        schedule(c);
    }

    /** Proof that the owner is still around: any successful vault or app unlock. */
    static void checkin(Context c) {
        SharedPreferences p = sp(c);
        if (p.getInt("dead_days", 0) == 0) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("last_in", 0) > 60_000L) p.edit().putLong("last_in", now).apply();
    }

    /** True when the timer is on and the owner has been away longer than allowed. */
    static boolean overdue(Context c) {
        SharedPreferences p = sp(c);
        int d = p.getInt("dead_days", 0);
        if (d <= 0) return false;
        long last = p.getLong("last_in", 0);
        if (last == 0) return false;
        return System.currentTimeMillis() - last > d * 86_400_000L;
    }

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (deadDays(c) <= 0) {
                js.cancel(JOB);
                return;
            }
            JobInfo info = new JobInfo.Builder(JOB, new ComponentName(c, DeadManJob.class))
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Throwable ignored) {
        }
    }
}
