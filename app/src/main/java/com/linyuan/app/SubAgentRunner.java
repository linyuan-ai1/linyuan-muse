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

    static List<String> grantableNames(boolean forSubAgent) {
        List<String> out = new ArrayList<>();
        for (String[] row : TOOL_CATALOG) {
            if (forSubAgent && asstOnly(row[0])) continue;
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
                    buildTools(act, granted), handler, 8, null, null, null);
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
                        null, null, 1, null, null, null);
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

            log.steps.add(node("OUTPUT", "拿到结果，判断可以交付",
                    "交出 " + out.length() + " 字", "成功", ""));
            boolean cut = r != null && r.truncated;
            log.outcome = cut ? "part" : "ok";
            log.summary = brief(out, 160);
            if (toolCalls == 0) {
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
            fn.put("description", "Delegate a specialized task to a dedicated sub-agent; it hands the result back to you when done. "
                    + "Fits work that has an owner or needs repeated research, e.g. checking the latest news, dedicated lookup work, or organizing things in a fixed routine: "
                    + "write the task out clearly, send it off, then use the result it returns when you answer the user. "
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
                    + "instead of researching from scratch yourself; when dispatching, write the task description in full (they cannot see your conversation with the user). "
                    + "Once they hand results back, synthesize them into your answer to the user.\n");
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
                    + "action=delete removes one (agent required). "
                    + "Note: sub-agents the user has fixed in place (e.g. the built-in 屏幕操作员) "
                    + "can be neither modified nor deleted by you - only assigned work within their fixed role; "
                    + "to adjust their rules, the user must change them under Sub-agents themselves.");

            JSONObject props = new JSONObject();
            props.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("create").put("update").put("delete"))
                    .put("description", "create = new, update = modify, delete = remove"));
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
