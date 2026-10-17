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
 * <p>为什么单独留一套无界面工具（不能直接用 MainActivity.handleAllTool）：那套工具实现挂在
 * Activity 上（浏览器面板、地图面板、无障碍界面都要有个活的界面），界面没了会崩。
 * 所以这里是**两级**：主助手还活着（{@code MainActivity.current()} 非空）就用它的完整工具集
 * （含派活 call_subagent，跟面板里说话等价）；进程还在但没有界面，才退回这套无界面工具。
 * 跟 TaskRunner 给定时任务的做法一致。
 *
 * <p>失败重试：模型请求连续失败最多 3 次、每次间隔约 1 分钟；全失败记一条失败日志
 * （面板「定时」按钮上会冒红点，数字就是没看过的失败次数）。
 */
public final class AssistAutoRunner {

    private AssistAutoRunner() { }

    /** 失败后最多再试 3 次（连同第一次，一轮最多 4 次请求 —— 用户定「最多重试3次」）。 */
    private static final int RETRY = 3;
    private static final long RETRY_GAP_MS = 60_000L;

    /**
     * 工具轮数上限：跟主对话/助手面板同一个来源（voice_companion 的 maxToolIterations）。
     *
     * <p>2026-10-17 改：原来这里写死 6、子智能体写死 8、面板夹在 20 —— 三个数各管一段，
     * 用户在设置里填多少都不作数。现在只有一处可配，全读它。
     */
    private static int maxIters(Context c) {
        int v = c.getSharedPreferences("voice_companion", Context.MODE_PRIVATE)
                .getInt("maxToolIterations", 500);
        if (v <= 0) v = 500;
        return v;
    }

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

            MainActivity act = MainActivity.current();
            if (act == null) {
                // 用户定案：不要"残缺版助手"兜底。没界面就把主助手拉起来，拉不起来如实失败（重试+记失败）。
                android.util.Log.i("VC_ASSIST_T", "定时激活：主助手不在，拉起它");
                act = MainActivity.wake(c);
            }
            if (act == null) {
                throw new Exception("主助手起不来（后台拉 Activity 被拦），这一轮没跑");
            }
            JSONArray tools = SubAgentRunner.fullAssistantTools(act, agentId);
            AiClient.ToolHandler handler = new AssistantToolHandler(act, agentId);
            s = act.buildAssistantSettings(model, baseUrl, apiKey, thinkLevel);
            android.util.Log.i("VC_ASSIST_T", "定时激活走主助手完整工具集（" + tools.length() + " 个工具）");
            AiClient.ChatResult r = AiClient.chatDirect(
                    s.baseUrl, s.apiKey, s.model, sys, hist,
                    "【自我激活】现在是你自己定下的时间到了。按上面说的做这一步。",
                    tools, maxIters(c), handler, thinkLevel);
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
        // 总开关关着的时候排了也不会触发（AssistFireTimes.next 直接返回 0），
        // 所以这里必须挡住，不能让助手回一句"定了"然后就没了（2026-10-16 用户就是被这么坑的）。
        if (!AssistTimerStore.enabled(c)) {
            return err("定时激活总开关关着（用户还没在助手面板的「定时」里打开并保存），"
                    + "现在排了也不会触发。先跟用户说清楚，让他去打开那个开关。");
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

    private static String err(String msg) {
        try { return new JSONObject().put("success", false).put("error", msg).toString(); }
        catch (Exception e) { return "{\"success\":false}"; }
    }
}
