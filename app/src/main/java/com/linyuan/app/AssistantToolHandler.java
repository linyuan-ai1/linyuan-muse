package com.linyuan.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 助手对话用的工具执行器。
 *
 * <p>工具集跟主对话完全一样（{@code MainActivity.buildAllTools()}），执行也走同一个
 * {@code handleAllTool}，只是把记忆库映射到助手自己的三本（{@code asst:} 前缀）。
 * 这里只多做一件事：把记忆类调用记下来，给消息气泡里的「🧠记忆」折叠栏用。
 */
public class AssistantToolHandler implements AiClient.ToolHandler {

    private final MainActivity act;
    private final String agentId;
    private final List<String> memoryActions = new ArrayList<>();

    AssistantToolHandler(MainActivity act, String agentId) {
        this.act = act;
        this.agentId = agentId;
    }

    String memoryLog() {
        if (memoryActions.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String s : memoryActions) sb.append("· ").append(s).append("\n");
        return sb.toString().trim();
    }

    @Override
    public String handle(String name, JSONObject args) throws Exception {
        if (MainActivity.isSubAgentTool(name)) {
            String result = "manage_subagent".equals(name)
                    ? act.handleManageSubAgent(agentId, args)
                    : act.handleSubAgentTool(agentId, args);
            recordSub(name, args, result);
            return result;
        }
        String result = act.handleAllToolForAssistant(name, args);
        if ("manage_memory".equals(name) || "self_note".equals(name) || "perception".equals(name)) {
            record(name, args, result);
        }
        return result;
    }

    private void recordSub(String name, JSONObject args, String result) {
        if (name == null) return;
        boolean ok = result != null && result.contains("\"success\":true");
        if ("manage_subagent".equals(name)) {
            String action = args == null ? "" : args.optString("action", "").trim();
            String nm = args == null ? "" : args.optString("name", "").trim();
            String who = args == null ? "" : args.optString("agent", "").trim();
            String subject = nm.isEmpty() ? who : nm;
            if ("create".equals(action)) {
                memoryActions.add(ok ? ("新建子智能体 " + subject) : ("建子智能体失败：" + subject));
            } else if ("delete".equals(action)) {
                memoryActions.add(ok ? ("删掉子智能体 " + subject) : ("删子智能体失败：" + subject));
            } else {
                memoryActions.add(ok ? ("改了子智能体 " + subject) : ("改子智能体失败：" + subject));
            }
            return;
        }
        String who = args == null ? "" : args.optString("agent", "").trim();
        memoryActions.add(ok ? ("派给子智能体 " + who) : ("子智能体 " + who + " 没做成"));
    }

    private void record(String name, JSONObject args, String result) {
        if (result == null || !result.contains("\"success\":true")) return;
        String action = args.optString("action", "").trim();
        if ("perception".equals(name)) {
            if ("add".equals(action)) {
                memoryActions.add("档案 +" + brief(args.optString("title", "")));
            } else if ("update".equals(action)) {
                memoryActions.add("改档案：" + brief(args.optString("title", "")));
            } else if ("delete".equals(action)) {
                memoryActions.add("档案移入归档（id " + args.optLong("id", 0) + "）");
            } else if ("restore".equals(action)) {
                memoryActions.add("从归档恢复（" + args.optLong("archive_id", 0) + "）");
            } else if ("read".equals(action)) {
                memoryActions.add("查了档案 id " + args.optLong("id", 0));
            } else if ("tree".equals(action)) {
                memoryActions.add("翻了档案地图");
            } else if ("archive".equals(action)) {
                memoryActions.add("看了归档");
            }
            return;
        }
        boolean self = "self_note".equals(name);
        if ("add".equals(action)) {
            memoryActions.add((self ? "写给自己：" : "记下：") + brief(args.optString("content", "")));
        } else if ("replace".equals(action)) {
            memoryActions.add((self ? "改写自述：" : "改写：")
                    + brief(args.optString("old_text", "")) + " → " + brief(args.optString("content", "")));
        } else if ("remove".equals(action)) {
            memoryActions.add((self ? "删除自述：" : "删除：") + brief(args.optString("old_text", "")));
        } else if ("list".equals(action)) {
            memoryActions.add("翻看了自己的记忆");
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }
}
