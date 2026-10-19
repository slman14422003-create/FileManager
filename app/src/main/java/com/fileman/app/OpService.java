package com.fileman.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/** Keeps long file operations alive in the background and shows their progress in the status bar. */
public final class OpService extends Service {
    private static final String CHANNEL = "ops";
    private static final int ID = 7001;
    private static final int ID_DONE = 7002;
    private static final String ACT_CANCEL = "com.fileman.app.OP_CANCEL";

    private static final Object LOCK = new Object();
    private static int depth;
    private static String title = "";
    private static String text = "";
    private static int pct = -1;
    private static volatile Runnable onCancel;
    private static long lastPost;
    private static Context app;

    static void begin(Context c, String t, Runnable cancel) {
        synchronized (LOCK) {
            app = c.getApplicationContext();
            depth++;
            title = t;
            text = "";
            pct = -1;
            onCancel = cancel;
            if (depth == 1) {
                try {
                    Intent i = new Intent(app, OpService.class);
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                    else app.startService(i);
                } catch (Throwable ignored) {
                }
            } else {
                post();
            }
        }
    }

    static void update(String t, int percent) {
        synchronized (LOCK) {
            if (depth == 0 || app == null) return;
            long now = System.currentTimeMillis();
            if (now - lastPost < 500 && percent < 100) return;
            lastPost = now;
            if (t != null) text = t;
            pct = percent;
            post();
        }
    }

    static void end(String doneText) {
        Context c;
        synchronized (LOCK) {
            c = app;
            if (depth > 0) depth--;
            if (depth > 0) {
                post();
                return;
            }
            onCancel = null;
        }
        if (c == null) return;
        try {
            c.stopService(new Intent(c, OpService.class));
        } catch (Throwable ignored) {
        }
        if (doneText != null) showDone(c, doneText);
    }

    private static void showDone(Context c, String msg) {
        try {
            if (!Notifs.allowed(c)) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            channel(nm, c);
            Notification n = new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_check_circle)
                    .setContentTitle(c.getString(R.string.app_name))
                    .setContentText(msg)
                    .setContentIntent(open(c))
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID_DONE, n);
        } catch (Throwable ignored) {
        }
    }

    private static void channel(NotificationManager nm, Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.op_channel),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private static PendingIntent open(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent();
        return PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification build(Context c) {
        Intent cancel = new Intent(c, OpService.class).setAction(ACT_CANCEL);
        PendingIntent pc = PendingIntent.getService(c, 2, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_folder)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(open(c))
                .addAction(0, c.getString(R.string.cancel), pc);
        if (pct >= 0) b.setProgress(100, Math.min(100, pct), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private static void post() {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build(app));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_CANCEL.equals(intent.getAction())) {
            Runnable r = onCancel;
            if (r != null) r.run();
            return START_NOT_STICKY;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) channel(nm, this);
            Notification n;
            synchronized (LOCK) {
                n = build(this);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID, n);
            }
        } catch (Throwable t) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
