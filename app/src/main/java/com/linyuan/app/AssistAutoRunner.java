package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时激活主助手：不用打开界面，自己跑一轮，把结论写回面板当前对话。
 *
 * <p>为什么单独写一套（不复用 MainActivity.handleAllTool）：那套工具实现挂在 Activity 上
 * （浏览器面板、地图面板、无障碍界面都要有个活的界面），无界面下会崩。
 * 这里只带**不依赖界面**的那部分工具，跟 TaskRunner 给定时任务的做法一样。
 *
 * <p>失败重试：模型请求连续失败最多 3 次、每次间隔约 1 分钟；全失败记一条失败日志
 * （面板「定时」按钮上会冒红点，数字就是没看过的失败次数）。
 */
public final class AssistAutoRunner {

    private AssistAutoRunner() { }

    /** 失败后最多再试 3 次（连同第一次，一轮最多 4 次请求 —— 用户定「最多重试3次」）。 */
    private static final int RETRY = 3;
    private static final long RETRY_GAP_MS = 60_000L;
    private static final int MAX_ITERS = 6;

    /** 入口。跑完自己排下一次。 */
    public static void run(Context ctx, String src) {
        final Context c = ctx.getApplicationContext();
        try {
            // 1) 总开关关着就什么都不做（用户定：默认关闭，必须他选好并保存）
            if (!AssistTimerStore.enabled(c)) {
                android.util.Log.i("VC_ASSIST_T", "定时激活：总开关关着，跳过");
                AssistTimer.reschedule(c);   // 只保留"撤掉闹钟"的效果
                return;
            }

            // 2) 摘掉本次（已到点）的一次性时间点，再排下一个 ——
            //    摘除在跑之前做：即使这一轮请求全失败，这个时间点也不会再触发第二次。
            consumeDueSelf(c);
            AssistTimer.reschedule(c);

            String err = null;
            final int attempts = RETRY + 1;   // 第一次 + 最多 3 次重试
            for (int i = 0; i < attempts; i++) {
                try {
                    String out = runOnce(c);
                    if (out != null) {
                        android.util.Log.i("VC_ASSIST_T", "定时激活完成（第 " + (i + 1) + " 次尝试）");
                        return;
                    }
                    err = "本轮没有产出内容";
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                    if (err.length() > 300) err = err.substring(0, 300);
                    android.util.Log.w("VC_ASSIST_T", "定时激活第 " + (i + 1) + " 次失败: " + err);
                }
                if (i < attempts - 1) {
                    try { Thread.sleep(RETRY_GAP_MS); } catch (InterruptedException ie) { return; }
                }
            }
            AssistTimerStore.recordFail(c, err, src);
            android.util.Log.w("VC_ASSIST_T", "定时激活重试 " + RETRY + " 次全失败：" + err);
        } catch (Throwable t) {
            android.util.Log.w("VC_ASSIST_T", "定时激活调度异常: " + t);
        }
    }

    /** 摘掉所有已到点的一次性时间点，返回是否摘到了。 */
    private static boolean consumeDueSelf(Context c) {
        long border = System.currentTimeMillis() + 30_000L;
        boolean hit = false;
        for (AssistTimerStore.SelfAt s : AssistTimerStore.selfList(c)) {
            if (s.at <= border) {
                AssistTimerStore.removeSelf(c, s.id);
                hit = true;
            }
        }
        return hit;
    }

    private static boolean hadDueSelf(Context c) {
        long border = System.currentTimeMillis() + 30_000L;
        for (AssistTimerStore.SelfAt s : AssistTimerStore.selfList(c)) {
            if (s.at <= border) return true;
        }
        return false;
    }

    /** 面板对话里也用同一个工具（界面版只是多了别的工具，行为完全一致）。 */
    public static String runScheduleTool(Context c, JSONObject args) throws Exception {
        return handleScheduleSelf(c, args);
    }

