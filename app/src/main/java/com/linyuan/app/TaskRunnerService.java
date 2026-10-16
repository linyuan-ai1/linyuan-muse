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

public class TaskRunnerService extends Service {

    public static final String EXTRA_TASK_ID = "task_id";
    private static final int NOTIFY_ID = 9301;
    private static final String CHANNEL_ID = "vc_task_runner";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger(0);

    private android.os.PowerManager.WakeLock taskWakeLock;

    private void acquireTaskWakeLock() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (taskWakeLock == null) {
                taskWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "linyuan:task_runner");
                taskWakeLock.setReferenceCounted(false);
            }
            if (!taskWakeLock.isHeld()) {
                taskWakeLock.acquire(30 * 60_000L);
                android.util.Log.i("VC_TASK", "已申请唤醒锁（熄屏也把任务跑完）");
            }
        } catch (Exception e) {
            android.util.Log.w("VC_TASK", "申请唤醒锁失败: " + e.getMessage());
        }
    }

    private void releaseTaskWakeLock() {
        try {
            if (taskWakeLock != null && taskWakeLock.isHeld()) taskWakeLock.release();
        } catch (Exception ignored) { }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long taskId = intent == null ? 0 : intent.getLongExtra(EXTRA_TASK_ID, 0);
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Exception e) {

            android.util.Log.w("VC_TASK", "startForeground 失败，直接执行: " + e.getMessage());
            if (taskId > 0) enqueue(taskId);
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (taskId <= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        enqueue(taskId);
        return START_NOT_STICKY;
    }

    private void enqueue(final long taskId) {
        pending.incrementAndGet();
        try {
            exec.execute(() -> {
                acquireTaskWakeLock();
                try {
                    TaskRunner.run(getApplicationContext(), taskId);
                } catch (Throwable t) {
                    android.util.Log.w("VC_TASK", "任务 " + taskId + " 执行失败: " + t.getMessage());
                } finally {
                    if (pending.decrementAndGet() <= 0) {
                        releaseTaskWakeLock();
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
        releaseTaskWakeLock();
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
                    CHANNEL_ID, "定时任务执行中", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("定时任务运行期间的临时通知，跑完自动消失");
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
                .setContentTitle("正在执行定时任务")
                .setContentText("跑完会自动结束")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
