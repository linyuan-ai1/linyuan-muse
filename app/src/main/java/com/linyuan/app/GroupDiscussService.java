package com.linyuan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class GroupDiscussService extends Service {

    private static final int NOTIFY_ID = 9401;
    private static final String CHANNEL_ID = "vc_group_discuss";

    public static void start(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, GroupDiscussService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {

            android.util.Log.w("VC_GM", "启动讨论保活服务失败: " + e.getMessage());
        }
    }

    public static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, GroupDiscussService.class));
        } catch (Exception ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Exception e) {
            android.util.Log.w("VC_GM", "讨论保活 startForeground 失败: " + e.getMessage());
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "群聊讨论进行中", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("群聊多角色讨论运行期间的保活通知，讨论结束自动消失");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("群聊讨论进行中")
                .setContentText("专家们在后台讨论，可放心锁屏；结束后自动停止")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