    /** 把「你现在排了哪些时间」写进助手提示词（界面版和无界面版共用）。 */
    public static void appendTimerStatePublic(StringBuilder sb, Context c) {
        appendTimerState(sb, c);
    }

    /** 面板配置：服务商 / 模型 / 思考档位 / 当前助手。 */
    private static SharedPreferences pprefs(Context c) {
        return c.getSharedPreferences(AssistantPanel.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 真跑一轮。
     * @return 成功返回写回的内容；失败抛异常。
     */
    private static String runOnce(Context c) throws Exception {
        SharedPreferences pp = pprefs(c);
        String model = pp.getString("model", "");
        String baseUrl = pp.getString("baseUrl", "");
        String apiKey = pp.getString("apiKey", "");
        if (model == null || model.trim().isEmpty()) {
            throw new Exception("没选服务商和模型（去面板设置里选）");
        }
        int thinkLevel = ReasoningNegotiator.clampLevel(pp.getInt("thinkLevel", -1));
        String agentId = pp.getString("activeAgent", "main");
        String rolePrompt = pp.getString("role_" + agentId, "");

        AppDatabase db = new AppDatabase(c);
        try {
            long convId = pp.getLong("convId", 0);
            if (convId <= 0) {
                List<AppDatabase.ConversationSummary> convs = db.listAssistantConversations();
                convId = convs.isEmpty() ? db.getFreshAssistantConversationId()
                        : convs.get(0).conversationId;
            }

            AppSettings s = AutoReplyService.loadSettingsHeadless(c);
            s.model = model.trim();
            s.chatModel = model.trim();
            if (baseUrl != null && !baseUrl.trim().isEmpty()) {
                s.baseUrl = baseUrl.trim();
                s.chatBaseUrl = baseUrl.trim();
            }
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                s.apiKey = apiKey.trim();
                s.chatApiKey = apiKey.trim();
            }
            if (thinkLevel >= 0) s.thinkLevel = thinkLevel;

            String sys = headlessPrompt(c, db, rolePrompt);
            List<AppDatabase.MessageItem> hist = tailOf(db.listMessagesInConversation(convId, true), 40);

            AiClient.ChatResult r = AiClient.chatDirect(
                    s.baseUrl, s.apiKey, s.model, sys, hist,
                    "【自我激活】现在是你自己定下的时间到了。按上面说的做这一步。",
                    buildTools(s), MAX_ITERS, buildHandler(c, db, s), thinkLevel);
            String out = r == null || r.content == null ? "" : r.content.trim();
            if (out.isEmpty()) return null;

            db.insertMessage("assistant", "⏰ 【定时激活】\n" + out, null, model.trim(), 0, convId, true);
            return out;
        } finally {
            try { db.close(); } catch (Exception ignored) { }
        }
    }

    private static List<AppDatabase.MessageItem> tailOf(List<AppDatabase.MessageItem> all, int n) {
        if (all == null) return new ArrayList<>();
        if (all.size() <= n) return all;
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    // ---------- 提示词（无界面版：不依赖 Activity，但和信息面尽量对齐） ----------

    private static String headlessPrompt(Context c, AppDatabase db, String rolePrompt) {
        SharedPreferences sp = c.getSharedPreferences("voice_companion", Context.MODE_PRIVATE);
        String base = sp.getString("systemPrompt", "");
        if (base == null || base.trim().isEmpty()) base = MainActivity.DEFAULT_SYSTEM_PROMPT;

        StringBuilder sb = new StringBuilder(AiClient.timeLineForPrompt());
        try { sb.append(PhoneAwareness.promptBlock(c, db, true)); } catch (Throwable ignored) { }

        sb.append("【Language】Reason in **English**. Everything the user reads must be in **Chinese**.\n\n");
        sb.append(base.trim()).append("\n");

        if (rolePrompt != null && !rolePrompt.trim().isEmpty()) {
            sb.append("\n").append(rolePrompt.trim()).append("\n");
        }

        // 它自己的四本（序/事/判/自述）—— 无界面下自己列，逻辑与界面版一致
        appendStore(sb, db, AssistantMemory.STORE_ORDER,
                "order - preferences / conventions / rules / skills (read before starting)");
        appendStore(sb, db, AssistantMemory.STORE_EPISODE,
                "episode - what you did and how it turned out");
        appendStore(sb, db, AssistantMemory.STORE_JUDGE,
                "judge - key judgments and the history of rule changes");
        appendStore(sb, db, AssistantMemory.STORE_SELF, "self - your private notes");

        // 感知树骨架（同界面版：只给标题，细节自己查）
        try { sb.append(PerceptionStore.skeletonBlock(db)); } catch (Throwable ignored) { }

        sb.append(MainActivity.buildAssistantWorkBlock());
        sb.append(MainActivity.buildBusinessFrameworkBlock());

        // 这次是「自我激活」的处境说明（跟用户当面说话时不一样：没人在等回复）
        sb.append("\n【This run is a self-activated timer, not the user speaking】\n");
        sb.append("Nobody is waiting on a reply right now - you woke yourself up on the schedule you set. ")
                .append("Work the step you had in mind, using the memory above and the phone status, then write down what you did ")
                .append("(it lands in your panel conversation, and the user reads it there when he opens it).\n");
        sb.append("If the next step needs to happen later, set your own wake-up with schedule_self. ")
                .append("One-shot: each time you set fires once and then disappears - if you want it again after that, set it again then.\n");
        sb.append("You can also drop the times you set yourself (schedule_self action=remove/list). ")
                .append("The times the user set are his, not yours - you cannot change those.\n");
        appendTimerState(sb, c);
        return sb.toString();
    }

    /** 把「你现在排了哪些时间」写给它看，免得它重复排。 */
    static void appendTimerState(StringBuilder sb, Context c) {
        try {
            sb.append("\n【Your current schedule】\n");
            boolean on = AssistTimerStore.enabled(c);
            List<String> times = AssistTimerStore.times(c);
            String days = AssistTimerStore.daysRaw(c);
            if (on && !times.isEmpty()) {
                sb.append("· The user's repeating times: ").append(String.join(", ", times));
                sb.append(days == null || days.trim().isEmpty() ? " (every day)" : (" (weekdays " + days + ")"));
                sb.append(" - not yours to change.\n");
            } else {
                sb.append("· The user has no repeating times switched on.\n");
            }
            List<AssistTimerStore.SelfAt> self = AssistTimerStore.selfList(c);
            if (self.isEmpty()) {
                sb.append("· You have nothing of your own scheduled right now.\n");
            } else {
                sb.append("· Yours, one-shot each (").append(AssistFireTimes.SELF_MIN_LEAD_MIN)
                        .append("min-").append(AssistFireTimes.SELF_MAX_DAYS).append("d ahead, one created per ")
                        .append(AssistFireTimes.SELF_CREATE_MIN_INTERVAL_MIN).append("min):\n");
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
                for (AssistTimerStore.SelfAt a : self) {
                    sb.append("   - id=").append(a.id).append(" at ").append(f.format(new java.util.Date(a.at)));
                    if (a.note != null && !a.note.trim().isEmpty()) sb.append(" (").append(a.note.trim()).append(")");
                    sb.append("\n");
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void appendStore(StringBuilder sb, AppDatabase db, String store, String title) {
        try {
            List<AppDatabase.MemoryItem> items = db.listMemoriesByStore(store);
            sb.append("\n【").append(title).append("】\n");
            if (items.isEmpty()) {
                sb.append("(nothing yet)\n");
                return;
            }
            int used = 0;
            for (AppDatabase.MemoryItem it : items) {
                int t = AiClient.estimateTokensText(it.content);
                if (used + t > 120000 && used > 0) break;
                sb.append("- ").append(it.content.trim()).append("\n");
                used += t;
            }
        } catch (Throwable ignored) { }
    }

    // ---------- 工具（无界面子集） ----------

    private static JSONArray buildTools(AppSettings s) {
        JSONArray tools = new JSONArray();
        try {
            // schedule_self：设置 / 查看 / 移除它自己的触发时间
            JSONObject st = new JSONObject();
            st.put("name", "schedule_self");
            st.put("description", "Set, list or remove your OWN one-shot wake-up times (for continuing a task later). "
                    + "Each time you set fires ONCE and then disappears - if you need it again after that, set it again at that point. "
                    + "Limits (enforced by the system): at least 5 minutes ahead, at most 15 days ahead, "
                    + "one created per 5 minutes at most (you cannot set a dozen in the same minute), "
                    + "and any two of yours at least 5 minutes apart. A rejected add comes back with the reason and your current list. "
                    + "To repeat a step, wake yourself, do it, then set the next one from there. "
                    + "The user's repeating times are not yours to touch - this tool only manages what you set.");
            JSONObject sp = new JSONObject();
            sp.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("add").put("list").put("remove")));
            sp.put("delay_minutes", new JSONObject().put("type", "integer")
                    .put("description", "For add, relative form: wake me in N minutes (at least 5, at most 21600 = 15 days). E.g. 60 = one hour from now."));
            sp.put("date", new JSONObject().put("type", "string")
                    .put("description", "For add, absolute form: the date, format YYYY-MM-DD (must be within 15 days). Use with time."));
            sp.put("times", new JSONObject().put("type", "string")
                    .put("description", "For add, absolute form: one or more clock times, HH:mm (24-hour), comma-separated, "
                            + "e.g. \"03:00, 09:30, 21:00\". Use with date - this is how you set several times on one day."));
            sp.put("note", new JSONObject().put("type", "string")
                    .put("description", "For add, optional: one line on what this wake-up is for (your own note)."));
            sp.put("id", new JSONObject().put("type", "integer")
                    .put("description", "For remove: the id of the entry to drop (see action=list)."));
            st.put("parameters", new JSONObject().put("type", "object").put("properties", sp)
                    .put("required", new JSONArray().put("action")));
            tools.put(new JSONObject().put("type", "function").put("function", st));

            // 记忆工具（写它自己的三本 + 自述）
            JSONObject mm = new JSONObject();
            mm.put("name", "manage_memory");
            mm.put("description", "Add/edit/delete your own long-term memory. Three sections: "
                    + "target=order for preferences/conventions/rules/skills; target=episode for things you did and their outcomes; "
                    + "target=judge for key judgments and the history of rule changes. "
                    + "add inserts; replace rewrites the entry whose text contains old_text; remove deletes by old_text; list shows the section.");
            JSONObject mp = new JSONObject();
            mp.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("add").put("replace").put("remove").put("list")));
            mp.put("target", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("order").put("episode").put("judge")));
            mp.put("content", new JSONObject().put("type", "string").put("description", "The content to write (for add/replace)"));
            mp.put("old_text", new JSONObject().put("type", "string")
                    .put("description", "A unique substring locating the entry (for replace/remove)"));
            mm.put("parameters", new JSONObject().put("type", "object").put("properties", mp)
                    .put("required", new JSONArray().put("action").put("target")));
            tools.put(new JSONObject().put("type", "function").put("function", mm));

            JSONObject sn = new JSONObject();
            sn.put("name", "self_note");
            sn.put("description", "Your own private notebook - only you read it. What goes in is your call. "
                    + "add appends one entry; replace rewrites the entry containing old_text; remove deletes it; list shows everything.");
            JSONObject snp = new JSONObject();
            snp.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("add").put("replace").put("remove").put("list")));
            snp.put("content", new JSONObject().put("type", "string").put("description", "The content to write (for add/replace)"));
            snp.put("old_text", new JSONObject().put("type", "string").put("description", "A unique substring locating the entry"));
            sn.put("parameters", new JSONObject().put("type", "object").put("properties", snp)
                    .put("required", new JSONArray().put("action")));
            tools.put(new JSONObject().put("type", "function").put("function", sn));

            // 翻历史（主对话 + 面板自己的会话都搜，2026-10-13）
            JSONObject ss = new JSONObject();
            ss.put("name", "search_sessions");
            ss.put("description", "Full-text search over past messages - both the main conversations with the user and your own panel history - for recalling what was discussed or decided before.");
            JSONObject ssp = new JSONObject();
            ssp.put("query", new JSONObject().put("type", "string").put("description", "Search keywords"));
            ss.put("parameters", new JSONObject().put("type", "object").put("properties", ssp)
                    .put("required", new JSONArray().put("query")));
            tools.put(new JSONObject().put("type", "function").put("function", ss));

            // 查看用户侧记忆库（只读；用户 2026-10-13 定：助手能看不能改）
            JSONObject rml = new JSONObject();
            rml.put("name", "read_memory_library");
            rml.put("description", "Read-only search over the **user-side memory library**: long-term memory about the user"
                    + " (his facts, preferences, conclusions, saved details) plus the main conversation's own notes."
                    + " **Read-only** - you cannot add, edit or delete these entries; your own books go through manage_memory and self_note.");
            JSONObject rmlProps = new JSONObject();
            rmlProps.put("query", new JSONObject().put("type", "string")
                    .put("description", "Optional keywords; omit to list what is in the library"));
            rmlProps.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "Max entries to return, default 30, max 100"));
            rml.put("parameters", new JSONObject().put("type", "object").put("properties", rmlProps)
                    .put("required", new JSONArray()));
            tools.put(new JSONObject().put("type", "function").put("function", rml));

            // 感知树（用户 2026-10-13）：档案树的增删改查；标题骨架已在上面注入了。
            tools.put(new JSONObject().put("type", "function").put("function",
                    PerceptionStore.buildToolSchema()));

            if (s != null && s.webSearchEnabled) {
                JSONObject ws = new JSONObject();
                ws.put("name", "web_search");
                ws.put("description", "Search the web for current facts (news / live data / policy / prices).");
                JSONObject wsp = new JSONObject();
                wsp.put("query", new JSONObject().put("type", "string").put("description", "Search keywords"));
                wsp.put("freshness", new JSONObject().put("type", "string")
                        .put("description", "Optional time range: dN = last N days, mN = months, yN = years; omit = no limit"));
                ws.put("parameters", new JSONObject().put("type", "object").put("properties", wsp)
                        .put("required", new JSONArray().put("query")));
                tools.put(new JSONObject().put("type", "function").put("function", ws));

                JSONObject fu = new JSONObject();
                fu.put("name", "fetch_url");
                fu.put("description", "Fetch and read the body text of a web page.");
                JSONObject fup = new JSONObject();
                fup.put("url", new JSONObject().put("type", "string").put("description", "Full URL (http/https)"));
                fu.put("parameters", new JSONObject().put("type", "object").put("properties", fup)
                        .put("required", new JSONArray().put("url")));
                tools.put(new JSONObject().put("type", "function").put("function", fu));
            }
        } catch (Exception ignored) { }
        return tools;
    }

    private static AiClient.ToolHandler buildHandler(final Context c, final AppDatabase db, final AppSettings s) {
        return (name, args) -> {
            if ("schedule_self".equals(name)) return handleScheduleSelf(c, args);
            if ("manage_memory".equals(name)) return handleManageMemory(db, args);
            if ("self_note".equals(name)) return handleSelfNote(db, args);
            if ("search_sessions".equals(name)) {
                String q = args == null ? "" : args.optString("query", "").trim();
                if (q.isEmpty()) return err("query must not be empty");
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
                JSONArray arr = new JSONArray();
                for (AppDatabase.MessageItem m : db.searchMessages(q, 20, true)) {
                    String cc = m.content == null ? "" : m.content;
                    int t = cc.indexOf("@@THINKING:");
                    if (t >= 0) cc = cc.substring(0, t).trim();
                    arr.put(new JSONObject().put("time", f.format(new java.util.Date(m.createdAt)))
                            .put("role", "user".equals(m.role) ? "user" : "you").put("text", cc));
                }
                return new JSONObject().put("results", arr).put("now", AiClient.nowText()).toString();
            }
            if ("web_search".equals(name)) {
                String q = args == null ? "" : args.optString("query", "").trim();
                if (q.isEmpty()) return err("query must not be empty");
                try {
                    JSONArray rs = AiClient.searchWebPublic(s, q, args.optString("freshness", "").trim());
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < rs.length(); i++) {
                        JSONObject r = rs.optJSONObject(i);
                        if (r == null) continue;
                        out.put(new JSONObject().put("title", r.optString("title", ""))
                                .put("url", r.optString("url", ""))
                                .put("snippet", r.optString("snippet", ""))
                                .put("date", r.optString("date", "unknown")));
                    }
                    return new JSONObject().put("results", out).put("now", AiClient.nowText()).toString();
                } catch (Exception e) {
                    return err("Search failed: " + e.getMessage());
                }
            }
            if ("fetch_url".equals(name)) {
                String u = args == null ? "" : args.optString("url", "").trim();
                if (u.isEmpty()) return err("url must not be empty");
                try {
                    return new JSONObject().put("url", u).put("text", AiClient.fetchUrlText(s, u))
                            .put("now", AiClient.nowText()).toString();
                } catch (Exception e) {
                    return err("Failed to fetch the page: " + e.getMessage());
                }
            }
            if ("read_memory_library".equals(name)) {
                String q = args == null ? "" : args.optString("query", "").trim();
                int limit = args == null ? 30 : args.optInt("limit", 30);
                limit = Math.max(1, Math.min(100, limit));
                JSONArray arr = new JSONArray();
                try {
                    for (AppDatabase.MemoryItem it : db.searchUserSideMemories(q, limit)) {
                        arr.put(new JSONObject()
                                .put("content", it.content == null ? "" : it.content)
                                .put("category", it.category == null ? "" : it.category)
                                .put("who", "self".equals(it.store) ? "主对话自己的笔记" : "用户")
                                .put("pinned", it.pinned));
                    }
                } catch (Exception e) {
                    return err("Read failed: " + e.getMessage());
                }
                return new JSONObject().put("success", true).put("entries", arr)
                        .put("note", "Read-only view of the user-side memory library.")
                        .toString();
            }
            // 感知树（用户 2026-10-13）
            if ("perception".equals(name)) {
                return PerceptionStore.handleTool(db, args);
            }
            return err("Tool not available in a self-activated run: " + name);
        };
    }

    private static String handleScheduleSelf(Context c, JSONObject args) throws Exception {
        String action = args == null ? "" : args.optString("action", "").trim();
        if ("list".equals(action)) {
            JSONArray arr = new JSONArray();
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            for (AssistTimerStore.SelfAt a : AssistTimerStore.selfList(c)) {
                arr.put(new JSONObject().put("id", a.id)
                        .put("at", f.format(new java.util.Date(a.at))).put("note", a.note));
            }
            return new JSONObject().put("success", true).put("entries", arr)
                    .put("now", AiClient.nowText()).toString();
        }
        if ("remove".equals(action)) {
            long id = args.optLong("id", 0);
            if (id <= 0) return err("remove needs the id (see action=list)");
            boolean hit = AssistTimerStore.removeSelf(c, id);
            AssistTimer.reschedule(c);
            return hit ? new JSONObject().put("success", true).put("message", "removed").toString()
                    : err("no entry with that id");
        }
        // add：支持一次给多个时间点（"某一天的某几个时间段"）
        List<Long> ats = new ArrayList<>();
        String delay = args.optString("delay_minutes", "").trim();
        if (!delay.isEmpty()) {
            try { ats.add(System.currentTimeMillis() + Long.parseLong(delay) * 60_000L); }
            catch (Exception ignored) { }
        } else {
            String date = args.optString("date", "").trim();
            String timesRaw = args.optString("times", "").trim();
            if (timesRaw.isEmpty()) timesRaw = args.optString("time", "").trim();
            if (!date.isEmpty() && !timesRaw.isEmpty()) {
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
                for (String one : timesRaw.split("[,，]")) {
                    String t = AssistTimerStore.norm(one.trim());
                    try {
                        java.util.Date d = f.parse(date + " " + t);
                        if (d != null) ats.add(d.getTime());
                    } catch (Exception ignored) { }
                }
            }
        }
        if (ats.isEmpty()) {
            return err("give either delay_minutes, or date (YYYY-MM-DD) plus times (e.g. \"03:00, 09:30\")");
        }
        AssistTimerStore.AddResult ar = AssistTimerStore.addSelfMany(c, ats, args.optString("note", ""));
        if (ar.id < 0) {
            // 把原因连同"你现在排了哪些"一起给回去 —— 它看到现状就知道该怎么办
            // （撤掉哪个、或者等哪个响完），不用靠猜。
            JSONObject o = new JSONObject().put("success", false)
                    .put("error", ar.why == null ? "could not schedule that time" : ar.why)
                    .put("now", AiClient.nowText());
            JSONArray cur = new JSONArray();
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            for (AssistTimerStore.SelfAt a : AssistTimerStore.selfList(c)) {
                cur.put(new JSONObject().put("id", a.id)
                        .put("at", f.format(new java.util.Date(a.at))).put("note", a.note));
            }
            o.put("your_schedule", cur);
            o.put("limits", new JSONObject()
                    .put("earliest_minutes_ahead", AssistFireTimes.SELF_MIN_LEAD_MIN)
                    .put("max_days_ahead", AssistFireTimes.SELF_MAX_DAYS)
                    .put("min_minutes_between_creations", AssistFireTimes.SELF_CREATE_MIN_INTERVAL_MIN)
                    .put("min_gap_minutes", AssistFireTimes.SELF_MIN_GAP_MIN));
            return o.toString();
        }
        AssistTimer.reschedule(c);
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
        JSONArray setAt = new JSONArray();
        List<Long> made = new ArrayList<>(ats);
        java.util.Collections.sort(made);
        for (Long at : made) setAt.put(f.format(new java.util.Date(at)));
        return new JSONObject().put("success", true).put("id", ar.id)
                .put("ids", new JSONArray(ar.ids == null ? new ArrayList<>() : ar.ids))
                .put("at", setAt)
                .put("message", "Set. Each fires once and then disappears.").toString();
    }

    private static String handleManageMemory(AppDatabase db, JSONObject args) throws Exception {
        String action = args == null ? "" : args.optString("action", "").trim();
        String target = args == null ? "episode" : args.optString("target", "episode").trim();
        if (!"order".equals(target) && !"judge".equals(target) && !"episode".equals(target)) target = "episode";
        String store = "order".equals(target) ? AssistantMemory.STORE_ORDER
                : ("judge".equals(target) ? AssistantMemory.STORE_JUDGE : AssistantMemory.STORE_EPISODE);
        String cat = "order".equals(target) ? "序" : ("judge".equals(target) ? "判" : "事");
        String content = args == null ? "" : args.optString("content", "").trim();
        String oldText = args == null ? "" : args.optString("old_text", "").trim();

        if ("list".equals(action)) {
            JSONArray entries = new JSONArray();
            for (AppDatabase.MemoryItem it : db.listMemoriesByStore(store)) entries.put(it.content);
            return new JSONObject().put("success", true).put("entries", entries).toString();
        }
        if ("add".equals(action)) {
            if (content.isEmpty()) return err("content must not be empty");
            for (AppDatabase.MemoryItem it : db.listMemoriesByStore(store)) {
                if (it.content.trim().equals(content)) {
                    return new JSONObject().put("success", true).put("message", "already there; not added again").toString();
                }
            }
            db.insertMemoryStore(content, cat, store);
            return new JSONObject().put("success", true).put("message", "saved to " + target).toString();
        }
        if ("replace".equals(action)) {
            if (oldText.isEmpty() || content.isEmpty()) return err("replace needs old_text and content");
            List<Long> ids = db.findMemoryIdsBySubstring(store, oldText);
            if (ids.isEmpty()) return err("no entry matches old_text");
            if (ids.size() > 1) return err("old_text matches more than one entry - be more specific");
            db.updateMemoryContent(ids.get(0), content);
            return new JSONObject().put("success", true).put("message", "updated").toString();
        }
        if ("remove".equals(action)) {
            if (oldText.isEmpty()) return err("remove needs old_text");
            List<Long> ids = db.findMemoryIdsBySubstring(store, oldText);
            if (ids.isEmpty()) return err("no entry matches old_text");
            for (Long id : ids) db.deleteMemory(id);
            return new JSONObject().put("success", true).put("message", "removed " + ids.size()).toString();
        }
        return err("unknown action");
    }

    private static String handleSelfNote(AppDatabase db, JSONObject args) throws Exception {
        String action = args == null ? "" : args.optString("action", "").trim();
        String content = args == null ? "" : args.optString("content", "").trim();
        String oldText = args == null ? "" : args.optString("old_text", "").trim();
        String store = AssistantMemory.STORE_SELF;
        if ("list".equals(action)) {
            JSONArray entries = new JSONArray();
            for (AppDatabase.MemoryItem it : db.listMemoriesByStore(store)) entries.put(it.content);
            return new JSONObject().put("success", true).put("entries", entries).toString();
        }
        if ("add".equals(action)) {
            if (content.isEmpty()) return err("content must not be empty");
            for (AppDatabase.MemoryItem it : db.listMemoriesByStore(store)) {
                if (it.content.trim().equals(content)) {
                    return new JSONObject().put("success", true).put("message", "already there; not added again").toString();
                }
            }
            String cat = args.optString("category", "").trim();
            db.insertMemoryStore(content, cat.isEmpty() ? "身份" : MemoryDim.normalizeSelf(cat), store);
            return new JSONObject().put("success", true).put("message", "noted").toString();
        }
        if ("replace".equals(action)) {
            if (oldText.isEmpty() || content.isEmpty()) return err("replace needs old_text and content");
            List<Long> ids = db.findMemoryIdsBySubstring(store, oldText);
            if (ids.isEmpty()) return err("no entry matches old_text");
            if (ids.size() > 1) return err("old_text matches more than one entry - be more specific");
            db.updateMemoryContent(ids.get(0), content);
            return new JSONObject().put("success", true).put("message", "updated").toString();
        }
        if ("remove".equals(action)) {
            if (oldText.isEmpty()) return err("remove needs old_text");
            List<Long> ids = db.findMemoryIdsBySubstring(store, oldText);
            if (ids.isEmpty()) return err("no entry matches old_text");
            for (Long id : ids) db.deleteMemory(id);
            return new JSONObject().put("success", true).put("message", "removed " + ids.size()).toString();
        }
        return err("unknown action");
    }

    private static String err(String msg) {
        try { return new JSONObject().put("success", false).put("error", msg).toString(); }
        catch (Exception e) { return "{\"success\":false}"; }
    }
}
