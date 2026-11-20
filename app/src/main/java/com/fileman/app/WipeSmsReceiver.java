package com.fileman.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;

/** Erases the vault when a text message carrying the secret command arrives. Nothing else is ever read from messages. */
public class WipeSmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        try {
            if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            if (pdus == null) return;
            String format = intent.getStringExtra("format");
            StringBuilder body = new StringBuilder();
            for (Object o : pdus) {
                SmsMessage m = SmsMessage.createFromPdu((byte[]) o, format);
                if (m != null && m.getMessageBody() != null) body.append(m.getMessageBody());
            }
            if (!Remote.smsMatches(context, body.toString())) return;
            final Context app = context.getApplicationContext();
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    Vault.destroyAll(app, "secret text message");
                    Remote.smsDisable(app);
                } finally {
                    pr.finish();
                }
            }, "fm-smswipe").start();
        } catch (Throwable ignored) {
        }
    }
}
