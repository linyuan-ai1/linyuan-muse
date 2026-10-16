package com.linyuan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Compactor {

    public static final String ROLE_SUMMARY = "summary";

    public static boolean compactIfNeeded(android.content.Context ctx, AppSettings settings,
                                          AppDatabase db, List<AppDatabase.MessageItem> allMessages,
                                          int keepRecentCount, long conversationId) {
        if (!settings.compactEnabled) return false;
        try {
            if (allMessages == null || allMessages.size() <= keepRecentCount + 4) return false;

            AppDatabase.MessageItem prevSummary = null;
            for (AppDatabase.MessageItem m : allMessages) {
                if (ROLE_SUMMARY.equals(m.role)) prevSummary = m;
            }
            long uptoId = prevSummary == null ? 0 : parseUptoMarker(prevSummary.content);
            String priorSummary = prevSummary == null ? "" : stripUptoMarker(prevSummary.content);

            int cut = allMessages.size() - keepRecentCount;
            if (cut <= 0) return false;
            List<AppDatabase.MessageItem> old = new ArrayList<>(allMessages.subList(0, cut));
            long cutId = old.isEmpty() ? 0 : old.get(old.size() - 1).id;

            StringBuilder transcript = new StringBuilder();
            for (AppDatabase.MessageItem m : old) {
                if (ROLE_SUMMARY.equals(m.role)) continue;
                if (uptoId > 0 && m.id <= uptoId) continue;
                String content = AiClient.stripMarkersPublic(m.content);
                if (content.isEmpty()) continue;
                String who = "user".equals(m.role) ? "用户" : "AI";
                transcript.append("[").append(who).append("] ").append(content).append("\n");
            }
            if (transcript.length() < 800) return false;

            int estimated = estimateTokensCJK(transcript.toString());
            int trigger = settings.compactTriggerTokens > 0
                    ? settings.compactTriggerTokens
                    : (settings.shortTermBudgetTokens > 0
                        ? (int) (settings.shortTermBudgetTokens * 0.85) : 255000);
            if (estimated < trigger) return false;

            android.util.Log.i("VC_COMPACT", "开始压缩：新增老消息约 " + estimated + " token（断点 upto=" + uptoId + "）");

            String summary = summarize(settings, transcript.toString(), priorSummary);
            if (summary == null || summary.trim().isEmpty()) {
                android.util.Log.w("VC_COMPACT", "压缩失败（空结果），保留原样");
                return false;
            }

            String marked = summary.trim() + "\n@@UPTO:" + cutId;
            if (prevSummary != null) {
                db.updateMessageContent(prevSummary.id, marked);
            } else {

                db.insertMessage(ROLE_SUMMARY, marked, null, null, 0, conversationId);
            }
            android.util.Log.i("VC_COMPACT", "压缩完成，摘要 " + summary.length() + " 字，断点推进到 " + cutId);
            return true;
        } catch (Exception e) {
            android.util.Log.w("VC_COMPACT", "压缩异常：" + e.getMessage());
            return false;
        }
    }

    private static long parseUptoMarker(String content) {
        if (content == null) return 0;
        int i = content.indexOf("@@UPTO:");
        if (i < 0) return 0;
        int s = i + "@@UPTO:".length();
        int e = s;
        while (e < content.length() && Character.isDigit(content.charAt(e))) e++;
        if (e == s) return 0;
        try { return Long.parseLong(content.substring(s, e)); } catch (Exception ex) { return 0; }
    }

    private static String stripUptoMarker(String content) {
        if (content == null) return "";
        int i = content.indexOf("@@UPTO:");
        if (i < 0) return content;
        int nl = content.indexOf('\n', i);
        return (nl >= 0 ? content.substring(0, i) + content.substring(nl + 1) : content.substring(0, i)).trim();
    }

    private static String summarize(AppSettings settings, String transcript, String priorSummary) throws Exception {
        String base = !isBlank(settings.compactBaseUrl) ? settings.compactBaseUrl
                : (!isBlank(settings.chatBaseUrl) ? settings.chatBaseUrl : settings.baseUrl);
        String key = !isBlank(settings.compactApiKey) ? settings.compactApiKey
                : (!isBlank(settings.chatApiKey) ? settings.chatApiKey : settings.apiKey);
        String model = !isBlank(settings.compactModel) ? settings.compactModel
                : (!isBlank(settings.chatModel) ? settings.chatModel : settings.model);

        String sys = CompactionPrompt.systemPrompt() + CompactionPrompt.mergeInstruction(priorSummary);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", sys));
        messages.put(new JSONObject().put("role", "user").put("content", transcript));

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("temperature", 0.2);
        body.put("max_tokens", 110000);
        body.put("enable_thinking", false);
        // 压缩思考档位（用户 2026-10-13；0=关，保持原行为）。
        // 开档时移除 enable_thinking 硬关，改用协商器挑写法；
        // 消息里的思考片段由下面 endThink 截断彻底剥掉，不会混进摘要。
        if (settings.compactThinkLevel >= ReasoningNegotiator.LEVEL_LOW) {
            body.remove("enable_thinking");
            try { ReasoningNegotiator.applyReasoning(body, base, model, settings.compactThinkLevel, 0); }
            catch (Exception ignored) { }
        }

        String content = AiClient.simpleChat(base, key, body.toString());
        if (content == null) return null;
        content = content.trim();

        final String endThink = "<｜end▁of▁thinking｜>";
        int t = content.indexOf(endThink);
        if (t >= 0) content = content.substring(t + endThink.length()).trim();
        return content;
    }

    public static int estimateTokensCJK(String text) {
        return AiClient.estimateTokensText(text);
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
