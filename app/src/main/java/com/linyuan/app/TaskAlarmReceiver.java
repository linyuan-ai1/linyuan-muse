package com.linyuan.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TaskAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TaskScheduler.ACTION_FIRE.equals(intent.getAction())) return;
        final long taskId = intent.getLongExtra(TaskScheduler.EXTRA_TASK_ID, 0);
        if (taskId <= 0) return;
        android.util.Log.i("VC_TASK", "定时任务触发 id=" + taskId);

        final Context appCtx = context.getApplicationContext();
        Intent svc = new Intent(appCtx, TaskRunnerService.class);
        svc.putExtra(TaskRunnerService.EXTRA_TASK_ID, taskId);
        try {

            appCtx.startForegroundService(svc);
        } catch (Exception e) {

            android.util.Log.w("VC_TASK", "startForegroundService 失败，退回 goAsync: " + e.getMessage());
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    TaskRunner.run(appCtx, taskId);
                } catch (Throwable t) {
                    android.util.Log.w("VC_TASK", "执行失败: " + t.getMessage());
                } finally {
                    try { pr.finish(); } catch (Exception ignored) { }
                }
            }, "vc-task-run").start();
        }
    }
}
