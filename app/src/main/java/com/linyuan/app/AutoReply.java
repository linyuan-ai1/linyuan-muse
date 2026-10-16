package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.List;

public final class AutoReply {

    private static final String PREFS = "auto_reply";
    private static final String K_ENABLED = "enabled";

    private AutoReply() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_ENABLED, v).apply();
    }

    private static volatile boolean userInMainChat = false;

    public static void setUserInMainChat(boolean in) {
        userInMainChat = in;
    }

    public static boolean isUserInMainChat() {
        return userInMainChat;
    }

    private static volatile boolean callActive = false;

    public static void setCallActive(boolean on) {
        callActive = on;
    }

    public static boolean isCallActive() {
        return callActive;
    }

    public static boolean inTimeWindow(Context c, long nowMs) {
        List<AutoReplyRule> rules = AutoReplyRule.list(c, true);
        if (rules.isEmpty()) return false;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        for (AutoReplyRule r : rules) {

            if (!r.matchesDay(dow)) continue;
            if (r.containsMinute(minutes)) return true;
        }
        return false;
    }

    public static boolean notifGranted(Context c) {
        return PhoneNotificationListener.granted(c);
    }

    public static boolean accessibilityGranted(Context c) {
        return AssistantAccessibilityService.isEnabled();
    }

    public static boolean ready(Context c) {
        return notifGranted(c) && accessibilityGranted(c);
    }

    public static String missingPermissionHint(Context c) {
        boolean n = notifGranted(c);
        boolean a = accessibilityGranted(c);
        if (n && a) return null;
        if (!n && !a) {
            return "还缺两个授权，缺一个都不行：\n\n"
                    + "① 通知使用权 —— 这是「门铃」，没有它我收不到微信来消息的信号。\n"
                    + "② 无障碍服务 —— 这是「我的手和眼睛」，没有它我进不去微信、点不了、打不了字。\n\n"
                    + "点确定后我会依次带你过去打开。";
        }
        if (!n) {
            return "还缺「通知使用权」。\n\n"
                    + "它是这个功能的**门铃**：没有它，微信来消息时我根本收不到信号，"
                    + "也就无从谈起自动回复。\n\n"
                    + "点确定后我带你过去打开。";
        }
        return "还缺「无障碍服务」。\n\n"
                + "它是我的**手和眼睛**：没有它，我进不去微信、读不了消息、也打不了字。\n\n"
                + "点确定后我带你过去打开。";
    }

    private static volatile boolean busy = false;

    private static volatile String currentTrigger = null;

    public static synchronized boolean tryAcquire(String trigger) {
        if (busy) return false;
        busy = true;
        currentTrigger = trigger;
        android.util.Log.i("VC_AUTOREPLY", "激活 AI（触发源：" + trigger + "）");
        return true;
    }

    public static synchronized void release(String reason) {
        if (!busy) return;
        busy = false;
        android.util.Log.i("VC_AUTOREPLY", "AI 收工，重新允许激活（" + reason
                + "，本轮触发源：" + currentTrigger + "）");
        currentTrigger = null;
    }

    public static boolean isBusy() {
        return busy;
    }

    private static final String K_LAST_AT = "lastEventAt";
    private static final String K_LAST_WHAT = "lastEventWhat";

    public static void rememberEvent(Context c, String what) {
        prefs(c).edit()
                .putLong(K_LAST_AT, System.currentTimeMillis())
                .putString(K_LAST_WHAT, what)
                .apply();
    }

    public static String lastEventText(Context c) {
        long at = prefs(c).getLong(K_LAST_AT, 0);
        String what = prefs(c).getString(K_LAST_WHAT, null);
        if (at <= 0 || what == null || what.isEmpty()) return null;
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        return f.format(new java.util.Date(at)) + "　" + what;
    }

    private static volatile long lastBlockedToastAt = 0;

    private static void toastBlocked(Context ctx, String why) {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (now - lastBlockedToastAt < 20000) return;
        lastBlockedToastAt = now;
        try {

            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(ctx.getApplicationContext(), why,
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }

    public static void onNotification(Context ctx, String pkg) {
        if (ctx == null) return;
        try {
            if (!enabled(ctx)) return;
            if (!isWeChat(pkg)) return;

            if (userInMainChat) {
                android.util.Log.i("VC_AUTOREPLY", "用户正在主对话，本次不抢手机");

                rememberEvent(ctx, "没动：你正在用主对话（这是你要求的保护，退到桌面就会自动回复）");
                toastBlocked(ctx, "微信来了消息，但你正在用主对话 —— 按你的设置没有抢手机。\n"
                        + "（退到桌面后，新消息才会自动回复）");
                return;
            }

            if (callActive) {
                android.util.Log.i("VC_AUTOREPLY", "用户正在通话中，本次不抢手机");
                rememberEvent(ctx, "没动：你正在通话");
                toastBlocked(ctx, "微信来了消息，但你正在通话中 —— 没有抢手机。");
                return;
            }

            if (!inTimeWindow(ctx, System.currentTimeMillis())) {
                android.util.Log.i("VC_AUTOREPLY", "不在允许回复的时段内，忽略");
                rememberEvent(ctx, "没动：不在你设定的时段内");

                return;
            }

            if (busy) {
                android.util.Log.i("VC_AUTOREPLY", "AI 正在干活，本次通知忽略（不排队）");
                return;
            }

            if (!accessibilityGranted(ctx)) {
                AutoReplyNotice.markBlocked(ctx, "无障碍服务没开");
                rememberEvent(ctx, "没动：无障碍服务被系统撤销了（重装 App 会这样）");
                toastBlocked(ctx, "微信来了消息，但「无障碍服务」被系统关掉了，我进不去微信。\n"
                        + "重装 App 后 ColorOS 会静默撤销它 —— 去把它重新打开。");
                return;
            }
            if (!notifGranted(ctx)) {

                AutoReplyNotice.markBlocked(ctx, "通知使用权没了");
                rememberEvent(ctx, "没动：通知使用权没了");
                return;
            }

            if (!tryAcquire("微信消息")) return;

            try {
                AutoReplyService.start(ctx);
                rememberEvent(ctx, "已触发：正在进微信帮你看消息…");
            } catch (Throwable t) {

                release("服务启动失败：" + t.getMessage());
                android.util.Log.w("VC_AUTOREPLY", "起服务失败: " + t);
                rememberEvent(ctx, "没动：服务起不来（" + t.getMessage() + "）");
            }
        } catch (Throwable t) {
            android.util.Log.w("VC_AUTOREPLY", "通知入口异常: " + t);
        }
    }

    private static boolean isWeChat(String pkg) {
        return "com.tencent.mm".equals(pkg);
    }
}
