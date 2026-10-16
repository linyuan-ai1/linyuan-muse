package com.linyuan.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(a)) {
            android.util.Log.i("VC_TASK", "收到 " + a + "，重排定时任务");
            TaskScheduler.rescheduleAll(context.getApplicationContext());
            // 主助手的定时激活同样要重排（重启后闹钟会被系统清掉）
            try { AssistTimer.reschedule(context.getApplicationContext()); } catch (Exception ignored) { }
        }
    }
}
