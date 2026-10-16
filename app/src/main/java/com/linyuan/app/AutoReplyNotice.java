package com.linyuan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public final class AutoReplyNotice {

    private static final String PREFS = "auto_reply";
    private static final String K_BLOCKED_REASON = "blockedReason";
    private static final String K_BLOCKED_AT = "blockedAt";

    private static final String CHANNEL_ID = "vc_autoreply";
    private static final int NOTIFY_ID = 9310;

    private AutoReplyNotice() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void markBlocked(Context c, String reason) {
        if (c == null) return;
        try {
            long now = System.currentTimeMillis();
            SharedPreferences p = prefs(c);
            long last = p.getLong(K_BLOCKED_AT, 0);

            boolean shouldNotify = (now - last) > 10 * 60_000L;
            p.edit().putString(K_BLOCKED_REASON, reason).putLong(K_BLOCKED_AT, now).apply();
            if (shouldNotify) notifyBlocked(c, reason);
        } catch (Throwable t) {
            android.util.Log.w("VC_AUTOREPLY", "记录拦截失败: " + t);
        }
    }

    public static boolean hasPendingBlocked(Context c) {
        return prefs(c).getBoolean("pendingShown", false) == false
                && prefs(c).getString(K_BLOCKED_REASON, null) != null;
    }

    public static String pendingReason(Context c) {
        return prefs(c).getString(K_BLOCKED_REASON, null);
    }

    public static void clearBlocked(Context c) {
        prefs(c).edit().remove(K_BLOCKED_REASON).remove(K_BLOCKED_AT).putBoolean("pendingShown", false).apply();
    }

    private static void notifyBlocked(Context c, String reason) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "微信自动回复", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("自动回复相关的状态提醒（例如授权被系统撤销）");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, NOTIFY_ID, open, flags);

            String text = "微信来消息了，但我没法动手：" + reason + "。点这里去打开。";
            Notification n = new Notification.Builder(c, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⚠️ 自动回复没能生效")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTIFY_ID, n);
        } catch (Throwable t) {
            android.util.Log.w("VC_AUTOREPLY", "发提醒通知失败: " + t);
        }
    }
}
