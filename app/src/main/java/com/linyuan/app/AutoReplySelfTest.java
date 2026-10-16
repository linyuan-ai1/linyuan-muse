package com.linyuan.app;

import android.content.Context;

public class AutoReplySelfTest extends android.content.BroadcastReceiver {

    @Override
    public void onReceive(Context context, android.content.Intent intent) {
        final Context ctx = context.getApplicationContext();

        boolean debuggable;
        try {
            debuggable = (ctx.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            debuggable = false;
        }
        if (!debuggable) {
            android.util.Log.w("VC_SELFTEST", "自动回复自检只在 debug 构建可用，已忽略");
            return;
        }

        android.util.Log.i("VC_SELFTEST", "===== 自动回复链路自检（绕过时段/主对话/总开关）=====");
        android.util.Log.i("VC_SELFTEST", "无障碍=" + AutoReply.accessibilityGranted(ctx)
                + "  通知使用权=" + AutoReply.notifGranted(ctx));

        if (!AutoReply.tryAcquire("自检")) {
            android.util.Log.w("VC_SELFTEST", "已有 AI 在跑（或锁被占），本次自检跳过 —— 不复用不抢占");
            return;
        }
        try {
            AutoReplyService.start(ctx);
            android.util.Log.i("VC_SELFTEST", "已启动 AutoReplyService，接着看 VC_AUTOREPLY / VC_AGENT 日志");
        } catch (Throwable t) {
            AutoReply.release("自检启动失败：" + t.getMessage());
            android.util.Log.w("VC_SELFTEST", "启动失败: " + t);
        }
    }
}
