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

            String sys = buildAutoRunSystemPrompt(sysPrompt);
            List<AppDatabase.MessageItem> history = loadConversationHistory(db, t.conversationStart);

            int maxIter = sp.getInt("maxToolIterations", 4);
            AiClient.ChatResult result = AiClient.chatDirect(
                    baseUrl, apiKey, model, sys, history, t.prompt,
                    buildAutoTools(), maxIter > 0 ? maxIter : 4,
                    buildAutoToolHandler(sp, db), taskThinkLevel);
            String reply = result == null ? "" : (result.content == null ? "" : result.content.trim());
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
                    + "\n\n@@TASK_ID:" + t.id, null, null, 0, t.conversationStart);

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

    private static String buildAutoRunSystemPrompt(String sysPrompt) {
        StringBuilder sb = new StringBuilder();
        String base = sysPrompt == null || sysPrompt.trim().isEmpty()
                ? "You are lin渊. Answer in casual, conversational Chinese." : sysPrompt.trim();
        sb.append(base).append("\n\n");
        sb.append("[This is an automated run of a scheduled task - the user did NOT just speak to you]\n");
        sb.append("The message below is a **task instruction the user set up in advance**, delivered to you automatically at the scheduled time. Execute it as written.\n");
        sb.append("Important: nobody is watching in real time, and nobody has just approved anything - ");
        sb.append("do not assume the user is online right now or has granted fresh permission.\n");
        sb.append("When done, output the result itself (the user will read it later); no process talk like \"OK, on it\".\n");

        sb.append("\n[Tools] You really are online now: web_search (search), fetch_url (read a given page's content), ");
        sb.append("search_sessions (search past conversations), manage_memory (write long-term memory), ");
        sb.append("perception (the phone knowledge map about this user: add/update/read/delete filed records).\n");
        sb.append("- Anything that changes over time (news, prices, weather, match results, the latest state of a page) - ");
        sb.append("**you must look it up with a tool first; never write it from memory**. Your training data is long outdated; a briefing made up from memory looks real but is wrong.\n");
        sb.append("- After searching, check each result's date to confirm it is actually fresh enough; if you only find old material, say so honestly (\"nothing from today; the latest is from X\"), ");
        sb.append("**never dress up old news as today's**.\n");
        sb.append("- For monitoring tasks (\"tell me when something changes\"), first use search_sessions to see what you reported last time; do not push repeats.\n");
        sb.append("- When a tool errors or finds nothing, state honestly which step failed; never fill the gap with guesses passed off as results.\n");

        AppDatabase memDb = null;
        try {
            memDb = new AppDatabase(ctxRef);
            List<AppDatabase.MemoryItem> memo = memDb.listMemoriesByStore("memory");
            if (!memo.isEmpty()) {
                sb.append("\n[Long-term memory (about the user, for reference)]\n");
                for (AppDatabase.MemoryItem m : memo) {
                    String c = m.category == null ? "" : m.category;
                    sb.append("- ").append(c.isEmpty() ? "" : ("[" + c + "] ")).append(m.content).append("\n");
                }
            }
            // 感知树骨架（同助手侧：只带标题，细节用 perception 工具查）
            sb.append(PerceptionStore.skeletonBlock(memDb));
        } catch (Exception ignored) {
        } finally {
            if (memDb != null) try { memDb.close(); } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    private static List<AppDatabase.MessageItem> loadConversationHistory(AppDatabase db, long conversationId) {
        return db.listRecentMessagesInConversation(conversationId, 60);
    }

    private static JSONArray buildAutoTools() {
        JSONArray tools = new JSONArray();
        try {
            JSONObject ws = new JSONObject();
            ws.put("name", "web_search");
            ws.put("description", "Search the web for the latest information. Call it whenever a scheduled task needs news/data. "
                    + "Results carry a publish date (date); for scheduled tasks the date matters especially - serving old news to the user as today's is a serious error.");
            JSONObject wsp = new JSONObject();
            wsp.put("query", new JSONObject().put("type", "string").put("description", "Search keywords"));
            wsp.put("freshness", new JSONObject().put("type", "string")
                    .put("description", "Optional; restricts the time range of results. Format: dN = last N days (d1-d30), mN = last N months (m1-m12), yN = last N years; omit = no limit. "
                            + "For daily briefings, weather, market quotes and news monitoring use d1 or d7."));
            ws.put("parameters", new JSONObject().put("type", "object").put("properties", wsp)
                    .put("required", new JSONArray().put("query")));
            tools.put(new JSONObject().put("type", "function").put("function", ws));

            JSONObject fu = new JSONObject();
            fu.put("name", "fetch_url");
            fu.put("description", "Read the main content of a web page directly. Use it to watch a fixed page for changes (announcement page, price page, changelog), "
                    + "or when search results only show snippets and you need the full content.");
            JSONObject fup = new JSONObject();
            fup.put("url", new JSONObject().put("type", "string").put("description", "Full URL (including http/https)"));
            fu.put("parameters", new JSONObject().put("type", "object").put("properties", fup)
                    .put("required", new JSONArray().put("url")));
            tools.put(new JSONObject().put("type", "function").put("function", fu));

            JSONObject ss = new JSONObject();
            ss.put("name", "search_sessions");
            ss.put("description", "Search past conversation records. For monitoring tasks, use it first to see what you reported last time; "
                    + "do not push again what the user was already told - only a genuinely new change is worth the interruption.");
            JSONObject ssp = new JSONObject();
            ssp.put("query", new JSONObject().put("type", "string").put("description", "Keywords to search for"));
            ss.put("parameters", new JSONObject().put("type", "object").put("properties", ssp)
                    .put("required", new JSONArray().put("query")));
            tools.put(new JSONObject().put("type", "function").put("function", ss));

            JSONObject mm = new JSONObject();
            mm.put("name", "manage_memory");
            mm.put("description", "Add / update / remove long-term memory. ❗Scheduled tasks run repeatedly, so write only **stable facts** "
                    + "worth remembering long-term (e.g. the user's specific target price, an object they follow long-term); "
                    + "things that change daily (today's news, current prices, weather) must **never go into memory**, or it fills up fast.");
            JSONObject mmp = new JSONObject();
            mmp.put("action", new JSONObject().put("type", "string").put("description", "add / replace / remove"));
            mmp.put("content", new JSONObject().put("type", "string").put("description", "New content for add/replace"));
            mmp.put("category", new JSONObject().put("type", "string")
                    .put("description", "Category (use one of these exact Chinese labels): " + java.util.Arrays.toString(MemoryDim.USER_DIMS).replace("[", "").replace("]", "")));
            mmp.put("old_text", new JSONObject().put("type", "string").put("description", "Text fragment locating the entry for replace/remove"));
            mm.put("parameters", new JSONObject().put("type", "object").put("properties", mmp)
                    .put("required", new JSONArray().put("action")));
            tools.put(new JSONObject().put("type", "function").put("function", mm));

            // 感知树（用户 2026-10-13：定时任务也可以决定"现在值得更新一下档案"）
            tools.put(new JSONObject().put("type", "function").put("function",
                    PerceptionStore.buildToolSchema()));
        } catch (Exception ignored) { }
        return tools;
    }

    private static AiClient.ToolHandler buildAutoToolHandler(android.content.SharedPreferences sp, AppDatabase db) {
        final AppSettings settings = buildSearchSettings(sp);
        return (name, args) -> {
            if ("web_search".equals(name)) {
                String query = args == null ? "" : args.optString("query", "").trim();
                if (query.isEmpty()) return "{\"error\":\"query must not be empty\"}";
                String freshness = args.optString("freshness", "").trim();
                try {
                    JSONArray results = AiClient.searchWebPublic(settings, query, freshness);
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject r = results.optJSONObject(i);
                        if (r == null) continue;
                        String date = r.optString("date", "").trim();
                        out.put(new JSONObject()
                                .put("title", r.optString("title", ""))
                                .put("url", r.optString("url", ""))
                                .put("snippet", r.optString("snippet", ""))
                                .put("date", date.isEmpty() ? "unknown" : date));
                    }
                    return new JSONObject().put("results", out)
                            .put("now", AiClient.nowText())
                            .put("note", "date is the page's publish date; \"unknown\" means the search source did not provide one. "
                                    + "For scheduled tasks you must first compare date against now: old content must not be sent to the user as today's news; "
                                    + "if you truly only found old material, state its dates explicitly.").toString();
                } catch (Exception e) { return errJson("Search failed: " + e.getMessage()); }
            }
            if ("fetch_url".equals(name)) {
                String url = args == null ? "" : args.optString("url", "").trim();
                if (url.isEmpty()) return errJson("url must not be empty");
                try {
                    String text = AiClient.fetchUrlText(settings, url);
                    return new JSONObject().put("url", url).put("text", text)
                            .put("now", AiClient.nowText()).toString();
                } catch (Exception e) { return errJson("Failed to fetch the page: " + e.getMessage()); }
            }
            if ("search_sessions".equals(name)) {
                String q = args == null ? "" : args.optString("query", "").trim();
                if (q.isEmpty()) return errJson("query must not be empty");
                try {
                    java.text.SimpleDateFormat fmt =
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
                    JSONArray arr = new JSONArray();
                    for (AppDatabase.MessageItem m : db.searchMessages(q, 20)) {
                        String cc = m.content == null ? "" : m.content;
                        int t = cc.indexOf("@@THINKING:");
                        if (t >= 0) cc = cc.substring(0, t).trim();
                        arr.put(new JSONObject().put("time", fmt.format(new java.util.Date(m.createdAt)))
                                .put("role", "user".equals(m.role) ? "user" : "you").put("text", cc));
                    }
                    return new JSONObject().put("results", arr).put("now", AiClient.nowText()).toString();
                } catch (Exception e) { return errJson("Failed to search past conversations: " + e.getMessage()); }
            }
            if ("manage_memory".equals(name)) {
                return handleMemoryTool(db, args);
            }
            // 感知树（用户 2026-10-13：定时任务里也用它更新档案）
            if ("perception".equals(name)) {
                return PerceptionStore.handleTool(db, args);
            }
            return errJson("Tool not supported in scheduled tasks: " + name);
        };
    }

    private static String handleMemoryTool(AppDatabase db, JSONObject args) {
        try {
            String action = args == null ? "" : args.optString("action", "").trim();
            String content = args == null ? "" : args.optString("content", "").trim();
            String oldText = args == null ? "" : args.optString("old_text", "").trim();
            String catArg = args == null ? "" : args.optString("category", "").trim();
            final String store = "memory";
            final String catOf = catArg.isEmpty() ? "笔记" : MemoryDim.normalizeUser(catArg);
            final int budget = 8000;
            java.util.List<AppDatabase.MemoryItem> cur = db.listMemoriesByStore(store);
            if ("add".equals(action)) {
                if (content.isEmpty()) return errJson("content must not be empty");
                int used = 0;
                for (AppDatabase.MemoryItem it : cur) {
                    used += estimateTokens(it.content);
                    if (it.content != null && it.content.trim().equals(content)) {
                        return okJson("Already exists; not added again");
                    }
                }
                if (used + estimateTokens(content) > budget) {
                    return errJson("Memory is full (" + used + "/" + budget + " tokens). Do not clean up old memories on your own in a scheduled task; "
                            + "skipping this write does not affect the current task - just finish the task and mention it in the result.");
                }
                db.insertMemoryStore(content, catOf, store);
                return okJson("Saved");
            }
            if ("replace".equals(action)) {
                if (oldText.isEmpty() || content.isEmpty()) return errJson("replace needs both old_text and content");
                java.util.List<Long> ids = db.findMemoryIdsBySubstring(store, oldText);
                if (ids.isEmpty()) return errJson("No entry matches old_text");
                if (ids.size() > 1) return errJson("old_text matches multiple entries; be more specific");
                db.updateMemoryContent(ids.get(0), content);
                return okJson("Updated");
            }
            if ("remove".equals(action)) {
                if (oldText.isEmpty()) return errJson("remove needs old_text");
                java.util.List<Long> ids = db.findMemoryIdsBySubstring(store, oldText);
                if (ids.isEmpty()) return errJson("No entry matches old_text");
                for (Long id : ids) db.deleteMemory(id);
                return okJson("Deleted " + ids.size() + " entries");
            }
            return errJson("action only supports add / replace / remove");
        } catch (Exception e) {
            return errJson("Memory operation failed: " + e.getMessage());
        }
    }

    private static int estimateTokens(String text) {
        return AiClient.estimateTokensText(text);
    }

    private static String okJson(String msg) {
        try { return new JSONObject().put("success", true).put("message", msg).toString(); }
        catch (Exception e) { return "{\"success\":true}"; }
    }

    private static String errJson(String msg) {
        try { return new JSONObject().put("success", false).put("error", msg == null ? "" : msg).toString(); }
        catch (Exception e) { return "{\"success\":false}"; }
    }

    private static AppSettings buildSearchSettings(android.content.SharedPreferences sp) {
        AppSettings s = new AppSettings();
        s.webSearchApiUrl = sp.getString("webSearchApiUrl", s.webSearchApiUrl);
        s.tokeniqBaseUrl = sp.getString("tokeniqBaseUrl", s.tokeniqBaseUrl);
        s.tokeniqApiKey = sp.getString("tokeniqApiKey", s.tokeniqApiKey);
        s.tokeniqFreshness = sp.getString("tokeniqFreshness", s.tokeniqFreshness);
        try { s.tokeniqSearchCount = sp.getInt("tokeniqSearchCount", s.tokeniqSearchCount); } catch (Exception ignored) {}
        try { s.connectTimeout = sp.getInt("connectTimeout", s.connectTimeout); } catch (Exception ignored) {}
        try { s.readTimeout = sp.getInt("readTimeout", s.readTimeout); } catch (Exception ignored) {}

        try { AiClient.refreshSearchPool(s); } catch (Exception ignored) {}
        return s;
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
