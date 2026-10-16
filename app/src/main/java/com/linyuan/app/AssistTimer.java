package com.linyuan.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * 「定时激活主助手」的闹钟调度。
 *
 * <p>只有一个闹钟：永远对准最近的那个触发时刻（用户设的周期点 或 助手自己设的一次性点）。
 * 响过之后由 {@link AssistAutoRunner} 重新算下一个再排。
 *
 * <p>一次性时间点的语义（用户 2026-10-13 定）：响过即消失。所以在**真正开始跑**
 * 之前就把它从列表里摘掉 —— 这样即使模型请求失败重试，也不会再触发第二次。
 */
public final class AssistTimer {

    private AssistTimer() { }

    public static final String ACTION_FIRE = "com.linyuan.app.ASSIST_TIMER_FIRE";
    private static final int REQ_CODE = 9611;

    /** 重新算最近的一次并排上；没有待触发项就把闹钟撤掉。 */
    public static void reschedule(Context ctx) {
        try {
            long next = AssistFireTimes.next(System.currentTimeMillis(),
                    AssistTimerStore.enabled(ctx),
                    AssistTimerStore.times(ctx),
                    AssistTimerStore.daysRaw(ctx),
                    selfAts(ctx));
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pending(ctx);
            if (next <= 0) {
                try { am.cancel(pi); } catch (Exception ignored) { }
                android.util.Log.i("VC_ASSIST_T", "没有待触发的定时激活，闹钟已撤");
                return;
            }
            try {
                if (TaskScheduler.canScheduleExact(am)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                }
                android.util.Log.i("VC_ASSIST_T", "下次定时激活：" + AiClient.nowText()
                        + " 起 " + ((next - System.currentTimeMillis()) / 60000) + " 分钟后");
            } catch (SecurityException se) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_ASSIST_T", "排定时激活失败: " + e.getMessage());
        }
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try { am.cancel(pending(ctx)); } catch (Exception ignored) { }
    }

    private static List<Long> selfAts(Context ctx) {
        List<Long> out = new ArrayList<>();
        for (AssistTimerStore.SelfAt s : AssistTimerStore.selfList(ctx)) out.add(s.at);
        return out;
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, AssistTimerReceiver.class);
        i.setAction(ACTION_FIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, flags);
    }
}
