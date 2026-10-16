package com.linyuan.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 定时激活到点了。丢给前台服务去跑（跑完自己算下一个）。
 */
public class AssistTimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AssistTimer.ACTION_FIRE.equals(intent.getAction())) return;
        android.util.Log.i("VC_ASSIST_T", "定时激活到点");
        final Context appCtx = context.getApplicationContext();
        Intent svc = new Intent(appCtx, AssistTimerService.class);
        try {
            appCtx.startForegroundService(svc);
        } catch (Exception e) {
            android.util.Log.w("VC_ASSIST_T", "起前台服务失败，退回 goAsync: " + e.getMessage());
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    AssistAutoRunner.run(appCtx, "auto");
                } catch (Throwable t) {
                    android.util.Log.w("VC_ASSIST_T", "执行失败: " + t.getMessage());
                } finally {
                    try { pr.finish(); } catch (Exception ignored) { }
                }
            }, "vc-assist-timer").start();
        }
    }
}
