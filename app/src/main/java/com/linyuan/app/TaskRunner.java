package com.linyuan.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TaskRunner {

    private static Context ctxRef;

    public static void run(Context ctx, long taskId) throws Exception {
        ctxRef = ctx.getApplicationContext();
        AppDatabase db = new AppDatabase(ctx);
        AppDatabase.ScheduledTask t = db.getScheduledTask(taskId);
        if (t == null || !t.enabled) return;

        long since = System.currentTimeMillis() - t.lastRunAt;
        if (t.lastRunAt > 0 && since < 6 * 3600_000L) {
            android.util.Log.i("VC_TASK", "任务 " + taskId + " 刚跑过，跳过");

            if (t.scheduleType != 0) {
                try { TaskScheduler.schedule(ctx, t); } catch (Exception ignored) { }
            }
            return;
        }

        android.content.SharedPreferences sp =
                ctx.getSharedPreferences("voice_companion", Context.MODE_PRIVATE);
        String baseUrl = sp.getString("chatBaseUrl", "");
        String apiKey = sp.getString("chatApiKey", "");
        String model = sp.getString("chatModel", "");
        String sysPrompt = sp.getString("systemPrompt", "");

        if (t.baseUrl != null && !t.baseUrl.trim().isEmpty()) baseUrl = t.baseUrl.trim();
        if (t.apiKey != null && !t.apiKey.trim().isEmpty()) apiKey = t.apiKey.trim();
        if (t.model != null && !t.model.trim().isEmpty()) model = t.model.trim();

        int taskThinkLevel = t.thinkLevel;
        boolean ok = false;
        try {

            // 回报写回助手面板（用户平时说话的那个会话），不是任务创建时记下的会话号
            android.content.SharedPreferences pp =
                    ctx.getSharedPreferences(AssistantPanel.PREFS, Context.MODE_PRIVATE);
            long panelConv = pp.getLong("convId", 0);
            long targetConv = panelConv > 0 ? panelConv : t.conversationStart;
            List<AppDatabase.MessageItem> history = loadConversationHistory(db, targetConv);

            int maxIter = sp.getInt("maxToolIterations", 500);
            if (maxIter <= 0) maxIter = 500;

            String reply;
            MainActivity act = MainActivity.current();
            if (act == null) {
                // 用户定案：不要"残缺版助手"兜底（工具不全，任务根本干不成）。
                // 没界面就把主助手拉起来；拉不起来就如实报失败，等下一轮/下一次重试。
                android.util.Log.i("VC_TASK", "任务 " + taskId + " 主助手不在，拉起它");
                act = MainActivity.wake(ctx);
            }
            if (act == null) {
                throw new IllegalStateException("主助手起不来（后台拉 Activity 被拦），这一轮没跑");
            }
            {
                // 用主助手自己的完整工具集跑这一轮（含派活/屏幕工具），跟面板里说话等价
                String panelModel = pp.getString("model", "");
                String panelBase = pp.getString("baseUrl", "");
                String panelKey = pp.getString("apiKey", "");
                String m = (t.model != null && !t.model.trim().isEmpty()) ? t.model.trim()
                        : (panelModel == null || panelModel.trim().isEmpty() ? model : panelModel.trim());
                String b = (t.baseUrl != null && !t.baseUrl.trim().isEmpty()) ? t.baseUrl.trim()
                        : (panelBase == null ? "" : panelBase.trim());
                String k = (t.apiKey != null && !t.apiKey.trim().isEmpty()) ? t.apiKey.trim()
                        : (panelKey == null ? "" : panelKey.trim());
                int lv = t.thinkLevel >= 0 ? t.thinkLevel : pp.getInt("thinkLevel", -1);
                String rolePrompt = pp.getString("role_main", "");
                String sys = act.buildAssistantPrompt(t.prompt, rolePrompt)
                        + act.buildAssistantSubAgentBlock("main") + taskFraming();

                JSONArray tools = SubAgentRunner.fullAssistantTools(act, "main");

                AiClient.ChatResult r = AiClient.chatWithToolsStream(
                        act.buildAssistantSettings(m, b, k, lv), sys, history, t.prompt,
                        tools, new AssistantToolHandler(act, "main"), maxIter, null, null, null);
                reply = r == null || r.content == null ? "" : r.content.trim();
                android.util.Log.i("VC_TASK", "任务 " + taskId + " 走主助手完整工具集（"
                        + tools.length() + " 个工具）→ 写回会话 " + targetConv);
            }
            if (reply.isEmpty()) {
                android.util.Log.w("VC_TASK", "任务 " + taskId + " 没有产出内容");
                db.markScheduledTaskRun(taskId, false);

                AppDatabase.ScheduledTask freshEmpty = db.getScheduledTask(taskId);
                if (freshEmpty != null && freshEmpty.failCount >= 3) {
                    db.setScheduledTaskEnabled(taskId, false);
                    android.util.Log.w("VC_TASK", "任务 " + taskId + " 连续失败，已自动停用");
                } else if (freshEmpty != null && freshEmpty.scheduleType != 0) {
                    TaskScheduler.schedule(ctx, freshEmpty);
                }
                return;
            }
            ok = true;

            boolean worthNotifying = true;
            if (t.onlyIfImportant) {
                worthNotifying = judgeWorthNotifying(baseUrl, apiKey, model, t.prompt, reply);
            }

            String head = "⏰ 【定时任务】" + (t.title == null || t.title.isEmpty() ? "" : t.title + "：");
            db.insertMessage("assistant", head + "\n" + reply
                    + "\n\n@@TASK_ID:" + t.id, null, null, 0, targetConv);

            if (t.notify && worthNotifying) {
                notifyDone(ctx, t, reply);
            }

            db.markScheduledTaskRun(taskId, true);
            AppDatabase.ScheduledTask fresh = db.getScheduledTask(taskId);
            if (fresh != null && fresh.scheduleType == 0) {

                db.setScheduledTaskEnabled(taskId, false);
            } else if (fresh != null && fresh.failCount >= 3) {

                db.setScheduledTaskEnabled(taskId, false);
                android.util.Log.w("VC_TASK", "任务 " + taskId + " 连续失败，已自动停用");
            } else if (fresh != null) {
                TaskScheduler.schedule(ctx, fresh);
            }
        } catch (Exception e) {
            db.markScheduledTaskRun(taskId, false);
            android.util.Log.w("VC_TASK", "任务执行异常: " + e.getMessage());
            AppDatabase.ScheduledTask fresh = db.getScheduledTask(taskId);
            if (fresh != null && fresh.failCount >= 3) {
                db.setScheduledTaskEnabled(taskId, false);
            } else if (fresh != null && fresh.scheduleType != 0) {
                TaskScheduler.schedule(ctx, fresh);
            }
            if (!ok) throw e;
        } finally {

            try { db.close(); } catch (Exception ignored) { }
        }
    }

    private static List<AppDatabase.MessageItem> loadConversationHistory(AppDatabase db, long conversationId) {
        return db.listRecentMessagesInConversation(conversationId, 60);
    }

    /** 定时任务那一轮的处境说明（接在主助手的完整提示词后面）。 */
    private static String taskFraming() {
        return "\n[This is an automated run of a scheduled task - the user did NOT just speak to you]\n"
                + "The message below is a **task instruction the user set up in advance**, delivered to you automatically at the scheduled time. Execute it as written.\n"
                + "Nobody is watching in real time, and nobody has just approved anything - do not assume the user is online right now.\n"
                + "When done, output the result itself (the user will read it later); no process talk like \"OK, on it\".\n";
    }

    private static boolean judgeWorthNotifying(String base, String key, String model,
                                               String taskPrompt, String result) {
        try {
            String sys = "You are a notification gatekeeper. Based on the user's original request and this run's result, decide whether the user is worth notifying.\n"
                    + "Notify only when the result genuinely satisfies the condition the user asked for; otherwise do not disturb them.\n"
                    + "Output exactly one JSON object: {\"reason\":\"...\",\"notify\":true/false}, and nothing else.";
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", sys));
            msgs.put(new JSONObject().put("role", "user").put("content",
                    "User's original request: " + taskPrompt + "\n\nThis run's result:\n" + result));
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", msgs);
            body.put("stream", false);
            body.put("max_tokens", 110000);
            body.put("temperature", 0.1);
            String resp = AiClient.simpleChat(base, key, body.toString());
            if (resp == null) return true;
            String t = resp.trim();
            int i = t.indexOf('{'), j = t.lastIndexOf('}');
            if (i >= 0 && j > i) {
                JSONObject o = new JSONObject(t.substring(i, j + 1));
                return o.optBoolean("notify", true);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_TASK", "判定失败，默认通知: " + e.getMessage());
        }
        return true;
    }

    private static void notifyDone(Context ctx, AppDatabase.ScheduledTask t, String reply) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "vc_task", "定时任务", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("定时任务执行结果");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, (int) (t.id + 9000), open, flags);

            String title = (t.title == null || t.title.isEmpty()) ? "定时任务完成" : t.title;
            String text = reply.length() > 120 ? reply.substring(0, 120) + "…" : reply;
            Notification n = new Notification.Builder(ctx, "vc_task")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("⏰ " + title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(reply))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify((int) (t.id + 9000), n);
        } catch (Exception e) {
            android.util.Log.w("VC_TASK", "通知失败: " + e.getMessage());
        }
    }
}
