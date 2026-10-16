package com.linyuan.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

public class TaskScheduler {

    public static final String ACTION_FIRE = "com.linyuan.app.TASK_FIRE";
    public static final String EXTRA_TASK_ID = "task_id";

    public static void schedule(Context ctx, AppDatabase.ScheduledTask t) {
        if (t == null || !t.enabled) return;
        long triggerAt = nextTriggerAt(t);
        if (triggerAt <= 0) return;

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = pendingIntent(ctx, t.id);

        int jitter = 0;
        if (t.scheduleType != 0) jitter = (int) (Math.random() * 60_000);

        try {
            if (canScheduleExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt + jitter, pi);
            } else {

                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt + jitter, pi);
            }
        } catch (SecurityException e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt + jitter, pi);
            } catch (Exception ignored) { }
        } catch (Exception e) {
            android.util.Log.w("VC_TASK", "排闹钟失败: " + e.getMessage());
        }
    }

    public static void cancel(Context ctx, long taskId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try { am.cancel(pendingIntent(ctx, taskId)); } catch (Exception ignored) { }
    }

    public static void rescheduleAll(Context ctx) {

        AppDatabase db = null;
        try {
            db = new AppDatabase(ctx);
            List<AppDatabase.ScheduledTask> tasks = db.listScheduledTasks(true);
            for (AppDatabase.ScheduledTask t : tasks) schedule(ctx, t);
            android.util.Log.i("VC_TASK", "已重排 " + tasks.size() + " 个定时任务");
        } catch (Exception e) {
            android.util.Log.w("VC_TASK", "重排失败: " + e.getMessage());
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) { }
        }
    }

    public static boolean canScheduleExact(AlarmManager am) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        try { return am.canScheduleExactAlarms(); } catch (Exception e) { return false; }
    }

    public static long nextTriggerAt(AppDatabase.ScheduledTask t) {
        long now = System.currentTimeMillis();
        if (t.scheduleType == 0) {
            return t.timeMillis > now ? t.timeMillis : 0;
        }
        int hour = (int) (t.timeMillis / 60);
        int minute = (int) (t.timeMillis % 60);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, hour)));
        c.set(Calendar.MINUTE, Math.max(0, Math.min(59, minute)));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        if (t.scheduleType == 1) {
            if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_YEAR, 1);
            return c.getTimeInMillis();
        }

        int target = t.dayOfWeek;
        int calTarget = (target % 7) + 1;
        c.set(Calendar.DAY_OF_WEEK, calTarget);
        if (c.getTimeInMillis() <= now) c.add(Calendar.WEEK_OF_YEAR, 1);

        c.set(Calendar.DAY_OF_WEEK, calTarget);
        c.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, hour)));
        c.set(Calendar.MINUTE, Math.max(0, Math.min(59, minute)));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.WEEK_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(Context ctx, long taskId) {
        Intent i = new Intent(ctx, TaskAlarmReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra(EXTRA_TASK_ID, taskId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, (int) (taskId % Integer.MAX_VALUE), i, flags);
    }
}
