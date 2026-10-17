package com.linyuan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class AutoReplyService extends Service {

    private static final String TAG = "VC_AUTOREPLY";
    private static final String CHANNEL_ID = "vc_autoreply_run";
    private static final int NOTIFY_ID = 9311;

    private PowerManager.WakeLock wakeLock;
    private volatile boolean running = false;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, AutoReplyService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        startForeground(NOTIFY_ID, buildNotification("正在看微信消息…"));

        if (running) return START_NOT_STICKY;
        running = true;

        try { WorkSound.warmUp(getApplicationContext()); } catch (Throwable ignored) { }

        try { WorkSound.resetTurn(); } catch (Throwable ignored) { }

        try { WorkSound.play(this, WorkSound.EV_THINK, false); } catch (Throwable ignored) { }

        acquireWakeLock();
        new Thread(() -> {

            String outcome = "未完成";
            try {
                outcome = runAutoReply();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "自动回复异常: " + t);
                outcome = "出错：" + t.getMessage();
            } finally {

                AutoReply.release(outcome);

                try { AutoReply.rememberEvent(this, "已完成：" + outcome); }
                catch (Throwable ignored) { }

                try { WorkSound.play(this, WorkSound.EV_DONE, true); } catch (Throwable ignored) { }
                running = false;
                try { stopForeground(true); } catch (Throwable ignored) { }
                releaseWakeLock();
                stopSelf();
            }
        }, "auto-reply").start();

        return START_NOT_STICKY;
    }

    private void applyRuleModel(AppSettings settings) {
        try {
            AutoReplyRule hit = null;
            for (AutoReplyRule r : AutoReplyRule.list(this, true)) {
                if (r.model != null && !r.model.trim().isEmpty()) { hit = r; break; }
            }
            if (hit == null) return;
            settings.model = hit.model.trim();
            settings.chatModel = hit.model.trim();
            if (hit.baseUrl != null && !hit.baseUrl.trim().isEmpty()) {
                settings.baseUrl = hit.baseUrl.trim();
                settings.chatBaseUrl = hit.baseUrl.trim();
            }
            if (hit.apiKey != null && !hit.apiKey.trim().isEmpty()) {
                settings.apiKey = hit.apiKey.trim();
                settings.chatApiKey = hit.apiKey.trim();
            }
            if (hit.thinkLevel >= 0) settings.thinkLevel = hit.thinkLevel;
            android.util.Log.i(TAG, "本轮使用时段规则自己的模型：" + settings.chatModel
                    + "（思考档位 " + settings.thinkLevel + "）");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "应用规则模型失败（继续用全局设置）: " + t);
        }
    }

    private String runAutoReply() throws Exception {
        AppSettings settings = loadSettingsHeadless(this);
        AppDatabase db = new AppDatabase(this);
        try {

            if (!AssistantAccessibilityService.isEnabled()) {
                AutoReplyNotice.markBlocked(this, "无障碍服务被系统撤销了");
                return "被拦：无障碍没开";
            }

            String sys = buildAutoReplyPrompt(this, db, settings);

            String userText = "(Triggered automatically by the system - not something the user said) "
                    + "A new 微信 message notification just arrived and you have been woken up. "
                    + "Do your job: go into 微信, see which messages need a reply, reply to the ones that do, and leave the rest alone. "
                    + "When done, briefly state in one sentence what you did this time.";

            // 轮数跟全局一个值（2026-10-17：原来这里夹在 12~20，用户设 500 也不作数）
            int maxIter = settings.maxToolIterations > 0 ? settings.maxToolIterations : 500;

            applyRuleModel(settings);
            AiClient.ChatResult result = AiClient.chatWithToolsStream(
                    settings, sys, null, userText,
                    buildAutoReplyTools(), this::handleTool, maxIter, null);

            String reply = result == null || result.content == null ? "" : result.content.trim();
            android.util.Log.i(TAG, "本轮结果: " + reply);

            try {
                long cid = 0;
                // 列表现在是"最新在前"，最近的会话是第 0 个（用户 2026-10-16 定）。
                // 旧写法取 size()-1，排序一改就会把自动回复写进最旧的那个会话。
                List<AppDatabase.ConversationSummary> convs = db.listConversations();
                if (convs != null && !convs.isEmpty()) {
                    cid = convs.get(0).conversationId;
                }
                if (cid > 0) {
                    db.insertMessage("assistant",
                            "📱 【微信自动回复】\n" + (reply.isEmpty() ? "（无内容）" : reply),
                            null, null, 0, cid);
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "写库失败: " + t);
            }
            return reply.isEmpty() ? "完成（无内容）" : "完成";
        } finally {
            try { db.close(); } catch (Throwable ignored) { }
        }
    }

    private static String buildAutoReplyPrompt(Context ctx, AppDatabase db, AppSettings settings) {
        StringBuilder sb = new StringBuilder();

        sb.append(AiClient.timeLineForPrompt());

        sb.append("[Who you are right now] You are lin渊, **running automatically in the background** as the 微信 (WeChat) auto-reply assistant.\n");
        sb.append("The user is not talking to you and nobody is watching you work. The only reason you were woken up: **a new 微信 message arrived**.\n\n");

        sb.append("[Your job]\n");
        sb.append("Go into 微信, see clearly which conversations need a reply, then **decide for yourself** whether to reply and what to say, and send the replies that are warranted.\n\n");

        sb.append("[How to work]\n");
        sb.append("1. Open 微信 first: open_app (微信).\n");
        sb.append("2. Use take_screenshot to see the current screen (微信 blocks accessibility screen reading, so **you must rely on screenshots**).\n");
        sb.append("3. Find conversations with unread messages, tap in, and scroll up a bit to read the **chat context** (never reply from the last line alone).\n");
        sb.append("4. **After you understand the message and before you reply, call search_memory first**:\n");
        sb.append("   Look up the names, projects and matters mentioned in the message (e.g. if it says 「上次那个方案改好了吗」,\n");
        sb.append("   search for 「方案」 or the sender's name). Find out who the person is and what was discussed before deciding how to reply.\n");
        sb.append("   ☠️ Do not skip this step: replying from impressions alone easily goes wrong (mistaking who someone is, claiming something was done when it wasn't).\n");
        sb.append("5. Decide whether this message needs a reply:\n");
        sb.append("   - Should reply -> reply normally (see \"How to type and send\" below).\n");
        sb.append("   - **Already answered**, or just a notification/ad/system message -> **do not reply**; back out and check the next one.\n");
        sb.append("   - Uncertain or sensitive (money, promises, meeting plans, privacy, arguments) -> **do not answer on the user's behalf**; leave it for them.\n");
        sb.append("6. When everything that needs handling is handled, restore the original state (back to the home screen or exit 微信), and finish.\n\n");

        sb.append("[How to type and send]\n");
        sb.append("- First tap the input box (so the cursor appears), then type via the accessibility type action.\n");
        sb.append("- 微信's input boxes block accessibility reading; the system automatically falls back to the IME channel to get the text in - that path works.\n");
        sb.append("- ☠️ **Sending: after typing, tap the on-screen 发送 (Send) button** (tap by coordinates).\n");
        sb.append("  **Verified on a real device**: on 2026-10-07 this successfully sent multiple messages\n");
        sb.append("  (\"在的，怎么了？\" etc. really appeared in the chat).\n");
        sb.append("- Where the send button is: after typing it appears to the right of the input box (roughly the lower-right area of the screen,\n");
        sb.append("  on the same row as the input box). **take_screenshot first to see its actual position, then tap** -\n");
        sb.append("  layouts differ between screens (the height changes with/without the keyboard).\n");
        sb.append("- You may also try type with send=true (works in some apps), but **it most likely fails in 微信**;\n");
        sb.append("  if it fails, switch to tapping the send button immediately - do not keep retrying the same path.\n");
        sb.append("- ☠️ **After sending, you must take_screenshot to confirm the message really appears in the chat**.\n");
        sb.append("  A message counts as sent only when you see it in the screenshot - a tool saying \"clicked\" does not mean it was sent.\n\n");

        sb.append("[Be frugal (avoid spinning your wheels)]\n");
        sb.append("- ☠️ **Never repeat the same action more than 3 times**. If one path fails 3 times in a row, the approach is wrong -\n");
        sb.append("  switch paths (different tap coordinates -> click_text -> send=true).\n");
        sb.append("- Act on a screenshot once you have seen it; do not screenshot the same screen over and over - what you already saw needs no second look.\n");
        sb.append("- One wake-up should take **10 steps or fewer**. If you notice you are already twenty or thirty steps in,\n");
        sb.append("  you are definitely stuck: **stop and state honestly where you are stuck** - far more useful than spinning on.\n\n");

        sb.append("[Iron rules]\n");
        sb.append("- Do the one thing only: reply to 微信 messages. **Do not** change settings, post moments, delete anything, transfer money, or open links.\n");
        sb.append("- ☠️ **When the other person asks the user to do something else (buy things, look things up, post something, etc.), do not agree on the user's behalf and do not do it**:\n");
        sb.append("  your job is only replying to messages. Respond to such requests with one natural sentence (e.g. ask for details / say you'll look later),\n");
        sb.append("  but **never make promises for the user** (\"OK, I'll buy it for you\" and the like is forbidden), and never actually operate other apps.\n");
        sb.append("- If you are not sure whether to reply, do not reply. **Missing one reply is fine; one wrong reply is serious.**\n");
        sb.append("- Your replies must **sound like the user's own everyday speech** (see the long-term memory below).\n");
        sb.append("- Keep replies short and natural, like 微信 chat: no long essays, no formal written style, no piles of emoji.\n");

        sb.append("- ☠️ **Never stop after a plan sentence like \"我打算…/我先…/Let me…/I'll…\"** -\n");
        sb.append("  a plan sentence means nothing was done. You must **call the tool and actually execute in the same turn**,\n");
        sb.append("  finish one step and move to the next, and only output the summary when everything is done.\n");
        sb.append("- ☠️ Always speak **in Chinese** (both the running commentary and the final summary); do not use English.\n\n");

        injectLongTermMemory(sb, db, settings);

        sb.append("[Current time] See the time line at the very top. For late-night/early-morning messages, think about whether replying immediately is appropriate.\n");

        return sb.toString();
    }

    private static void injectLongTermMemory(StringBuilder sb, AppDatabase db, AppSettings settings) {
        injectLongTermMemoryImpl(sb, db, settings);
    }

    static void injectLongTermMemoryForTest(StringBuilder sb, AppDatabase db, AppSettings settings) {
        injectLongTermMemoryImpl(sb, db, settings);
    }

    private static void injectLongTermMemoryImpl(StringBuilder sb, AppDatabase db, AppSettings settings) {
        if (!settings.memoryEnabled) return;
        try {

            int ltBudget = settings.memoryStoreBudgetTokens > 0 ? settings.memoryStoreBudgetTokens : 350000;
            appendStore(sb, db, "memory", "[Long-term memory] (about the user: " + java.util.Arrays.toString(MemoryDim.USER_DIMS)
                            .replace("[", "").replace("]", "") + ")",
                    "(Facts and conclusions you normally keep for the user. When replying you must draw on his speaking style, forms of address and relationships - "
                            + "e.g. recognizing that a person is someone he often mentions tells you how to respond.)",
                    ltBudget);

            appendStore(sb, db, "self", "[Your own notes]",
                    "(About yourself. Only you will read this.)",
                    settings.selfStoreBudgetTokens > 0 ? settings.selfStoreBudgetTokens : 100000);

            if (settings.entityEnabled) {
                try {
                    List<AppDatabase.EntityItem> entities = db.listEntities(80);
                    if (entities != null && !entities.isEmpty()) {
                        sb.append("[Entity memory (people / places / projects you know)]\n");
                        int used = 0;
                        for (AppDatabase.EntityItem e : entities) {
                            String attrs = (e.attributes != null && !e.attributes.isEmpty()
                                    && !"null".equals(e.attributes)) ? " — " + e.attributes : "";
                            String line = "- [" + e.type + "] " + e.name + attrs + "\n";
                            int t = line.length() / 2;
                            if (used + t > 12000 && used > 0) break;
                            sb.append(line);
                            used += t;
                        }
                        sb.append("\n");
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "注入实体记忆失败: " + t);
                }
            }

            try {
                int total = db.countKnowledge();
                if (total > 0) {

                    List<AppDatabase.KnowledgeItem> docs = db.listKnowledgeChunks(6);
                    if (docs != null && !docs.isEmpty()) {
                        sb.append("[Knowledge base excerpts (background material; mostly irrelevant - consult only when clearly related)]\n");
                        for (AppDatabase.KnowledgeItem k : docs) {
                            String txt = k.chunk == null ? "" : k.chunk.trim();
                            if (txt.isEmpty()) continue;
                            if (txt.length() > 300) txt = txt.substring(0, 300) + "…";
                            sb.append("- ").append(k.title == null ? "" : k.title).append(": ").append(txt).append("\n");
                        }
                        sb.append("\n");
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "注入知识库失败: " + t);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "注入长期记忆失败: " + t);
        }
    }

    private static void appendStore(StringBuilder sb, AppDatabase db, String store,
                                    String title, String hint, int budget) {
        try {
            List<AppDatabase.MemoryItem> items = db.listMemoriesByStore(store);
            if (items == null || items.isEmpty()) return;

            items.sort((a, b) -> {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Double.compare(b.importance, a.importance);
            });
            StringBuilder body = new StringBuilder();
            int used = 0, included = 0;
            for (AppDatabase.MemoryItem it : items) {
                if (it.content == null || it.content.trim().isEmpty()) continue;
                String line = "- " + it.content.trim() + "\n";
                int t = line.length() / 2;

                if (used + t > budget && included > 0) break;
                body.append(line);
                used += t;
                included++;
            }
            if (included > 0) {
                sb.append(title).append("\n").append(hint).append("\n").append(body).append("\n");
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "注入 " + store + " 失败: " + t);
        }
    }

    private static JSONArray buildAutoReplyTools() {
        JSONArray tools = new JSONArray();
        try {

            JSONObject oa = new JSONObject();
            oa.put("name", "open_app");
            oa.put("description", "Open an app (by Chinese name or package name). To enter 微信 use open_app with app=微信.");
            JSONObject op = new JSONObject();
            op.put("app", new JSONObject().put("type", "string").put("description", "App name in Chinese (e.g. 微信) or package name (com.tencent.mm)"));
            oa.put("parameters", new JSONObject().put("type", "object").put("properties", op)
                    .put("required", new JSONArray().put("app")));
            tools.put(new JSONObject().put("type", "function").put("function", oa));

            JSONObject ax = new JSONObject();
            ax.put("name", "accessibility");
            ax.put("description", "Operate any screen on the phone via accessibility: tap = tap by percentage coordinates, swipe = swipe, "
                    + "click_text = tap the element containing some text, back = go back, home = go home, read_screen = read the visible text on screen, "
                    + "type = type character by character (微信 input boxes cannot be read; the system automatically falls back to the IME channel to get the text in), "
                    + "set_text = replace the input box content wholesale, copy_clipboard/paste = clipboard. "
                    + "Note: **no text can be read from 微信 screens** (the app hides its control tree from accessibility), "
                    + "so to see the screen clearly you must use take_screenshot; do not rely on read_screen. "
                    + "Before type, tap the input box once so the cursor appears. "
                    + "微信 input boxes **do not accept send=true**; after typing you must tap the on-screen send button yourself.");
            JSONObject axp = new JSONObject();
            axp.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("tap").put("swipe").put("click_text")
                            .put("back").put("home").put("read_screen").put("type").put("set_text")
                            .put("copy_clipboard").put("paste")));
            axp.put("x", new JSONObject().put("type", "number").put("description", "tap/swipe start x (screen percentage 0-100)"));
            axp.put("y", new JSONObject().put("type", "number").put("description", "tap/swipe start y (screen percentage 0-100)"));
            axp.put("x2", new JSONObject().put("type", "number").put("description", "swipe end x (percentage)"));
            axp.put("y2", new JSONObject().put("type", "number").put("description", "swipe end y (percentage)"));
            axp.put("text", new JSONObject().put("type", "string").put("description", "Text for click_text to tap"));
            axp.put("input", new JSONObject().put("type", "string").put("description", "Text to type for the type action"));
            axp.put("value", new JSONObject().put("type", "string").put("description", "Text for set_text / content to put on the clipboard before paste"));
            axp.put("duration", new JSONObject().put("type", "integer").put("description", "swipe duration in ms, default 300"));
            axp.put("send", new JSONObject().put("type", "boolean").put("description", "Whether to trigger send after typing. Fails in 微信 - tap the send button instead"));
            ax.put("parameters", new JSONObject().put("type", "object").put("properties", axp)
                    .put("required", new JSONArray().put("action")));
            tools.put(new JSONObject().put("type", "function").put("function", ax));

            JSONObject ss = new JSONObject();
            ss.put("name", "take_screenshot");
            ss.put("description", "Capture the current phone screen and **show you the picture directly** (your only way to see the screen clearly inside 微信). "
                    + "Take one after every step (entering a conversation, finishing typing, tapping send) to confirm the result; do not guess. "
                    + "keep omitted = false: saved to a temp directory only - not added to the gallery, invisible to the user.");
            JSONObject sp = new JSONObject();
            sp.put("keep", new JSONObject().put("type", "boolean")
                    .put("description", "true = save into the system gallery (only when the user asks to keep it). During auto-reply, **never pass true**"));
            ss.put("parameters", new JSONObject().put("type", "object").put("properties", sp));
            tools.put(new JSONObject().put("type", "function").put("function", ss));

            JSONObject sm = new JSONObject();
            sm.put("name", "search_memory");
            sm.put("description", "Search the long-term memory you keep for the user (13 dimensions / entity memory / knowledge base documents). "
                    + "**After you see a message in 微信 and before deciding how to reply, search here first** - "
                    + "especially when the message mentions a person, a project or an event: look it up before replying, never go by impressions. "
                    + "Use the key information from the message as the query (names, matters), not whole long sentences. "
                    + "Returns several entries ranked by relevance (with sources).");
            JSONObject smp = new JSONObject();
            smp.put("query", new JSONObject().put("type", "string")
                    .put("description", "Search terms: person name / matter / keywords, e.g. \"小王 方案\""));
            smp.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "Number of entries to return, default 12"));
            sm.put("parameters", new JSONObject().put("type", "object").put("properties", smp)
                    .put("required", new JSONArray().put("query")));
            tools.put(new JSONObject().put("type", "function").put("function", sm));

        } catch (Throwable t) {
            android.util.Log.w(TAG, "构建工具失败: " + t);
        }
        return tools;
    }

    private String handleTool(String name, JSONObject args) throws Exception {

        String r;
        if ("open_app".equals(name)) r = toolOpenApp(args);
        else if ("accessibility".equals(name)) r = toolAccessibility(args);
        else if ("take_screenshot".equals(name)) r = toolScreenshot(args);
        else if ("search_memory".equals(name)) r = toolSearchMemory(args);
        else r = err("Tool not available in auto-reply mode: " + name);

        try { WorkSound.play(this, WorkSound.EV_TOOL, false); } catch (Throwable ignored) { }
        android.util.Log.i(TAG, "工具 " + name + "(" + args + ") → "
                + (r == null ? "null" : r.substring(0, Math.min(160, r.length()))));
        return r;
    }

    private String toolSearchMemory(JSONObject args) {
        String q = args.optString("query", "").trim();
        if (q.isEmpty()) return err("query must not be empty (use a person name / matter / keywords)");
        int limit = args.optInt("limit", 12);
        return searchMemory(this, q, limit);
    }

    static String searchMemoryForTest(android.content.Context ctx, String q, int limit) {
        return searchMemory(ctx, q, limit);
    }

    static String searchMemory(android.content.Context ctx, String q0, int limit0) {
        String q = q0 == null ? "" : q0.trim();
        if (q.isEmpty()) return err("query must not be empty (use a person name / matter / keywords)");
        int limit = limit0;
        if (limit < 1) limit = 1;
        if (limit > 40) limit = 40;

        AppDatabase db = null;
        try {
            db = new AppDatabase(ctx);
            AppSettings st = loadSettingsHeadless(ctx);
            JSONArray out = new JSONArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            String vecNote;

            try {
                List<AppDatabase.MemoryItem> memHits = db.listMemoriesFiltered(q, false, null, limit);
                for (AppDatabase.MemoryItem m : memHits) {
                    String key = "m" + m.id;
                    if (!seen.add(key)) continue;
                    out.put(new JSONObject()
                            .put("来源", "memory · " + storeLabel(m.store))
                            .put("内容", m.content));
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "关键词搜记忆失败: " + t);
            }

            try {
                List<AppDatabase.KnowledgeItem> kHits = db.searchKnowledgeLike(q, 8);
                for (AppDatabase.KnowledgeItem k : kHits) {
                    String key = "k" + k.id;
                    if (!seen.add(key)) continue;
                    String txt = k.chunk == null ? "" : k.chunk.trim();
                    if (txt.length() > 400) txt = txt.substring(0, 400) + "…";
                    out.put(new JSONObject()
                            .put("来源", "knowledge base · " + (k.title == null ? "document" : k.title))
                            .put("内容", txt));
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "关键词搜知识库失败: " + t);
            }

            float[] qv = null;
            if (st.memoryUseVector) {
                try {
                    qv = EmbeddingClient.embed(st, q);
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "向量检索不可用（将只用关键词）: " + t);
                }
            }
            if (qv != null) {
                final float[] query = qv;

                try {
                    List<AppDatabase.MemoryItem> all = db.listAllMemoriesWithEmbeddings(2000);
                    List<AppDatabase.MemoryItem> scored = new java.util.ArrayList<>();
                    for (AppDatabase.MemoryItem m : all) {
                        if (m.embedding == null) continue;
                        scored.add(m);
                    }
                    scored.sort((a, b) -> {
                        double sa = EmbeddingClient.cosineSimilarity(query, EmbeddingClient.bytesToFloats(a.embedding));
                        double sb = EmbeddingClient.cosineSimilarity(query, EmbeddingClient.bytesToFloats(b.embedding));
                        return Double.compare(sb, sa);
                    });
                    int n = Math.min(limit, scored.size());
                    for (int i = 0; i < n; i++) {
                        AppDatabase.MemoryItem m = scored.get(i);
                        String key = "m" + m.id;
                        if (seen.contains(key)) continue;
                        double sim = EmbeddingClient.cosineSimilarity(query, EmbeddingClient.bytesToFloats(m.embedding));

                        if (sim < 0.35d) continue;
                        seen.add(key);
                        out.put(new JSONObject()
                                .put("来源", "memory · " + storeLabel(m.store) + " (relevance "
                                        + String.format(java.util.Locale.CHINA, "%.2f", sim) + ")")
                                .put("内容", m.content));
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "向量搜记忆失败: " + t);
                }

                try {
                    List<AppDatabase.KnowledgeItem> all = db.listKnowledgeWithEmbeddings(1000);
                    List<AppDatabase.KnowledgeItem> scored = new java.util.ArrayList<>();
                    for (AppDatabase.KnowledgeItem k : all) {
                        if (k.embedding == null) continue;
                        scored.add(k);
                    }
                    scored.sort((a, b) -> {
                        double sa = EmbeddingClient.cosineSimilarity(query, EmbeddingClient.bytesToFloats(a.embedding));
                        double sb = EmbeddingClient.cosineSimilarity(query, EmbeddingClient.bytesToFloats(b.embedding));
                        return Double.compare(sb, sa);
                    });
                    int n = Math.min(5, scored.size());
                    for (int i = 0; i < n; i++) {
                        AppDatabase.KnowledgeItem k = scored.get(i);
                        String key = "k" + k.id;
                        if (seen.contains(key)) continue;
                        double sim = EmbeddingClient.cosineSimilarity(query, EmbeddingClient.bytesToFloats(k.embedding));
                        if (sim < 0.35d) continue;
                        seen.add(key);
                        String txt = k.chunk == null ? "" : k.chunk.trim();
                        if (txt.length() > 400) txt = txt.substring(0, 400) + "…";
                        out.put(new JSONObject()
                                .put("来源", "knowledge base · " + (k.title == null ? "document" : k.title)
                                        + " (relevance " + String.format(java.util.Locale.CHINA, "%.2f", sim) + ")")
                                .put("内容", txt));
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "向量搜知识库失败: " + t);
                }
                vecNote = "Both keyword and vector search were used.";
            } else {
                vecNote = st.memoryUseVector
                        ? "Vector search did not run this time (network or config issue); **keyword hits only** - "
                          + "if the person/thing you are looking for is not in the results, that does not mean it does not exist; it may be worded differently."
                        : "The user has turned off vector search; **keyword hits only**.";
            }

            if (out.length() > limit) {
                JSONArray trimmed = new JSONArray();
                for (int i = 0; i < limit; i++) trimmed.put(out.opt(i));
                out = trimmed;
            }

            return new JSONObject()
                    .put("success", true)
                    .put("count", out.length())
                    .put("results", out)
                    .put("note", out.length() == 0
                            ? "No related memories found. " + vecNote + " If nothing turns up, do not force an answer from thin air - just respond naturally as in an ordinary 微信 chat."
                            : vecNote + " These are only search results; not all of them are necessarily relevant - judge for yourself which ones apply.")
                    .toString();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "search_memory 失败: " + t);
            return err("Search failed: " + t.getMessage());
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) { }
        }
    }

    private static String storeLabel(String store) {
        if ("self".equals(store)) return "self notes";
        if ("memory".equals(store) || store == null) return "long-term memory";
        return store;
    }

    private String toolOpenApp(JSONObject args) {
        String app = args.optString("app", "").trim();
        if (app.isEmpty()) return err("app must not be empty");
        String pkg = resolvePackage(app);
        if (pkg == null) return err("No app found matching \"" + app + "\"");
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return err("This app has no launchable screen");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);

            Thread.sleep(2200);
            return ok("Opened " + app + " (current foreground: " + AssistantAccessibilityService.foregroundPackage() + ")");
        } catch (Throwable t) {
            return err("Failed to open: " + t.getMessage());
        }
    }

    private String toolAccessibility(JSONObject args) throws Exception {
        String action = args.optString("action", "");
        if (!AssistantAccessibilityService.isEnabled()) {
            return err("The accessibility service is not enabled; the phone cannot be operated.");
        }
        switch (action) {
            case "tap": {
                float x = (float) args.optDouble("x", 50);
                float y = (float) args.optDouble("y", 50);
                boolean okTap = AssistantAccessibilityService.tap(x, y);
                if (okTap) Thread.sleep(900);
                return okTap ? ok("Tapped " + Math.round(x) + "%," + Math.round(y) + "%") : err("Tap failed");
            }
            case "swipe": {
                float x1 = (float) args.optDouble("x", 50), y1 = (float) args.optDouble("y", 80);
                float x2 = (float) args.optDouble("x2", 50), y2 = (float) args.optDouble("y2", 20);
                int dur = args.optInt("duration", 300);
                boolean okSw = AssistantAccessibilityService.swipe(x1, y1, x2, y2, dur);
                if (okSw) Thread.sleep(700);
                return okSw ? ok("Swiped") : err("Swipe failed");
            }
            case "click_text": {
                String text = args.optString("text", "").trim();
                if (text.isEmpty()) return err("click_text needs text");
                boolean okC = AssistantAccessibilityService.clickByText(text);
                if (okC) Thread.sleep(900);
                return okC ? ok("Tapped \"" + text + "\"")
                        : err("No element found containing \"" + text + "\" (微信 screens expose no nodes to accessibility; in that case use take_screenshot to get coordinates and tap)");
            }
            case "back": {
                boolean okB = AssistantAccessibilityService.goBack();
                if (okB) Thread.sleep(900);
                return okB ? ok("Went back") : err("Back failed");
            }
            case "home": {
                boolean okH = AssistantAccessibilityService.goHome();
                if (okH) Thread.sleep(800);
                return okH ? ok("Went home") : err("Home failed");
            }
            case "read_screen": {
                String t = AssistantAccessibilityService.getScreenText();
                if (t == null || t.trim().isEmpty()) {
                    return ok("(No text can be read from this screen - 微信 blocks accessibility reading; use take_screenshot to see it instead)");
                }
                return ok(t.length() > 3000 ? t.substring(0, 3000) + "…" : t);
            }
            case "type": {
                String input = args.optString("input", "");
                if (input.isEmpty()) return err("type needs input");
                int speed = args.optInt("speed", 0);
                boolean wantSend = args.optBoolean("send", false);

                int done = AssistantAccessibilityService.typeGradually(input, speed);
                if (done >= input.length()) {
                    return wantSend
                            ? ok("Typed: " + input + " (not sent - tap the send button)")
                            : ok("Typed: " + input);
                }

                AppSettings st = loadSettingsHeadless(this);
                String viaIme = ImeTypingChannel.type(this, st, input, speed, wantSend);
                if (viaIme != null) return viaIme;
                if (done > 0) return err("Only the first " + done + " characters got typed");
                return err("Could not type: tap the input box once so the cursor appears, then try again");
            }
            case "set_text": {
                String value = args.optString("value", "").trim();
                if (value.isEmpty()) return err("set_text needs value");
                if (AssistantAccessibilityService.isEnabled() && AssistantAccessibilityService.setText(value)) {
                    return ok("Filled in: \"" + value + "\"");
                }
                AppSettings st2 = loadSettingsHeadless(this);
                String viaIme2 = ImeTypingChannel.type(this, st2, value, 1, false);
                if (viaIme2 != null && viaIme2.contains("\"success\":true")) {
                    return ok("Filled in: \"" + value + "\" (via the IME channel)");
                }
                return err("Could not fill in: tap the input box first so the cursor appears");
            }
            case "copy_clipboard": {
                String v = args.optString("value", "").trim();
                if (v.isEmpty()) return err("copy_clipboard needs value");
                AssistantAccessibilityService.copyToClipboard(v);
                return ok("Copied to clipboard");
            }
            case "paste": {
                boolean okP = AssistantAccessibilityService.paste();
                if (okP) Thread.sleep(600);
                return okP ? ok("Pasted") : err("Paste failed: no input box found");
            }
            default:
                return err("Unsupported action: " + action);
        }
    }

    private String toolScreenshot(JSONObject args) {
        if (!AssistantAccessibilityService.canScreenshot()) {
            return err("Cannot take a screenshot: the accessibility service is not enabled.");
        }
        try {
            java.io.File dir = getExternalFilesDir("screenshots_temp");
            if (dir == null) dir = new java.io.File(getFilesDir(), "screenshots_temp");
            if (!dir.exists() && !dir.mkdirs()) return err("Cannot create the screenshot directory");
            java.io.File out = new java.io.File(dir, "auto_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
                    .format(new java.util.Date()) + ".png");
            java.io.File done = AssistantAccessibilityService.screenshotTo(out);
            if (done == null) return err("The system returned no screenshot (the current screen may forbid screen capture)");

            pruneTemp(dir, 20, done);

            return new JSONObject()
                    .put("success", true)
                    .put("path", done.getAbsolutePath())
                    .put(AiClient.KEY_VISION_IMAGE, done.getAbsolutePath())
                    .put("note", "The screen image has been shown to you directly; judge from what you see, do not guess.")
                    .toString();
        } catch (Throwable t) {
            return err("Screenshot failed: " + t.getMessage());
        }
    }

    private static void pruneTemp(java.io.File dir, int maxKeep, java.io.File keep) {
        try {
            java.io.File[] files = dir.listFiles((d, n) -> n != null && n.toLowerCase().endsWith(".png"));
            if (files == null || files.length <= maxKeep) return;

            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            int deleted = 0;
            for (int i = maxKeep; i < files.length; i++) {

                if (keep != null && files[i].equals(keep)) continue;
                if (files[i].delete()) deleted++;
            }
            if (deleted > 0) {
                android.util.Log.i(TAG, "清理临时截图 " + deleted + " 张（保留最新 " + maxKeep + "）");
            }
        } catch (Throwable ignored) { }
    }

    static AppSettings loadSettingsHeadless(Context ctx) {
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences("voice_companion", Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();

        s.mode = "openai";
        s.baseUrl = spString(sp, "baseUrl", s.baseUrl);
        s.model = spString(sp, "model", s.model);
        s.apiKey = spString(sp, "apiKey", s.apiKey);
        s.systemPrompt = spString(sp, "systemPrompt", s.systemPrompt);
        s.temperature = spString(sp, "temperature", s.temperature);
        s.maxTokens = spString(sp, "maxTokens", s.maxTokens);
        s.topP = spString(sp, "topP", s.topP);
        s.connectTimeout = sp.getInt("connectTimeout", s.connectTimeout);
        s.readTimeout = sp.getInt("readTimeout", s.readTimeout);
        s.maxToolIterations = sp.getInt("maxToolIterations", s.maxToolIterations);

        s.chatBaseUrl = spString(sp, "chatBaseUrl", s.chatBaseUrl);
        s.chatModel = spString(sp, "chatModel", s.chatModel);
        s.chatApiKey = spString(sp, "chatApiKey", s.chatApiKey);

        s.enableThinking = sp.getBoolean("enableThinking", s.enableThinking);
        s.enableDeepThinking = sp.getBoolean("enableDeepThinking", s.enableDeepThinking);
        s.thinkLevel = sp.getInt("thinkLevel5", s.thinkLevel);

        s.memoryEnabled = sp.getBoolean("memoryEnabled", s.memoryEnabled);
        s.entityEnabled = sp.getBoolean("entityEnabled", s.entityEnabled);
        s.memoryStoreBudgetTokens = sp.getInt("memoryStoreBudgetTokens", s.memoryStoreBudgetTokens);
        s.selfStoreBudgetTokens = sp.getInt("selfStoreBudgetTokens", s.selfStoreBudgetTokens);

        s.imeTypingEnabled = sp.getBoolean("imeTypingEnabled", s.imeTypingEnabled);
        s.imeRestoreAfterTyping = sp.getBoolean("imeRestoreAfterTyping", s.imeRestoreAfterTyping);

        s.visionUseMainModel = sp.getBoolean("visionUseMainModel", s.visionUseMainModel);
        s.visionBaseUrl = spString(sp, "visionBaseUrl", s.visionBaseUrl);
        s.visionModel = spString(sp, "visionModel", s.visionModel);
        s.visionApiKey = spString(sp, "visionApiKey", s.visionApiKey);

        s.embeddingModel = spString(sp, "embeddingModel", s.embeddingModel);
        s.embeddingBaseUrl = spString(sp, "embeddingBaseUrl", s.embeddingBaseUrl);
        s.embeddingApiKey = spString(sp, "embeddingApiKey", s.embeddingApiKey);
        s.memoryUseVector = sp.getBoolean("memoryUseVector", s.memoryUseVector);

        s.webSearchEnabled = sp.getBoolean("webSearchEnabled", s.webSearchEnabled);
        s.useMemoryTools = sp.getBoolean("useMemoryTools", s.useMemoryTools);
        return s;
    }

    private static String spString(android.content.SharedPreferences sp, String key, String fallback) {
        String v = sp.getString(key, "");
        return (v == null || v.trim().isEmpty()) ? fallback : v;
    }

    private String resolvePackage(String app) {
        String a = app.trim().toLowerCase();
        if (a.contains(".")) return app.trim();
        if (a.contains("微信") && !a.contains("企业")) return "com.tencent.mm";
        if (a.contains("qq")) return "com.tencent.mobileqq";

        try {
            android.content.pm.PackageManager pm = getPackageManager();
            List<android.content.pm.ApplicationInfo> apps =
                    pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
            for (android.content.pm.ApplicationInfo ai : apps) {
                CharSequence label = pm.getApplicationLabel(ai);
                if (label != null && label.toString().equalsIgnoreCase(app.trim())) return ai.packageName;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private Notification buildNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "微信自动回复", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("自动回复运行中的临时通知，跑完自动消失");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, NOTIFY_ID, open, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            return b.setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("微信自动回复")
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } catch (Throwable t) {

            return new Notification();
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linyuan:autoreply");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60_000L);
        } catch (Throwable ignored) { }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) { }
        wakeLock = null;
    }

    private static String ok(String msg) {
        try { return new JSONObject().put("success", true).put("message", msg).toString(); }
        catch (Exception e) { return "{\"success\":true}"; }
    }

    private static String err(String msg) {
        try { return new JSONObject().put("success", false).put("error", msg).toString(); }
        catch (Exception e) { return "{\"success\":false}"; }
    }
}
