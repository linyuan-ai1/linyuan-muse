package com.linyuan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SubAgentRunner {

    static final int DEFAULT_CTX_TOKENS = 1000000;

    static final String[][] TOOL_CATALOG = {
            {"web_search", "联网搜索", "网络"},
            {"fetch_url", "抓取网页正文", "网络"},
            {"browser_open", "打开网址", "浏览器"},
            {"browser_search", "在搜索引擎里搜", "浏览器"},
            {"browser_read", "读当前网页正文", "浏览器"},
            {"browser_click", "点网页上的链接或按钮", "浏览器"},
            {"browser_links", "列出页面可点项", "浏览器"},
            {"browser_type", "在网页输入框里打字", "浏览器"},
            {"browser_back", "网页后退", "浏览器"},
            {"browser_close", "关掉内置浏览器", "浏览器"},
            {"get_device_status", "看设备状态（电量/网络等）", "设备"},
            {"get_system_info", "看系统信息", "设备"},
            {"phone_status", "看手机状态", "设备"},
            {"list_apps", "列出已装应用", "设备"},
            {"read_sms", "读短信", "设备"},
            {"list_contacts", "查联系人", "设备"},
            {"read_calendar", "读日历", "设备"},
            {"read_photos", "看相册照片", "设备"},
            {"read_phone_history", "读通话记录", "设备"},
            {"take_screenshot", "截屏", "设备"},
            {"media_control", "控制媒体播放", "设备"},
            {"flashlight", "开关手电筒", "设备"},
            {"set_volume", "调音量", "设备"},
            {"set_brightness", "调亮度", "设备"},
            {"set_dnd", "开关免打扰", "设备"},
            {"set_alarm", "设闹钟", "设备"},
            {"add_calendar_event", "加日历日程", "设备"},
            {"open_app", "打开应用", "设备"},
            {"open_maps_app", "调起地图 App", "设备"},
            {"make_call", "打电话", "设备"},
            {"send_sms", "发短信", "设备"},
            {"contacts", "通讯录相关", "设备"},
            {"accessibility", "按无障碍操作界面", "操作"},
            {"tap_script", "按脚本点击", "操作"},
            {"app_control", "控制应用", "操作"},
            {"file_manager", "读写文件", "文件"},
            {"save_document", "存文档", "文件"},
            {"generate_image", "生成图片", "生成"},
            {"list_image_models", "列出画图模型", "生成"},
            {"map_open", "地图上显示位置", "地图"},
            {"map_search", "按关键词找地点", "地图"},
            {"map_route", "路线规划", "地图"},
            {"map_navigate", "开始导航", "地图"},
            {"map_locate", "定位当前位置", "地图"},
            {"map_weather", "查天气", "地图"},
            {"map_layer", "切换地图图层", "地图"},
            {"map_stop", "停止导航", "地图"},
            {"map_close", "关掉地图", "地图"},
            {"create_task", "创建定时任务", "任务"},
            {"list_tasks", "列出定时任务", "任务"},
            {"manage_task", "暂停/恢复/取消任务", "任务"},
            {"manage_memory", "读写长期记忆", "记忆"},
            {"self_note", "写自我笔记", "记忆"},
            {"read_memory_library", "查看用户侧记忆库（只读）", "记忆"},
            {"perception", "感知树（手机档案树）", "记忆"},
            {"search_sessions", "翻自己的聊天记录", "隐私"},
            {"list_group_rooms", "列出群聊房间", "隐私"},
            {"read_group_chat", "读群聊内容", "隐私"},
            {"schedule_self", "设置自己的触发时间", "定时"},
    };

    static final String[] ASST_ONLY_GROUPS = {"记忆", "隐私", "定时"};

    /**
     * 主对话专属的工具（主助手侧一律不给）。
     *
     * <p>用户 2026-10-17 定案：定时任务三件套从主助手**剥离**（不是禁用）。
     * 区别在于——定时激活（schedule_self）到点是"叫醒一个有判断力的助手"，
     * 它可以自己决定干什么、怎么干、派谁去干；定时任务（create_task）到点只能照着一条
     * 写死的指令垂直执行。两者不是一回事，不一样的东西不该给同一个人同时用。
     * 主助手要排时间就用 schedule_self，不要用这套。
     */
    static final String[] MAIN_CHAT_ONLY_TOOLS = {
            // 定时任务三件套：到点只能照一条写死的指令垂直跑，那是主对话那套（用户 2026-10-17 剥离）
            "create_task", "list_tasks", "manage_task",
            // 内置浏览器 8 件套：要有人看着（主对话里用户能看见网页在动）；主助手无人值守跑，
            // 浏览器没人看，联网搜索已经够用（用户 2026-10-17 剥离）
            "browser_open", "browser_search", "browser_read", "browser_click",
            "browser_links", "browser_type", "browser_back", "browser_close",
    };

    static boolean isMainChatOnly(String tool) {
        if (tool == null) return false;
        for (String t : MAIN_CHAT_ONLY_TOOLS) if (t.equals(tool)) return true;
        return false;
    }

    /** 屏幕操作类工具：一台手机同一时刻只能有一个无障碍操作者，
     *  两个人同时驱动会互相抢焦点、抢输入框，谁都干不成。
     *  所以这类工具是独占的，第二个带它的子智能体不许建。*/
    static final String[] SCREEN_TOOLS = {"accessibility", "tap_script"};

    static boolean isScreenTool(String tool) {
        if (tool == null) return false;
        for (String s : SCREEN_TOOLS) if (s.equals(tool)) return true;
        return false;
    }

    static boolean usesScreenTools(List<String> tools) {
        if (tools == null) return false;
        for (String s : tools) if (isScreenTool(s)) return true;
        return false;
    }

    static boolean asstOnly(String tool) {
        for (String group : ASST_ONLY_GROUPS) {
            for (String[] row : TOOL_CATALOG) {
                if (row[0].equals(tool) && row[2].equals(group)) return true;
            }
        }
        return false;
    }

    /**
     * 可以勾选的工具清单。
     *
     * <p>forSubAgent=true  = 能授权给子智能体（排掉助手专属的记忆/隐私/定时）；
     * forSubAgent=false = 主助手自己能用的（排掉主对话专属的定时任务三件套）。
     */
    static List<String> grantableNames(boolean forSubAgent) {
        List<String> out = new ArrayList<>();
        for (String[] row : TOOL_CATALOG) {
            if (forSubAgent && asstOnly(row[0])) continue;
            if (!forSubAgent && isMainChatOnly(row[0])) continue;
            out.add(row[0]);
        }
        return out;
    }

    static String toolLabel(String name) {
        if (name == null) return "";
        for (String[] row : TOOL_CATALOG) {
            if (row[0].equals(name)) return row[1];
        }
        return name;
    }

    static boolean knownTool(String name) {
        if (name == null) return false;
        for (String[] row : TOOL_CATALOG) {
            if (row[0].equals(name)) return true;
        }
        return false;
    }

    static JSONArray buildTools(MainActivity act, List<String> granted) {
        JSONArray out = new JSONArray();
        if (granted == null || granted.isEmpty()) return out;
        JSONArray all = act.buildAssistantTools();
        for (String name : granted) {
            // 主助手侧不给主对话专属的定时任务三件套：旧 prefs 里存着也在这里排掉
            if (isMainChatOnly(name)) continue;
            for (int i = 0; i < all.length(); i++) {
                JSONObject t = all.optJSONObject(i);
                JSONObject fn = t == null ? null : t.optJSONObject("function");
                if (fn != null && name.equals(fn.optString("name"))) {
                    out.put(t);
                    break;
                }
            }
        }
        return out;
    }

    /** 主助手在面板里勾选的那套工具（派活/管理两个由 fullAssistantTools 单独补）。 */
    static List<String> grantedToolsFromPrefs(android.content.Context c) {
        String raw = c.getSharedPreferences(AssistantPanel.PREFS, android.content.Context.MODE_PRIVATE)
                .getString("mainTools", "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && knownTool(t)) out.add(t);
        }
        return out;
    }

    /** 面板里说话用的那一整套工具：勾选的 + 派活 + 管理。
     *  后台（定时任务 / 定时激活）在主助手还活着时也用它，这样"定时唤醒"跟"当面说"是同一套能力。 */
    static JSONArray fullAssistantTools(MainActivity act, String agentId) throws Exception {
        JSONArray tools = buildTools(act, grantedToolsFromPrefs(act));
        JSONArray subTools = buildTool(SubAgentStore.list(act, agentId));
        for (int i = 0; i < subTools.length(); i++) tools.put(subTools.get(i));
        List<String> grantable = grantableNames(true);
        List<String> provNames = new ArrayList<>();
        for (AsstProviderStore.Prov pvd : AsstProviderStore.list(act)) {
            provNames.add(pvd.name + "（" + joinText(pvd.modelList()) + "）");
        }
        JSONArray mgTools = buildManageTool(grantable, provNames);
        for (int i = 0; i < mgTools.length(); i++) tools.put(mgTools.get(i));
        return tools;
    }

    static String toolsText(List<String> granted) {
        if (granted == null || granted.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : granted) {
            if (sb.length() > 0) sb.append("、");
            sb.append(toolLabel(t));
        }
        return sb.toString();
    }

    static int ctxOf(SubAgentStore.Sub sub) {
        return sub != null && sub.ctxTokens > 0 ? sub.ctxTokens : DEFAULT_CTX_TOKENS;
    }

    public static String run(MainActivity act, SubAgentStore.Sub sub, String task) {
        return run(act, sub, task, "");
    }

    public static String run(MainActivity act, SubAgentStore.Sub sub, String task, String caller) {
        return run(act, sub, task, caller, null);
    }

    /**
     * 跑一次子智能体（阻塞到跑完）。stop 非空时可被外面叫停 —— 灵动岛连点三下暂停屏幕操作员走这里。
     *
     * <p>面板/主助手用的入口是 {@link #dispatch}：立刻回真回执、后台并行跑、跑完叫醒主助手
     * （用户 2026-10-16 定：不必一直卡着等它，不然根本没法并行）。
     */
    public static String run(MainActivity act, SubAgentStore.Sub sub, String task, String caller,
                             java.util.concurrent.atomic.AtomicBoolean stop) {
        if (stop != null && stop.get()) return err("Stopped before it started");
        if (sub == null) return err("No such sub-agent");
        if (!sub.usable()) return err("Sub-agent \"" + sub.name + "\" is not set up yet (needs a provider + model)");
        String t = task == null ? "" : task.trim();
        if (t.isEmpty()) return err("No task content given");

        int ctxBudget = sub.ctxTokens > 0 ? sub.ctxTokens : DEFAULT_CTX_TOKENS;
        int digestCap = Math.max(4000, ctxBudget * 2);

        List<String> granted = sub.toolList();
        if (granted.isEmpty()) granted = SubAgentStore.subList(SubAgentStore.DEFAULT_TOOLS);

        long startAt = System.currentTimeMillis();
        SubAgentLog.Run log = new SubAgentLog.Run();
        log.at = startAt;
        log.caller = caller == null ? "" : caller.trim();
        log.task = t;
        log.steps.add(node("DECIDE", "接到任务，按职责判断该怎么做",
                "职责：" + brief(sub.purpose, 60), "已受理", ""));
        String toolNames = toolsText(granted);
        if (!toolNames.isEmpty()) {
            log.steps.add(node("OBSERVE", "看清自己手上有什么工具",
                    "本任务可用工具：" + toolNames, "已确认", ""));
        }

        try {
            AppSettings s = act.buildAssistantSettings(sub.model, sub.baseUrl, sub.apiKey, sub.thinkLevel);
            String sys = buildSystemPrompt(sub, granted);
            AgentToolHandler handler = new AgentToolHandler(act);
            handler.digestCap = digestCap;
            AiClient.ChatResult r = AiClient.chatWithToolsStream(
                    s, sys, new ArrayList<AppDatabase.MessageItem>(), t,
                    buildTools(act, granted), handler, maxIters(act), null, stop, null);
            String out = r == null || r.content == null ? "" : r.content.trim();
            int toolCalls = countLines(r == null ? null : r.toolLog);
            appendToolNodes(log, handler, r);

            if (out.isEmpty()) {
                android.util.Log.w("VC_SUBAGENT", "「" + sub.name + "」第一轮只调工具没写结论"
                        + "（工具日志 " + (r == null ? "无" : String.valueOf(r.toolLog == null
                        ? 0 : r.toolLog.length())) + " 字）→ 去掉工具，带着资料要一次结论");
                String digest = handler.digest();
                if (digest.isEmpty()) {
                    log.outcome = "fail";
                    log.summary = "只查资料没写出结论，且没拿到任何内容";
                    log.steps.add(node("EXCEPTION", "没有再查下去，因为查到的资料为空",
                            "停止并回报委托方", "失败", "空转无产出"));
                    finish(act, sub, log, startAt, "" , false);
                    return err("Sub-agent \"" + sub.name + "\" produced no result (it kept gathering data but never wrote a conclusion)");
                }
                log.steps.add(node("DECIDE", "发现它只查不写，判断不能就这么交",
                        "撤掉工具，把已查到的资料塞回去再要一次结论", "重试一次", ""));
                String wrapTask = "[Original task]\n" + t
                        + "\n\n[Data gathered so far]\n" + digest
                        + "\n\nDo no further research. Write the task result now, based solely on the data above.";
                AiClient.ChatResult r2 = AiClient.chatWithToolsStream(
                        s, sys, new ArrayList<AppDatabase.MessageItem>(), wrapTask,
                        null, null, 1, null, stop, null);
                out = r2 == null || r2.content == null ? "" : r2.content.trim();
            }

            if (out.isEmpty()) {
                log.outcome = "fail";
                log.summary = "没有产出内容";
                log.steps.add(node("EXCEPTION", "两次都没拿到正文",
                        "停止并回报委托方", "失败", "模型未产出"));
                finish(act, sub, log, startAt, "", false);
                return err("Sub-agent \"" + sub.name + "\" returned no content");
            }

            boolean narr = looksLikeNarration(out);
            log.steps.add(node(narr ? "EXCEPTION" : "OUTPUT",
                    narr ? "交回来的是过程碎碎念，不是汇报" : "拿到结果，判断可以交付",
                    "交出 " + out.length() + " 字", narr ? "不合格" : "成功",
                    narr ? "要求按 做了什么/结果/证据/没做到 四段交，它没做到" : ""));
            boolean cut = r != null && r.truncated;
            log.outcome = (cut || narr) ? "part" : "ok";
            if (narr) {
                log.lesson = "交回来的是过程碎碎念，不是汇报（已作废标 ok）";
                out = "⚠️ 这一份不是按汇报格式交的（下面可能是它的过程原文，别当成已验证的结论）：\n" + out;
            }
            log.summary = brief(out, 160);
            if (!narr && toolCalls == 0) {
                log.lesson = "这次没用工具就直接答了；时效性内容应至少查一次再下结论";
            }
            finish(act, sub, log, startAt, out, cut);

            JSONObject o = new JSONObject();
            o.put("success", true);
            o.put("agent", sub.name);
            o.put("content", out);
            if (cut) o.put("truncated", true);
            return o.toString();
        } catch (Throwable e) {
            String m = e.getMessage();
            log.outcome = "fail";
            log.summary = "执行出错：" + brief(m, 100);
            log.steps.add(node("EXCEPTION", "上游/网络出错，无法继续",
                    "停止并回报委托方", "失败", brief(m, 60)));
            finish(act, sub, log, startAt, "", false);
            return err("Sub-agent \"" + sub.name + "\" failed: " + (m == null ? e.toString() : m));
        }
    }


    // ── 异步派活：真回执 + 后台并行 + 跑完唤醒主助手（用户 2026-10-16 定） ──────────────────

    /** 一个正在后台跑的子智能体活儿。 */
    /**
     * 工具轮数上限：跟主对话/面板同一个来源（voice_companion 的 maxToolIterations）。
     *
     * <p>2026-10-17 改：原来这里写死 8、定时激活写死 6、面板夹在 20 —— 三个数各管一段，
     * 用户在设置里填多少都不作数。现在只有一处可配，全读它。
     */
    static int maxIters(android.content.Context c) {
        int v = c.getSharedPreferences("voice_companion", android.content.Context.MODE_PRIVATE)
                .getInt("maxToolIterations", 500);
        if (v <= 0) v = 500;
        return v;
    }

    static final class Job {
        final String id;
        final SubAgentStore.Sub sub;
        final String caller;
        final String task;
        final long startedAt = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile String state = "运行中";
        volatile String result = "";
        /** 延迟派发：到点才开始（0 = 立刻）。等待期间状态是「等时间到」。 */
        volatile long dueAt = 0L;
        Job(String id, SubAgentStore.Sub sub, String caller, String task) {
            this.id = id;
            this.sub = sub;
            this.caller = caller;
            this.task = task;
        }
    }

    private static final java.util.LinkedHashMap<String, Job> JOBS = new java.util.LinkedHashMap<>();

    static List<Job> jobs() {
        synchronized (JOBS) { return new ArrayList<>(JOBS.values()); }
    }

    /** 给主助手看的"现在有哪些活儿在跑"。 */
    static String jobsText() {
        List<Job> list = jobs();
        if (list.isEmpty()) return "现在没有子智能体在跑。";
        StringBuilder sb = new StringBuilder("正在跑的活儿：\n");
        for (Job j : list) {
            long sec = (System.currentTimeMillis() - j.startedAt) / 1000;
            sb.append("· ").append(j.sub == null ? "?" : j.sub.name)
                    .append("（job ").append(j.id);
            if (j.dueAt > 0) {
                long leftMin = Math.max(0, (j.dueAt - System.currentTimeMillis() + 59_999L) / 60_000L);
                sb.append("，还没开始，约 ").append(leftMin).append(" 分钟后开始，")
                        .append(j.state);
            } else {
                sb.append("，已跑 ").append(sec).append(" 秒，").append(j.state);
            }
            sb.append("）\n  任务：").append(brief(j.task, 80)).append("\n");
        }
        return sb.toString();
    }

    /** 带屏幕工具的活儿（屏幕操作员）—— 灵动岛连点三下只暂停这些。 */
    private static boolean usesScreenTools(SubAgentStore.Sub sub) {
        if (sub == null) return false;
        for (String t : sub.toolList()) {
            for (String sc : SCREEN_TOOLS) if (sc.equals(t)) return true;
        }
        return false;
    }

    /**
     * 派活（异步）：立刻回真回执，活儿在后台线程跑；跑完写进助手会话并唤醒主助手跑下一轮。
     *
     * <p>同一个子智能体同时只允许一个活儿（两个一起跑会抢同一套屏幕、同一份日志）；
     * 不同子智能体可以并行。
     */
    static String dispatch(final android.content.Context ctx, final MainActivity act,
                           final SubAgentStore.Sub sub, final String task, final String caller) {
        return dispatch(ctx, act, sub, task, caller, 0);
    }

    /**
     * 派活（异步，可延迟）。
     *
     * <p>2026-10-17 加 delayMinutes：用户说「6 分钟后再做」时，助手以前只能
     * "先派出去、在任务里写别立刻开始"，子智能体照做空跑，助手又以为没做、重派一遍 ——
     * 于是手机上就"提前动起来了"。现在延迟由系统管：到点才真正开跑。
     */
    static String dispatch(final android.content.Context ctx, final MainActivity act,
                           final SubAgentStore.Sub sub, final String task, final String caller,
                           final int delayMinutes) {
        if (sub == null) return err("No such sub-agent");
        if (!sub.usable()) return err("Sub-agent \"" + sub.name + "\" is not set up yet (needs a provider + model)");
        final String t = task == null ? "" : task.trim();
        if (t.isEmpty()) return err("No task content given");
        final long delayMs = Math.max(0, Math.min(24 * 60, delayMinutes)) * 60_000L;
        final Job job;
        synchronized (JOBS) {
            for (Job j : JOBS.values()) {
                if (j.sub != null && sub.id != null && sub.id.equals(j.sub.id)) {
                    return err("Sub-agent \"" + sub.name + "\" is already running a job (job " + j.id
                            + "). It reports back on its own - do not dispatch the same job twice. "
                            + "Use manage_subagent action=stop if it really has to be stopped.");
                }
            }
            job = new Job("job" + System.currentTimeMillis(), sub, caller, t);
            if (delayMs > 0) {
                job.dueAt = System.currentTimeMillis() + delayMs;
                job.state = "等时间到";
            }
            JOBS.put(job.id, job);
        }
        Thread th = new Thread(() -> {
            try {
                // 延迟派发：到点之前一直等（可被叫停打断，不必等满）
                if (job.dueAt > 0) {
                    long left = job.dueAt - System.currentTimeMillis();
                    while (left > 0 && !job.stop.get()) {
                        long step = Math.min(left, 1000L);
                        try { Thread.sleep(step); } catch (InterruptedException ie) { return; }
                        left = job.dueAt - System.currentTimeMillis();
                    }
                    if (job.stop.get()) { job.state = "已叫停"; return; }
                    job.state = "运行中";
                }
                AiClient.bindGenerationToken(job.stop);
                String res = run(act, sub, t, caller, job.stop);
                try {
                    JSONObject o = new JSONObject(res);
                    job.result = o.optString("content", o.optString("error", res));
                    if (!o.optBoolean("success", false)) {
                        job.state = job.stop.get() ? "已叫停" : "失败";
                    } else {
                        job.state = job.stop.get() ? "已叫停" : "跑完了";
                    }
                } catch (Exception e) {
                    job.result = res == null ? "" : res;
                    job.state = "跑完了";
                }
            } catch (Throwable e) {
                job.result = "执行出错：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                job.state = "失败";
            } finally {
                try { AiClient.clearGenerationToken(); } catch (Throwable ignored) { }
                synchronized (JOBS) { JOBS.remove(job.id); }
            }
            try {
                afterJob(ctx, sub, job);
            } catch (Throwable t2) {
                android.util.Log.w("VC_SUBAGENT", "回报后续处理失败: " + t2.getMessage());
            }
        }, "vc-sub-" + (sub.name == null || sub.name.isEmpty() ? "agent" : sub.name));
        th.setDaemon(true);
        th.start();
        try {
            JSONObject ok = new JSONObject().put("success", true)
                    .put("status", "dispatched")
                    .put("agent", sub.name)
                    .put("job", job.id);
            if (delayMs > 0) {
                ok.put("note", "已经排好了，它会在约 " + delayMinutes + " 分钟后**自己开始**，"
                        + "现在不会碰手机（不用等它、也别替它写结果）。"
                        + "把这一步告诉用户：到点谁去做什么；它跑完会自己带着结果回来叫醒你。");
            } else {
                ok.put("note", "已经派出去了，它在后台跑（不用等它、也别替它写结果）。"
                        + "把这一步告诉用户：派给了谁、让它做什么，然后正常收尾；"
                        + "它跑完会自己带着结果回来叫醒你，那时你再决定汇报还是接着派。");
            }
            return ok.toString();
        } catch (Exception e) {
            return "{\"success\":true,\"status\":\"dispatched\"}";
        }
    }

    /** 叫停在跑的活儿：who 为空 = 全部；screenOnly = 只停带屏幕工具的（灵动岛连点三下）。返回停掉几个。 */
    static int stopJobs(String who, boolean screenOnly) {
        int n = 0;
        String w = who == null ? "" : who.trim();
        for (Job j : jobs()) {
            if (j.sub == null) continue;
            if (!w.isEmpty() && !w.equals(j.sub.name) && !w.equals(j.sub.id)) continue;
            if (screenOnly && !usesScreenTools(j.sub)) continue;
            j.stop.set(true);
            j.state = "已叫停";
            try { AiClient.abortGeneration(j.stop); } catch (Throwable ignored) { }
            android.util.Log.i("VC_SUBAGENT", "叫停 " + j.id + "（" + j.sub.name + "）");
            ++n;
        }
        return n;
    }

    /** 子智能体跑完：结果写进助手会话，然后唤醒主助手跑一轮，由它决定下一步。 */
    private static void afterJob(android.content.Context ctx, SubAgentStore.Sub sub, Job job) {
        String name = sub == null ? "" : sub.name;
        String body = job.result == null || job.result.trim().isEmpty()
                ? "（它没交出内容，状态：" + job.state + "）" : job.result.trim();
        long cost = (System.currentTimeMillis() - job.startedAt) / 1000;
        String note = "🧩 【子智能体回报】" + name + "：\n" + body
                + "\n\n（job " + job.id + "，用时 " + cost + " 秒，状态 " + job.state + "）";
        AppDatabase db = new AppDatabase(ctx);
        try {
            android.content.SharedPreferences pp =
                    ctx.getSharedPreferences(AssistantPanel.PREFS, android.content.Context.MODE_PRIVATE);
            long convId = pp.getLong("convId", 0);
            if (convId > 0) db.insertMessage("assistant", note, null, null, 0, convId, true);
            // 被叫停的活儿不再唤醒主助手（2026-10-17）：用户/主助手已经决定不要这个结果了，
            // 再跑一轮等于"停了又自己动起来"，正是用户反馈的那个现象。
            if (job.stop.get() || "已叫停".equals(job.state)) {
                android.util.Log.i("VC_SUBAGENT", "活儿 " + job.id + " 是叫停的，不唤醒主助手");
                return;
            }
            String model = pp.getString("model", "");
            String baseUrl = pp.getString("baseUrl", "");
            String apiKey = pp.getString("apiKey", "");
            int lv = pp.getInt("thinkLevel", -1);
            String rolePrompt = pp.getString("role_main", "");
            List<AppDatabase.MessageItem> hist = convId > 0
                    ? db.listRecentMessagesInConversation(convId, 40)
                    : new ArrayList<AppDatabase.MessageItem>();
            MainActivity act = MainActivity.current();
            if (act == null) act = MainActivity.wake(ctx);
            if (act == null) {
                android.util.Log.w("VC_SUBAGENT", "回报已写进会话，但主助手起不来，没跑后续那一轮");
                return;
            }
            String sys = act.buildAssistantPrompt("[子智能体回报]", rolePrompt)
                    + act.buildAssistantSubAgentBlock("main") + reportFraming();
            AiClient.ChatResult r = AsstTurn.run(ctx, model, baseUrl, apiKey, lv, sys, hist, note,
                    maxIters(ctx));
            String out = r == null || r.content == null ? "" : r.content.trim();
            if (!out.isEmpty() && convId > 0) {
                db.insertMessage("assistant", out, null, null, 0, convId, true);
            }
            android.util.Log.i("VC_SUBAGENT", "回报后主助手又跑了一轮：" + out.length() + " 字");
        } catch (Throwable t) {
            android.util.Log.w("VC_SUBAGENT", "回报后续处理失败: " + t.getMessage());
        } finally {
            try { db.close(); } catch (Exception ignored) { }
        }
    }

    private static String reportFraming() {
        return "\n[A sub-agent you dispatched has just reported back - the user did NOT just speak to you]\n"
                + "Below is its report. This is your next turn: decide what to do and answer the user in your normal voice\n"
                + "(what came back, what it means for him, whether anything needs a follow-up dispatch).\n"
                + "If it failed or came back incomplete, say so plainly and say what you will do differently - do not dress it up as success.\n"
                + "If there is genuinely nothing worth telling the user, write one short line and stop.\n";
    }

    /** 交回来的是不是"过程碎碎念"（不是汇报）——这种不许标 ok（用户 2026-10-16 定）。 */
    private static boolean looksLikeNarration(String out) {
        if (out == null) return false;
        String s = out.trim();
        if (s.length() < 80) return false;
        String head = s.length() > 240 ? s.substring(0, 240) : s;
        if (head.contains("做了什么") || head.contains("结果：") || head.contains("结果:")) return false;
        String[] openers = {"I'll ", "I will ", "Let me ", "Sure, ", "Okay, ", "OK, ", "First, ", "Let's "};
        for (String o : openers) {
            if (head.startsWith(o)) return true;
        }
        return false;
    }

    private static void finish(MainActivity act, SubAgentStore.Sub sub, SubAgentLog.Run log,
                               long startAt, String out, boolean cut) {
        log.costMs = System.currentTimeMillis() - startAt;
        log.nodes = log.steps.size();
        if (cut && log.lesson.isEmpty()) log.lesson = "输出被上游截断，交付内容可能不完整";
        try {
            SubAgentLog.add(act, sub.id, log);
        } catch (Throwable t) {
            android.util.Log.w("VC_SUBAGENT", "写子智能体记录失败: " + t.getMessage());
        }
    }

    private static void appendToolNodes(SubAgentLog.Run log, AgentToolHandler handler,
                                        AiClient.ChatResult r) {
        List<String> hits = handler.searchDigests();
        int failed = handler.failedCount();
        for (String h : hits) {
            boolean bad = h.startsWith("没做成：");
            log.steps.add(node(bad ? "EXCEPTION" : "ACT", "按题意判断该用哪个工具",
                    h, bad ? "失败" : "成功", bad ? "这一步没做成" : ""));
        }
        if (failed > 0) {
            log.steps.add(node("EXCEPTION", "有步骤没做成，判断要在交付里说明",
                    "共 " + failed + " 步失败", "已如实记录", "不许当成做成了"));
        }
        if (r != null && r.toolLog != null && !r.toolLog.trim().isEmpty()) {
            int n = countLines(r.toolLog);
            if (n > hits.size()) {
                log.steps.add(node("ACT", "还需要读页面正文",
                        "又调了 " + (n - hits.size()) + " 次工具", "成功", ""));
            }
        }
    }

    private static int countLines(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        int n = 0;
        for (String line : s.split("\n")) {
            if (line.trim().startsWith("🔧") || line.contains("🔧 ")) n++;
        }
        return n;
    }

    private static SubAgentLog.Node node(String type, String judge, String action,
                                         String result, String remark) {
        SubAgentLog.Node n = new SubAgentLog.Node();
        n.type = type;
        n.judge = judge;
        n.action = action;
        n.result = result;
        n.remark = remark;
        return n;
    }

    private static String brief(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    static String buildSystemPrompt(SubAgentStore.Sub sub, List<String> granted) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are \"").append(sub.name).append("\", a dedicated sub-agent. "
                + "You handle only the category of tasks described below; when done, hand the result back to the assistant that dispatched you.\n\n");
        if (sub.purpose != null && !sub.purpose.trim().isEmpty()) {
            sb.append("[Your role]\n").append(sub.purpose.trim()).append("\n\n");
        }
        String work = sub.effectivePrompt();
        if (!work.isEmpty()) {
            sb.append("[Work requirements]\n").append(work).append("\n\n");
        }
        sb.append("[How to report back]\n");
        sb.append("Your final result must be a report in these four parts, keep the labels:\n");
        sb.append("1) 做了什么: one line on what you did.\n");
        sb.append("2) 结果: the result itself - the answer / the data / the concrete outcome.\n");
        sb.append("3) 证据: what you actually saw or called that backs it up (screen text, tool output, order number...).\n");
        sb.append("4) 没做到: which step failed and why; write 无 when every step worked.\n");
        sb.append("Never hand back your own process narration (\"I'll start by ...\", \"Let me ...\") as the result - \n");
        sb.append("narration is not a report; the assistant that dispatched you cannot tell what you actually achieved.\n");
        sb.append("[Output requirements]\n");
        sb.append("Deliver the task result itself: no pleasantries, no restating the task, no questions back to the caller.\n");
        sb.append("You **must end with a conclusion**: what you hand back must contain the result you wrote, "
                + "not just a list of what you looked up.\n");
        sb.append("For time-sensitive content, rely on the data you actually retrieved; never make things up from memory.\n");
        sb.append("If background the task needs is neither spelled out in the task nor findable by you, state plainly in the result what is missing - do not guess or fabricate.\n");
        if (granted == null || granted.isEmpty()) {
            sb.append("You have no tools; answer from your own knowledge only.\n");
        }
        return sb.toString();
    }

    private static class AgentToolHandler implements AiClient.ToolHandler {
        private final MainActivity act;
        private final StringBuilder digest = new StringBuilder();
        private final List<String> searched = new ArrayList<>();
        private int failed = 0;
        int digestCap = DEFAULT_CTX_TOKENS * 2;

        AgentToolHandler(MainActivity act) { this.act = act; }

        @Override
        public String handle(String name, JSONObject args) throws Exception {
            String result = act.handleAllToolForAssistant(name, args);
            collect(name, args, result);
            return result;
        }

        int failedCount() {
            return failed;
        }

        List<String> searchDigests() {
            return searched;
        }

        String digest() {
            String s = digest.toString().trim();
            if (s.length() > digestCap) s = s.substring(0, digestCap) + "…(more follows, truncated)";
            return s;
        }

        private void collect(String name, JSONObject args, String result) {
            if (result == null || result.isEmpty()) return;
            String argText = "";
            if (args != null) {
                java.util.Iterator<String> it = args.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = args.optString(k, "");
                    if (v != null && !v.trim().isEmpty()) {
                        argText = brief(v, 40);
                        break;
                    }
                }
            }
            boolean ok = !result.contains("\"success\":false");
            if (!ok) failed++;
            searched.add((ok ? "" : "没做成：") + toolLabel(name)
                    + (argText.isEmpty() ? "" : "（" + argText + "）"));
            if (!ok) return;
            try {
                if ("web_search".equals(name)) {
                    JSONObject o = new JSONObject(result);
                    JSONArray arr = o.optJSONArray("results");
                    if (arr == null) return;
                    String q = args == null ? "" : args.optString("query", "");
                    digest.append("[Search: ").append(q).append("]\n");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.optJSONObject(i);
                        if (r == null) continue;
                        digest.append("- ").append(r.optString("title", "")).append("\n");
                        digest.append("  ").append(r.optString("date", "")).append(" ")
                                .append(r.optString("url", "")).append("\n");
                        String sn = r.optString("snippet", "");
                        if (!sn.isEmpty()) {
                            if (sn.length() > 600) sn = sn.substring(0, 600) + "…";
                            digest.append("  ").append(sn).append("\n");
                        }
                    }
                    digest.append("\n");
                } else if ("fetch_url".equals(name)) {
                    JSONObject o = new JSONObject(result);
                    String txt = o.optString("text", "");
                    if (txt.isEmpty()) return;
                    String u = o.optString("url", "");
                    if (u.isEmpty() && args != null) u = args.optString("url", "");
                    if (txt.length() > 3000) txt = txt.substring(0, 3000) + "…";
                    digest.append("[Fetched: ").append(u).append("]\n").append(txt).append("\n\n");
                } else if ("browser_read".equals(name)) {
                    JSONObject o = new JSONObject(result);
                    String txt = o.optString("text", "");
                    if (txt.isEmpty()) txt = o.optString("content", "");
                    if (txt.isEmpty()) return;
                    if (txt.length() > 3000) txt = txt.substring(0, 3000) + "…";
                    digest.append("[Page content]\n").append(txt).append("\n\n");
                } else {
                    String body = result;
                    String msg = "";
                    try {
                        JSONObject o = new JSONObject(result);
                        msg = o.optString("message", "");
                        if (msg.isEmpty()) msg = o.optString("text", "");
                        if (msg.isEmpty()) body = o.toString();
                        else body = msg;
                    } catch (Exception ignored) { }
                    if (body.length() > 1500) body = body.substring(0, 1500) + "…";
                    digest.append("[").append(toolLabel(name)).append("]\n")
                            .append(body).append("\n\n");
                }
            } catch (Exception ignored) { }
        }
    }

    private static String err(String msg) {
        try {
            return new JSONObject().put("success", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + msg + "\"}";
        }
    }

    static JSONArray buildTool(List<SubAgentStore.Sub> subs) {
        JSONArray arr = new JSONArray();
        List<SubAgentStore.Sub> usable = new ArrayList<>();
        if (subs != null) for (SubAgentStore.Sub s : subs) if (s != null && s.usable()) usable.add(s);
        if (usable.isEmpty()) return arr;
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", "call_subagent");
            fn.put("description", "Dispatch a task to a dedicated sub-agent. This returns IMMEDIATELY with a dispatch receipt - "
                    + "the job runs in the background and you are NOT waiting for it. "
                    + "Say in your reply who you dispatched and what for, then end your turn normally; when the job finishes it comes back to you as a new message "
                    + "and you get another turn to decide (report it to the user, dispatch the next step, or wrap up). "
                    + "You may dispatch several sub-agents in a row - they run in parallel - so never hold one job open waiting for another one. "
                    + "Never state its result before it has reported back - you do not know it yet. "
                    + "Fits work that has an owner or needs repeated research, e.g. checking the latest news, dedicated lookup work, or organizing things in a fixed routine. "
                    + "task must be a self-contained, complete task description - the sub-agent cannot see your conversation with the user, "
                    + "so spell out the background, the requirements, and the format you want.");

            JSONObject props = new JSONObject();
            JSONArray names = new JSONArray();
            StringBuilder who = new StringBuilder("Which sub-agent to dispatch to. Options: ");
            for (int i = 0; i < usable.size(); i++) {
                SubAgentStore.Sub s = usable.get(i);
                names.put(s.name);
                who.append(i == 0 ? "" : "; ").append(s.name);
                if (s.purpose != null && !s.purpose.trim().isEmpty()) {
                    who.append(" (").append(s.purpose.trim()).append(")");
                }
            }
            who.append(".");
            JSONObject agentProp = new JSONObject();
            agentProp.put("type", "string");
            agentProp.put("enum", names);
            agentProp.put("description", who.toString());
            props.put("agent", agentProp);
            props.put("task", new JSONObject().put("type", "string")
                    .put("description", "The concrete task for it to do: self-contained, with background and requirements spelled out"));
            props.put("delay_minutes", new JSONObject().put("type", "integer")
                    .put("description", "Optional. When the user says to do it later (\"6 minutes from now\", \"after 9\"), "
                            + "put the number of minutes here - the system holds the job and it starts on its own at that time. "
                            + "Do NOT dispatch it immediately and tell it to wait in the task text; pass delay_minutes instead. "
                            + "0 or omitted = start right now. Max 1440."));

            fn.put("parameters", new JSONObject().put("type", "object")
                    .put("properties", props)
                    .put("required", new JSONArray().put("agent").put("task")));
            arr.put(new JSONObject().put("type", "function").put("function", fn));
        } catch (Exception ignored) { }
        return arr;
    }

    static String promptBlock(List<SubAgentStore.Sub> subs, List<String> toolNames,
                              List<String> providers) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n[Your sub-agents]\n");
        List<SubAgentStore.Sub> usable = new ArrayList<>();
        if (subs != null) for (SubAgentStore.Sub s : subs) if (s != null && s.usable()) usable.add(s);
        if (usable.isEmpty()) {
            sb.append("You currently have no workers. For jobs that need repeated research - checking the latest news, "
                    + "dedicated lookup work, organizing things in a fixed routine - rather than researching from scratch yourself, "
                    + "first create a dedicated sub-agent with manage_subagent, then dispatch the job to it.\n");
        } else {
            sb.append("These dedicated sub-agents work for you; use call_subagent to dispatch specialized tasks to them:\n");
            sb.append(listText(usable));
            sb.append("Rule of thumb: if a job has a dedicated owner or needs repeated research, dispatch it to the matching sub-agent "
                    + "instead of researching from scratch yourself; when dispatching, write the task description in full (they cannot see your conversation with the user).\n");
            // 2026-10-17 加：把"已经派出去、还没回报"的活儿直接写进提示词。
            // 用户反馈的"旧任务又被拉起来"真相就是：助手没有派发记录，看到 job 空跑就重派一遍。
            // 有了这一段，它每次开口前就知道自己手里还有谁在跑。
            String live = jobsText();
            if (!live.startsWith("现在没有子智能体")) {
                sb.append("**Jobs you have already dispatched (still not reported back)**:\n").append(live)
                        .append("Do NOT dispatch the same work again while it is listed here - it will report back on its own.\n\n");
            }
            sb.append("**Dispatch is asynchronous**: call_subagent returns at once with a receipt - tell the user what you sent and to whom, then stop. "
                    + "When the job finishes it comes back to you as a new message and you get another turn to decide how to report it. "
                    + "Several sub-agents can run in parallel - never dispatch one and sit waiting, and never state a result before it has reported back.\n");
            sb.append("**Do not create a new agent when an existing one already covers the role**: if someone above already handles this kind of work, "
                    + "use that one instead of building another that does the same thing - two agents on the same capability "
                    + "fight over the same tools and the same screen, and each keeps its own duplicate log. "
                    + "For example, if 屏幕操作员 (the built-in screen operator) already controls the phone screen, do not create a second screen-controlling agent; "
                    + "if a dedicated researcher already exists, do not create another one. "
                    + "Note: the ones marked built-in (e.g. 屏幕操作员) you cannot even modify - "
                    + "you may only assign work within their fixed role; to add requirements for them, the user must write the extra requirements themselves under Sub-agents.\n");
        }
        sb.append("Use manage_subagent to create, modify, or remove agents: "
                + "grant tool permissions based on what the task actually needs (tools takes tool names; if omitted, it gets only web search and page fetching); "
                + "you also set the context window and thinking level according to task difficulty.");
        if (toolNames != null && !toolNames.isEmpty()) {
            sb.append("Tools you can grant right now: ").append(joinText(toolNames)).append(".");
        }
        if (providers != null && !providers.isEmpty()) {
            sb.append("\nWhen creating a sub-agent you can pick which provider and model to use (provider / model). "
                    + "These providers are reserved for you: ").append(joinText(providers))
                    .append(". If not specified, the one you are currently using applies.");
        }
        sb.append("\n");
        return sb.toString();
    }

    static String joinText(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append("、");
            sb.append(s);
        }
        return sb.toString();
    }

    static JSONArray buildManageTool(List<String> grantable, List<String> providers) {
        JSONArray arr = new JSONArray();
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", "manage_subagent");
            fn.put("description", "Create / modify / delete your own sub-agents. "
                    + "When a kind of work (repeated research, organizing in a fixed routine, watching a source) will keep coming up, "
                    + "create a dedicated sub-agent for it and dispatch to it later via call_subagent; "
                    + "you can also change its role, prompt, context, thinking level and tool permissions at any time, or delete it. "
                    + "After creating one, tell the user in the same reply what you created and which tools you enabled for it.\n"
                    + "**Check the existing agents before creating one**: if an existing sub-agent already covers this kind of work "
                    + "(e.g. 屏幕操作员 handles the screen, a dedicated one handles research), do not build a duplicate - "
                    + "two agents on the same job fight over tools and the screen, and their logs split in two. "
                    + "To make an existing one work more to your liking: ones you created can be changed with action=update, "
                    + "but built-in (fixed) ones cannot be changed by you - only the user can write extra requirements for them. "
                    + "action=create makes a new one (name required, the rest optional); "
                    + "action=update modifies an existing one (agent required to say which); "
                    + "action=delete removes one (agent required); "
                    + "action=jobs lists what is running right now (use it when the user asks what is going on); "
                    + "action=stop stops a running job (agent required - that sub-agent's current job is dropped). "
                    + "Note: sub-agents the user has fixed in place (e.g. the built-in 屏幕操作员) "
                    + "can be neither modified nor deleted by you - only assigned work within their fixed role; "
                    + "to adjust their rules, the user must change them under Sub-agents themselves.");

            JSONObject props = new JSONObject();
            props.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("create").put("update").put("delete")
                            .put("jobs").put("stop"))
                    .put("description", "create = new, update = modify, delete = remove, "
                            + "jobs = list running jobs, stop = stop a running job"));
            props.put("agent", new JSONObject().put("type", "string")
                    .put("description", "Name of the sub-agent to modify/delete (not needed for create)"));
            props.put("name", new JSONObject().put("type", "string")
                    .put("description", "The sub-agent's name, e.g. \"Finance News Briefer\""));
            props.put("purpose", new JSONObject().put("type", "string")
                    .put("description", "Its role: one sentence saying what it is dedicated to"));
            props.put("prompt", new JSONObject().put("type", "string")
                    .put("description", "Extra work requirements (optional), e.g. output format or decision criteria"));
            props.put("tools", new JSONObject().put("type", "array")
                    .put("items", new JSONObject().put("type", "string"))
                    .put("description", "Tool permissions to grant it; pick as needed. Omitted = only web_search and fetch_url. "
                            + "Available: " + (grantable == null ? "" : joinText(grantable))));
            props.put("provider", new JSONObject().put("type", "string")
                    .put("description", "Which provider to use (optional). Available: "
                            + (providers == null || providers.isEmpty() ? "(none configured yet; if omitted, the provider this assistant currently uses applies)"
                            : joinText(providers))));
            props.put("model", new JSONObject().put("type", "string")
                    .put("description", "Which model to use (optional); must be in the model list of the provider above"));
            props.put("think_level", new JSONObject().put("type", "integer")
                    .put("description", "Thinking level: 0=off 1=low 2=medium 3=high 4=max; set by task difficulty"));
            props.put("ctx_tokens", new JSONObject().put("type", "integer")
                    .put("description", "Context window in tokens; set by task size. Default 1000000"));

            fn.put("parameters", new JSONObject().put("type", "object")
                    .put("properties", props)
                    .put("required", new JSONArray().put("action")));
            arr.put(new JSONObject().put("type", "function").put("function", fn));
        } catch (Exception ignored) { }
        return arr;
    }

    static String listText(List<SubAgentStore.Sub> subs) {
        if (subs == null || subs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SubAgentStore.Sub s : subs) {
            sb.append("· ").append(s.name);
            if (s.fixed) sb.append(" [built-in - you cannot modify it]");
            if (s.purpose != null && !s.purpose.trim().isEmpty()) {
                sb.append(" (").append(s.purpose.trim()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
