package com.linyuan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时激活主助手时的前台服务（保证熄屏/后台也能把请求跑完）。
 * 跟 TaskRunnerService 同构。
 */
public class AssistTimerService extends Service {

    private static final int NOTIFY_ID = 9402;
    private static final String CHANNEL_ID = "vc_assist_timer";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger(0);

    private android.os.PowerManager.WakeLock wake;

    private void acquireWake() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wake == null) {
                wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "linyuan:assist_timer");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(30 * 60_000L);
        } catch (Exception e) {
            android.util.Log.w("VC_ASSIST_T", "申请唤醒锁失败: " + e.getMessage());
        }
    }

    private void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
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
            android.util.Log.w("VC_ASSIST_T", "startForeground 失败，直接跑: " + e.getMessage());
            enqueue();
            return START_NOT_STICKY;
        }
        enqueue();
        return START_NOT_STICKY;
    }

    private void enqueue() {
        pending.incrementAndGet();
        try {
            exec.execute(() -> {
                acquireWake();
                try {
                    AssistAutoRunner.run(getApplicationContext(), "auto");
                } catch (Throwable t) {
                    android.util.Log.w("VC_ASSIST_T", "定时激活执行失败: " + t.getMessage());
                } finally {
                    if (pending.decrementAndGet() <= 0) {
                        releaseWake();
                        stopSelf();
                    }
                }
            });
        } catch (Exception e) {
            if (pending.decrementAndGet() <= 0) stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        releaseWake();
        exec.shutdownNow();
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
                    CHANNEL_ID, "主助手定时激活", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("定时激活主助手期间的临时通知，跑完自动消失");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("正在激活主助手")
                .setContentText("它正在自己处理这一步，跑完会自动结束")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
