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

/** Keeps the file-theft guard alive in the background (decoy watchers live only while this process does). */
public final class GuardService extends Service {
    static final String ACT_TRUST = "com.fileman.app.GUARD_TRUST";
    static final String EXTRA_PKG = "pkg";
    private static final String CH_LIVE = "guard_live";
    private static final int ID_LIVE = 7102;

    static void ensure(Context c) {
        try {
            Context app = c.getApplicationContext();
            if (!GuardCore.canRun(app)) return;
            Intent i = new Intent(app, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable ignored) {
        }
    }

    static void halt(Context c) {
        try {
            GuardCore.stop();
            c.getApplicationContext().stopService(new Intent(c.getApplicationContext(), GuardService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_TRUST.equals(intent.getAction())) {
            GuardCore.trust(this, intent.getStringExtra(EXTRA_PKG), true);
            GuardCore.clearAlert(this);
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CH_LIVE, getString(R.string.g_channel_live),
                        NotificationManager.IMPORTANCE_MIN));
            }
            PendingIntent po = PendingIntent.getActivity(this, 33, new Intent(this, GuardActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(this, CH_LIVE)
                    .setSmallIcon(R.drawable.ic_lock_fill)
                    .setContentTitle(getString(R.string.g_live_title))
                    .setContentText(getString(R.string.g_live_text))
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setContentIntent(po)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID_LIVE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID_LIVE, n);
            }
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!GuardCore.canRun(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        GuardCore.start(this);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
