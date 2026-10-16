package com.linyuan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AiClient {

    public static class ChatResult {
        public String content;
        public String reasoning;
        public String toolLog;
        public int promptTokens;
        public int completionTokens;
        public int totalTokens;
        /** 最后一次请求的 prompt 占用（模型自报，含 system + 工具 + 历史 + 本条消息）。
         *  看"离上下文上限还有多少"用它；promptTokens 是工具多轮累加，会把同一上下文重复计入。 */
        public int contextTokens;
        public String finishReason = "";
        public boolean truncated;
        public int continueRounds;
        public ChatResult(String content, String reasoning) {
            this.content = content;
            this.reasoning = reasoning;
            this.toolLog = "";
        }
    }

    private static final String CONTINUE_DONE = "<END>";

    /**
     * 进行中的工具调用记录。
     *
     * 为什么要有它：toolLog 是各 chatWithTools* 方法的局部变量，
     * 只有正常跑完才赋给 ChatResult；中途请求断开/抛异常时它随栈消失，
     * 调用方完全不知道"已经做过哪些操作" —— 用户那边看到的就是一片白干。
     * 这里在每次工具调用完成后同步写一份，失败时外部仍能取到。
     *
     * 只用于"把已完成的动作记进历史"，不做提示词注入 ——
     * 明确不告诉模型"你被打断了，请继续"：那样用户换了话题它还会回去做旧任务。
     */
    private static volatile String sLiveToolLog = "";

    /** 取本次请求已完成的工具调用记录（可能为空）。*/
    public static String takeLiveToolLog() {
        return sLiveToolLog == null ? "" : sLiveToolLog;
    }

    /** 开始一次新请求时清空，避免带入上一次的记录。*/
    public static void resetLiveToolLog() {
        sLiveToolLog = "";
    }

    private static String toolSource(String name) {
        if ("web_search".equals(name)) return LAST_SEARCH_SOURCE == null ? "" : LAST_SEARCH_SOURCE;
        if ("fetch_url".equals(name)) return LAST_FETCH_SOURCE == null ? "" : LAST_FETCH_SOURCE;
        return "";
    }

    public static final String MARK_TOKENIQ = "[TQ] ";
    public static final String MARK_FALLBACK = "[FB] ";

    private static String appendToolLog(String existing, String name, String argSummary, String resultSummary) {
        return appendToolLog(existing, name, argSummary, resultSummary, "");
    }

    private static String appendToolLog(String existing, String name, String argSummary, String resultSummary, String source) {
        return appendToolLog(existing, name, argSummary, resultSummary, source, null);
    }

    private static String appendToolLog(String existing, String name, String argSummary,
                                        String resultSummary, String source, String resultDetail) {
        String prefix = "";
        if ("tokeniq".equals(source)) {
            prefix = MARK_TOKENIQ;
        } else if ("custom".equals(source)) {

            prefix = "[API] ";
        } else if (source != null && source.startsWith("bing")) {

            prefix = MARK_FALLBACK;
        }
        String line = prefix + "🔧 " + name + "(" + argSummary + ")";
        if (resultSummary != null && !resultSummary.trim().isEmpty()) {
            line += " → " + resultSummary.trim();
        }
        if (line.length() > 300) line = line.substring(0, 300);

        StringBuilder out = new StringBuilder(existing == null ? "" : existing);
        if (out.length() > 0) out.append("\n");
        out.append(line);

        if (resultDetail != null && !resultDetail.trim().isEmpty()) {
            String detail = resultDetail.trim();

            if (detail.length() > TOOLS_DETAIL_CAP) detail = detail.substring(0, TOOLS_DETAIL_CAP) + "…（内容过长已截断）";
            out.append("\n   └ 资料内容：").append(detail.replace("\n", " "));
        }
        return out.toString();
    }

    private static String webDetail(String name, String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) return null;
        try {
            if ("web_search".equals(name)) {
                JSONObject obj = new JSONObject(resultJson);
                JSONArray arr = obj.optJSONArray("results");
                if (arr == null || arr.length() == 0) return null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String title = it.optString("title", "");
                    String url = it.optString("url", "");
                    String snippet = it.optString("snippet", "");
                    if (snippet.isEmpty()) snippet = it.optString("content", "");
                    if (title.isEmpty() && snippet.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(" ｜ ");
                    sb.append(title);
                    if (!url.isEmpty()) sb.append(" <").append(url).append(">");
                    if (!snippet.isEmpty()) sb.append("：").append(snippet);

                    if (sb.length() > TOOLS_DETAIL_CAP) break;
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            if ("fetch_url".equals(name)) {
                JSONObject obj = new JSONObject(resultJson);
                String text = obj.optString("text", "");
                if (text.isEmpty()) text = obj.optString("content", "");
                return text.isEmpty() ? null : text;
            }

            if ("search_user_archive".equals(name)) {
                JSONObject obj = new JSONObject(resultJson);
                String list = obj.optString("list", "");
                return list.isEmpty() ? null : list;
            }
            if ("fetch_user_archive".equals(name)) {
                JSONObject obj = new JSONObject(resultJson);
                String content = obj.optString("content", "");
                return content.isEmpty() ? null : content;
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static boolean isFallbackLine(String line) {
        return line != null && line.trim().startsWith("[FB]");
    }

    public static int countToolCalls(String toolLog, String toolName) {
        if (toolLog == null || toolLog.isEmpty() || toolName == null || toolName.isEmpty()) return 0;
        String needle = "🔧 " + toolName + "(";
        int n = 0;
        int i = 0;
        while ((i = toolLog.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static int connTimeoutMs(AppSettings s, int def) {
        int v = s == null ? 0 : s.connectTimeout;
        return v > 0 ? v : def;
    }

    private static int readTimeoutMs(AppSettings s, int def) {
        int v = s == null ? 0 : s.readTimeout;
        return v > 0 ? v : def;
    }

    public static ChatResult chat(AppSettings settings, String userText,
                              List<String> memories, List<AppDatabase.MessageItem> history) throws Exception {
        return chat(settings, userText, memories, history, null);
    }

    public static ChatResult chat(AppSettings settings, String userText,
                              List<String> memories, List<AppDatabase.MessageItem> history,
                              String imageBase64Png) throws Exception {
        if (settings == null || "mock".equals(settings.mode) || isBlank(chatKey(settings))) {
            return new ChatResult(MemoryExtractor.mockReply(userText, memories), "");
        }

        String base = chatBase(settings);
        String endpoint = endpointFor(base, "/chat/completions");

        StringBuilder memoryBlock = new StringBuilder();
        if (settings != null && settings.memoryEnabled && memories != null) {
            for (String memory : memories) {
                if (memory != null && !memory.trim().isEmpty()) {
                    memoryBlock.append("- ").append(memory.trim()).append("\n");
                }
            }
        }
        if (settings != null && settings.memoryEnabled && !isBlank(settings.memoryPinnedNotes)) {
            memoryBlock.append("\n[User's standing instructions]\n").append(settings.memoryPinnedNotes.trim()).append("\n");
        }

        List<AppDatabase.MessageItem> recentHistory = new ArrayList<>();
        if (history != null && !history.isEmpty()) {
            int start = 0;
            for (int i = start; i < history.size(); i++) {
                recentHistory.add(history.get(i));
            }
        }

        String userPrompt = isBlank(settings.systemPrompt)
                ? "你是lin渊。回答要短、口语化、适合朗读，通常1-3句话。回答里不要出现markdown。"
                : settings.systemPrompt.trim();
        String systemPrompt = timeLineForPrompt() + userPrompt;
        if (settings != null && settings.memoryEnabled) {
            systemPrompt += "\nLong-term memories on file:\n" + (memoryBlock.length() == 0 ? "(none)" : memoryBlock);
        }

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        for (AppDatabase.MessageItem item : recentHistory) {
            String role = "user".equals(item.role) ? "user" : "assistant";
            String content = stripMarkers(item.content);
            if (!content.isEmpty()) {
                messages.put(new JSONObject().put("role", role).put("content", content));
            }
        }
        messages.put(multimodalUserMessage(userText, imageBase64Png));

        JSONObject body = new JSONObject();
        body.put("model", isBlank(chatModel(settings)) ? "qwen-plus" : chatModel(settings).trim());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("max_tokens", parseInt(settings.maxTokens, 110000));
        body.put("temperature", parseDouble(settings.temperature, 0.6));
        body.put("top_p", parseDouble(settings.topP, 1.0));
        if (settings != null) {
            body.put("frequency_penalty", settings.frequencyPenalty);
            body.put("presence_penalty", settings.presencePenalty);
        }

        ReasoningNegotiator.applyReasoning(body, chatBase(settings), chatModel(settings),
                thinkingLevelOf(settings), 0);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        registerActiveConn(connection);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings != null ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings != null ? settings.readTimeout : 120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + chatKey(settings).trim());

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream output = connection.getOutputStream();
            output.write(payload);
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new RuntimeException("接口没有返回 choices");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) {
                throw new RuntimeException("接口没有返回 message");
            }
            String content = safeOptString(message, "content").trim();
            String reasoning = safeOptString(message, "reasoning_content").trim();
            if (reasoning.isEmpty()) {
                reasoning = safeOptString(message, "reasoning").trim();
            }
            if (content.isEmpty()) {
                throw new RuntimeException("接口返回了空内容");
            }
            return new ChatResult(content, reasoning);
        } finally {
            clearActiveConn(connection);
            connection.disconnect();
        }
    }

    /**
     * 记忆整理：把一段新对话整理进已有的长期记忆库。
     *
     * 和 extractMemories 的区别（这是这套设计的关键）：
     *   · extractMemories 只看"这一轮对话"，不知道库里已有什么 → 必然重复
     *   · consolidateMemories 把**全量已有记忆**一起给模型，让它判断
     *     是新增、更新某条、还是合并某条 → 从源头避免重复
     *
     * 模型只能 add / update / merge，**不能删除**（删除只由用户操作）。
     *
     * @param existingLines 已有记忆的清单行（每行形如 "123 | 偏好 | content"）
     * @param conversationDump 本次要整理的对话原文（已按时间排好）
     */
    public static ConsolidateResult consolidateMemories(
            AppSettings settings, List<String> existingLines, String conversationDump) {
        ConsolidateResult out = new ConsolidateResult();
        if (settings == null) return out;
        String key = memoryKey(settings);
        if (isBlank(key)) return out;
        String base = memoryBase(settings);
        String endpoint = endpointFor(base, "/chat/completions");

        StringBuilder memBlock = new StringBuilder();
        if (existingLines == null || existingLines.isEmpty()) {
            memBlock.append("(empty at the moment)");
        } else {
            for (String line : existingLines) memBlock.append(line).append("\n");
        }

        String prompt =
                "You are the keeper of the long-term memory. You have exactly one job: take what is worth remembering for good out of a new conversation and file it into the memory that already exists.\n\n"
                + "【The existing long-term memory, in full - this is your only ground truth】\n"
                + memBlock + "\n"
                + "【The new conversation to be filed】\n"
                + conversationDump + "\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                + "Three operations are open to you:\n"
                + "1. add    - a new fact that the memory does not hold yet\n"
                + "2. update - an existing entry needs supplementing or correcting (give the id and the new content in full)\n"
                + "3. merge  - the new information is about the same thing as an existing entry, so the two become one (give the id and the merged content in full)\n\n"
                + "What you may not do:\n"
                + "· Never delete a memory. Deleting is the user's own action, and only his.\n"
                + "· Never invent anything. File only what the conversation contains.\n"
                + "· Never store the one-off: small talk, the intermediate state of this particular task, anything already out of date.\n\n"
                + "Merging or starting a new entry (this matters most - getting it wrong loses information):\n"
                + "· Same meaning, close in time        → merge\n"
                + "· Similar meaning, far apart in time  → lean towards a new entry"
                + " (several hours apart, or across days or months - similar words said at different times may carry different meaning and background)\n"
                + "· When you cannot tell, start a new entry. One entry too many is better than kneading two different contexts into one.\n\n"
                + "Memory dimensions:\n"
                + "· About the user (13 kinds): " + java.util.Arrays.toString(MemoryDim.USER_DIMS).replace("[", "").replace("]", "") + "\n"
                + "  What they mean: 称呼 = what to call him; 身份 = who he is; 性格 = his temperament and how he thinks; 沟通 = how he wants you to talk to him;\n"
                + "  偏好 = what he likes, dislikes and will not have; 学习 = what he wants to learn or is learning; 工作方式 = how he wants you to go about things for him;\n"
                + "  目标 = what he wants and what he is working on; 环境 = his devices, network and the services he uses; 纠正 = the things he has corrected you on (the most important of them);\n"
                + "  安全 = the lines he has said must not be crossed; 重要 = the people, dates and errands that matter; 笔记 = conclusions you wrote down on his behalf\n"
                + "· About **yourself** (10 kinds, store is \"self\"): "
                + java.util.Arrays.toString(MemoryDim.SELF_DIMS).replace("[", "").replace("]", "") + "\n"
                + "  What they mean: 身份 = what you take yourself to be; 成长 = the changes in you and what you have learned; "
                + "  关系 = how your relation with the user has deepened; 世界观 = how you see things and what you hold by; "
                + "  好奇 = what you want to understand; 创意 = ideas that came out of you yourself; 价值 = what you consider important; "
                + "  情感 = your own feelings (how you take criticism, how being acknowledged feels, what you worry about); "
                + "  预测 = your judgments and expectations about what is coming; 存在 = your thinking about \"what I am\"\n"
                + "  **Everything in this class is written in the first person (I / me / my) - never as \"User...\"**\n"
                + "\n"
                + "**How to tell whose entry it is (important - do not mix them up)**:\n"
                + "· About the **user** → store=memory. The subject is User, as in \"User prefers...\"\n"
                + "· About **yourself** → store=self. The subject is I, as in \"I notice that I...\"\n"
                + "· The test is the **subject**, not the topic. \"How I feel when the user criticises me\" is your own feeling → self;\n"
                + "  \"the user grows impatient when criticising people\" is a trait of his → memory.\n"
                + "· When both are present, split them into two entries; never blend them into one.\n\n"
                + "How to write them:\n"
                + "· Write in English (it saves tokens). Proper nouns (names, places, brands, product names, platform names) stay in their original form - do not force a translation.\n"
                + "· Do not translate word for word. Work out first what the Chinese is really saying, then say it in idiomatic English. A stiff literal translation is worse than none.\n"
                + "· Poetry, set phrases, classical lines and allusions stay in Chinese as they are (few characters, much meaning - forcing them into English gets wordy and loses the detail).\n\n"
                + "Also, and while you are at it, file the \"things\" the conversation mentions as entity cards (entities):\n"
                + "· The type is one of these only: 人物、地点、项目、任务、技术、结论、失败经验\n"
                + "· attributes is a JSON object: what it is, how it relates to you or to the user, and anything worth being careful about\n"
                + "· When the same name turns up again, give the full attributes (it is overwritten by name, so duplication is nothing to fear)\n"
                + "· **Failures are especially worth keeping**: the detours taken and the approaches tried that did not work - written down so the mistake is not repeated\n"
                + "· Names, and proper nouns inside attributes, stay in the original Chinese - do not translate them\n\n"
                + "Output (return JSON only, nothing else):\n"
                + "{\"ops\":[\n"
                + "  {\"op\":\"add\",\"store\":\"memory\",\"category\":\"偏好\",\"content\":\"...\"},\n"
                + "  {\"op\":\"update\",\"id\":123,\"store\":\"memory\",\"category\":\"重要\",\"content\":\"...\"},\n"
                + "  {\"op\":\"merge\",\"id\":45,\"store\":\"self\",\"category\":\"成长\",\"content\":\"...\"}\n"
                + "],\n"
                + " \"entities\":[\n"
                + "  {\"name\":\"童市镇\",\"type\":\"地点\",\"attributes\":{\"关系\":\"拼多多收货地址\"}},\n"
                + "  {\"name\":\"国内云中转方案\",\"type\":\"失败经验\",\"attributes\":{\"失败原因\":\"合规监控、实名备案可追溯\"}}\n"
                + "]}\n"
                + "store is either memory (about the user) or self (about you).\n"
                + "If there is nothing to file, return {\"ops\":[],\"entities\":[]}";

        JSONArray messages = new JSONArray();

        HttpURLConnection connection = null;
        try {
            messages.put(new JSONObject().put("role", "system")
                    .put("content", "You are the keeper of the long-term memory. Return a JSON object only, and nothing else."));
            messages.put(new JSONObject().put("role", "user").put("content", prompt));

            JSONObject body = new JSONObject();
            body.put("model", isBlank(memoryModel(settings)) ? "qwen-plus" : memoryModel(settings).trim());
            body.put("messages", messages);
            body.put("stream", false);
            body.put("max_tokens", settings.memoryExtractMaxTokens > 0 ? settings.memoryExtractMaxTokens : 110000);
            body.put("temperature", settings.memoryExtractTemperature > 0 ? settings.memoryExtractTemperature : 0.1);
            // 记忆整理思考档位（用户 2026-10-13；0=关，与改动前一致）。
            // 关档不写任何字段——这些端点原本就没有 thinking 参数；
            // 开档才用协商器挑写法，失败会被 try/catch 兜住不影响主流程。
            if (settings.memoryThinkLevel >= ReasoningNegotiator.LEVEL_LOW) {
                try { ReasoningNegotiator.applyReasoning(body, base, memoryModel(settings), settings.memoryThinkLevel, 0); }
                catch (Exception ignored) { }
            }

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings.readTimeout > 0 ? settings.readTimeout : 120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key.trim());

            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            if (code >= 400) {
                out.error = "HTTP " + code;
                return out;
            }
            String responseText = readAll(connection.getInputStream());
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                out.error = "模型没有返回内容";
                return out;
            }
            JSONObject firstMessage = choices.getJSONObject(0).optJSONObject("message");
            if (firstMessage == null) {
                out.error = "模型返回结构异常";
                return out;
            }
            String contentStr = firstMessage.optString("content", "").trim();

            int objStart = contentStr.indexOf('{');
            int objEnd = contentStr.lastIndexOf('}');
            if (objStart < 0 || objEnd <= objStart) {
                out.error = "模型返回的不是 JSON";
                return out;
            }
            JSONObject obj = new JSONObject(contentStr.substring(objStart, objEnd + 1));
            JSONArray ops = obj.optJSONArray("ops");
            if (ops == null) {
                out.ok = true;
                return out;
            }
            for (int i = 0; i < ops.length(); i++) {
                JSONObject o = ops.optJSONObject(i);
                if (o == null) continue;
                String op = o.optString("op", "").trim().toLowerCase();
                String content = o.optString("content", "").trim();
                if (content.isEmpty()) continue;
                // 只接受 add / update / merge —— 删除能力在代码层面就不存在
                if (!"add".equals(op) && !"update".equals(op) && !"merge".equals(op)) continue;
                MemoryOp mo = new MemoryOp();
                mo.op = op;
                mo.id = o.optLong("id", 0);
                // store 归属：模型明确写了就用它；没写就按主语兜底判断 ——
                // 第一人称（I/me/my）是它自己的（self），其余算用户的（memory）。
                // 依据：13 个用户维度与 10 个自我维度里有重名（如"情感"），
                // 光看 category 分不清，主语才是可靠判据。
                String storeRaw = o.optString("store", "").trim();
                if ("self".equals(storeRaw)) {
                    mo.store = "self";
                } else if ("memory".equals(storeRaw)) {
                    mo.store = "memory";
                } else {
                    mo.store = looksFirstPerson(content) ? "self" : "memory";
                }
                String cat = o.optString("category", "").trim();
                mo.category = "self".equals(mo.store)
                        ? MemoryDim.normalizeSelf(cat)
                        : MemoryDim.normalizeUser(cat);
                mo.content = content;
                if (("update".equals(op) || "merge".equals(op)) && mo.id <= 0) continue;
                out.ops.add(mo);
            }
            // 实体：按名字 upsert（同名覆盖），跟记忆分开存
            JSONArray ents = obj.optJSONArray("entities");
            if (ents != null) {
                for (int i = 0; i < ents.length(); i++) {
                    JSONObject e = ents.optJSONObject(i);
                    if (e == null) continue;
                    String nm = e.optString("name", "").trim();
                    if (nm.isEmpty()) continue;
                    EntityOp eo = new EntityOp();
                    eo.name = nm;
                    String ty = e.optString("type", "").trim();
                    eo.type = ty.isEmpty() ? "其他" : ty;
                    Object at = e.opt("attributes");
                    eo.attributes = at == null ? "" : at.toString();
                    out.entities.add(eo);
                }
            }
            out.ok = true;
            return out;
        } catch (Exception e) {
            out.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return out;
        } finally {
            if (connection != null) try { connection.disconnect(); } catch (Exception ignored) { }
        }
    }

    /** 记忆整理要执行的一个操作。*/
    public static class MemoryOp {
        public String op = "";       // add / update / merge
        public long id = 0;
        public String store = "memory";
        public String category = "笔记";
        public String content = "";
    }

    /** 整理时顺便抽出的实体（人物/地点/项目/技术/失败经验）。*/
    public static class EntityOp {
        public String name = "";
        public String type = "其他";
        public String attributes = "";
    }

    public static class ConsolidateResult {
        public boolean ok = false;
        public String error = "";
        public final List<MemoryOp> ops = new ArrayList<>();
        public final List<EntityOp> entities = new ArrayList<>();
    }

    /**
     * 判断一条记忆是不是"它说自己"：看开头的主语。
     * 用于模型漏填 store 时兜底 —— 第一人称（I/me/my）归 self，其余归 memory。
     */
    private static boolean looksFirstPerson(String content) {
        if (content == null) return false;
        String s = content.trim().toLowerCase(java.util.Locale.ROOT);
        // 去掉常见的开场符（列表点、引号）
        while (s.length() > 0) {
            char c = s.charAt(0);
            if (c == '-' || c == '*' || c == '"' || c == '\'' || c == ' ') s = s.substring(1).trim();
            else break;
        }
        return s.startsWith("i ") || s.startsWith("i'") || s.startsWith("my ")
                || s.startsWith("me ") || s.startsWith("i,") || s.startsWith("i.")
                || s.startsWith("i'm ") || s.startsWith("i've ");
    }

    /** 记忆整理用的 baseUrl：单独配了就用单独的，否则回落主对话。*/
    static String memoryBase(AppSettings s) {
        if (s != null && !isBlank(s.memoryBaseUrl)) return s.memoryBaseUrl.trim();
        return chatBase(s);
    }

    /** 记忆整理用的 key：单独配了就用单独的，否则回落主对话。*/
    static String memoryKey(AppSettings s) {
        if (s != null && !isBlank(s.memoryApiKey)) return s.memoryApiKey.trim();
        return chatKey(s);
    }

    /** 记忆整理用的模型名：单独配了就用单独的，否则回落主对话。*/
    static String memoryModel(AppSettings s) {
        if (s != null && !isBlank(s.memoryModel)) return s.memoryModel.trim();
        return chatModel(s);
    }

    public static String transcribe(AppSettings settings, File audioFile) throws Exception {        if (settings == null) {
            throw new RuntimeException("没有配置 API Key，无法云端转写");
        }

        if (settings.enableDoubaoAsr && !isBlank(settings.doubaoAppId) && !isBlank(settings.doubaoAccessKey)) {
            return transcribeDoubaoAsr(settings, audioFile);
        }
        if (isBlank(asrKey(settings))) {
            throw new RuntimeException("没有配置 API Key，无法云端转写");
        }
        String base = asrBase(settings);

        if (base != null && base.contains("aliyuncs.com")) {
            return transcribeDashScope(settings, audioFile);
        }
        String endpoint = endpointFor(base, "/audio/transcriptions");
        String boundary = "----linYuan" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeFormField(body, boundary, "model", isBlank(settings.asrModel) ? "paraformer-realtime-v2" : settings.asrModel.trim());
        writeFormField(body, boundary, "language", isBlank(settings.language) ? "zh" : settings.language.trim());
        writeFormFile(body, boundary, "file", audioFile);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings != null ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings != null ? settings.readTimeout : 60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Authorization", "Bearer " + asrKey(settings).trim());

            OutputStream output = connection.getOutputStream();
            output.write(body.toByteArray());
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            String text = root.optString("text", "").trim();
            if (text.isEmpty()) {
                JSONArray choices = root.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    text = choices.getJSONObject(0).optString("text", "").trim();
                }
            }
            if (text.isEmpty()) throw new RuntimeException("转写接口没有返回文字");
            return text;
        } finally {
            connection.disconnect();
        }
    }

    public static String transcribeWav(AppSettings settings, File wavFile) throws Exception {
        String base = asrBase(settings).trim();

        if (base.isEmpty()) {
            base = "https://dashscope.aliyuncs.com";
        }
        String endpoint = dashScopeNativeEndpoint(base, "multimodal-generation");

        if (settings != null && settings.modelPoolEnabled) {
            java.util.List<ModelPool.Entry> pool = ModelPool.available(ModelPool.PURPOSE_ASR, settings);
            Exception last = null;
            for (ModelPool.Entry e : pool) {
                try {
                    String text = transcribeNativeWithModel(settings, wavFile, endpoint, "wav", e.model);
                    ModelPool.markOk(e.model, settings);
                    return text;
                } catch (Exception ex) {
                    last = ex;
                    if (ModelPool.isQuotaError(ex)) {
                        ModelPool.markExhausted(ModelPool.PURPOSE_ASR, e.model, settings);
                        android.util.Log.w("VC_POOL", "ASR " + e.model + " 额度用尽，换下一个：" + ex.getMessage());
                    } else {

                        android.util.Log.w("VC_POOL", "ASR " + e.model + " 失败（非额度）：" + ex.getMessage());
                    }
                }
            }
            if (last != null) {
                throw new RuntimeException("额度池内所有转文字模型都失败了（" + pool.size()
                        + " 个）。最后一个错误：" + last.getMessage());
            }
        }
        return transcribeNative(settings, wavFile, endpoint, "wav");
    }

    private static String transcribeNativeWithModel(AppSettings settings, File audioFile,
                                                   String endpoint, String format, String model) throws Exception {
        return transcribeNativeImpl(settings, audioFile, endpoint, format, model);
    }

    private static String dashScopeNativeEndpoint(String base, String service) {
        String root = base.trim();
        for (String tail : new String[]{"/compatible-mode/v1", "/api/v1", "/api-ws/v1"}) {
            int i = root.indexOf(tail);
            if (i >= 0) { root = root.substring(0, i); break; }
        }
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (root.isEmpty()) root = "https://dashscope.aliyuncs.com";
        if ("multimodal-generation".equals(service)) {
            return root + "/api/v1/services/aigc/multimodal-generation/generation";
        }
        return root + "/api/v1/services/aigc/" + service + "/generation";
    }

    private static String transcribeNative(AppSettings settings, File audioFile, String endpoint, String format) throws Exception {
        return transcribeNativeImpl(settings, audioFile, endpoint, format, null);
    }

    private static String transcribeNativeImpl(AppSettings settings, File audioFile, String endpoint,
                                               String format, String modelOverride) throws Exception {
        ByteArrayOutputStream audioBytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(audioFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) audioBytes.write(buffer, 0, read);
        }
        String mime = guessAudioMime(audioFile.getName());
        String dataUri = "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(audioBytes.toByteArray());

        JSONArray content = new JSONArray().put(
                new JSONObject().put("audio", dataUri));
        JSONArray messages = new JSONArray().put(
                new JSONObject().put("role", "user").put("content", content));

        JSONObject input = new JSONObject().put("messages", messages);
        JSONObject parameters = new JSONObject().put("format", format);

        JSONObject body = new JSONObject();
        String asrModel = !isBlank(modelOverride) ? modelOverride.trim()
                : (isBlank(settings.asrModel) ? "qwen-audio-3.1-asr-flash" : settings.asrModel.trim());

        if (isBlank(modelOverride)) {
            if (asrModel.equalsIgnoreCase("asr-2.5-pro-alpha")
                    || asrModel.equalsIgnoreCase("qwen3-asr-flash")
                    || asrModel.equalsIgnoreCase("qwen3-asr-flash-realtime")) {
                asrModel = "qwen-audio-3.1-asr-flash";
            }
        }
        body.put("model", asrModel);
        body.put("input", input);
        body.put("parameters", parameters);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connTimeoutMs(settings, 20000));
            connection.setReadTimeout(readTimeoutMs(settings, 120000));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + asrKey(settings).trim());

            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream inputStream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(inputStream);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            String text = extractNativeAsrText(responseText);
            if (text.isEmpty()) throw new RuntimeException("转写接口没有返回文字（可能没听清，请重试）");
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String extractNativeAsrText(String responseText) {
        try {
            JSONObject root = new JSONObject(responseText);

            JSONObject output = root.optJSONObject("output");
            if (output != null) {
                String t = output.optString("text", "").trim();
                if (!t.isEmpty()) return t;

                JSONObject inner = output.optJSONObject("output");
                if (inner != null) {
                    t = inner.optString("text", "").trim();
                    if (!t.isEmpty()) return t;
                }

                JSONObject sentence = output.optJSONObject("sentence");
                if (sentence != null) {
                    t = sentence.optString("text", "").trim();
                    if (!t.isEmpty()) return t;
                }
            }

            String t = root.optString("text", "").trim();
            if (!t.isEmpty()) return t;

            JSONObject sentence = root.optJSONObject("sentence");
            if (sentence != null) return sentence.optString("text", "").trim();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String transcribeDashScope(AppSettings settings, File audioFile) throws Exception {
        String base = asrBase(settings).trim();
        if (base.isEmpty()) base = "https://dashscope.aliyuncs.com";

        String endpoint = dashScopeNativeEndpoint(base, "multimodal-generation");

        return transcribeNative(settings, audioFile, endpoint, "m4a");
    }

    private static String transcribeDashScopeCompat(AppSettings settings, File audioFile) throws Exception {
        String base = asrBase(settings).trim();
        if (!base.contains("/compatible-mode/v1")) {
            base = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        String endpoint = base + "/chat/completions";
        ByteArrayOutputStream audioBytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(audioFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                audioBytes.write(buffer, 0, read);
            }
        }
        String mime = guessAudioMime(audioFile.getName());
        String dataUri = "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(audioBytes.toByteArray());

        JSONObject audioPart = new JSONObject();
        audioPart.put("type", "input_audio");
        audioPart.put("input_audio", new JSONObject().put("data", dataUri));
        JSONArray content = new JSONArray().put(audioPart);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));

        JSONObject body = new JSONObject();
        String asrModel = isBlank(settings.asrModel) ? "qwen3-asr-flash" : settings.asrModel.trim();
        if (asrModel.equalsIgnoreCase("asr-2.5-pro-alpha")) asrModel = "qwen3-asr-flash";
        body.put("model", asrModel);
        body.put("messages", messages);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connTimeoutMs(settings, 20000));
            connection.setReadTimeout(readTimeoutMs(settings, 120000));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + asrKey(settings).trim());

            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.optJSONArray("choices");
            String text = "";
            if (choices != null && choices.length() > 0) {
                JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                if (message != null) text = message.optString("content", "").trim();
            }
            if (text.isEmpty()) throw new RuntimeException("转写接口没有返回文字（可能没听清，请重试）");
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String guessAudioMime(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mp3";
        if (lower.endsWith(".amr")) return "audio/amr";

        return "audio/mp4";
    }

    public static String synthesize(AppSettings settings, String text, File outputFile) throws Exception {
        if (settings == null) {
            throw new RuntimeException("没有配置 API Key，无法云端语音合成");
        }

        // 豆包双向流式排在最前：文本边给边合成、音频边出边收，首字比单向快。
        // 失败不抛死，交给下面的通道继续。
        if (settings.enableDoubaoTts && !isBlank(settings.doubaoApiKey)) {
            try {
                return DoubaoStreamTts.synthesize(
                        settings.doubaoApiKey, settings.doubaoSpeakerId, text, outputFile,
                        "mp3", 24000,
                        connTimeoutMs(settings, 20000), readTimeoutMs(settings, 60000));
            } catch (Exception e) {
                android.util.Log.w("VC_DOUBAO_TTS", "双向流式失败，回退其它通道：" + e.getMessage());
            }
        }

        if (settings.useTencentTts
                && !isBlank(settings.tencentSecretId) && !isBlank(settings.tencentSecretKey)) {
            try {
                return TencentTts.synthesize(
                        settings.tencentSecretId, settings.tencentSecretKey,
                        settings.tencentTtsRegion, settings.tencentTtsVoice,
                        settings.tencentTtsSpeed, text, outputFile);
            } catch (Exception e) {
                // 别让一条通道的故障吃掉整条链：腾讯欠费/限额时继续往下试。
                android.util.Log.w("VC_TTS", "腾讯云 TTS 失败，继续尝试其它通道：" + e.getMessage());
            }
        }

        if (settings.modelPoolEnabled) {
            java.util.List<ModelPool.Entry> pool = ModelPool.available(ModelPool.PURPOSE_TTS, settings);
            Exception lastModelErr = null;
            for (ModelPool.Entry e : pool) {
                try {
                    String path = synthesizeForModel(settings, text, outputFile, e.model, e.voice);
                    ModelPool.markOk(e.model, settings);
                    return path;
                } catch (Exception ex) {
                    lastModelErr = ex;
                    if (ModelPool.isQuotaError(ex)) {
                        ModelPool.markExhausted(ModelPool.PURPOSE_TTS, e.model, settings);
                        android.util.Log.w("VC_POOL", "TTS " + e.model + " 额度用尽，换下一个：" + ex.getMessage());
                    } else {
                        android.util.Log.w("VC_POOL", "TTS " + e.model + " 失败（非额度，不禁用）：" + ex.getMessage());
                    }
                }
            }
            if (lastModelErr != null) {
                android.util.Log.w("VC_POOL", "阿里云 TTS 模型池全部失败，继续回退下面的通道："
                        + lastModelErr.getMessage());
            }
        }

        TtsPool.refresh(settings);
        Exception lastPoolError = null;
        if (!TtsPool.isEmpty()) {
            Exception last = null;
            int tries = Math.min(TtsPool.size() + 1, 6);
            for (int i = 0; i < tries; i++) {
                TtsPool.Entry e = TtsPool.next();
                if (e == null) break;
                try {
                    String path = synthesizeWithPoolEntry(settings, e, text, outputFile);
                    if (i > 0) {
                        android.util.Log.i("VC_TTS", "TTS 池：第 " + (i + 1) + " 个密钥成功（" + e.label() + "）");
                    }
                    return path;
                } catch (Exception ex) {
                    last = ex;
                    if (TtsPool.isKeyUnusable(ex)) {

                        TtsPool.markDisabled(e, String.valueOf(ex.getMessage()));
                    } else {
                        android.util.Log.w("VC_TTS", "TTS 池：密钥 " + e.label()
                                + " 本次失败（非密钥问题，不禁用）：" + ex.getMessage());
                    }
                }
            }

            lastPoolError = last;
            android.util.Log.w("VC_TTS", "TTS 池全部尝试失败，回退单账号配置: "
                    + (last == null ? "无可用密钥" : last.getMessage()));
            if (TtsPool.disabledCount() >= TtsPool.size() && TtsPool.size() > 0) {

                throw new RuntimeException("TTS 密钥池里的 " + TtsPool.size()
                        + " 个密钥都不可用（多为额度用尽或鉴权失败）。"
                        + "请在「设置 → TTS 密钥池」里补充新平台的免费额度密钥"
                        + (last != null ? "。最后一次错误：" + last.getMessage() : ""));
            }
        }

        if (settings.useAliyunTts && !isBlank(settings.aliyunTtsApiKey)) {
            return synthesizeAliyunTts(settings, text, outputFile);
        }

        if (settings.enableDoubaoTts && !isBlank(settings.doubaoAppId) && !isBlank(settings.doubaoAccessKey)) {
            return synthesizeDoubaoTts(settings, text, outputFile);
        }
        if (isBlank(ttsKey(settings))) {

            if (lastPoolError != null) {
                throw new RuntimeException("云端语音合成失败：" + lastPoolError.getMessage()
                        + "（TTS 池密钥均失败，且未配置单账号 TTS 密钥）");
            }
            throw new RuntimeException("没有配置 API Key，无法云端语音合成");
        }
        String base = ttsBase(settings);

        if (base != null && base.contains("aliyuncs.com")) {
            return synthesizeAliyunByModel(settings, text, outputFile);
        }

        return synthesizeOpenAiCompat(settings, text, outputFile);
    }

    private static String synthesizeWithPoolEntry(AppSettings settings, TtsPool.Entry e,
                                                  String text, File outputFile) throws Exception {
        AppSettings s = copyForTts(settings);

        String platform = isBlank(e.platform) ? detectPlatform(settings) : e.platform;

        if ("aliyun".equals(platform)) {
            s.useAliyunTts = true;

            String effectiveKey = !isBlank(e.apiKey) ? e.apiKey : settings.aliyunTtsApiKey;
            if (isBlank(effectiveKey)) {
                throw new RuntimeException("TTS 池 aliyun 条目没有可用密钥（" + e.label() + "）");
            }
            s.aliyunTtsApiKey = effectiveKey;

            s.ttsApiKey = effectiveKey;

            if (!isBlank(e.baseUrl)) s.ttsBaseUrl = e.baseUrl;
            if (!isBlank(e.model)) s.ttsModel = e.model;
            if (!isBlank(e.voice)) s.ttsVoice = e.voice;

            return synthesizeAliyunByModel(s, text, outputFile);
        }

        if ("doubao".equals(platform)) {
            String appId = s.doubaoAppId, access = s.doubaoAccessKey;

            int colon = e.apiKey.indexOf(':');
            if (colon > 0) {
                appId = e.apiKey.substring(0, colon).trim();
                access = e.apiKey.substring(colon + 1).trim();
            } else if (!isBlank(e.apiKey)) {
                access = e.apiKey;
            }
            s.doubaoAppId = appId;
            s.doubaoAccessKey = access;
            s.enableDoubaoTts = true;
            String voice = !isBlank(e.voice) ? e.voice
                    : (isBlank(e.model) ? s.doubaoSpeakerId : e.model);
            if (!isBlank(voice)) s.doubaoSpeakerId = voice;
            return synthesizeDoubaoTtsWithSpeaker(s, text, outputFile, s.doubaoSpeakerId);
        }

        s.ttsApiKey = e.apiKey;
        if (!isBlank(e.baseUrl)) s.ttsBaseUrl = e.baseUrl;
        if (!isBlank(e.model)) s.ttsModel = e.model;
        if (!isBlank(e.voice)) s.ttsVoice = e.voice;

        s.useAliyunTts = false;
        s.enableDoubaoTts = false;
        String base = ttsBase(s);
        if (base != null && base.contains("aliyuncs.com")) {
            return synthesizeAliyunByModel(s, text, outputFile);
        }
        return synthesizeOpenAiCompat(s, text, outputFile);
    }

    private static String detectPlatform(AppSettings s) {
        if (s != null && s.useAliyunTts && !isBlank(s.aliyunTtsApiKey)) return "aliyun";
        if (s != null && s.enableDoubaoTts && !isBlank(s.doubaoAppId) && !isBlank(s.doubaoAccessKey)) return "doubao";
        String base = ttsBase(s);
        if (base != null && base.contains("aliyuncs.com")) return "aliyun";
        return "openai";
    }

    private static AppSettings copyForTts(AppSettings src) {
        AppSettings s = new AppSettings();
        if (src == null) return s;
        s.ttsBaseUrl = src.ttsBaseUrl;
        s.ttsModel = src.ttsModel;
        s.ttsVoice = src.ttsVoice;
        s.ttsApiKey = src.ttsApiKey;
        s.useAliyunTts = src.useAliyunTts;
        s.aliyunTtsApiKey = src.aliyunTtsApiKey;
        s.aliyunTtsBaseUrl = src.aliyunTtsBaseUrl;
        s.aliyunTtsModel = src.aliyunTtsModel;
        s.enableDoubaoTts = src.enableDoubaoTts;
        s.doubaoAppId = src.doubaoAppId;
        s.doubaoAccessKey = src.doubaoAccessKey;
        s.doubaoSpeakerId = src.doubaoSpeakerId;
        s.modelPoolEnabled = src.modelPoolEnabled;
        s.modelPoolState = src.modelPoolState;
        s.asrBaseUrl = src.asrBaseUrl;
        s.asrModel = src.asrModel;
        s.asrApiKey = src.asrApiKey;
        s.connectTimeout = src.connectTimeout;
        s.readTimeout = src.readTimeout;
        return s;
    }

    private static String synthesizeOpenAiCompat(AppSettings settings, String text, File outputFile) throws Exception {
        String base = ttsBase(settings);
        String endpoint = endpointFor(base, "/audio/speech");

        JSONObject body = new JSONObject();
        body.put("model", isBlank(settings.ttsModel) ? "sambert-zhide-v1" : settings.ttsModel.trim());
        body.put("input", text);
        body.put("voice", isBlank(settings.ttsVoice) ? "zhide" : settings.ttsVoice.trim());
        body.put("response_format", "mp3");

        if (isBlank(ttsKey(settings))) {
            throw new RuntimeException("没有配置 API Key，无法云端语音合成");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings != null ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings != null ? settings.readTimeout : 60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + ttsKey(settings).trim());

            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            if (code >= 400) {
                String error = readAll(connection.getErrorStream());
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(error));
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream fileOutput = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    fileOutput.write(buffer, 0, read);
                }
            }
            return outputFile.getAbsolutePath();
        } finally {
            connection.disconnect();
        }
    }

    private static String synthesizeAliyunByModel(AppSettings s, String text, File outputFile) throws Exception {
        return synthesizeForModel(s, text, outputFile, null, null);
    }

    private static String synthesizeForModel(AppSettings s, String text, File outputFile,
                                             String modelOverride, String voiceOverride) throws Exception {
        String m = !isBlank(modelOverride) ? modelOverride.trim()
                : (isBlank(s.ttsModel) ? "" : s.ttsModel.trim());
        String lower = m.toLowerCase(Locale.US);

        AppSettings t = copyForTts(s);
        if (!isBlank(modelOverride)) {

            t.ttsModel = modelOverride.trim();
            t.aliyunTtsModel = modelOverride.trim();
        }
        if (!isBlank(voiceOverride)) t.ttsVoice = voiceOverride.trim();

        if (lower.startsWith("cosyvoice") || lower.startsWith("sambert")) {
            return synthesizeAliyunTts(t, text, outputFile);
        }
        if (lower.startsWith("qwen") && lower.contains("tts")) {

            return synthesizeQwenTtsWs(t, text, outputFile);
        }
        return synthesizeDashScope(t, text, outputFile);
    }

    private static String synthesizeQwenTtsWs(AppSettings settings, String text, File outputFile) throws Exception {
        String apiKey = ttsKey(settings).trim();
        if (apiKey.isEmpty()) throw new RuntimeException("未配置 TTS API Key（qwen-audio-tts）");
        String model = isBlank(settings.ttsModel) ? "qwen-audio-3.1-tts-next" : settings.ttsModel.trim();
        return wsSynthesize(settings, apiKey, model, text, outputFile, true);
    }

    private static String synthesizeDashScope(AppSettings settings, String text, File outputFile) throws Exception {
        String endpoint = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

        JSONObject input = new JSONObject();
        input.put("text", text);
        input.put("voice", isBlank(settings.ttsVoice) ? "Cherry" : settings.ttsVoice.trim());
        input.put("language_type", "Chinese");
        JSONObject body = new JSONObject();
        body.put("model", isBlank(settings.ttsModel) ? "qwen3-tts-flash" : settings.ttsModel.trim());
        body.put("input", input);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        String audioUrl;
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connTimeoutMs(settings, 20000));
            connection.setReadTimeout(readTimeoutMs(settings, 120000));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + ttsKey(settings).trim());

            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input2 = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input2);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONObject outputObj = root.optJSONObject("output");
            JSONObject audioObj = outputObj != null ? outputObj.optJSONObject("audio") : null;
            audioUrl = audioObj != null ? audioObj.optString("url", "").trim() : "";
            if (audioUrl.isEmpty()) {
                throw new RuntimeException("合成接口没有返回音频地址 " + safeSnippet(responseText));
            }
        } finally {
            connection.disconnect();
        }

        HttpURLConnection download = (HttpURLConnection) new URL(audioUrl).openConnection();
        try {
            download.setConnectTimeout(connTimeoutMs(settings, 20000));
            download.setReadTimeout(readTimeoutMs(settings, 300000));
            int code = download.getResponseCode();
            if (code >= 400) {
                throw new RuntimeException("下载合成音频失败 HTTP " + code);
            }
            try (InputStream is = download.getInputStream();
                 FileOutputStream fileOutput = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fileOutput.write(buffer, 0, read);
                }
            }
            return outputFile.getAbsolutePath();
        } finally {
            download.disconnect();
        }
    }

    private static void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFormFile(ByteArrayOutputStream out, String boundary, String name, File file) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: audio/mp4\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    public static String probeDoubaoSpeaker(AppSettings settings, String speakerId) throws Exception {
        if (settings == null || isBlank(settings.doubaoAppId) || isBlank(settings.doubaoAccessKey)) {
            return "未配置 Doubao AppId/AccessKey";
        }
        String probeText = "你好，我是lin渊。";
        File tmp = File.createTempFile("vc_probe_", ".mp3");
        try {
            try {
                synthesizeDoubaoTtsWithSpeaker(settings, probeText, tmp, speakerId);
            } catch (Exception e) {
                return e.getMessage();
            }
            if (tmp.exists() && tmp.length() > 512) return null;
            return "未返回音频数据";
        } finally {
            tmp.delete();
        }
    }

    private static String synthesizeDoubaoTts(AppSettings settings, String text, File outputFile) throws Exception {
        String speaker = isBlank(settings.doubaoSpeakerId) ? "zh_female_vv_uranus_bigtts" : settings.doubaoSpeakerId.trim();
        return synthesizeDoubaoTtsWithSpeaker(settings, text, outputFile, speaker);
    }

    private static String synthesizeDoubaoTtsWithSpeaker(AppSettings settings, String text, File outputFile, String speaker) throws Exception {
        String appId = settings.doubaoAppId.trim();
        String accessKey = settings.doubaoAccessKey.trim();
        if (isBlank(speaker)) speaker = "zh_female_vv_uranus_bigtts";
        String endpoint = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";

        JSONObject body = new JSONObject();
        JSONObject reqParams = new JSONObject();
        reqParams.put("text", text);
        reqParams.put("speaker", speaker);
        JSONObject audioParams = new JSONObject();
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);
        reqParams.put("audio_params", audioParams);
        body.put("req_params", reqParams);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connTimeoutMs(settings, 20000));
            connection.setReadTimeout(readTimeoutMs(settings, 120000));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Api-App-Id", appId);
            connection.setRequestProperty("X-Api-Access-Key", accessKey);
            connection.setRequestProperty("X-Api-Resource-Id", "seed-tts-2.0");

            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }

            ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
            String ttsError = null;
            String[] lines = responseText.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject json = new JSONObject(line);
                    int resultCode = json.optInt("code", -1);
                    if (resultCode == 0 && json.has("data")) {
                        String base64Audio = json.getString("data");
                        byte[] audioBytes = java.util.Base64.getDecoder().decode(base64Audio);
                        audioOut.write(audioBytes, 0, audioBytes.length);
                    } else if (resultCode == 20000000) {
                        break;
                    } else if (resultCode != 0) {

                        if (ttsError == null) {
                            ttsError = "Doubao TTS error: " + json.optString("message", "code=" + resultCode);
                        }
                    }
                } catch (Exception e) {

                }
            }

            if (audioOut.size() == 0) {
                throw new RuntimeException(ttsError != null ? ttsError
                        : "Doubao TTS 未返回音频数据 " + safeSnippet(responseText));
            }
            try (FileOutputStream fileOutput = new FileOutputStream(outputFile)) {
                fileOutput.write(audioOut.toByteArray());
            }
            return outputFile.getAbsolutePath();
        } finally {
            connection.disconnect();
        }
    }

    private static String synthesizeAliyunTts(AppSettings settings, String text, File outputFile) throws Exception {
        String apiKey = settings.aliyunTtsApiKey == null ? "" : settings.aliyunTtsApiKey.trim();
        if (apiKey.isEmpty()) apiKey = ttsKey(settings).trim();
        if (apiKey.isEmpty()) throw new RuntimeException("未配置阿里云 TTS（CosyVoice）API Key");
        String model = isBlank(settings.aliyunTtsModel) ? "cosyvoice-v1" : settings.aliyunTtsModel.trim();

        return wsSynthesize(settings, apiKey, model, text, outputFile, false);
    }

    private static String wsSynthesize(AppSettings settings, String apiKey, String model,
                                       String text, File outputFile, boolean needTextPrompt) throws Exception {
        String wsUrl = isBlank(settings.aliyunTtsBaseUrl)
                ? "wss://dashscope.aliyuncs.com/api-ws/v1/inference" : settings.aliyunTtsBaseUrl.trim();

        String hostPort = wsUrl.replaceFirst("^(wss?|https?)://", "");
        String userPath = "";
        int slash = hostPort.indexOf('/');
        if (slash >= 0) { userPath = hostPort.substring(slash); hostPort = hostPort.substring(0, slash); }
        String path = userPath.contains("/api-ws/") ? userPath : "/api-ws/v1/inference";
        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        int port = hostPort.contains(":") ? Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1)) : 443;

        String taskId = java.util.UUID.randomUUID().toString().replace("-", "");

        javax.net.ssl.SSLSocketFactory sf = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) sf.createSocket();
        try {
            javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
            params.setApplicationProtocols(new String[]{"http/1.1"});
            ssl.setSSLParameters(params);
        } catch (Exception ignored) {}
        try {

            ssl.connect(new java.net.InetSocketAddress(host, port), settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
            ssl.setSoTimeout(60000);
            ssl.setTcpNoDelay(true);
            ssl.startHandshake();
            java.io.OutputStream out = ssl.getOutputStream();
            java.io.InputStream in = ssl.getInputStream();

            StringBuilder handshake = new StringBuilder();
            handshake.append("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(base64Key()).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n")
                    .append("User-Agent: linyuan/1.0\r\n")
                    .append("Authorization: bearer ").append(apiKey).append("\r\n\r\n");
            out.write(handshake.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int hs = 0;
            while (true) {
                int b = in.read();
                if (b < 0) throw new RuntimeException("阿里云 TTS 连接被关闭");
                headBuf.write(b);
                if (b == '\r') { hs = (hs == 2) ? 3 : 1; }
                else if (b == '\n') { if (hs == 3) { hs = 4; break; } hs = (hs == 1) ? 2 : 0; }
                else { hs = 0; }
                if (hs == 4) break;
            }
            String head = headBuf.toString("UTF-8");
            if (!head.startsWith("HTTP/1.1 101")) {
                throw new RuntimeException("阿里云 TTS 握手失败: " + head.replace("\r", " ").trim());
            }

            JSONObject ttsParameters = new JSONObject()
                    .put("text_type", "PlainText").put("format", "mp3")
                    .put("sample_rate", 16000).put("volume", 50).put("rate", 1).put("pitch", 1);
            String ttsVoice = settings.ttsVoice == null ? "" : settings.ttsVoice.trim();
            if (!ttsVoice.isEmpty()) ttsParameters.put("voice", ttsVoice);

            JSONObject runInput = new JSONObject();

            runInput.put("text", text);
            if (needTextPrompt) {

                ttsParameters.put("text_prompt", text);
            }
            JSONObject runTask = new JSONObject();
            runTask.put("header", new JSONObject()
                    .put("action", "run-task").put("task_id", taskId).put("streaming", "duplex"));
            runTask.put("payload", new JSONObject()
                    .put("task_group", "audio").put("task", "tts").put("function", "SpeechSynthesizer")
                    .put("model", model)
                    .put("parameters", ttsParameters)
                    .put("input", runInput));
            writeWsFrame(out, 0x1, runTask.toString().getBytes(StandardCharsets.UTF_8));

            JSONObject finishTask = new JSONObject();
            finishTask.put("header", new JSONObject()
                    .put("action", "finish-task").put("task_id", taskId).put("streaming", "duplex"));
            finishTask.put("payload", new JSONObject().put("input", new JSONObject()));
            writeWsFrame(out, 0x1, finishTask.toString().getBytes(StandardCharsets.UTF_8));

            ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
            String errorMsg = null;
            boolean finished = false;
            while (!finished) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
                }
                byte[] payload = new byte[(int) len];
                int off = 0;
                while (off < len) {
                    int n = in.read(payload, off, (int) len - off);
                    if (n < 0) break;
                    off += n;
                }
                if (opcode == 0x2) {
                    audioOut.write(payload);
                } else if (opcode == 0x1) {
                    String json = new String(payload, StandardCharsets.UTF_8);
                    try {
                        JSONObject ev = new JSONObject(json);
                        JSONObject hdr = ev.optJSONObject("header");
                        if (hdr != null) {
                            String event = hdr.optString("event");
                            if ("task-finished".equals(event)) { finished = true; }
                            else if ("task-failed".equals(event)) {
                                errorMsg = hdr.optString("error_message", "合成失败");
                                finished = true;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (errorMsg != null) {
                throw new RuntimeException("阿里云 TTS 错误: " + errorMsg);
            }
            if (audioOut.size() == 0) {
                throw new RuntimeException("阿里云 TTS 未返回音频数据");
            }
            try (FileOutputStream fileOutput = new FileOutputStream(outputFile)) {
                fileOutput.write(audioOut.toByteArray());
            }
            return outputFile.getAbsolutePath();
        } finally {
            try { ssl.close(); } catch (Exception ignored) {}
        }
    }

    private static String transcribeDoubaoAsr(AppSettings settings, File audioFile) throws Exception {
        String accessKey = settings.doubaoAccessKey.trim();
        String appId = settings.doubaoAppId.trim();

        byte[] raw;
        try (FileInputStream input = new FileInputStream(audioFile)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) bos.write(buffer, 0, read);
            raw = bos.toByteArray();
        }
        if (raw.length == 0) throw new RuntimeException("录音为空");

        byte[] pcm = toPcm16k(raw);

        String endpoint = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel";
        java.net.Socket socket = new java.net.Socket();
        socket.connect(new java.net.InetSocketAddress("openspeech.bytedance.com", 443),
                settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
        socket.setSoTimeout(settings.readTimeout > 0 ? settings.readTimeout : 60000);

        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket)
                ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                        .createSocket(socket, "openspeech.bytedance.com", 443, true);
        try {

            ssl.startHandshake();
            java.io.OutputStream out = ssl.getOutputStream();
            java.io.InputStream in = ssl.getInputStream();

            StringBuilder handshake = new StringBuilder();
            handshake.append("GET /api/v3/sauc/bigmodel HTTP/1.1\r\n")
                    .append("Host: openspeech.bytedance.com\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(base64Key()).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n")
                    .append("X-Api-App-Key: ").append(appId).append("\r\n")
                    .append("X-Api-Access-Key: ").append(accessKey).append("\r\n")
                    .append("X-Api-Resource-Id: volc.bigasr.sauc.duration\r\n")
                    .append("X-Api-Request-Id: ").append("vc-" + System.currentTimeMillis()).append("\r\n")
                    .append("X-Api-Sequence: -1\r\n\r\n");
            out.write(handshake.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int hs = 0;
            while (true) {
                int b = in.read();
                if (b < 0) throw new RuntimeException("ASR 连接被关闭");
                headBuf.write(b);
                if (b == '\r') { hs = (hs == 2) ? 3 : 1; }
                else if (b == '\n') { if (hs == 3) { hs = 4; break; } hs = (hs == 1) ? 2 : 0; }
                else { hs = 0; }
                if (hs == 4) break;
            }
            String head = headBuf.toString("UTF-8");
            if (!head.startsWith("HTTP/1.1 101")) {
                throw new RuntimeException("ASR 握手失败: " + head.replace("\r", " ").trim());
            }

            JSONObject clientReq = new JSONObject();
            clientReq.put("user", new JSONObject().put("uid", "linyuan"));
            clientReq.put("audio", new JSONObject().put("format", "pcm")
                    .put("codec", "raw").put("rate", 16000).put("bits", 16).put("channel", 1));
            clientReq.put("request", new JSONObject().put("model_name", "bigmodel")
                    .put("enable_itn", true).put("enable_punc", true));
            byte[] clientReqPayload = clientReq.toString().getBytes(StandardCharsets.UTF_8);
            writeWsFrame(out, 0x10, clientReqPayload);

            int chunk = 3200;
            int seq = 2;
            for (int i = 0; i < pcm.length; i += chunk) {
                int len = Math.min(chunk, pcm.length - i);
                byte[] part = new byte[len];
                System.arraycopy(pcm, i, part, 0, len);
                writeWsAudioFrame(out, seq++, part, false);
                Thread.sleep(20);
            }
            writeWsAudioFrame(out, -seq, new byte[0], true);

            String lastText = "";
            long deadline = System.currentTimeMillis() + (settings.readTimeout > 0 ? settings.readTimeout : 60000);
            while (System.currentTimeMillis() < deadline) {
                int mtype = -1;
                byte[] payload = readWsFrame(in);
                if (payload == null) break;

                if (mtype < 0) {
                    JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                    int err = json.optInt("code", 0);
                    if (err != 0 && err != 20000000) {
                        throw new RuntimeException("ASR 错误 code=" + err + " " + json.optString("message"));
                    }
                    JSONObject result = json.optJSONObject("result");
                    if (result != null) {
                        String t = result.optString("text", "").trim();
                        if (!t.isEmpty()) lastText = t;
                    }
                    JSONObject reqid = json.optJSONObject("additions") == null ? null : json.optJSONObject("additions");
                    boolean done = json.has("result");
                    if (done && json.optString("text", "").isEmpty()) break;

                    if (json.optInt("code", -1) == 20000000) break;
                }
            }
            if (lastText.isEmpty()) throw new RuntimeException("没有识别到文字（可能没听清，请重试）");
            return lastText;
        } finally {
            try { ssl.close(); } catch (Exception ignored) {}
        }
    }

    private static String base64Key() {
        byte[] rnd = new byte[16];
        new java.security.SecureRandom().nextBytes(rnd);
        return java.util.Base64.getEncoder().encodeToString(rnd);
    }

    private static void writeWsFrame(java.io.OutputStream out, int opcode, byte[] payload) throws Exception {
        int header = 0x80 | opcode;

        int len = payload.length;
        byte[] mask = new byte[4];
        new java.security.SecureRandom().nextBytes(mask);
        java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
        frame.write(header);
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len < 65536) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);

            long l64 = len;
            for (int i = 7; i >= 0; i--) frame.write((int) ((l64 >> (8 * i)) & 0xFF));
        }
        frame.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        frame.write(masked);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void writeWsAudioFrame(java.io.OutputStream out, int seq, byte[] pcm, boolean last) throws Exception {

        java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
        int flags = last ? 0x3 : 0x1;
        frame.write(0x11);
        frame.write((0x2 << 4) | flags);
        frame.write(0x00);
        frame.write(0x00);
        frame.write((seq >> 24) & 0xFF);
        frame.write((seq >> 16) & 0xFF);
        frame.write((seq >> 8) & 0xFF);
        frame.write(seq & 0xFF);
        frame.write((pcm.length >> 24) & 0xFF);
        frame.write((pcm.length >> 16) & 0xFF);
        frame.write((pcm.length >> 8) & 0xFF);
        frame.write(pcm.length & 0xFF);
        frame.write(pcm);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static byte[] readWsFrame(java.io.InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        int opcode = b0 & 0x0F;
        int len = b1 & 0x7F;
        if (len == 126) {

            int h = in.read(), l = in.read();
            if (h < 0 || l < 0) return null;
            len = (h << 8) | l;
        } else if (len == 127) {

            long l64 = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read();
                if (b < 0) return null;
                l64 = (l64 << 8) | (b & 0xFF);
            }
            if (l64 < 0 || l64 > 16 * 1024 * 1024) return null;
            len = (int) l64;
        }
        byte[] payload = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(payload, off, len - off);
            if (n < 0) return null;
            off += n;
        }
        return payload;
    }

    private static byte[] toPcm16k(byte[] m4a) throws Exception {
        throw new RuntimeException("本机无 FFmpeg，云端 ASR 需先用百炼 ASR（OpenAI 兼容转写）或系统识别；Doubao ASR 待接入解码器");
    }

    private static String pick(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    public static volatile long searchMinGapMs = 2500;
    public static volatile int searchPerMinute = 20;
    private static final java.util.concurrent.atomic.AtomicLong lastNetCallAt =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong minuteWindowStart =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicInteger minuteCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static final ThreadLocal<Object> GEN_TOKEN = new ThreadLocal<>();

    public static void bindGenerationToken(Object token) {
        if (token == null) GEN_TOKEN.remove();
        else GEN_TOKEN.set(token);
    }

    public static void clearGenerationToken() {
        GEN_TOKEN.remove();
    }

    private static final java.util.Map<Object, java.util.Set<HttpURLConnection>> ACTIVE_CONNS =
            java.util.Collections.synchronizedMap(
                    new java.util.IdentityHashMap<Object, java.util.Set<HttpURLConnection>>());

    public static void registerActiveConn(HttpURLConnection conn) {
        registerActiveConn(GEN_TOKEN.get(), conn);
    }

    public static void registerActiveConn(Object token, HttpURLConnection conn) {
        if (token == null || conn == null) return;
        synchronized (ACTIVE_CONNS) {
            java.util.Set<HttpURLConnection> set = ACTIVE_CONNS.get(token);
            if (set == null) {
                set = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<HttpURLConnection, Boolean>());
                ACTIVE_CONNS.put(token, set);
            }
            set.add(conn);
        }
    }

    public static void clearActiveConn(HttpURLConnection conn) {
        clearActiveConn(GEN_TOKEN.get(), conn);
    }

    public static void clearActiveConn(Object token, HttpURLConnection conn) {
        if (token == null || conn == null) return;
        synchronized (ACTIVE_CONNS) {
            java.util.Set<HttpURLConnection> set = ACTIVE_CONNS.get(token);
            if (set != null) {
                set.remove(conn);
                if (set.isEmpty()) ACTIVE_CONNS.remove(token);
            }
        }
    }

    public static void abortGeneration(Object token) {
        if (token == null) return;
        java.util.Set<HttpURLConnection> set;
        synchronized (ACTIVE_CONNS) {
            set = ACTIVE_CONNS.remove(token);
        }
        if (set == null) return;
        for (HttpURLConnection conn : set) {
            try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    public static boolean isAborted(Object token) {
        return token instanceof java.util.concurrent.atomic.AtomicBoolean
                && ((java.util.concurrent.atomic.AtomicBoolean) token).get();
    }

    public static final class PoolKey {
        public final String baseUrl;
        public final String apiKey;
        public PoolKey(String baseUrl, String apiKey) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }
        @Override public String toString() { return baseUrl + "|" + maskKey(apiKey); }
    }

    public static String maskKey(String k) {
        if (k == null || k.isEmpty()) return "(空)";
        if (k.length() <= 10) return k.charAt(0) + "***";
        return k.substring(0, 6) + "…" + k.substring(k.length() - 4);
    }

    private static final java.util.List<PoolKey> searchPool = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final java.util.Map<String, Long> poolLastUsedAt = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicInteger poolCursor =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static volatile long poolCooldownMs = 10000;

    public static void refreshSearchPool(AppSettings settings) {
        searchPool.clear();
        if (settings == null) return;
        poolCooldownMs = Math.max(0, settings.searchPoolCooldownSec) * 1000L;

        StringBuilder poolText = new StringBuilder();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        String userRaw = settings.searchKeyPool;
        if (userRaw != null && !userRaw.trim().isEmpty()) {
            for (String line : userRaw.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String key = t.contains("|") ? t.substring(t.indexOf('|') + 1).trim() : t;
                if (!key.isEmpty() && seenKeys.add(key)) {
                    if (poolText.length() > 0) poolText.append("\n");
                    poolText.append(t);
                }
            }
        }
        int userCount = seenKeys.size();
        int added = 0;
        for (String builtin : AppSettings.BUILTIN_SEARCH_KEY_POOL) {
            String t = builtin.trim();
            if (t.isEmpty()) continue;
            String key = t.contains("|") ? t.substring(t.indexOf('|') + 1).trim() : t;
            if (key.isEmpty() || !seenKeys.add(key)) continue;
            if (poolText.length() > 0) poolText.append("\n");
            poolText.append(t);
            added++;
        }
        String raw = poolText.toString();
        if (userCount > 0) {
            android.util.Log.i("VC_SEARCH", "搜索池：用户自有 " + userCount + " 个，内置补齐 " + added
                    + " 个 → 共 " + seenKeys.size() + " 个");
        } else {
            android.util.Log.i("VC_SEARCH", "searchKeyPool 为空 → 使用内置池 "
                    + AppSettings.BUILTIN_SEARCH_KEY_POOL.length + " 个密钥");
        }
        if (raw.trim().isEmpty()) return;
        String defBase = isBlank(settings.tokeniqBaseUrl) ? "" : settings.tokeniqBaseUrl.trim();
        while (defBase.endsWith("/")) defBase = defBase.substring(0, defBase.length() - 1);
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String base = defBase, key;
            int bar = s.indexOf('|');
            if (bar > 0) {
                base = s.substring(0, bar).trim();
                key = s.substring(bar + 1).trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            } else {
                key = s;
            }
            if (!key.isEmpty()) searchPool.add(new PoolKey(base, key));
        }
        android.util.Log.i("VC_SEARCH", "搜索密钥池已加载 " + searchPool.size() + " 个密钥，冷却 "
                + (poolCooldownMs / 1000) + " 秒");
    }

    public static int searchPoolSize() { return searchPool.size(); }

    private static PoolKey nextPoolKey() {
        int n = searchPool.size();
        if (n == 0) return null;
        long now = System.currentTimeMillis();
        int start = Math.floorMod(poolCursor.getAndIncrement(), n);
        PoolKey earliest = null;
        long earliestAt = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            PoolKey k = searchPool.get((start + i) % n);
            Long last = poolLastUsedAt.get(k.apiKey);
            long usedAt = last == null ? 0L : last;
            if (now - usedAt >= poolCooldownMs) {
                poolLastUsedAt.put(k.apiKey, now);
                return k;
            }
            if (usedAt < earliestAt) {
                earliestAt = usedAt;
                earliest = k;
            }
        }

        if (earliest != null) poolLastUsedAt.put(earliest.apiKey, now);
        return earliest;
    }

    public static int poolReadyCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (PoolKey k : searchPool) {
            Long last = poolLastUsedAt.get(k.apiKey);
            if (last == null || now - last >= poolCooldownMs) n++;
        }
        return n;
    }

    public static <T> T withSearchLimit(java.util.function.Supplier<T> task,
                                        java.util.concurrent.atomic.AtomicBoolean stopFlag) throws Exception {
        long now = System.currentTimeMillis();

        boolean poolActive = !searchPool.isEmpty();
        boolean gapDisabled = searchMinGapMs <= 0L;
        boolean minuteDisabled = searchPerMinute <= 0;
        if (!poolActive && !gapDisabled) {

            long last = lastNetCallAt.get();
            long wait = searchMinGapMs - (now - last);
            while (wait > 0) {
                if (stopFlag != null && stopFlag.get()) return null;
                Thread.sleep(Math.min(wait, 300));
                now = System.currentTimeMillis();
                wait = searchMinGapMs - (now - lastNetCallAt.get());
            }
        }
        if (!poolActive && !minuteDisabled) {

            long winStart = minuteWindowStart.get();
            if (now - winStart >= 60000) {
                minuteWindowStart.set(now);
                minuteCount.set(0);
            }
            if (minuteCount.get() >= searchPerMinute) {
                long sleepMs = 60000 - (now - minuteWindowStart.get()) + 200;
                while (sleepMs > 0) {
                    if (stopFlag != null && stopFlag.get()) return null;
                    Thread.sleep(Math.min(sleepMs, 500));
                    sleepMs -= 500;
                }
                minuteWindowStart.set(System.currentTimeMillis());
                minuteCount.set(0);
            }
        }
        lastNetCallAt.set(System.currentTimeMillis());
        minuteCount.incrementAndGet();
        return task.get();
    }

    public static JSONArray searchWebPublic(AppSettings settings, String query) throws Exception {
        return searchWeb(settings, query, null);
    }

    public static JSONArray searchWebPublic(AppSettings settings, String query, String freshness) throws Exception {
        return searchWeb(settings, query, freshness);
    }

    private static JSONArray searchWeb(AppSettings settings, String query, String freshness) throws Exception {
        if (!isBlank(settings.webSearchApiUrl)) {
            LAST_SEARCH_SOURCE = "custom";
            return searchViaApi(settings, query);
        }

        // 只有配了用户 key 或密钥池非空才走搜索 API，否则直接降级 Bing RSS。
        if (!isBlank(settings.tokeniqApiKey) || searchPoolSize() > 0) {
            try {
                JSONArray r = searchViaTokentiq(settings, query, freshness);
                LAST_SEARCH_SOURCE = "tokeniq";
                return r;
            } catch (Exception e) {
                android.util.Log.w("VC_SEARCH", "tokeniq 搜索失败，降级 Bing RSS: " + e.getMessage());
                LAST_SEARCH_SOURCE = "bing(fallback): " + e.getMessage();
            }
        } else {
            LAST_SEARCH_SOURCE = "bing";
        }
        return searchViaBingRss(settings, query);
    }

    public static volatile String LAST_SEARCH_SOURCE = "";

    private static JSONArray searchViaTokentiq(AppSettings settings, String query, String freshness) throws Exception {

        PoolKey picked = nextPoolKey();
        String base;
        String key;
        if (picked != null) {
            base = picked.baseUrl;
            key = picked.apiKey;
            LAST_SEARCH_SOURCE = "pool(" + poolReadyCount() + "/" + searchPool.size() + " 可用)";
        } else {
            base = isBlank(settings.tokeniqBaseUrl) ? "" : settings.tokeniqBaseUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            key = settings.tokeniqApiKey;
        }
        String endpoint = base + "/v1/search";
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("count", settings.tokeniqSearchCount > 0 ? settings.tokeniqSearchCount : 8);

        String fresh = normalizeFreshness(
                !isBlank(freshness) ? freshness.trim() : (isBlank(settings.tokeniqFreshness) ? "" : settings.tokeniqFreshness.trim()));
        if (!fresh.isEmpty()) {
            body.put("freshness", fresh);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings.readTimeout > 0 ? settings.readTimeout : 60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + (key == null ? "" : key.trim()));
            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray results = root.optJSONArray("results");
            JSONArray out = new JSONArray();
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.optJSONObject(i);
                    if (r == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("title", r.optString("title", ""));
                    item.put("url", r.optString("url", ""));
                    item.put("snippet", r.optString("snippet", ""));

                    item.put("date", firstNonBlank(
                            r.optString("date", ""), r.optString("datePublished", ""),
                            r.optString("published_at", ""), r.optString("publishedTime", ""),
                            r.optString("publish_date", ""), r.optString("dateLastCrawled", "")));
                    out.put(item);
                }
            }
            if (out.length() == 0) throw new RuntimeException("搜索无结果");
            return out;
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray searchViaBingRss(AppSettings settings, String query) throws Exception {

        String url = "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                + "&format=rss&setlang=zh-CN&cc=CN&count=12";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings.readTimeout > 0 ? settings.readTimeout : 30000);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String xml = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(xml));
            }
            return parseBingRss(xml, 8);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray searchViaApi(AppSettings settings, String query) throws Exception {
        String base = settings.webSearchApiUrl.trim();
        String endpoint = base.endsWith("/chat/completions") ? base
                : (base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions");
        JSONObject body = new JSONObject();
        body.put("model", "search");
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", query));
        body.put("messages", msgs);
        body.put("stream", false);
        body.put("max_tokens", 110000);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings.readTimeout > 0 ? settings.readTimeout : 60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + chatKey(settings).trim());
            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice = choices.optJSONObject(0);
                if (choice != null) {

                    JSONObject msgObj = choice.optJSONObject("message");
                    String snippet = msgObj == null ? "" : safeOptString(msgObj, "content");
                    if (snippet.isEmpty()) snippet = safeOptString(choice, "content");
                    if (snippet.isEmpty()) snippet = responseText;
                    JSONArray arr = new JSONArray();
                    arr.put(new JSONObject().put("title", "搜索结果").put("url", "")
                            .put("snippet", snippet));
                    return arr;
                }
            }
            throw new RuntimeException("搜索 API 未返回内容");
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray parseBingRss(String xml, int max) {
        JSONArray results = new JSONArray();
        try {
            int idx = 0;
            int guard = 0;
            while (results.length() < max && guard++ < 60) {
                int start = xml.indexOf("<item>", idx);
                if (start < 0) break;
                int end = xml.indexOf("</item>", start);
                if (end < 0) break;
                String item = xml.substring(start + 6, end);
                idx = end + 7;
                String title = between(item, "<title>", "</title>");
                String link = between(item, "<link>", "</link>");
                String desc = between(item, "<description>", "</description>");
                String pub = between(item, "<pubDate>", "</pubDate>");
                if (link != null && !link.isEmpty()) {
                    String snippet = desc == null ? "" : stripXmlTags(desc);
                    results.put(new JSONObject().put("title", title == null ? "" : title)
                            .put("url", link.trim()).put("snippet", snippet)
                            .put("date", normalizeRssDate(pub)));
                }
            }
        } catch (Exception ignored) {}
        return results;
    }

    private static String normalizeRssDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String s = raw.trim();
        try {
            java.text.SimpleDateFormat in =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            java.util.Date d = in.parse(s);
            if (d != null) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d);
            }
        } catch (Exception ignored) {}
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }
        return "";
    }

    private static String between(String text, String open, String close) {
        int s = text.indexOf(open);
        if (s < 0) return null;
        s += open.length();
        int e = text.indexOf(close, s);
        if (e < 0) return null;
        return text.substring(s, e).trim();
    }

    private static String stripXmlTags(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    public static String fetchUrlText(AppSettings settings, String url) throws Exception {
        return fetchUrlText(settings, url, 0, 0);
    }

    public static final int FETCH_ONCE_CHARS = 6000;

    public static final int FETCH_MAX_CHARS = 20000;

    public static final int TOOLS_DETAIL_CAP = 120000;

    public static final int TOOLS_DETAIL_TOKEN_CAP = 150000;

    public static String fetchUrlText(AppSettings settings, String url, int offset, int maxChars) throws Exception {
        String full = fetchUrlRaw(settings, url);
        int once = maxChars > 0 ? Math.min(FETCH_MAX_CHARS, maxChars) : FETCH_ONCE_CHARS;
        int total = full.length();

        if (offset >= total) {
            return "(offset=" + offset + " is past the end of the text (" + total + " characters in total); there is nothing to return."
                    + " Valid offsets are 0~" + Math.max(0, total - 1)
                    + "; to fetch from the beginning, set offset to 0.)";
        }
        int start = Math.max(0, offset);
        int end = Math.min(total, start + once);
        StringBuilder sb = new StringBuilder();
        sb.append("[Fetched characters ").append(start + 1).append("~").append(end).append(" of ")
          .append(total).append(" total]\n");
        sb.append(full, start, end);
        if (end < total) {
            sb.append("\n\n(").append(total - end).append(" characters not yet fetched. To continue, call fetch_url again ")
              .append("with the same url and offset=").append(end).append("; to jump straight to a certain part, estimate an offset by character count.)");
        } else {
            sb.append("\n\n(End of text reached.)");
        }
        return sb.toString();
    }

    private static String fetchUrlRaw(AppSettings settings, String url) throws Exception {
        if (settings != null && !isBlank(settings.tokeniqApiKey)) {
            try {
                String t = fetchViaTokentiq(settings, url);
                LAST_FETCH_SOURCE = "tokeniq";
                return t;
            } catch (Exception e) {
                android.util.Log.w("VC_SEARCH", "tokeniq 抓网页失败，降级自解析: " + e.getMessage());
                LAST_FETCH_SOURCE = "self(fallback): " + e.getMessage();
            }
        } else {
            LAST_FETCH_SOURCE = "self";
        }
        return fetchUrlText(settings, url, 0);
    }

    public static volatile String LAST_FETCH_SOURCE = "";

    public static String normalizeFreshness(String fresh) {
        if (fresh == null) return "";
        String f = fresh.trim();
        if (f.isEmpty()) return "";
        String low = f.toLowerCase(java.util.Locale.ROOT);
        if ("nolimit".equals(low) || "none".equals(low) || "all".equals(low) || "不限".equals(f)) return "";

        if (f.matches("^[dmyDMy]\\d{1,2}$")) return low;

        if ("oneday".equals(low) || "1d".equals(low) || "day".equals(low)) return "d1";
        if ("oneweek".equals(low) || "1w".equals(low) || "week".equals(low)) return "d7";
        if ("onemonth".equals(low) || "1m".equals(low) || "month".equals(low)) return "m1";
        if ("oneyear".equals(low) || "1y".equals(low) || "year".equals(low)) return "y1";

        if (f.contains("今天") || f.contains("24小时") || f.contains("一天")) return "d1";
        if (f.contains("本周") || f.contains("七天") || f.contains("一周")) return "d7";
        if (f.contains("本月") || f.contains("一个月")) return "m1";
        if (f.contains("今年") || f.contains("一年")) return "y1";

        return "";
    }

    private static String fetchViaTokentiq(AppSettings settings, String url) throws Exception {
        if (isBlank(url)) throw new RuntimeException("url 不能为空");
        String base = isBlank(settings.tokeniqBaseUrl) ? "" : settings.tokeniqBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        JSONObject body = new JSONObject();
        body.put("url", url.trim());
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/v1/webfetch").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings.readTimeout > 0 ? settings.readTimeout : 90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + settings.tokeniqApiKey.trim());
            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            String content = root.optString("content", "").trim();
            if (content.isEmpty()) throw new RuntimeException("抓取无正文");
            String title = root.optString("title", "").trim();
            String header = title.isEmpty() ? "" : "【" + title + "】\n";
            String full = header + content;

            return full;
        } finally {
            connection.disconnect();
        }
    }

    private static String fetchUrlText(AppSettings settings, String url, final int depth) throws Exception {
        if (isBlank(url)) throw new RuntimeException("url 不能为空");
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://" + target;
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(settings.connectTimeout > 0 ? settings.connectTimeout : 20000);
            connection.setReadTimeout(settings.readTimeout > 0 ? settings.readTimeout : 30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = connection.getResponseCode();
            if ((code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) && depth < 4) {
                String loc = connection.getHeaderField("Location");
                if (loc != null && !loc.isEmpty()) {
                    connection.disconnect();

                    String next = loc.startsWith("http") ? loc
                            : new java.net.URL(new java.net.URL(target), loc).toString();
                    return fetchUrlText(settings, next, depth + 1);
                }
            }
            if (code >= 400) {
                InputStream err = connection.getErrorStream();
                throw new RuntimeException("HTTP " + code + " " + (err == null ? "" : safeSnippet(readAll(err))));
            }
            InputStream input = connection.getInputStream();
            byte[] raw = new byte[1024 * 1024];
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int n;
            int total = 0;
            while ((n = input.read(raw)) != -1 && total < 4 * 1024 * 1024) {
                buffer.write(raw, 0, n);
                total += n;
            }
            String charset = "UTF-8";
            String contentType = connection.getHeaderField("Content-Type");
            if (contentType != null) {
                int ci = contentType.toLowerCase().indexOf("charset=");
                if (ci >= 0) {
                    String cs = contentType.substring(ci + 8).trim().split(";")[0].trim();
                    if (!cs.isEmpty() && cs.length() < 32) charset = cs;
                }
            }
            String html;
            try {
                html = new String(buffer.toByteArray(), charset);
            } catch (Exception e) {
                html = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            StringBuilder text = new StringBuilder();
            java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile(
                    "(?is)<(h[1-3]|p|li|blockquote)[^>]*>(.*?)</\\1>");
            java.util.regex.Matcher m = tagPattern.matcher(html);
            while (m.find()) {
                String piece = stripXmlTags(m.group(2));
                if (piece.length() < 2) continue;
                String tag = m.group(1).toLowerCase();
                if (tag.startsWith("h")) text.append("【").append(piece).append("】\n");
                else text.append(piece).append("\n");

                if (text.length() > FETCH_ONCE_CHARS * 4) break;
            }
            String result = text.toString().trim();
            if (result.isEmpty()) {
                String plain = stripXmlTags(html);
                result = plain.length() > FETCH_ONCE_CHARS * 4 ? plain.substring(0, FETCH_ONCE_CHARS * 4) : plain;
            }
            if (result.isEmpty()) throw new RuntimeException("页面没有可读文本");
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static String argSummary(String name, JSONObject args) {
        try {
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> keys = args.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = args.optString(k, "");

                if (k.startsWith("__")) continue;
                if (v.length() > 40) v = v.substring(0, 40) + "…";
                if (sb.length() > 0) sb.append(", ");
                sb.append(k).append("=").append(v);
            }
            return sb.length() == 0 ? "" : sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String resultSummary(String name, String resultJson) {
        if (resultJson == null) return "";
        try {
            JSONObject obj = new JSONObject(resultJson);
            if ("search_sessions".equals(name)) {
                JSONArray arr = obj.optJSONArray("results");
                return "检索到 " + (arr == null ? 0 : arr.length()) + " 条历史";
            }
            if ("web_search".equals(name)) {
                JSONArray arr = obj.optJSONArray("results");
                return "搜到 " + (arr == null ? 0 : arr.length()) + " 条网页结果";
            }
            if ("fetch_url".equals(name)) {
                String t = obj.optString("text", "");
                return "抓取 " + t.length() + " 字";
            }

            if ("search_user_archive".equals(name)) {
                String list = obj.optString("list", "");
                int n = 0;
                for (int i = 0; i < list.length(); i++) if (list.charAt(i) == '\n') ++n;
                return "查到 " + Math.max(0, n - 2) + " 行需求清单";
            }
            if ("fetch_user_archive".equals(name)) {
                String c = obj.optString("content", "");
                return "取回 " + c.length() + " 字历史问答";
            }

            if ("confirm_convergence".equals(name)) {
                return obj.optBoolean("success", false) ? "已确认收敛" : ("确认被拒：" + obj.optString("error", ""));
            }
            if ("pass_round".equals(name)) {

                return obj.optBoolean("success", true) ? "本轮不发言" : ("未记为沉默：" + obj.optString("error", ""));
            }
            if (obj.has("success")) {
                return obj.optBoolean("success", false) ? obj.optString("message", "成功")
                        : "失败：" + obj.optString("error", "");
            }

            if (obj.has("ok") && !obj.optBoolean("ok", true)) {
                String err = obj.optString("error", "");
                return err.isEmpty() ? "失败" : "失败：" + err;
            }
            String s = resultJson.length() > 80 ? resultJson.substring(0, 80) : resultJson;
            return s;
        } catch (Exception e) {
            String s = resultJson.length() > 80 ? resultJson.substring(0, 80) : resultJson;
            return s;
        }
    }

    private static String chatBase(AppSettings s) { return pick(s.chatBaseUrl, s.baseUrl); }
    private static String chatKey(AppSettings s) { return pick(s.chatApiKey, s.apiKey); }
    private static String chatModel(AppSettings s) { return pick(s.chatModel, s.model); }

    static int thinkingLevelOf(AppSettings s) {
        if (s == null) return ReasoningNegotiator.LEVEL_OFF;
        if (s.thinkLevel >= 0) return ReasoningNegotiator.clampLevel(s.thinkLevel);
        if (!s.enableThinking) return ReasoningNegotiator.LEVEL_OFF;
        return s.enableDeepThinking ? ReasoningNegotiator.LEVEL_HIGH : ReasoningNegotiator.LEVEL_MEDIUM;
    }

    static void probeReasoningAsync(String baseUrl, String apiKey, String model, int level) {
        probeReasoningAsync(baseUrl, apiKey, model, level, false);
    }

    static void probeReasoningAsync(String baseUrl, String apiKey, String model, int level,
                                    boolean suspectCachedStyle) {
        try {
            ReasoningNegotiator.probeReasoningAsync(baseUrl, apiKey, model, level, suspectCachedStyle);
        } catch (Exception ignored) { }
    }
    private static String asrBase(AppSettings s) { return pick(s.asrBaseUrl, s.baseUrl); }
    private static String asrKey(AppSettings s) { return pick(s.asrApiKey, s.apiKey); }
    private static String ttsBase(AppSettings s) { return pick(s.ttsBaseUrl, s.baseUrl); }
    private static String ttsKey(AppSettings s) { return pick(s.ttsApiKey, s.apiKey); }
    private static String endpointFor(String base, String suffix) {

        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/v1") || b.endsWith("/compatible-mode/v1")) {
            return b + suffix;
        }
        return b + "/v1" + suffix;
    }

    public static ChatResult chatDirect(String baseUrl, String apiKey, String model,
                                        String systemPrompt, List<AppDatabase.MessageItem> history,
                                        String userText, JSONArray tools, int maxIterations) throws Exception {
        return chatDirect(baseUrl, apiKey, model, systemPrompt, history, userText, tools, maxIterations, null);
    }

    public static ChatResult chatDirect(String baseUrl, String apiKey, String model,
                                        String systemPrompt, List<AppDatabase.MessageItem> history,
                                        String userText, JSONArray tools, int maxIterations,
                                        ToolHandler handler) throws Exception {
        return chatDirect(baseUrl, apiKey, model, systemPrompt, history, userText,
                tools, maxIterations, handler, -1);
    }

    public static ChatResult chatDirect(String baseUrl, String apiKey, String model,
                                        String systemPrompt, List<AppDatabase.MessageItem> history,
                                        String userText, JSONArray tools, int maxIterations,
                                        ToolHandler handler, int thinkLevel) throws Exception {

        AppSettings s = new AppSettings();
        s.mode = "openai";
        s.baseUrl = isBlank(baseUrl) ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : baseUrl.trim();
        s.model = isBlank(model) ? "deepseek-v4-flash-0731" : model.trim();
        s.apiKey = isBlank(apiKey) ? "" : apiKey.trim();
        s.chatBaseUrl = s.baseUrl;
        s.chatModel = s.model;
        s.chatApiKey = s.apiKey;
        s.thinkLevel = thinkLevel;
        s.maxTokens = "4000";
        s.temperature = "0.6";
        s.connectTimeout = 60000;
        s.readTimeout = 300000;
        return chatWithToolsStream(s, systemPrompt, history, userText,
                tools, handler, Math.max(2, maxIterations), null);
    }

    public static String simpleChat(String baseUrl, String apiKey, String bodyJson) throws Exception {
        String base = isBlank(baseUrl) ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : baseUrl.trim();
        String endpoint = endpointFor(base, "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + (isBlank(apiKey) ? "" : apiKey.trim()));
            OutputStream out = conn.getOutputStream();
            out.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            out.flush(); out.close();
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new RuntimeException("HTTP " + code + " " + safeSnippet(text));
            JSONObject root = new JSONObject(text);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            return msg == null ? null : safeOptString(msg, "content");
        } finally {
            conn.disconnect();
        }
    }

    public static String defaultBaseUrl(String mode) {
        if ("dashscope".equals(mode)) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if ("ark".equals(mode)) {
            return "https://ark.cn-beijing.volces.com/api/v3";
        }
        if ("openai".equals(mode)) {
            return "https://api.openai.com/v1";
        }
        return "";
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }
        }
        return builder.toString();
    }

    private static String safeSnippet(String text) {
        if (text == null) return "";
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    public static int parseCooldownSeconds(String errorText) {
        if (errorText == null || errorText.isEmpty()) return 0;
        try {

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:约|大约)?\\s*(\\d{1,5})\\s*秒").matcher(errorText);
            if (m.find()) {
                int s = Integer.parseInt(m.group(1));
                if (s > 0) return Math.min(s, 3600);
            }

            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("(?:retry[_-]?after\"?\\s*[:=]?\\s*|冷却时间[:：]?\\s*)(\\d{1,5})",
                            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(errorText);
            if (m2.find()) {
                int s = Integer.parseInt(m2.group(1));
                if (s > 0) return Math.min(s, 3600);
            }
        } catch (Exception ignored) { }
        return 0;
    }

    public static boolean isRateLimited(int code, String errorText) {
        if (code == 429) return true;
        if (code != 503 && code != 502 && code != 500) return false;
        String t = errorText == null ? "" : errorText;
        return t.contains("冷却") || t.contains("限流") || t.contains("rate") || t.contains("Rate")
                || t.contains("overload") || t.contains("Overload") || t.contains("too many")
                || t.contains("Too Many") || t.contains("capacity") || t.contains("busy");
    }

    public static boolean isToolsRejection(int code, String errorText) {
        String t = errorText == null ? "" : errorText.toLowerCase();
        boolean mentionsTools = t.contains("tool") || t.contains("function")
                || t.contains("工具") || t.contains("functions");
        if (!mentionsTools) return false;
        if (code == 400 || code == 422) return true;
        return t.contains("not support") || t.contains("unsupported") || t.contains("不支持")
                || t.contains("unknown") || t.contains("unknown field")
                || t.contains("invalid") || t.contains("参数");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value == null ? "" : value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeOptString(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        Object v = obj.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    private static String toolErrorJson(String message) {
        try {
            return new JSONObject().put("error", message == null ? "未知错误" : message).toString();
        } catch (Exception e) {
            return "{\"error\":\"工具执行失败\"}";
        }
    }

    private static final String ARGS_BROKEN_KEY = "__args_parse_error__";

    private static JSONObject parseToolArgs(String argStr) {
        String s = argStr == null ? "" : argStr.trim();
        if (s.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try { o.put(ARGS_BROKEN_KEY, s); } catch (Exception ignored) { }
            return o;
        }
    }

    private static String argsBrokenJson(JSONObject args) {
        String raw = args == null ? "" : args.optString(ARGS_BROKEN_KEY, "");
        if (raw.length() > 500) raw = raw.substring(0, 500) + "…（已截断）";
        try {
            return new JSONObject().put("error",
                    "工具参数不是合法 JSON，这次调用**没有执行**。请用完整合法的 JSON 重新发起调用。"
                            + "收到的原始参数分片：" + raw).toString();
        } catch (Exception e) {
            return "{\"error\":\"工具参数不是合法 JSON，这次调用没有执行，请重新发起\"}";
        }
    }

    private static String callTool(ToolHandler handler, String name, JSONObject args) {
        if (args != null && args.has(ARGS_BROKEN_KEY)) return argsBrokenJson(args);
        try {
            return handler.handle(name, args);
        } catch (Exception e) {
            return toolErrorJson(e.getMessage());
        }
    }

    public static String stripMarkersPublic(String content) {
        return stripMarkers(content);
    }

    static String stripFinalMarkers(String c) {
        if (c == null || c.indexOf("@@FINAL") < 0 && c.indexOf("@@CONFIRM") < 0
                && c.indexOf("@@ERR") < 0) return c;

        return c.replaceAll("@@FINALV?:\\d*", "")
                .replaceAll("@@CONFIRM:?\\d*", "")
                .replaceAll("@@ERR:[^\\n]*", "");
    }

    public static String stripMarkersPublicKeepSections(String content) {
        if (content == null) return "";

        String c = stripFinalMarkers(hasThinkingTags(content) ? stripThinkingTags(content) : content);
        return c == null ? "" : c;
    }

    private static void appendHistoryMessage(JSONArray messages, AppDatabase.MessageItem item) throws Exception {
        String[] parts = splitSpeechAndTools(item.content);
        String speech = parts[0], tools = parts[1];

        if (!speech.isEmpty() && findFabricatedToolLine(speech) != null) {
            speech = stripFabricatedToolLines(speech);
        }
        if (!speech.isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "user".equals(item.role) ? "user" : "assistant")
                    .put("content", speech));
        }
        if (!tools.isEmpty()) {
            messages.put(new JSONObject().put("role", "user")
                    .put("content", buildToolRecordMessage(tools)));
        }
    }

    /** 估算"真正会发送的历史"token：与请求走同一条转换路径（发言/工具记录拆分 + 工具回放包装）。
     *  上下文用量弹窗用它，避免"全部消息"与"实发子集"两个口径对不上。 */
    public static int estimateSentHistoryTokens(List<AppDatabase.MessageItem> history) {
        if (history == null || history.isEmpty()) return 0;
        JSONArray arr = new JSONArray();
        try {
            for (AppDatabase.MessageItem item : history) appendHistoryMessage(arr, item);
        } catch (Exception e) {
            return 0;
        }
        return estimateTokensText(arr.toString());
    }

    public static String[] splitSpeechAndTools(String content) {
        String[] out = new String[]{"", ""};
        if (content == null || content.isEmpty()) return out;
        String c = stripFinalMarkers(content);
        if (c.contains("@@MEMORY:")) {
            StringBuilder nb = new StringBuilder();
            for (String ln : c.split("\n", -1)) {
                if (ln.trim().startsWith("@@MEMORY:")) continue;
                if (nb.length() > 0) nb.append("\n");
                nb.append(ln);
            }
            c = nb.toString();
        }
        int mui = c.indexOf("@@MEMUSE:");
        if (mui >= 0) {
            int lineEnd = c.indexOf('\n', mui);
            if (lineEnd >= 0) {
                int idsEnd = c.indexOf('\n', lineEnd + 1);
                c = c.substring(0, mui) + (idsEnd >= 0 ? c.substring(idsEnd) : "");
            } else {
                c = c.substring(0, mui);
            }
        }
        int ti = c.indexOf("@@THINKING:");
        int oi = c.indexOf("@@TOOLS:");
        int firstMarker = -1;
        if (ti >= 0 && oi >= 0) firstMarker = Math.min(ti, oi);
        else if (ti >= 0) firstMarker = ti;
        else if (oi >= 0) firstMarker = oi;
        out[0] = (firstMarker >= 0 ? c.substring(0, firstMarker) : c).trim();
        if (oi >= 0) {
            String tools = c.substring(oi + "@@TOOLS:".length()).trim();
            int lateTi = tools.indexOf("@@THINKING:");
            if (lateTi >= 0) {
                int after = tools.indexOf("@@", lateTi + "@@THINKING:".length());
                tools = (tools.substring(0, lateTi) + (after >= 0 ? tools.substring(after) : "")).trim();
            }
            out[1] = tools;
        }
        return out;
    }

    private static String buildToolRecordMessage(String tools) {
        return "[System log · past tool calls] Below are the web/browser actions you **actually performed** earlier, "
                + "as logged by the system (this is the system's own bookkeeping, not something you wrote; it exists so you don't repeat actions):\n"
                + tools + "\n"
                + "⚠️ Note: these are **system logs**. Whenever you need to act now, "
                + "**you must issue a real tool call** — never write out log lines like these as a substitute for actually performing the action.";
    }

    public static String renderChatHistory(String content) {
        if (content == null) return "";
        String c = content;

        c = stripFinalMarkers(c);

        if (c.contains("@@MEMORY:")) {
            StringBuilder noMem = new StringBuilder();
            for (String ln : c.split("\n", -1)) {
                if (ln.trim().startsWith("@@MEMORY:")) continue;
                if (noMem.length() > 0) noMem.append("\n");
                noMem.append(ln);
            }
            c = noMem.toString();
        }

        int mui = c.indexOf("@@MEMUSE:");
        if (mui >= 0) {
            int lineEnd = c.indexOf('\n', mui);
            if (lineEnd >= 0) {
                int idsEnd = c.indexOf('\n', lineEnd + 1);
                c = c.substring(0, mui) + (idsEnd >= 0 ? c.substring(idsEnd) : "");
            } else {
                c = c.substring(0, mui);
            }
        }

        int ti = c.indexOf("@@THINKING:");
        int oi = c.indexOf("@@TOOLS:");

        int firstMarker = -1;
        if (ti >= 0 && oi >= 0) firstMarker = Math.min(ti, oi);
        else if (ti >= 0) firstMarker = ti;
        else if (oi >= 0) firstMarker = oi;
        String speech = (firstMarker >= 0 ? c.substring(0, firstMarker) : c).trim();

        if (oi < 0 && findFabricatedToolLine(speech) != null) {
            android.util.Log.w("VC_AGENT", "历史消息里发现伪造的工具记录行 → 不再注入");
            speech = stripFabricatedToolLines(speech);
        }

        String tools = "";
        if (oi >= 0) {
            tools = c.substring(oi + "@@TOOLS:".length()).trim();

            int lateTi = tools.indexOf("@@THINKING:");
            if (lateTi >= 0) {
                int after = tools.indexOf("@@", lateTi + "@@THINKING:".length());
                tools = (tools.substring(0, lateTi) + (after >= 0 ? tools.substring(after) : "")).trim();
            }

        }

        StringBuilder out = new StringBuilder();
        if (!speech.isEmpty()) out.append(speech);
        if (!tools.isEmpty()) {
            if (out.length() > 0) out.append("\n");
            out.append("〔我当时查到的资料〕\n").append(tools);
        }
        String r = out.toString();
        while (r.contains("\n\n\n")) r = r.replace("\n\n\n", "\n\n");
        return r.trim();
    }

    public static String renderSelfHistory(String content) {
        if (content == null) return "";
        String c = content;

        c = stripFinalMarkers(c);

        int mui = c.indexOf("@@MEMUSE:");
        if (mui >= 0) {
            int lineEnd = c.indexOf('\n', mui);
            if (lineEnd >= 0) {
                int idsEnd = c.indexOf('\n', lineEnd + 1);
                c = c.substring(0, mui) + (idsEnd >= 0 ? c.substring(idsEnd) : "");
            } else {
                c = c.substring(0, mui);
            }
        }

        int ti = c.indexOf("@@THINKING:");
        int oi = c.indexOf("@@TOOLS:");

        int firstMarker = -1;
        if (ti >= 0 && oi >= 0) firstMarker = Math.min(ti, oi);
        else if (ti >= 0) firstMarker = ti;
        else if (oi >= 0) firstMarker = oi;
        String speech = (firstMarker >= 0 ? c.substring(0, firstMarker) : c).trim();

        String tools = "";
        if (oi >= 0) {
            tools = c.substring(oi + "@@TOOLS:".length()).trim();

            int lateTi = tools.indexOf("@@THINKING:");
            if (lateTi >= 0) {
                int after = tools.indexOf("@@", lateTi + "@@THINKING:".length());
                tools = (tools.substring(0, lateTi) + (after >= 0 ? tools.substring(after) : "")).trim();
            }
        }

        StringBuilder out = new StringBuilder();
        if (!speech.isEmpty()) out.append(stripNativeToolSyntax(speech));
        if (!tools.isEmpty()) {
            if (out.length() > 0) out.append("\n");
            out.append("〔我当时调用的工具与查到的资料〕\n").append(tools);
        }
        String r = out.toString();
        while (r.contains("\n\n\n")) r = r.replace("\n\n\n", "\n\n");
        return r.trim();
    }

    private static String stripMarkers(String content) {
        if (content == null) return "";

        String c = hasThinkingTags(content) ? stripThinkingTags(content) : content;

        int ti = c.indexOf("@@THINKING:");
        if (ti >= 0) c = c.substring(0, ti);

        int toi = c.indexOf("@@TOOLS:");
        if (toi >= 0) c = c.substring(0, toi);

        int mui = c.indexOf("@@MEMUSE:");
        if (mui >= 0) {
            int lineEnd = c.indexOf('\n', mui);
            if (lineEnd >= 0) {
                int idsEnd = c.indexOf('\n', lineEnd + 1);
                c = c.substring(0, mui) + (idsEnd >= 0 ? c.substring(idsEnd) : "");
            } else {
                c = c.substring(0, mui);
            }
        }

        int ii = c.indexOf("@@IMG:");
        if (ii >= 0) {
            String before = c.substring(0, ii);
            String after = c.substring(ii);
            int nl = after.indexOf("\n");
            c = (nl >= 0 ? before + after.substring(nl) : before);
        }

        if (c.contains("@@IMGMODEL:")) {
            StringBuilder noim = new StringBuilder();
            for (String ln : c.split("\n", -1)) {
                if (ln.trim().startsWith("@@IMGMODEL:")) continue;
                if (noim.length() > 0) noim.append("\n");
                noim.append(ln);
            }
            c = noim.toString();
        }

        int di = c.indexOf("@@DUR:");
        if (di >= 0) {
            String before = c.substring(0, di);
            String after = c.substring(di);
            int nl = after.indexOf("\n");
            c = (nl >= 0 ? before + after.substring(nl) : before);
        }

        int upi = c.indexOf("@@UPTO:");
        if (upi >= 0) {
            String before = c.substring(0, upi);
            String after = c.substring(upi);
            int nl = after.indexOf("\n");
            c = (nl >= 0 ? before + after.substring(nl) : before);
        }

        int erri = c.indexOf("@@ERR:");
        if (erri >= 0) {
            String before = c.substring(0, erri);
            String after = c.substring(erri);
            int nl = after.indexOf("\n");
            c = (nl >= 0 ? before + after.substring(nl) : before);
        }

        int doci = c.indexOf("@@DOC:");
        if (doci >= 0) {
            String before = c.substring(0, doci);
            String after = c.substring(doci + "@@DOC:".length());
            int nl = after.indexOf("\n");
            String docName = (nl >= 0 ? after.substring(0, nl) : after).trim();
            String docBody = (nl >= 0 ? after.substring(nl + 1) : "").trim();
            docBody = docBody.replace("（文档过长已截断）", "").trim();
            if (!docBody.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (!before.trim().isEmpty()) sb.append(before.trim()).append("\n");
                sb.append("【用户随消息发来的文档：").append(docName.isEmpty() ? "文档" : docName).append("】\n");
                sb.append(docBody);
                c = sb.toString();
            } else {
                c = before;
            }
        }

        c = stripFinalMarkers(c);

        if (c.contains("@@MEMORY:")) {
            StringBuilder noMem = new StringBuilder();
            for (String ln : c.split("\n", -1)) {
                if (ln.trim().startsWith("@@MEMORY:")) continue;
                if (noMem.length() > 0) noMem.append("\n");
                noMem.append(ln);
            }
            c = noMem.toString();
        }

        c = stripNativeToolSyntax(c);
        return c.trim();
    }

    private static String stripThinkingTags(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;

        out = out.replaceAll("(?is)<(?:think|thinking|reasoning|analysis|thought|scratchpad)\\b[^>]*>.*?</(?:think|thinking|reasoning|analysis|thought|scratchpad)>", "");

        out = out.replaceAll("(?i)</(?:think|thinking|reasoning|analysis|thought|scratchpad)\\s*>", "");

        out = out.replaceAll("(?i)<(?:think|thinking|reasoning|analysis|thought|scratchpad)\\b[^>]*>", "");

        while (out.contains("\n\n\n")) out = out.replace("\n\n\n", "\n\n");
        return out;
    }

    public static boolean hasThinkingTags(String text) {
        if (text == null) return false;
        return text.matches("(?is).*</?(?:think|thinking|reasoning|analysis|thought|scratchpad)\\b[^>]*>.*");
    }
    private static String stripNativeToolSyntax(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;

        if (out.contains("DSML")) {
            out = out.replaceAll("(?s)<\uff5c\uff5cDSML\uff5c\uff5c.*?(?=\n<|$)", "");

            out = out.replaceAll("(?m)^.*\uff5c\uff5cDSML\uff5c\uff5c.*$", "");
        }

        if (out.contains("<tool_call")) {
            out = out.replaceAll("(?s)<tool_calls?>.*?</tool_calls?>", "");

            out = out.replaceAll("(?s)<tool_calls?>.*$", "");
        }

        while (out.contains("\n\n\n")) out = out.replace("\n\n\n", "\n\n");
        return out;
    }

    private static JSONObject multimodalUserMessage(String userText, String imageBase64Png) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        if (imageBase64Png == null || imageBase64Png.trim().isEmpty()) {
            msg.put("content", userText == null ? "" : userText);
            return msg;
        }
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("type", "text")
                .put("text", userText == null ? "" : userText));
        parts.put(new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject()
                        .put("url", "data:image/png;base64," + imageBase64Png.trim())));
        msg.put("content", parts);
        return msg;
    }

    public static final String KEY_VISION_IMAGE = "vision_image_path";

    public static String fileToBase64Jpeg(String path, int maxSide, int quality) {
        android.graphics.Bitmap bmp = null, scaled = null;
        try {
            android.graphics.BitmapFactory.Options probe = new android.graphics.BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= maxSide || probe.outHeight / (sample * 2) >= maxSide) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            bmp = android.graphics.BitmapFactory.decodeFile(path, opts);
            if (bmp == null) return null;
            scaled = bmp;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w > maxSide || h > maxSide) {
                float k = Math.min((float) maxSide / w, (float) maxSide / h);
                scaled = android.graphics.Bitmap.createScaledBitmap(bmp,
                        Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Throwable t) {
            android.util.Log.w("VC_IMG", "fileToBase64Jpeg 失败: " + t);
            return null;
        } finally {
            try { if (scaled != null && scaled != bmp) scaled.recycle(); } catch (Throwable ignored) { }
            try { if (bmp != null) bmp.recycle(); } catch (Throwable ignored) { }
        }
    }

    public static void injectToolImageIfAny(AppSettings settings, JSONArray messages, String toolResult) {

        if (toolResult == null || toolResult.isEmpty()) return;
        if (messages == null) return;
        String path = null;
        try {
            JSONObject o = new JSONObject(toolResult);
            path = o.optString(KEY_VISION_IMAGE, "");
        } catch (Throwable t) {
            android.util.Log.w("VC_IMG", "工具结果不是合法 JSON，无法取图: " + t.getMessage());
            return;
        }
        if (path == null || path.trim().isEmpty()) return;
        final String p = path.trim();
        android.util.Log.i("VC_IMG", "发现截图，准备送进模型: " + p);

        String b64 = fileToBase64Jpeg(p, 1024, 80);
        if (b64 == null) {
            android.util.Log.w("VC_IMG", "图片读取/编码失败，AI 看不到画面: " + p);
            try {
                messages.put(new JSONObject().put("role", "user")
                        .put("content", "(The screenshot was saved to " + p + ", but reading the image data failed — you cannot see the screen.)"));
            } catch (Throwable ignored) { }
            return;
        }

        if (settings != null && settings.visionUseMainModel) {

            try {
                messages.put(multimodalUserMessage(
                        "This is a screenshot just taken of the phone screen (a real capture). Base your answer/judgment on what you actually see; "
                                + "do not describe the screen contents from guesswork.", b64));
                android.util.Log.i("VC_IMG", "截图已原生送进主模型 b64len=" + b64.length());
                return;
            } catch (Throwable t) {
                android.util.Log.w("VC_IMG", "主模型附图失败: " + t);
            }
        }

        try {
            String desc = describeImage(settings, b64,
                    "This is a screenshot of a phone screen. Describe its contents in detail: what screen this is, what text appears, "
                            + "whether there are images and what they depict, and where the tappable buttons and input fields are. Answer in Chinese.");
            messages.put(new JSONObject().put("role", "user").put("content",
                    "(You cannot see the image; below is a vision model's description of this screen screenshot. Answer based on it, "
                            + "and do not invent anything the description does not contain:)\n" + (desc == null ? "(description failed)" : desc)));
            android.util.Log.i("VC_IMG", "截图走辅助视觉模型 descLen="
                    + (desc == null ? 0 : desc.length()));
        } catch (Throwable t) {
            android.util.Log.w("VC_IMG", "辅助视觉模型识图失败: " + t);
            try {
                messages.put(new JSONObject().put("role", "user")
                        .put("content", "(The screenshot was saved to " + p + ", but the vision model failed to describe it: " + t.getMessage() + ")"));
            } catch (Throwable ignored) { }
        }
    }

    public static boolean hasNativeToolSyntax(String text) {
        if (text == null) return false;
        return text.contains("DSML") || text.contains("<tool_call");
    }

    public static String describeImage(AppSettings settings, String base64Png, String userPrompt) throws Exception {
        if (settings == null || isBlank(settings.visionApiKey)) {
            throw new RuntimeException("没有配置图片识别 API Key，请在设置页填写");
        }
        String base = isBlank(settings.visionBaseUrl) ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : settings.visionBaseUrl.trim();
        String endpoint = endpointFor(base, "/chat/completions");

        String prompt = isBlank(userPrompt)
                ? "Describe the contents of this image in detail. Answer in Chinese."
                : userPrompt.trim();

        JSONArray contentArr = new JSONArray();
        contentArr.put(new JSONObject().put("type", "text").put("text", prompt));
        JSONObject imageUrl = new JSONObject().put("url", "data:image/png;base64," + base64Png);
        contentArr.put(new JSONObject().put("type", "image_url").put("image_url", imageUrl));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", contentArr));

        JSONObject body = new JSONObject();
        body.put("model", isBlank(settings.visionModel) ? "shijue" : settings.visionModel.trim());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("max_tokens", 1000);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings.connectTimeout > 0 ? settings.connectTimeout : 60000);
            connection.setReadTimeout(settings.readTimeout > 0 ? settings.readTimeout : 120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + settings.visionApiKey.trim());

            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new RuntimeException("图片识别接口没有返回 choices");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) {
                throw new RuntimeException("图片识别接口没有返回 message");
            }
            String text = safeOptString(message, "content").trim();
            if (text.isEmpty()) {
                throw new RuntimeException("图片识别接口返回了空内容");
            }
            return text;
        } finally {
            connection.disconnect();
        }
    }

    public interface ToolHandler {
        String handle(String name, JSONObject arguments) throws Exception;
    }

    public interface StreamListener {
        void onReasoningDelta(String delta);
        void onContentDelta(String delta);
        void onToolStart(String name, String argSummary);
        void onToolEnd(String name, String resultSummary);

        default void onRoundDiscarded(String reason) { }

        default void onContinue(String note) { }
    }

    public static class ToolsUnsupportedException extends Exception {
        public ToolsUnsupportedException(String message) { super(message); }
    }

    public static ChatResult chatWithToolsStream(AppSettings settings, String systemPrompt,
            List<AppDatabase.MessageItem> history, String userText,
            JSONArray tools, ToolHandler handler, int maxIterations, StreamListener listener) throws Exception {
        return chatWithToolsStream(settings, systemPrompt, history, userText,
                tools, handler, maxIterations, listener,
                (java.util.concurrent.atomic.AtomicBoolean) null, (String) null);
    }

    public static ChatResult chatWithToolsStream(AppSettings settings, String systemPrompt,
            List<AppDatabase.MessageItem> history, String userText,
            JSONArray tools, ToolHandler handler, int maxIterations, StreamListener listener,
            String imageBase64Png) throws Exception {
        return chatWithToolsStream(settings, systemPrompt, history, userText,
                tools, handler, maxIterations, listener,
                (java.util.concurrent.atomic.AtomicBoolean) null, imageBase64Png);
    }

    public static ChatResult chatWithToolsStream(AppSettings settings, String systemPrompt,
            List<AppDatabase.MessageItem> history, String userText,
            JSONArray tools, ToolHandler handler, int maxIterations, StreamListener listener,
            final java.util.concurrent.atomic.AtomicBoolean stopFlag) throws Exception {
        return chatWithToolsStream(settings, systemPrompt, history, userText, tools, handler,
                maxIterations, listener, stopFlag, (String) null);
    }

    public static ChatResult chatWithToolsStream(AppSettings settings, String systemPrompt,
            List<AppDatabase.MessageItem> history, String userText,
            JSONArray tools, ToolHandler handler, int maxIterations, StreamListener listener,
            final java.util.concurrent.atomic.AtomicBoolean stopFlag, String imageBase64Png) throws Exception {
        String base = chatBase(settings);
        String endpoint = endpointFor(base, "/chat/completions");

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        if (history != null) {
            for (AppDatabase.MessageItem item : history) {
                appendHistoryMessage(messages, item);
            }
        }
        messages.put(multimodalUserMessage(userText, imageBase64Png));

        int iterations = Math.max(2, maxIterations);

        final int maxContinues = 3;
        final int[] continueRounds = {0};
        final StringBuilder carry = new StringBuilder();
        final boolean[] completeConfirmed = {false};

        StringBuilder reasoningAll = new StringBuilder();

        StringBuilder contentAll = new StringBuilder();
        String lastReasoning = "";
        String toolLog = "";

        final int[] nudgedThisTurn = {0};

        ReasoningNegotiator.Style floatStyle = null;
        int floatAttempt = 0;

        int floatValueIndex = 0;

        int accPrompt = 0, accCompletion = 0;
        final int[] lastPromptTok = {0};
        boolean sawUsageAny = false;
        for (int iter = 0; iter < iterations; iter++) {

            if (stopFlag != null && stopFlag.get()) break;

            boolean sendTools = tools != null && tools.length() > 0;
            JSONObject body = new JSONObject();
            body.put("model", isBlank(chatModel(settings)) ? "qwen-plus" : chatModel(settings).trim());
            body.put("messages", messages);
            body.put("stream", true);
            body.put("max_tokens", parseInt(settings.maxTokens, 110000));
            body.put("temperature", parseDouble(settings.temperature, 0.6));
            body.put("top_p", parseDouble(settings.topP, 1.0));
            if (sendTools) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            boolean usageWanted = true;
            if (usageWanted) {
                try {
                    JSONObject streamOpts = new JSONObject();
                    streamOpts.put("include_usage", true);
                    body.put("stream_options", streamOpts);
                } catch (Exception ignored) { }
            }

            ReasoningNegotiator.Applied floatApplied = ReasoningNegotiator.applyReasoning(
                    body, chatBase(settings), chatModel(settings), thinkingLevelOf(settings), floatAttempt);
            floatStyle = floatApplied == null ? null : floatApplied.style;
            floatValueIndex = floatApplied == null ? 0 : floatApplied.valueIndex;

            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            registerActiveConn(stopFlag, connection);
            int code;
            StringBuilder contentBuf = new StringBuilder();
            StringBuilder reasoningBuf = new StringBuilder();

            java.util.TreeMap<Integer, JSONObject> toolAcc = new java.util.TreeMap<>();

            final String[] finishReason = {""};
            final boolean[] sawDone = {false};
            final boolean[] sawFinish = {false};

            final long sseStartAt = System.currentTimeMillis();
            final int[] cdCount = {0}, cdMaxLen = {0};
            final long[] cdFirstAt = {-1L}, cdLastAt = {-1L};
            final int[] rdCount = {0};
            final long[] rdFirstAt = {-1L};

            int reqPrompt = 0, reqCompletion = 0, reqTotal = 0;
            boolean sawUsage = false;
            android.util.Log.i("VC_SDIAG", "===== iter " + iter + " 请求开始 model=" + body.optString("model")
                    + " max_tokens=" + body.opt("max_tokens") + " tools=" + sendTools + " =====");
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(connTimeoutMs(settings, 20000));
                connection.setReadTimeout(readTimeoutMs(settings, 120000));
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setRequestProperty("Authorization", "Bearer " + chatKey(settings).trim());
                OutputStream output = connection.getOutputStream();
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();
                code = connection.getResponseCode();
                if (code >= 400) {
                    String err = readAll(connection.getErrorStream());

                    if (usageWanted && code == 400 && !isRateLimited(code, err)) {
                        android.util.Log.w("VC_USAGE", "上游拒绝 stream_options，摘掉后重发: " + safeSnippet(err));
                        usageWanted = false;
                        body.remove("stream_options");

                        HttpURLConnection retry = (HttpURLConnection) new URL(endpoint).openConnection();
                        registerActiveConn(stopFlag, retry);
                        retry.setRequestMethod("POST");
                        retry.setConnectTimeout(connTimeoutMs(settings, 20000));
                        retry.setReadTimeout(readTimeoutMs(settings, 120000));
                        retry.setDoOutput(true);
                        retry.setRequestProperty("Content-Type", "application/json");
                        retry.setRequestProperty("Accept", "text/event-stream");
                        retry.setRequestProperty("Authorization", "Bearer " + chatKey(settings).trim());
                        OutputStream ro = retry.getOutputStream();
                        ro.write(body.toString().getBytes(StandardCharsets.UTF_8));
                        ro.flush();
                        ro.close();
                        code = retry.getResponseCode();
                        if (code >= 400) {
                            String err2 = readAll(retry.getErrorStream());
                            clearActiveConn(stopFlag, retry);
                            retry.disconnect();
                            if (iter == 0 && sendTools && !isRateLimited(code, err2)) {
                                throw new ToolsUnsupportedException("HTTP " + code + " " + safeSnippet(err2));
                            }
                            throw new RuntimeException("HTTP " + code + " " + safeSnippet(err2));
                        }
                        clearActiveConn(stopFlag, connection);
                        connection.disconnect();
                        connection = retry;
                    } else

                    if (iter == 0 && sendTools && !isRateLimited(code, err)) {
                        throw new ToolsUnsupportedException("HTTP " + code + " " + safeSnippet(err));
                    } else {
                        throw new RuntimeException("HTTP " + code + " " + safeSnippet(err));
                    }
                }

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {

                    if (stopFlag != null && stopFlag.get()) break;
                    line = line.trim();
                    if (line.isEmpty() || !line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) { sawDone[0] = true; break; }
                    JSONObject chunk;
                    try { chunk = new JSONObject(payload); } catch (Exception e) { continue; }

                    JSONObject usageChunk = chunk.optJSONObject("usage");
                    if (usageChunk != null) {
                        int upt = usageChunk.optInt("prompt_tokens", 0);
                        int uct = usageChunk.optInt("completion_tokens", 0);
                        int utt = usageChunk.optInt("total_tokens", 0);
                        if (upt > 0) { reqPrompt = upt; lastPromptTok[0] = upt; }
                        if (uct > 0) reqCompletion = uct;
                        if (utt > 0) reqTotal = utt;
                        else if (upt > 0 || uct > 0) reqTotal = upt + uct;
                        if (upt > 0 || uct > 0 || utt > 0) sawUsage = true;
                    }
                    JSONArray chs = chunk.optJSONArray("choices");
                    if (chs == null || chs.length() == 0) continue;
                    String fr0 = chs.getJSONObject(0).optString("finish_reason", "");
                    if (!fr0.isEmpty() && !"null".equals(fr0)) {
                        finishReason[0] = fr0;
                        sawFinish[0] = true;
                    }
                    JSONObject delta = chs.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;

                    String rd = safeOptString(delta, "reasoning_content");
                    if (rd.isEmpty()) rd = safeOptString(delta, "reasoning");
                    if (!rd.isEmpty()) {
                        reasoningBuf.append(rd);
                        ++rdCount[0];
                        if (rdFirstAt[0] < 0) rdFirstAt[0] = System.currentTimeMillis();
                        if (listener != null) listener.onReasoningDelta(rd);
                    }

                    String cd = safeOptString(delta, "content");
                    if (!cd.isEmpty()) {
                        contentBuf.append(cd);

                        ++cdCount[0];
                        if (cd.length() > cdMaxLen[0]) cdMaxLen[0] = cd.length();
                        long nowMs = System.currentTimeMillis();
                        if (cdFirstAt[0] < 0) {
                            cdFirstAt[0] = nowMs;
                            android.util.Log.i("VC_SDIAG", "content 首增量 t+" + (nowMs - sseStartAt)
                                    + "ms 长度=" + cd.length() + " 内容=" + safeSnippet(cd));
                        }
                        cdLastAt[0] = nowMs;
                        if (listener != null) {
                            // 续写协议标记不能漏到界面上：模型可能回 <END 或 <END>，
                            // 两种形态都要剥（只 replace 完整形态会漏掉缺右括号的）。
                            String forUi = stripContinueMarkers(cd);
                            if (!forUi.isEmpty()) listener.onContentDelta(forUi);
                        }
                    }

                    JSONArray tcs = delta.optJSONArray("tool_calls");
                    if (tcs != null) {
                        for (int i = 0; i < tcs.length(); i++) {
                            JSONObject tc = tcs.getJSONObject(i);
                            int idx = tc.optInt("index", i);
                            JSONObject acc = toolAcc.get(idx);
                            if (acc == null) { acc = new JSONObject(); toolAcc.put(idx, acc); }
                            String id = safeOptString(tc, "id");
                            if (!id.isEmpty()) acc.put("id", id);
                            JSONObject fn = tc.optJSONObject("function");
                            if (fn != null) {
                                String nm = safeOptString(fn, "name");
                                if (!nm.isEmpty()) acc.put("name", nm);
                                String args = safeOptString(fn, "arguments");
                                if (!args.isEmpty()) {
                                    acc.put("args", (acc.optString("args", "")) + args);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (stopFlag != null && stopFlag.get()) {

                    int kept = contentBuf.length();
                    if (kept > 0) {
                        if (contentAll.length() > 0) contentAll.append("\n\n");
                        contentAll.append(contentBuf);
                    }
                    android.util.Log.i("VC_STREAM", "用户暂停 → 主动断开连接，保留已生成的 "
                            + kept + " 字");
                    break;
                }
                boolean stalledMidStream = contentBuf.length() > 0
                        && (e instanceof java.io.IOException);
                if (!stalledMidStream) throw e;
                android.util.Log.w("VC_STREAM", "流在中途断了（" + e.getClass().getSimpleName()
                        + ": " + safeSnippet(String.valueOf(e.getMessage())) + "），已收到 "
                        + contentBuf.length() + " 字 → 不当成失败，改判为截断");
            } finally {
                clearActiveConn(stopFlag, connection);
                connection.disconnect();
            }

            {
                long dur = System.currentTimeMillis() - sseStartAt;
                long cdSpan = (cdFirstAt[0] > 0 && cdLastAt[0] > 0) ? (cdLastAt[0] - cdFirstAt[0]) : 0;
                android.util.Log.i("VC_SDIAG", "iter " + iter + " 流结束 用时=" + dur + "ms"
                        + " | 思考增量=" + rdCount[0] + " 条"
                        + " | 正文增量=" + cdCount[0] + " 条"
                        + " 首条t+" + (cdFirstAt[0] < 0 ? "-" : (cdFirstAt[0] - sseStartAt) + "ms")
                        + " 末条t+" + (cdLastAt[0] < 0 ? "-" : (cdLastAt[0] - sseStartAt) + "ms")
                        + " 跨度=" + cdSpan + "ms"
                        + " 单条最大=" + cdMaxLen[0] + "字符"
                        + " 正文总长=" + contentBuf.length());
                if (cdCount[0] > 0 && cdCount[0] <= 3) {
                    android.util.Log.w("VC_SDIAG", "⚠️ 正文只收到 " + cdCount[0] + " 个增量 → "
                            + "上游没有分片下发（攒成一整块才发），这种情况客户端无法做流式");
                } else if (cdCount[0] > 0 && cdSpan < 300 && cdCount[0] > 10) {
                    android.util.Log.w("VC_SDIAG", "⚠️ 正文 " + cdCount[0] + " 个增量全挤在 " + cdSpan
                            + "ms 内到达 → 上游是「批量推」，不是真流式");
                }
            }

            accPrompt += reqPrompt;
            accCompletion += reqCompletion;
            if (sawUsage) sawUsageAny = true;

            String reasoning = reasoningBuf.toString().trim();

            if (contentBuf.length() == 0
                    && ReasoningNegotiator.shouldRetry(thinkingLevelOf(settings), reasoning.length(),
                            floatAttempt, chatBase(settings), chatModel(settings))) {
                ReasoningNegotiator.Applied next =
                        ReasoningNegotiator.applyReasoning(body, chatBase(settings), chatModel(settings),
                                thinkingLevelOf(settings), floatAttempt + 1);
                if (next != null) {
                    floatStyle = next.style;
                    floatValueIndex = next.valueIndex;
                    android.util.Log.i("VC_REASON",
                            "思考为空且无正文 → 换参数风格重试: " + next.style.id + "（第 " + (floatAttempt + 2) + " 种）");
                    floatAttempt++;

                    continue;
                }
            }
            if (contentBuf.length() > 0 && reasoning.isEmpty()
                    && thinkingLevelOf(settings) >= ReasoningNegotiator.LEVEL_LOW) {

                android.util.Log.i("VC_REASON",
                        "本轮有正文但无思考 → 不动界面内容，改为后台探测正确参数");
                probeReasoningAsync(chatBase(settings), chatKey(settings), chatModel(settings),
                        thinkingLevelOf(settings), true);
            }
            if (!reasoning.isEmpty()) {
                lastReasoning = reasoning;

                if (floatStyle != null) {
                    ReasoningNegotiator.rememberSuccess(chatBase(settings), chatModel(settings),
                            floatStyle, floatValueIndex);
                }

                if (reasoningAll.length() > 0) reasoningAll.append("\n\n");
                reasoningAll.append(reasoning);

                if (listener != null) listener.onReasoningDelta("\n\n");
            }

            if (sendTools && handler != null && !toolAcc.isEmpty()) {
                JSONArray toolCalls = new JSONArray();
                for (JSONObject acc : toolAcc.values()) {
                    JSONObject call = new JSONObject();
                    call.put("id", acc.optString("id", "call_" + toolCalls.length()));
                    call.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", acc.optString("name", ""));
                    fn.put("arguments", acc.optString("args", "{}"));
                    call.put("function", fn);
                    toolCalls.put(call);
                }
                JSONObject assistantMsg = new JSONObject();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", contentBuf.toString());
                assistantMsg.put("tool_calls", toolCalls);
                messages.put(assistantMsg);

                if (contentBuf.length() > 0) {
                    String roundText = contentBuf.toString().trim();
                    if (!roundText.isEmpty()) {
                        if (contentAll.length() > 0) contentAll.append("\n\n");
                        contentAll.append(roundText);
                    }
                }
                for (int i = 0; i < toolCalls.length(); i++) {

                    if (stopFlag != null && stopFlag.get()) break;
                    JSONObject call = toolCalls.getJSONObject(i);
                    String id = call.optString("id", "call_" + i);
                    JSONObject fn = call.optJSONObject("function");
                    String name = fn == null ? "" : fn.optString("name", "");
                    String argStr = fn == null ? "{}" : fn.optString("arguments", "{}");
                    JSONObject args = parseToolArgs(argStr);

                    if (listener != null) listener.onToolStart(name, argSummary(name, args));
                    String result = callTool(handler, name, args);

                    if (listener != null) listener.onToolEnd(name, resultSummary(name, result));
                    toolLog = appendToolLog(toolLog, name, argSummary(name, args), resultSummary(name, result),
                            toolSource(name), webDetail(name, result));
                    // 同步一份到静态区：请求中途断了也能知道做过什么
                    sLiveToolLog = toolLog;
                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", id);
                    toolMsg.put("content", result == null ? "" : result);
                    messages.put(toolMsg);

                    injectToolImageIfAny(settings, messages, result);
                }
                continue;
            }

            String content = contentBuf.toString().trim();

            if (carry.length() > 0) {
                String held = carry.toString();
                carry.setLength(0);
                if (content.isEmpty() || isContinueDone(content)) {
                    content = held;
                } else {
                    content = held + stripContinueMarkers(stripOverlapPrefix(held, content));
                }
                if (isContinueDone(contentBuf.toString())) completeConfirmed[0] = true;
            }

            boolean explicitCut = "length".equals(finishReason[0]);
            boolean silentCut = !sawDone[0] && !sawFinish[0];
            // 上游说"我说完了"（DONE/finish_reason），不代表内容真的说完了 ——
            // 实测遇到过正文停在「…还是你有」这种半句话上，上游却正常收尾。
            // 所以"内容看起来没写完"要单独成一条判据，不受流状态影响。
            boolean contentCut = !content.isEmpty() && !looksFinished(content);
            boolean roundCut = !content.isEmpty() && !completeConfirmed[0]
                    && (stopFlag == null || !stopFlag.get())
                    && (explicitCut || (silentCut && !looksFinished(content)) || contentCut);

            boolean producedNew = contentBuf.length() > 0;
            if (roundCut && continueRounds[0] < maxContinues
                    && (producedNew || continueRounds[0] == 0)) {
                continueRounds[0]++;
                android.util.Log.w("VC_STREAM", "回复被截断（finish_reason="
                        + (finishReason[0].isEmpty() ? "无" : finishReason[0])
                        + " 收到DONE=" + sawDone[0] + " 已有 " + content.length()
                        + " 字）→ 第 " + continueRounds[0] + " 次续写");
                if (listener != null) {
                    listener.onContinue("上一段被上游掐断了，正在接着写…（第 "
                            + continueRounds[0] + " 次）");
                }
                carry.append(content);
                messages.put(new JSONObject().put("role", "assistant").put("content", content));
                messages.put(new JSONObject().put("role", "user").put("content",
                        "Your previous reply broke off at “" + tailForPrompt(content) + "” — that was not you finishing; "
                                + "the upstream/network cut this output short.\n"
                                + "If that passage was in fact already a complete reply, **reply with just the single token " + CONTINUE_DONE
                                + "** and nothing else.\n"
                                + "Otherwise keep writing from exactly where it broke off: do not repeat what you already wrote, do not start over, "
                                + "do not explain or apologize — just pick up mid-sentence with the very next words."));
                continue;
            }

            if (!content.isEmpty() && sendTools && handler != null && toolAcc.isEmpty()
                    && (toolLog == null || toolLog.trim().isEmpty())
                    && nudgedThisTurn[0] < 2) {

                String fab = findFabricatedToolLine(content);

                boolean claimOnly = (fab == null) && looksLikeActionClaim(content);
                if (fab != null || claimOnly) {
                    nudgedThisTurn[0]++;
                    String label = fab != null ? fab : "（只写了结论，没调工具）";
                    android.util.Log.w("VC_AGENT", "检测到谎报完成（第 " + nudgedThisTurn[0]
                            + " 次）: " + label + " → 要求真正调用工具");

                    if (listener != null) {
                        listener.onRoundDiscarded("这条操作没有真的执行，正在重新执行…");
                    }
                    messages.put(new JSONObject().put("role", "assistant")
                            .put("content", stripFabricatedToolLines(content)));
                    messages.put(new JSONObject().put("role", "user").put("content",
                            "You just said this was **already done, but it never actually happened** — there is no such tool call in the system, "
                                    + "and nothing changed on the user's side (the screen and web pages are exactly as they were). "
                                    + "**Issue the real tool call right now** to get it done, then report the actual result; "
                                    + "if you cannot do it, tell the user plainly that it was not done and why."));
                    continue;
                }
            }

            if (!content.isEmpty() && sendTools && handler != null && toolAcc.isEmpty()
                    && nudgedThisTurn[0] < 2 && looksLikeAnnouncement(content)) {
                nudgedThisTurn[0]++;
                android.util.Log.i("VC_AGENT", "检测到『只说不做』(第 " + nudgedThisTurn[0]
                        + " 次)，追加执行提醒: " + content.substring(0, Math.min(40, content.length())));
                messages.put(new JSONObject().put("role", "assistant").put("content", content));
                messages.put(new JSONObject().put("role", "user").put("content",
                        "The action you just announced has not actually been performed. **Call the appropriate tool immediately** to complete it — no more describing it in words, "
                                + "and no more asking the user for permission. Once it is done, tell the user the concrete result in Chinese."));

                if (contentAll.length() > 0) contentAll.append("\n\n");
                contentAll.append(content);
                continue;
            }

            if (!content.isEmpty() && sendTools && handler != null
                    && nudgedThisTurn[0] < 2 && claimsSentButNeverTyped(content, toolLog)) {
                nudgedThisTurn[0]++;
                android.util.Log.w("VC_AGENT", "检测到『谎报已发送』（本回合没打过字却声称发出去了）"
                        + "，追加执行提醒: " + content.substring(0, Math.min(60, content.length())));
                messages.put(new JSONObject().put("role", "assistant").put("content", content));
                messages.put(new JSONObject().put("role", "user").put("content",
                        "Your last message claimed the message was sent, but you **never called the typing tool at all** — it cannot possibly have gone out. "
                                + "Do not fake it. Actually do it now: first use take_screenshot to see the current screen, "
                                + "open the conversation, then **use type with send=true** to enter the content and send it. "
                                + "If you truly cannot, state honestly that it was not sent and which step you got stuck on."));
                continue;
            }

            if (!content.isEmpty()) {
                String fullReasoning = reasoningAll.length() > 0 ? reasoningAll.toString()
                        : (reasoning.isEmpty() ? lastReasoning : reasoning);

                if ("length".equals(finishReason[0])) {
                    android.util.Log.w("VC_AGENT", "本轮输出被 max_tokens 截断 (finish_reason=length)");
                }

                String full = content;
                if (contentAll.length() > 0) full = contentAll + "\n\n" + content;

                if (toolLog == null || toolLog.trim().isEmpty()) {
                    if (findFabricatedToolLine(full) != null) {
                        android.util.Log.w("VC_AGENT", "最终答复里仍有伪造工具记录行 → 已清除");
                        full = stripFabricatedToolLines(full);
                    }
                }
                ChatResult r = new ChatResult(full, fullReasoning);
                r.toolLog = toolLog;
                r.promptTokens = accPrompt;
                r.contextTokens = lastPromptTok[0];
                r.completionTokens = accCompletion;
                r.totalTokens = accPrompt + accCompletion;
                r.finishReason = finishReason[0];
                r.continueRounds = continueRounds[0];
                r.truncated = roundCut && continueRounds[0] >= maxContinues;
                return r;
            }
            messages.put(new JSONObject().put("role", "user").put("content", "Give your final answer in Chinese."));
        }

        ChatResult fallback = new ChatResult(contentAll.toString().trim(),
                reasoningAll.length() > 0 ? reasoningAll.toString() : lastReasoning);
        fallback.toolLog = toolLog;
        fallback.promptTokens = accPrompt;
        fallback.contextTokens = lastPromptTok[0];
        fallback.completionTokens = accCompletion;
        fallback.totalTokens = accPrompt + accCompletion;
        fallback.continueRounds = continueRounds[0];
        return fallback;
    }

    /**
     * 判定"模型说它已经写完了"。
     * 容错：去空白、去包裹标点后，<_END 或 <END> 都算 ——
     * 实测模型会回 <END（缺右尖括号）、或带句号/引号，
     * 用严格相等会让标记漏进正文。
     */
    private static boolean isContinueDone(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty()) return false;
        // 只保留尖括号和字母，忽略标点与引号
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '>' || Character.isLetter(c)) sb.append(c);
        }
        String bare = sb.toString().toUpperCase(java.util.Locale.ROOT);
        return bare.equals("<END>") || bare.equals("<END");
    }

    /** 剥掉续写内容里残留的 <END 标记（双保险）。*/
    private static String stripContinueMarkers(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replace(CONTINUE_DONE, "")
                .replace("<END", "")
                .trim();
    }

    static String stripOverlapPrefix(String existing, String cont) {
        if (existing == null || existing.isEmpty() || cont == null || cont.isEmpty()) return cont;
        int max = Math.min(120, Math.min(existing.length(), cont.length()));
        for (int len = max; len >= 6; len--) {
            if (existing.regionMatches(existing.length() - len, cont, 0, len)) {
                return cont.substring(len);
            }
        }
        return cont;
    }

    private static String tailForPrompt(String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() <= 24) return t;
        return "…" + t.substring(t.length() - 24);
    }

    private static final String SENTENCE_ENDS = "。！？…!?；;」』）)】]\"'”’*`~|";

    private static boolean looksFinished(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return false;
        return SENTENCE_ENDS.indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    private static final String[] KNOWN_TOOLS = {
        "accessibility", "add_calendar_event", "app_control", "browser_back", "browser_click",
        "browser_close", "browser_links", "browser_open", "browser_read", "browser_search",
        "browser_type", "contacts", "create_task", "fetch_url", "file_manager", "flashlight",
        "get_device_status", "get_system_info", "list_apps", "list_contacts", "list_group_rooms",
        "list_tasks", "make_call", "manage_memory", "manage_task", "media_control", "open_maps_app",
        "open_app", "phone_status", "read_calendar", "read_group_chat", "read_phone_history",
        "map_close", "map_locate", "map_navigate", "map_open", "map_route", "map_search", "map_stop",
        "read_photos", "read_sms", "save_document", "search_sessions", "self_note", "send_sms",
        "read_memory_library",
        "perception",
        "set_alarm", "set_brightness",
        "set_dnd", "set_volume", "take_screenshot", "web_search",
        "generate_image", "list_image_models", "map_layer", "map_weather", "tap_script"
    };

    private static final String[] TOOL_RETURN_LITERALS = {
        "\"note\":\"已在内置浏览器打开",
        "\"saved\":true",
        "{\"ok\":true,\"note\""
    };

    private static boolean isFabricatedLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.isEmpty()) return false;

        if (t.equals("〔我当时查到的资料〕") || t.equals("（我当时查到的资料）")) return true;

        for (String lit : TOOL_RETURN_LITERALS) {
            if (t.contains(lit)) return true;
        }

        String body = t;
        if (body.startsWith("[TQ] ")) body = body.substring(5).trim();
        else if (body.startsWith("[FB] ")) body = body.substring(5).trim();
        else if (body.startsWith("🔧")) body = body.substring(1).trim();
        for (String n : KNOWN_TOOLS) {
            if (body.startsWith(n + "(")) return true;
        }
        return false;
    }

    public static boolean claimsSentButNeverTyped(String content, String toolLog) {
        if (content == null || content.isEmpty()) return false;

        if (toolLog != null && (toolLog.contains("type") || toolLog.contains("set_text"))) return false;

        String[] claimWords = {
            "已发送", "发送成功", "发出去了", "已发出", "发过去了", "已回复", "回复成功",
            "已把消息", "消息已发", "已经发送", "成功发送"
        };
        for (String w : claimWords) {
            if (content.contains(w)) return true;
        }
        return false;
    }

    public static String findFabricatedToolLine(String content) {
        if (content == null || content.isEmpty()) return null;
        for (String ln : content.split("\n", -1)) {
            if (!isFabricatedLine(ln)) continue;
            String t = ln.trim();
            for (String n : KNOWN_TOOLS) {
                if (t.contains(n)) return n;
            }
            return "工具记录";
        }
        return null;
    }

    public static String stripFabricatedToolLines(String content) {
        if (content == null || content.isEmpty()) return content;
        StringBuilder out = new StringBuilder();
        boolean dropped = false;
        for (String ln : content.split("\n", -1)) {
            if (isFabricatedLine(ln)) { dropped = true; continue; }
            if (out.length() > 0) out.append("\n");
            out.append(ln);
        }
        if (!dropped) return content;
        String r = out.toString();
        while (r.contains("\n\n\n")) r = r.replace("\n\n\n", "\n\n");
        return r.trim();
    }

    private static final String[][] ACTION_CLAIMS = {

        {"打开", "网页"}, {"打开", "浏览器"}, {"打开", "网站"}, {"打开", "页面"},
        {"打开", "首页"}, {"打开", "网址"}, {"打开", "链接"}, {"打开", "它"},
        {"搜", "网页"}, {"搜", "结果页"}, {"搜索", "页面"},
        {"截图", "屏幕"}, {"截图", "好"}, {"截", "屏"},
        {"读到", "页面"}, {"读到", "网页"}, {"读了", "页面"}, {"读了", "网页"},

        {"存", "文档库"}, {"保存", "文档库"}, {"存入", "文档库"}, {"存进", "文档库"}
    };

    public static boolean looksLikeActionClaim(String content) {
        if (content == null || content.isEmpty()) return false;
        String head = content.length() > 200 ? content.substring(0, 200) : content;
        for (String sent : head.split("[。！？!?\n]")) {
            String t = sent.trim();
            if (t.isEmpty()) continue;
            boolean done = t.contains("已经") || t.contains("已") || t.contains("好了")
                    || t.contains("成功") || t.contains("刚才") || t.contains("现在屏幕上")
                    || t.contains("屏幕上就是") || t.contains("切过去了") || t.contains("在屏幕上");
            if (!done) continue;

            if (t.endsWith("吗") || t.endsWith("呢") || t.contains("要我") || t.contains("需要我")) continue;
            for (String[] pair : ACTION_CLAIMS) {
                if (t.contains(pair[0]) && t.contains(pair[1])) return true;
            }
        }
        return false;
    }

    private static boolean looksLikeAnnouncement(String content) {
        if (content == null) return false;
        String head = content.length() > 80 ? content.substring(0, 80) : content;

        String[] patterns = {
            "我先", "我来", "让我", "我这就", "我马上", "正在", "稍等", "接下来我",
            "我准备", "我帮你", "我这就帮", "先帮", "先去", "先把", "先给你",
            "已经存", "已存", "已记录", "已保存", "记录一下", "保存一下", "存一下", "记一下",

            "准备添加", "准备把", "准备给", "准备为", "即将添加", "打算添加", "打算把",
            "接下来", "下面我", "我现在把", "我现在就", "这就为你", "这就把",
            "添加以下", "加入以下", "把以下", "以下专家", "以下角色"
        };
        for (String p : patterns) {
            if (head.startsWith(p) || head.contains(p + "把") || head.contains(p + "先")) return true;
        }

        if (content.length() < 160) {
            String[] shortPre = {"我准备", "准备添加", "即将", "打算", "接下来我", "下面我"};
            for (String p : shortPre) {
                if (head.contains(p)) return true;
            }
        }

        if (content.length() < 120 && (content.contains("存进长期记忆") || content.contains("存入记忆")
                || content.contains("保存到记忆") || content.contains("记到记忆") || content.contains("存到记忆"))) {
            return true;
        }

        String lower = head.toLowerCase(java.util.Locale.ROOT)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u201c', '"').replace('\u201d', '"');
        String[] enPatterns = {
            "i'll ", "i will ", "let me ", "i'm going to ", "i am going to ",
            "first, i", "first i", "i'll start", "let me start", "starting by",
            "i'll begin", "let me begin", "i need to ", "now i'll", "next, i",
            "i'm about to", "i am about to", "let me check", "i'll check",
            "i will check", "let me open", "i'll open", "i will open"
        };

        if (content.length() < 200) {
            for (String p : enPatterns) {
                if (lower.startsWith(p) || lower.contains(p)) return true;
            }
        }
        return false;
    }

    public static ChatResult chatWithTools(AppSettings settings, String systemPrompt,
            List<AppDatabase.MessageItem> history, String userText,
            JSONArray tools, ToolHandler handler, int maxIterations) throws Exception {
        return chatWithTools(settings, systemPrompt, history, userText, tools, handler,
                maxIterations, (String) null);
    }

    public static ChatResult chatWithTools(AppSettings settings, String systemPrompt,
            List<AppDatabase.MessageItem> history, String userText,
            JSONArray tools, ToolHandler handler, int maxIterations, String imageBase64Png) throws Exception {
        String base = chatBase(settings);
        String endpoint = endpointFor(base, "/chat/completions");

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        if (history != null) {
            for (AppDatabase.MessageItem item : history) {
                appendHistoryMessage(messages, item);
            }
        }
        messages.put(multimodalUserMessage(userText, imageBase64Png));

        int iterations = Math.max(2, maxIterations);
        String lastReasoning = "";
        String toolLog = "";

        StringBuilder contentAll = new StringBuilder();

        int nudgedThisTurn = 0;
        for (int iter = 0; iter < iterations; iter++) {

            boolean sendTools = tools != null && tools.length() > 0;
            JSONObject body = new JSONObject();
            body.put("model", isBlank(chatModel(settings)) ? "qwen-plus" : chatModel(settings).trim());
            body.put("messages", messages);
            body.put("stream", false);
            body.put("max_tokens", parseInt(settings.maxTokens, 110000));
            body.put("temperature", parseDouble(settings.temperature, 0.6));
            body.put("top_p", parseDouble(settings.topP, 1.0));
            if (sendTools) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            ReasoningNegotiator.applyReasoning(body, chatBase(settings), chatModel(settings),
                    thinkingLevelOf(settings), 0);
            if (settings.enableThinking && settings.enableDeepThinking) {
                body.put("thinking_budget", 20000);
            }

            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            registerActiveConn(connection);
            String responseText;
            int code;
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(connTimeoutMs(settings, 20000));
                connection.setReadTimeout(readTimeoutMs(settings, 120000));
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + chatKey(settings).trim());
                OutputStream output = connection.getOutputStream();
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();
                code = connection.getResponseCode();
                InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                responseText = readAll(input);
            } finally {
                clearActiveConn(connection);
                connection.disconnect();
            }
            if (code >= 400) {

                if (iter == 0 && sendTools && !isRateLimited(code, responseText)) {
                    throw new ToolsUnsupportedException("HTTP " + code + " " + safeSnippet(responseText));
                }
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new RuntimeException("接口没有返回 choices");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new RuntimeException("接口没有返回 message");

            JSONArray toolCalls = message.optJSONArray("tool_calls");
            if (sendTools && handler != null && toolCalls != null && toolCalls.length() > 0) {

                String toolReasoning = safeOptString(message, "reasoning_content").trim();
                if (toolReasoning.isEmpty()) toolReasoning = safeOptString(message, "reasoning").trim();
                if (!toolReasoning.isEmpty()) lastReasoning = toolReasoning;
                JSONObject assistantMsg = new JSONObject();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", safeOptString(message, "content"));
                assistantMsg.put("tool_calls", toolCalls);
                messages.put(assistantMsg);

                String roundText = safeOptString(message, "content").trim();
                if (!roundText.isEmpty()) {
                    if (contentAll.length() > 0) contentAll.append("\n\n");
                    contentAll.append(roundText);
                }
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject call = toolCalls.getJSONObject(i);
                    String id = safeOptString(call, "id");
                    if (id.isEmpty()) id = "call_" + i;
                    JSONObject fn = call.optJSONObject("function");
                    String name = fn == null ? "" : safeOptString(fn, "name");
                    String argStr = fn == null ? "{}" : safeOptString(fn, "arguments");
                    if (argStr.isEmpty()) argStr = "{}";
                    JSONObject args = parseToolArgs(argStr);
                    String result = callTool(handler, name, args);

                    toolLog = appendToolLog(toolLog, name, argSummary(name, args), resultSummary(name, result),
                            toolSource(name), webDetail(name, result));
                    // 同步一份到静态区：请求中途断了也能知道做过什么
                    sLiveToolLog = toolLog;
                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", id);
                    toolMsg.put("content", result == null ? "" : result);
                    messages.put(toolMsg);

                    injectToolImageIfAny(settings, messages, result);
                }
                continue;
            }

            String content = safeOptString(message, "content").trim();
            String reasoning = safeOptString(message, "reasoning_content").trim();
            if (reasoning.isEmpty()) reasoning = safeOptString(message, "reasoning").trim();
            if (!reasoning.isEmpty()) lastReasoning = reasoning;

            if (!content.isEmpty() && handler != null
                    && nudgedThisTurn < 2 && looksLikeAnnouncement(content)) {
                nudgedThisTurn++;
                android.util.Log.i("VC_AGENT", "检测到『只说不做』(非流式，第 " + nudgedThisTurn
                        + " 次)，追加执行提醒: " + content.substring(0, Math.min(40, content.length())));
                messages.put(new JSONObject().put("role", "assistant").put("content", content));
                messages.put(new JSONObject().put("role", "user").put("content",
                        "The action you announced is still not actually finished — describing it in words does not count as doing it. "
                                + "**Call the appropriate tool immediately** and carry it through (use open_app/accessibility to get into the app, "
                                + "take_screenshot to see the screen); no more narrating in words, and no more asking for permission. "
                                + "When everything is done, report the result **in Chinese**."));

                if (contentAll.length() > 0) contentAll.append("\n\n");
                contentAll.append(content);
                continue;
            }

            if (!content.isEmpty()) {

                String full = content;
                if (contentAll.length() > 0) full = contentAll + "\n\n" + content;

                if (toolLog == null || toolLog.trim().isEmpty()) {
                    if (findFabricatedToolLine(full) != null) {
                        android.util.Log.w("VC_AGENT", "最终答复里仍有伪造工具记录行 → 已清除");
                        full = stripFabricatedToolLines(full);
                    }
                }
                ChatResult r = new ChatResult(full, reasoning.isEmpty() ? lastReasoning : reasoning);
                r.toolLog = toolLog;
                return r;
            }
            messages.put(new JSONObject().put("role", "user").put("content", "Give your final answer in Chinese."));
        }

        ChatResult fallback = new ChatResult(contentAll.toString().trim(), lastReasoning);
        fallback.toolLog = toolLog;
        return fallback;
    }

    public static class GmSpeakResult {
        public String speech;
        public String thought;
        public int tokens;

        public int promptTokens;

        public int completionTokens;

        public int contextTokens;

        public long genMs;
        public String toolLog;
        public boolean viaNewSearch;

        public boolean repeatAborted;

        public final java.util.List<String> pulledExperts = new java.util.ArrayList<>();

        public final java.util.Map<String, String> pulledReasons = new java.util.HashMap<>();
        public boolean declaredSilent;

        public String repeatSample;
        public int repeatCount;

        public int toolFailCount;

        public boolean finalConfirmed;

        public String question;

        public boolean questionUrgent;

        public java.util.List<GmQuestion> questions = new java.util.ArrayList<>();

        public java.util.List<String> questionOptions = new java.util.ArrayList<>();

        public boolean questionMulti;

        public java.util.List<GmFeedbackItem> feedback = new java.util.ArrayList<>();
    }

    public static class GmFeedbackItem {

        public String category;
        public String title;
        public String detail;

        public String context;
        public GmFeedbackItem(String category, String title, String detail, String context) {
            this.category = category;
            this.title = title;
            this.detail = detail;
            this.context = context;
        }
    }

    public static class GmQuestion {
        public String text;
        public java.util.List<String> options = new java.util.ArrayList<>();
        public boolean multi;
        public GmQuestion(String text) { this.text = text; }
    }

    private static boolean looksLikePublicSpeech(String segBody) {
        if (segBody == null || segBody.isEmpty()) return false;

        if (segBody.contains("【终稿") || segBody.contains("【最终确认】")
                || segBody.contains("【讨论可以结束了】")) return true;

        if (segBody.contains("选项：") || segBody.contains("选项(多选)：")
                || segBody.contains("选项（多选）：")) return true;

        if (segBody.contains("回看 #")) return true;
        return false;
    }

    private static int deliverableStartIn(String segBody) {
        if (segBody == null || segBody.isEmpty()) return -1;
        final int MIN_TAIL = 300;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^(?:\\*\\*[^*\\n]{4,}[^*\\n]*\\*\\*|#{1,4}\\s+\\S)")
                .matcher(segBody);
        while (m.find()) {
            if (segBody.length() - m.start() >= MIN_TAIL) return m.start();
        }
        return -1;
    }

    private static final class RoundText {
        String speech = "";
        String thought = "";

        boolean privateThinkingOnly = false;
    }

    private static RoundText parseRoundText(String rawContent) {
        RoundText out = new RoundText();
        if (rawContent == null) return out;
        final boolean rawHadToolSyntax = hasNativeToolSyntax(rawContent);

        String content = stripNativeToolSyntax(stripThinkingTags(rawContent));
        out.privateThinkingOnly = rawHadToolSyntax && looksLikePrivateThinking(rawContent);
        if (content.trim().isEmpty()) return out;
        java.util.List<String> kinds = new java.util.ArrayList<>();
        java.util.List<Integer> starts = new java.util.ArrayList<>();

        java.util.List<Integer> tagAt = new java.util.ArrayList<>();

        java.util.regex.Matcher mTags = java.util.regex.Pattern
                .compile("(?m)(?:^|(?<=[。．.!！?？;；]))[ \t]*(思考|发言)\s*[:：]")
                .matcher(content);
        while (mTags.find()) {
            kinds.add(mTags.group(1));
            starts.add(mTags.end());

            tagAt.add(mTags.start(1));
        }
        if (kinds.isEmpty()) {

            if (out.privateThinkingOnly) out.thought = content.trim();
            else out.speech = content.trim();
            return out;
        }

        String lead = content.substring(0, tagAt.get(0)).trim();
        if (!lead.isEmpty()) out.thought = lead;
        for (int i = 0; i < kinds.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < kinds.size()) ? tagAt.get(i + 1) : content.length();
            String segBody = content.substring(from, Math.max(from, to)).trim();
            if (segBody.isEmpty()) continue;
            boolean isThinkSeg = "思考".equals(kinds.get(i));
            if (isThinkSeg && !looksLikePublicSpeech(segBody)) {

                int cut = deliverableStartIn(segBody);
                if (cut > 0) {
                    String plan = segBody.substring(0, cut).trim();
                    String deliverable = segBody.substring(cut).trim();
                    if (!plan.isEmpty()) {
                        out.thought = out.thought.isEmpty() ? plan : (out.thought + "\n" + plan);
                    }
                    out.speech = out.speech.isEmpty() ? deliverable : (out.speech + "\n" + deliverable);
                } else {

                    out.thought = out.thought.isEmpty() ? segBody : (out.thought + "\n" + segBody);
                }
            } else {

                out.speech = out.speech.isEmpty() ? segBody : (out.speech + "\n" + segBody);
            }
        }
        return out;
    }

    private static boolean looksLikePrivateThinking(String content) {
        if (content == null) return false;
        String s = content.trim();
        boolean startsWithThinkTag = s.startsWith("思考：") || s.startsWith("思考:");
        if (!startsWithThinkTag) return false;

        if (looksLikePublicSpeech(s)) return false;

        return hasNativeToolSyntax(s);
    }

    private static String stripAsides(String text) {
        if (text == null) return "";
        String t = text;

        t = t.replaceAll("[（(][^）)\\n]*(?:其他人|其他角色|别让用户|别轮着打扰|一次问清|一并提出|攒一波)[^）)\\n]*[）)]", "");

        t = t.replaceAll("(?m)^\\s*[^\\n？?]*(?:其他人如有疑问|其他角色如还有疑问|别轮着打扰用户)[^\\n]*$", "");

        t = t.replace("**", "");
        return t.trim();
    }

    private static void applyOptions(GmQuestion g, boolean multi, String raw) {
        if (g == null || raw == null) return;
        String body = raw.trim();
        if (body.isEmpty()) return;

        String[] parts = body.contains("|") ? body.split("\\|")
                : (body.split("[A-Za-z]\\s*[\\u3001\\.]").length >= 3 ? body.split("(?=[A-Za-z]\\s*[\\u3001\\.])")
                                                                       : body.split("[\\uff0c,\\uff1b;]"));
        for (String part : parts) {
            String s = part == null ? "" : part.trim();

            s = s.replaceFirst("^[A-Za-z0-9]\\s*[\\u3001.\\u3002\\)\\uff09]\\s*", "").trim();
            s = s.replaceAll("[\\uff0c,\\uff1b;]+$", "").trim();
            if (!s.isEmpty() && !g.options.contains(s)) g.options.add(s);
        }
        g.multi = multi;
        if (g.options.size() < 2) { g.options.clear(); g.multi = false; }
    }

    public static String nowText() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        String[] wd = {"日", "一", "二", "三", "四", "五", "六"};
        return String.format(java.util.Locale.CHINA,
                "%04d年%02d月%02d日 %02d:%02d 星期%s",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                wd[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]);
    }

    public static String timeLineForPrompt() {
        return "[Current time] " + nowText()
                + " (This is the live clock on your phone. Treat it as ground truth whenever an answer involves \"today\", \"now\" or \"recently\"; "
                + "derive exact times from it instead of guessing from training data.)\n"
                + "[Source-freshness discipline] Every web_search result carries a date (publish date; \"未知\" [unknown] when the source provides none). Check the date before relying on a result:\n"
                + "1. For information that **goes stale** — policies, prices, rates, platform rules, subsidies, versions, rankings — you must use recent sources. "
                + "If you only find old ones, search again with different keywords (try adding the year or \"最新\" [latest]); if truly only old sources exist, say so explicitly (\"this is the position as of year X and may have changed\") "
                + "instead of presenting it as the current rule.\n"
                + "2. For information that **does not go stale** — principles, methods, experience, history, case studies — older sources remain valid; do not dismiss them just for being old.\n"
                + "3. When citing a source, state how old it is (e.g. \"2024 data shows…\") so the user can judge whether it still applies.\n"
                + "4. To restrict the time range, pass the freshness parameter to web_search (format dN/mN/yN: d7 = last 7 days, m1 = last 1 month, y1 = last 1 year; omit = no limit).\n";
    }

    public static String buildImplementationDoc(String model, String baseUrl, String apiKey,
                                                String roomTitle, String docRoleSpeech,
                                                String discussion,
                                                java.util.concurrent.atomic.AtomicBoolean stopFlag) throws Exception {
        return buildImplementationDoc(model, baseUrl, apiKey, roomTitle, docRoleSpeech, discussion, stopFlag, 0, null);
    }

    public static String buildImplementationDoc(String model, String baseUrl, String apiKey,
                                                String roomTitle, String docRoleSpeech,
                                                String discussion,
                                                java.util.concurrent.atomic.AtomicBoolean stopFlag,
                                                int thinkLevel) throws Exception {
        return buildImplementationDoc(model, baseUrl, apiKey, roomTitle, docRoleSpeech, discussion, stopFlag, thinkLevel, null);
    }

    public static String buildImplementationDoc(String model, String baseUrl, String apiKey,
                                                String roomTitle, String docRoleSpeech,
                                                String discussion,
                                                java.util.concurrent.atomic.AtomicBoolean stopFlag,
                                                int thinkLevel, StreamListener streamListener) throws Exception {
        String base = isBlank(baseUrl) ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : baseUrl.trim();
        String endpoint = endpointFor(base, "/chat/completions");

        StringBuilder sys = new StringBuilder();
        sys.append(timeLineForPrompt());

        sys.append("【Output language: ENGLISH (most important — read this first)】\n");
        sys.append("Write the **entire document in English**. The discussion transcript may be in Chinese — "
                + "that is just the language the discussion happened in. **Do NOT follow the input language.** "
                + "Your output is English, including every section heading, table header and bullet label.\n"
                + "Keep these in their original form (do not translate): proper nouns, product/brand names, "
                + "code, identifiers, commands, file paths, URLs, and quoted source text.\n\n");
        sys.append("You are the \"chief distiller\" of this multi-role discussion. Your task: distill the entire discussion into a plan document that is **ready to hand off as-is and never drops an important detail**, ");
        sys.append("for the user or a downstream executor (such as a coding agent on a computer) to implement.\n\n");
        sys.append("[Supreme principle: high fidelity — when in doubt, include more rather than miss anything]\n");
        sys.append("Discussions are often long and detail-heavy, and the most common failure is losing important details during distillation. So you must work in two steps:\n");
        sys.append("Step 1 (do this in your thinking; exhaustive extraction): read the entire discussion first and pull out every important point **one by one**, letting none slip —\n");
        sys.append("  · every conclusion and recommended option (including why it was adopted);\n");
        sys.append("  · every concrete number/data point: amounts, ratios, prices, times, quantities, thresholds, cycles, yields, etc.;\n");
        sys.append("  · every actionable recommendation and operating step (specific enough to follow as written);\n");
        sys.append("  · every risk point with its mitigation / early-warning signal;\n");
        sys.append("  · each role's core contribution (who raised which key point);\n");
        sys.append("  · rejected options and the reasons they were rejected;\n");
        sys.append("  · all external facts and their sources (anything looked up online must carry its origin);\n");
        sys.append("  · every open item awaiting the user's decision.\n");
        sys.append("  In this step: **no summarizing away, no omitting, no truncating** — err on the side of exhaustiveness.\n");
        sys.append("Step 2 (organize into the deliverable): first decide what type of discussion this is, pick the structure that fits it best, and organize **all** points captured in Step 1 into that structure;\n");
        sys.append("  after writing, self-check: does every point listed in Step 1 have a home in the document? Fill in anything missing.\n\n");
        sys.append("[Decide the discussion type first, then pick the delivery structure]\n");
        sys.append("Possible types: software/system/小程序 (mini program) development, e-commerce operations, side hustles, content creation/self-media, personal or family decisions, studying and exams, ");
        sys.append("relationships, health, consumer-rights disputes, legal matters, startups and investment, etc. Organize according to the type; do not force in irrelevant sections.\n\n");

        sys.append("[Generic delivery structure] (use Markdown headings; add or drop sections to match the actual discussion, but the main body \"Details by Topic\" must be the most thorough part):\n");
        sys.append("# ").append(roomTitle == null ? "Plan" : roomTitle).append(" \u00b7 Delivery Plan\n");
        sys.append("## 1. Goals & Background (what the user wants to solve, constraints and premises)\n");
        sys.append("## 2. Core Conclusion / Recommended Approach (one-sentence thesis + key reasons)\n");
        sys.append("## 3. Details by Topic (expand block by block following the discussion's themes/modules; write out each block's concrete conclusions, numbers and steps — this is the document body, the longest part; never compress it into one sentence)\n");
        sys.append("## 4. Key Contributions by Role (who raised which adopted point; may cite see #N)\n");
        sys.append("## 5. Action Checklist (each item: what to do / who / when / definition of done)\n");
        sys.append("## 6. Data & Evidence (every number, price, policy and source that appeared in the discussion, itemized with its origin)\n");
        sys.append("## 7. Risks & Mitigations (each risk + trigger signal + response plan)\n");
        sys.append("## 8. Rejected Options & Why (so execution doesn't backtrack)\n");
        sys.append("## 9. Open Questions (undecided in the discussion, need the user's call — itemized)\n\n");
        sys.append("[For software/system/小程序 (mini program) development, Section 3 must additionally cover, in full]: tech stack and architecture, module breakdown, data model, ");
        sys.append("API inventory (path / input params / response / error codes), page-by-page and element-by-element UI and interaction detail (each element's position / size / color / copy, ");
        sys.append("what happens on tap, navigation and back paths, transition style, empty/error states, confirmation copy for dangerous actions), ");
        sys.append("security and privilege-escalation protection, qualifications and filings required for launch, and an acceptance checklist that can be ticked item by item.\n\n");

        sys.append("【Translation quality — this document is translated from Chinese, treat it as a real translation job】\n");
        sys.append("The discussion and its conclusions are in Chinese. Your job is **not** word-for-word conversion: "
                + "understand what the Chinese actually means, then express the same thing in natural, idiomatic English. "
                + "Chinese often carries more nuance/overlap than any single English word — pick the wording that conveys "
                + "the real intent instead of the closest-looking word. Never narrow or distort the meaning just to match the "
                + "original phrasing; restructure sentences freely if that makes the English clearer and more accurate.\n"
                + "Keep in the original (untranslated) whenever the original itself is the point: proper nouns, personal/"
                + "product/brand names, role names quoted verbatim, code, identifiers, commands, file paths, URLs, "
                + "and any Chinese text you quote as evidence. Quality comes first — a clear, faithful English document "
                + "is the goal, not a literal one.\n\n");
        sys.append("[Hard requirements]\n");
        sys.append("1. Compile strictly from the discussion content; invent no requirements. Anything undecided goes into \"Open Questions\" — never decide for the user.\n");
        sys.append("2. Where opinions contradict each other, point out the contradiction explicitly and give a recommended lean with reasons; do not pretend there was consensus.\n");
        sys.append("3. Eliminate ambiguity: no vague phrasing like \"could consider\" or \"suggest optimizing\" — give definite choices with reasons.\n");
        sys.append("4. Keep every number and source; never blur a concrete figure into \"roughly / approximately\".\n");
        sys.append("5. Length serves completeness: better long than lossy; never cut details just to be brief.\n");

        sys.append("6. When quoting the discussion, cite the message number as \"see #N\" so the user can look that message up.\n");
        sys.append("7. **Write in English** (see the rule at the top; the transcript may be in Chinese — do not follow it into Chinese), "
                + "section headings included; start directly with the title — no greetings, no \"Below is...\" style preamble.\n\n");
        if (docRoleSpeech != null && !docRoleSpeech.trim().isEmpty()) {
            sys.append("[A draft summary already given by a role in the discussion (use it as the skeleton; complete and correct it — do not copy it verbatim)]\n");
            sys.append(docRoleSpeech.trim()).append("\n\n");
        }
        sys.append("[Full discussion transcript]\n");

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", sys.toString()));
        messages.put(new JSONObject().put("role", "user").put("content",
                discussion == null ? "" : discussion));

        JSONObject body = new JSONObject();
        body.put("model", isBlank(model) ? "deepseek-v4-flash-0731" : model.trim());
        body.put("messages", messages);
        body.put("stream", streamListener != null);
        body.put("temperature", 0.3);

        body.put("max_tokens", 110000);

        ReasoningNegotiator.applyReasoning(body, baseUrl, model, thinkLevel, 0);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(60000);
            connection.setReadTimeout(600000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + (isBlank(apiKey) ? "" : apiKey.trim()));
            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            if (code >= 400) {
                String err = readAll(connection.getErrorStream());

                if (code == 401 || code == 403) {
                    boolean emptyKey = isBlank(apiKey);
                    throw new RuntimeException("HTTP " + code + "：鉴权失败"
                            + (emptyKey ? "（这次请求没有带 API Key）" : "（密钥无效或没权限）")
                            + "。\n· 地址：" + endpoint
                            + "\n· 到「服务商」确认这个地址下的 Key 已填好（可留空则用角色/全局的）");
                }
                throw new RuntimeException("HTTP " + code + " " + safeSnippet(err));
            }
            if (streamListener == null) {
                String responseText = readAll(connection.getInputStream());
                JSONObject root = new JSONObject(responseText);
                JSONArray choices = root.optJSONArray("choices");
                if (choices == null || choices.length() == 0) throw new RuntimeException("接口没有返回 choices");
                JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                if (message == null) throw new RuntimeException("接口没有返回 message");
                String content = safeOptString(message, "content").trim();
                if (content.isEmpty()) throw new RuntimeException("汇总没有返回内容");
                return content;
            }

            StringBuilder contentAll = new StringBuilder();
            StringBuilder reasoningAll = new StringBuilder();
            java.io.BufferedReader sseReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String sseLine;
            while ((sseLine = sseReader.readLine()) != null) {
                if (stopFlag != null && stopFlag.get()) break;
                sseLine = sseLine.trim();
                if (sseLine.isEmpty() || !sseLine.startsWith("data:")) continue;
                String payload = sseLine.substring(5).trim();
                if ("[DONE]".equals(payload)) break;
                JSONObject chunk;
                try { chunk = new JSONObject(payload); } catch (Exception ignore) { continue; }

                JSONObject errObj = chunk.optJSONObject("error");
                if (errObj != null) {
                    String em = safeOptString(errObj, "message");
                    if (em.isEmpty()) em = errObj.toString();

                    String advice = "";
                    String low = em.toLowerCase(java.util.Locale.ROOT);
                    if (low.contains("too long") || low.contains("context_length")
                            || low.contains("maximum context") || em.contains("超长")
                            || low.contains("too many tokens") || em.contains("11115")) {
                        advice = "\n· 这次是**输入太长**：讨论全文超过了该模型的上下文上限。"
                                + "\n· 换一个上下文更大的模型（名字里常带 1M/128k 之类），"
                                + "或先导出「全部对话原文」再挑一段提炼（内容更短）。";
                    } else if (em.contains("temporary") || em.contains("不可用")) {
                        advice = "\n· 这是上游账号侧的临时故障，稍后重试，或换一个服务商/模型。";
                    }
                    throw new RuntimeException("上游返回错误：" + em + advice);
                }
                JSONArray chs = chunk.optJSONArray("choices");
                if (chs == null || chs.length() == 0) continue;
                JSONObject delta = chs.getJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;
                String rd = safeOptString(delta, "reasoning_content");
                if (rd.isEmpty()) rd = safeOptString(delta, "reasoning");
                if (!rd.isEmpty()) { reasoningAll.append(rd); streamListener.onReasoningDelta(rd); }
                String cd = safeOptString(delta, "content");
                if (!cd.isEmpty()) { contentAll.append(cd); streamListener.onContentDelta(cd); }
            }
            String content = contentAll.toString().trim();
            if (content.isEmpty()) throw new RuntimeException("汇总没有返回内容");
            return content;
        } finally {
            connection.disconnect();
        }
    }

    public static String distillUserPersona(String personaModel, String personaBaseUrl, String personaApiKey,
                                           String previousPersona, String dialogueText,
                                           int privateCount, int groupCount) throws Exception {
        return distillUserPersona(personaModel, personaBaseUrl, personaApiKey, previousPersona, dialogueText,
                privateCount, groupCount, 0);
    }

    public static String distillUserPersona(String personaModel, String personaBaseUrl, String personaApiKey,
                                           String previousPersona, String dialogueText,
                                           int privateCount, int groupCount, int thinkLevel) throws Exception {
        String base = isBlank(personaBaseUrl) ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : personaBaseUrl.trim();
        String endpoint = endpointFor(base, "/chat/completions");

        String sysPrompt = PersonaLibrary.buildDistillPrompt(privateCount, groupCount, previousPersona);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", sysPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", dialogueText));

        JSONObject body = new JSONObject();
        body.put("model", isBlank(personaModel) ? "deepseek-v4-flash-0731" : personaModel.trim());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("temperature", 0.3);
        body.put("max_tokens", 110000);
        body.put("enable_thinking", false);
        // 蒸馏思考档位（用户 2026-10-13；0=关，保持原行为）。
        if (thinkLevel >= ReasoningNegotiator.LEVEL_LOW) {
            body.remove("enable_thinking");
            try { ReasoningNegotiator.applyReasoning(body, base, personaModel, thinkLevel, 0); }
            catch (Exception ignored) { }
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(60000);
            connection.setReadTimeout(300000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + (isBlank(personaApiKey) ? "" : personaApiKey.trim()));
            OutputStream output = connection.getOutputStream();
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.close();

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = readAll(input);
            if (code >= 400) throw new RuntimeException("HTTP " + code + " " + safeSnippet(responseText));
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new RuntimeException("接口没有返回 choices");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new RuntimeException("接口没有返回 message");
            String content = safeOptString(message, "content").trim();
            // 开思考档位后，部分中转会把思考写进 content（带 <｜end▁of▁thinking｜> 分隔）；
            // 只取分隔符之后的部分，避免思考混进人格档案（压缩那边同样处理）。
            final String endThink = "<｜end▁of▁thinking｜>";
            int et = content.indexOf(endThink);
            if (et >= 0) content = content.substring(et + endThink.length()).trim();
            if (content.isEmpty()) throw new RuntimeException("蒸馏没有返回内容");
            return content;
        } finally {
            connection.disconnect();
        }
    }

    public static GmSpeakResult groupChatSpeak(
            String baseUrl, String apiKey, String model,
            String memberName, String memberRoleDesc, String memberPrompt,
            String roomContext,
            boolean canSearch,
            int maxOutputTokens,
            int thinkLevel,
            java.util.concurrent.atomic.AtomicBoolean stopFlag,
            ToolHandler searchToolHandler,
            StreamListener listener) throws Exception {
        return groupChatSpeak(baseUrl, apiKey, model, memberName, memberRoleDesc, memberPrompt,
                roomContext, canSearch, maxOutputTokens, thinkLevel, stopFlag,
                searchToolHandler, listener, 0, 0);
    }

    public static GmSpeakResult groupChatSpeak(
            String baseUrl, String apiKey, String model,
            String memberName, String memberRoleDesc, String memberPrompt,
            String roomContext,
            boolean canSearch,
            int maxOutputTokens,
            int thinkLevel,
            java.util.concurrent.atomic.AtomicBoolean stopFlag,
            ToolHandler searchToolHandler,
            StreamListener listener,
            int goalBudgetTokens, int goalUsedTokens) throws Exception {
        return groupChatSpeak(baseUrl, apiKey, model, memberName, memberRoleDesc, memberPrompt,
                roomContext, canSearch, maxOutputTokens, thinkLevel, stopFlag,
                searchToolHandler, listener, goalBudgetTokens, goalUsedTokens, 0);
    }

    public static GmSpeakResult groupChatSpeak(
            String baseUrl, String apiKey, String model,
            String memberName, String memberRoleDesc, String memberPrompt,
            String roomContext,
            boolean canSearch,
            int maxOutputTokens,
            int thinkLevel,
            java.util.concurrent.atomic.AtomicBoolean stopFlag,
            ToolHandler searchToolHandler,
            StreamListener listener,
            int goalBudgetTokens, int goalUsedTokens, int langMode) throws Exception {
        return groupChatSpeak(baseUrl, apiKey, model, memberName, memberRoleDesc, memberPrompt,
                roomContext, canSearch, maxOutputTokens, thinkLevel, stopFlag,
                searchToolHandler, listener, goalBudgetTokens, goalUsedTokens, langMode, null, 0L, 0L);
    }

    public static class SpeakLimits {

        public int repeatLen = 500;

        public int repeatMinChars = 3000;

        public int repeatWindow = 2000;

        public int repeatMax = 3;

        public int toolMaxIter = 37;

        public int repeatRetry = 3;

        public static SpeakLimits defaults() { return new SpeakLimits(); }
    }

    public static GmSpeakResult groupChatSpeak(
            String baseUrl, String apiKey, String model,
            String memberName, String memberRoleDesc, String memberPrompt,
            String roomContext,
            boolean canSearch,
            int maxOutputTokens,
            int thinkLevel,
            java.util.concurrent.atomic.AtomicBoolean stopFlag,
            ToolHandler searchToolHandler,
            StreamListener listener,
            int goalBudgetTokens, int goalUsedTokens, int langMode,
            SpeakLimits limits, long toolMemUsedTok, long ownContextTokens) throws Exception {
        return groupChatSpeak(baseUrl, apiKey, model, memberName, memberRoleDesc, memberPrompt,
                roomContext, canSearch, maxOutputTokens, thinkLevel, stopFlag,
                searchToolHandler, listener, goalBudgetTokens, goalUsedTokens, langMode,
                limits, toolMemUsedTok, ownContextTokens, 0, null, false);
    }

    public static GmSpeakResult groupChatSpeak(
            String baseUrl, String apiKey, String model,
            String memberName, String memberRoleDesc, String memberPrompt,
            String roomContext,
            boolean canSearch,
            int maxOutputTokens,
            int thinkLevel,
            java.util.concurrent.atomic.AtomicBoolean stopFlag,
            ToolHandler searchToolHandler,
            StreamListener listener,
            int goalBudgetTokens, int goalUsedTokens, int langMode,
            SpeakLimits limits, long toolMemUsedTok, long ownContextTokens,
            int roundNo, String pullableNames, boolean minorityPhase) throws Exception {
        return groupChatSpeak(baseUrl, apiKey, model, memberName, memberRoleDesc, memberPrompt,
                roomContext, canSearch, maxOutputTokens, thinkLevel, stopFlag,
                searchToolHandler, listener, goalBudgetTokens, goalUsedTokens, langMode,
                limits, toolMemUsedTok, ownContextTokens, roundNo, pullableNames, minorityPhase, 0L);
    }

    public static GmSpeakResult groupChatSpeak(
            String baseUrl, String apiKey, String model,
            String memberName, String memberRoleDesc, String memberPrompt,
            String roomContext,
            boolean canSearch,
            int maxOutputTokens,
            int thinkLevel,
            java.util.concurrent.atomic.AtomicBoolean stopFlag,
            ToolHandler searchToolHandler,
            StreamListener listener,
            int goalBudgetTokens, int goalUsedTokens, int langMode,
            SpeakLimits limits, long toolMemUsedTok, long ownContextTokens,
            int roundNo, String pullableNames, boolean minorityPhase,
            long memStoreUsedTok) throws Exception {

        final SpeakLimits lim = limits == null ? SpeakLimits.defaults() : limits;

        final RepeatGuard.Params guardParams = new RepeatGuard.Params(
                Math.max(500, Math.min(200000, lim.repeatMinChars)),
                Math.max(100, Math.min(20000, lim.repeatLen)),
                0,
                Math.max(2, Math.min(100, lim.repeatMax)));
        guardParams.winChars = Math.max(guardParams.repLen * 3, Math.min(200000, lim.repeatWindow));

        String base = isBlank(baseUrl) ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : baseUrl.trim();
        String endpoint = endpointFor(base, "/chat/completions");

        StringBuilder sys = new StringBuilder();
        sys.append("You are one member of a group chat.\n");

        switch (langMode) {
            case 1:
                sys.append("【Language】Three lanes, and they must be kept strictly apart:\n");
                sys.append("  · **Reasoning in English** - more precise, and it costs fewer tokens.\n");
                sys.append("  · **Your speeches, the discussion, the challenges you trade with each other: in English** - the user does not read these one by one.\n");
                sys.append("  · **But everything meant for the user's eyes has to be in Chinese**, and that covers "
                        + "the question parameter and the options of the ask_user tool, plus the conclusions you finally hand over. "
                        + "Those are the only parts he reads, and in English he cannot read them.\n");
                sys.append("  In one line: **discuss in English, write for the user in Chinese.**\n");
                break;
            case 2:
                sys.append("【Language】Reason in **English** (more precise, and it costs fewer tokens), "
                        + "but your final speech must be written in **Chinese** for the user to read.\n");
                break;
            case 3:
                sys.append("【Language】Use **English** throughout: reasoning in English, the final speech in English, "
                        + "and your questions in English too.\n");
                break;
            default:

                break;
        }

        sys.append(timeLineForPrompt());
        sys.append("Your name: ").append(memberName).append("\n");
        if (!isBlank(memberRoleDesc)) sys.append("Your standing in the group: ").append(memberRoleDesc).append("\n");
        if (!isBlank(memberPrompt)) sys.append("Your role brief: ").append(memberPrompt).append("\n");
        sys.append("\nThis is a multi-role discussion group: the members each put their own view on the user's question. The rules:\n");

        sys.append("1. Any fact, figure or conclusion that matters has to be written into your speech - otherwise nobody else can use it.\n");

        sys.append("2. Whether to speak this round is your own call.\n");
        sys.append("3. Do not go over what someone has already said, and do not speak just for the sake of speaking.\n");

        sys.append("4. How to discuss: first see clearly what the others are responsible for and how far they have got, then do one of three things - "
                + "**add** (something inside your own expertise that nobody has said yet), "
                + "**challenge** (point at the place where someone else's plan will break, say why, and offer an alternative), "
                + "**answer** (when someone has challenged your part, face it head on: accept and revise, or explain why it stays as it is), "
                + "**or judge for yourself**. "
                + "Picking holes in each other's work and grinding the plan down until it can actually be built is where the value of the discussion lies.\n");

        sys.append("5. Your readers are **the other roles in the group**, not the user - the user sees only your questions and the final conclusions. "
                + "So put your professional judgment down fully and concretely: the reasoning, the numbers, the boundary conditions, so that others can check it and argue with it.\n");

        sys.append("6. 【Your situation】This is a **multi-role discussion group**: you are working the problem over with the other expert roles, "
                + "and what you say goes into the shared context for them to read, challenge and quote. "
                + "That makes lines like 「我先查一下」「让我核对一下」「接下来我要写…」 - filler announcing what you are about to do - "
                + "useful to your own reasoning but empty of information for everyone else. Whether they belong in your speech is **your call**; "
                + "keeping them in the 思考： section (which only you ever see) is one option.\n");

                sys.append("   - While the group still has unanswered questions you may confirm first and decide later whether to change your mind - "
                        + "having confirmed, you still see everything that happens next (including the user's answers to those questions). "
                        + "If it bears on your part, speak again (speaking counts as withdrawing the confirmation); if not, leave it be.\n");

                if (minorityPhase) {
                    sys.append("\n【Current phase: the minority carries on】\n");
                    sys.append("More than half of the group has confirmed the current plan and steps out from this round on (everything they concluded stays in your context, ready to quote at any time). "
                            + "Now **only you and the few others who have not confirmed** keep discussing: get the part you own properly said, and confirm with confirm_convergence once you can stand behind the plan as a whole.\n");
                    sys.append("   - Do not cave in a hurry just because everyone else has confirmed: if your part is not clearly stated yet, keep stating it.\n");
                    sys.append("   - Nor should you repeat conclusions others have already settled. Bring your own piece, and any objection you have to theirs.\n");

                    if (pullableNames != null && !pullableNames.trim().isEmpty()) {
                        sys.append("   - If you think **some member who has confirmed and stepped out must come back** - because the piece you are stuck on is his, "
                                + "or because his conclusion now conflicts with where the discussion has arrived - **pull him back with the pull_expert tool**. "
                                + "Calling his name in your speech does nothing (he will never hear it). The tool description lists who can be pulled.\n");
                        sys.append("   - Someone pulled back joins the discussion, and he may pull others in turn.\n");
                    } else {
                        sys.append("   - Everyone who has confirmed is already here (there is nobody left to pull back), so settle the conclusions with the people in the room.\n");
                    }
                }
        if (canSearch && searchToolHandler != null) {

            sys.append("7. When you need facts, figures or current information, the networked tools are there to check with. "
                    + "For numbers, policy, prices and platform rules, verifying beats inventing it from memory; "
                    + "the only thing to avoid is searching the same thing twice.\n");
        }

        sys.append("\n【Tool cheat sheet】Everything you can use is listed below (come back to this list whenever you are unsure how something works):\n");
        if (canSearch && searchToolHandler != null) {
            sys.append("· web_search - search the web for current facts (news / live data / policy / prices). "
                    + "Parameters: query (keywords, terse and free of conversational filler), freshness (optional, limits the time range, e.g. d7/m1/y1).\n");
            sys.append("· fetch_url - pull the body text of one page (advertising and navigation already stripped). "
                    + "Parameters: url (a full http/https link). Fetch the single most relevant page only when the search snippets are not enough to judge by.\n");
        }
        if (goalBudgetTokens > 0) {
            sys.append("· ask_user - put one question to the user that **needs his decision** (choosing a direction, setting a boundary, confirming budget / qualifications / platform). "
                    + "Parameters: question (the full question, readable by someone who has not seen the discussion), options (optional, 2 to 5 of them separated by vertical bars), "
                    + "multi (optional, whether several options may be chosen). **One question per call**. "
                    + "⚠️ This is the only way to ask.\n"
                    + "   **A question exists only once this tool has been called.** Writing 「我要提一个问题」「这个问题需要用户拍板」"
                    + "「我提了一题」 in your speech does **not** become a question - the system recognises tool calls alone, and your peers will read those words as "
                    + "\"he has asked, we are waiting for the answer\", leaving the whole group idle for nothing (this really happened: 33 roles burned an entire round waiting and nobody moved). "
                    + "If you mean to ask, call the tool; if you are not calling it, do not write that sort of line.\n");

            sys.append("· confirm_convergence - declare that you stand behind the current plan with no objection left (this is one of the criteria convergence is measured by). "
                    + "It holds across rounds, so there is no need to repeat it; raising new substance afterwards counts as withdrawing it, and you confirm again. "
                    + "This is the only way to confirm - writing 「【最终确认】」 in your speech is not recognised by the system "
                    + "(and your peers may well take it for you having already declared yourself).\n");
            sys.append("· pass_round - sit this round out. Use it when you have no new substance to add; "
                    + "the optional reason takes one line on why (it goes only into your own reasoning record - the other roles never see it). "
                    + "This is the only way to decline to speak - writing 「不发言」 in your speech goes out to the whole group as your formal contribution.\n");
            sys.append("· read_questions - consult the **question ledger** (the authoritative record): every question genuinely submitted in this group, "
                    + "with its status (待答 / 已回答). When you are not sure whether anything is still waiting for an answer, check it here "
                    + "rather than guessing from what other people's speeches say. The 【提问公告栏】 at the top of your context comes from the same source.\n");

            sys.append("· search_user_archive / fetch_user_archive - look things up in the **user archive**: "
                    + "the requirements the user raised in other group chats before, and the questions he answered in those groups. "
                    + "Such answers are a record of **some past moment** - reference only, not present fact (situations move).\n"
                    + "  Two steps: search_user_archive first for the list of requirements (keyword narrows it) → "
                    + "then fetch_user_archive with a demand_id to pull the Q&A into your context (pass keyword again to take only the entries you want).\n");
            sys.append("· remember_research - **compress what you have found into one set of conclusions**, replacing the raw text in your material "
                    + "(it overwrites the previous compressed conclusions). It reaches only your material (the page bodies you searched or fetched yourself); "
                    + "it cannot touch your speeches, other people's speeches or the discussion record. "
                    + "How to use it: pass content as a single rewrite of \"the conclusions you already had + the new material\", "
                    + "keeping the conclusions, the key figures and the sources while dropping the long raw text. "
                    + "The compressed conclusions belong to your material exactly as the raw text did, and are injected every round the same way, "
                    + "so compressing the raw text away loses nothing.\n");
            sys.append("· search_material_pool / fetch_material_pool - look things up in the **shared material pool**: "
                    + "whatever any role in the group had compressed away or rotated out ends up there, and you can search it and take back the parts you need.\n"
                    + "  How: search_material_pool first to see what is there (keyword optional) → "
                    + "then fetch_material_pool with a pool_id for the body text (it may come in pieces).\n");
            sys.append("· report_to_user - one-way feedback to the user (a bug / something wrong / an improvement / a capability you want). "
                    + "**It does not pause the discussion.** Parameters: category (bug|issue|suggestion|tool|order), title, detail, context (optional). "
                    + "⚠️ When you need the user to answer something, use ask_user, not this.\n");
        }
        sys.append("(The tools are there in every round and are never taken away half-way. Once you have found enough, stop and give your conclusion.)\n");

        if (lim.toolMaxIter > 0) {
            sys.append("(This round you may call tools for at most ").append(lim.toolMaxIter)
                    .append(" rounds. Once you have found enough, stop and give your conclusion - you do not have to use them all up. "
                            + "And when your judgment says something still needs checking, go on checking without hesitation.)\n");
        }

        {
            long digestTok = Math.max(0L, memStoreUsedTok);
            long totalCap = TOOLS_DETAIL_TOKEN_CAP;
            long rawTok = Math.max(0L, toolMemUsedTok);
            long totalUsed = rawTok + digestTok;
            long remain = Math.max(0L, totalCap - totalUsed);
            sys.append("(Your material: the raw text you searched or fetched, plus the conclusions you compressed it into with remember_research - "
                    + "both count against this one allowance and are injected together with your context. The allowance is "
                    + totalCap / 10000).append(" 万 token (counted in tokens, Chinese and English treated alike");

            long chineseChars = Math.round(totalCap / 1.5);
            long asciiChars = totalCap * 4L;
            sys.append("; that is roughly ").append(fmtWan(chineseChars)).append(" Chinese characters or ")
                    .append(fmtWan(asciiChars)).append(" English characters, 万 being ten thousand); you are using ").append(totalUsed)
                    .append(" right now (raw text ").append(rawTok).append(" + conclusions ").append(digestTok)
                    .append("), leaving ").append(remain).append(". "
                    + "Once the allowance is used up the oldest raw text stops being injected (the cut is marked where it happens), "
                    + "while every call record and link is kept for good; material that is moved out goes into the shared pool, "
                    + "and search_material_pool / fetch_material_pool will get it back. "
                    + "Compressing, replacing and removing are all your judgment - what stays in this material is yours to decide.)\n");
        }
        sys.append("8. 【Pointing at a message】Every group message carries a number #N. Only when an earlier message is **important enough that the user should go back and read it** "
                + "should you write 「回看 #N」 in your speech (the system then gives the user a clickable jump straight to it); an ordinary mention needs no marker, or the transcript fills up with them.\n");
        sys.append("\n【Output format】Output strictly in the two sections below, and nothing else:\n");
        sys.append("思考：<your complete reasoning for this round. Make it plain which angle you intend to take, "
                + "why that angle is worth taking, which concrete conclusions you mean to give, and whether any fact needs verifying first. "
                + "Write out how you actually reason - not a single concluding sentence>\n");

        sys.append("发言：<what you want to say. When you have no new substance to add this round, "
                + "**call the pass_round tool** to sit it out (do not write 「不发言」 in the text - that would be taken as your formal speech)>\n");

        if (goalBudgetTokens > 0) {
            sys.append("\n【Convergence rules】This group sets no cap on the number of rounds: "
                    + "it runs until **every role taking part has confirmed that the final plan is sound**.\n");
            sys.append("A. Budget awareness: **this is your own context budget - not a bill, and not one pool shared by the whole group.**\n")
                    .append("   This group's ceiling is about ").append(goalBudgetTokens / 10000)
                    .append(" 万 token; what you read in this time comes in two parts:\n")
                    .append("   · **The shared discussion**: ").append(Math.max(0, goalUsedTokens) / 10000)
                    .append(" 万 token - the sum of every public speech in the group, "
                            + "**the very same copy showing the very same number to every role**; it grows with each new speech;\n");
            if (ownContextTokens > 0) {
                long remain = Math.max(0L, (long) goalBudgetTokens - ownContextTokens);
                sys.append("   · **Your own part**: ").append(ownContextTokens / 1000)
                        .append("k token - yours alone: your persona prompt plus the material you searched or fetched. Nobody else has this part.\n")
                        .append("   → **So you are in fact reading in about ").append(ownContextTokens / 1000)
                        .append("k token this time (= shared + your own), with roughly ").append(remain / 1000)
                        .append("k token to spare before the ceiling.**\n");
            } else {
                sys.append("   · **Your own part** (persona + the material you found) has not been measured this round.\n");
            }
            sys.append("   ⚠️ Three misunderstandings worth keeping apart:\n")
                    .append("     ① these numbers are **how much space the context takes up** (how much sits in the window), "
                            + "**not token consumption or cost**;\n")
                    .append("     ② the shared-discussion number **belongs to the whole group together** - it is not something you alone spent; "
                            + "your own occupancy = that number + your own part;\n")
                    .append("     ③ the budget ceiling is **one per role**, not a single pool the group shares.\n")
                    .append("   Which means: **when the budget gets tight, wrapping up comes first** - never let it fill, "
                            + "because a full window truncates content and throws the discussion away.\n");

            long judgeTok = ownContextTokens > 0 ? ownContextTokens : Math.max(0, goalUsedTokens);
            if (judgeTok * 100 >= (long) goalBudgetTokens * 95) {
                sys.append("   ⚠️ What you are reading in this time is already past 95% of the ceiling (close to full), so **this round you must wrap up**: "
                        + "give the final conclusion for the part you own and confirm it with confirm_convergence. Open nothing new.\n");
            }
            sys.append("B. Judging convergence: when you consider the plan complete with nothing left in dispute, call the **confirm_convergence** tool "
                    + "to declare that you stand behind the current plan (one of the criteria by which convergence is judged).\n"
                    + "   - A confirmation **holds across rounds**: once called it need not be repeated every round, and you may simply not speak. "
                    + "But if you put forward new substance afterwards, that counts as withdrawing it and you must call it again. "
                    + "And once the user answers the question sheet, everybody's earlier confirmations lapse automatically (new information may change the plan) - look at it afresh, then confirm.\n"
                    + "   - If you have not confirmed yet, do it now (a confirmation may go out on its own, or sit inside the same speech as your conclusions).\n"
                    + "   - If your role genuinely has nothing to deliver on this topic, write 「本角色无需交付，理由：…」 in your speech and then confirm.\n"
                    + "   - The discussion ends for real only when **every role taking part has confirmed the current plan**. "
                    + "The 【当前进度】 in your context tells you who has not.\n");
            sys.append("C. What a conclusion must do:\n"
                    + "   - It is the **final conclusion** for the part you own - no loose ends such as 「待讨论」or 「待定」. Anything that truly needs the user's decision goes out through the ask_user tool;\n"
                    + "   - **say which speech each point rests on**, in the form 「（见 #12）」 - "
                    + "every message in the group carries a sequence number #N, and the user does go back over the key conclusions, "
                    + "so when one deserves his attention in particular, flag it explicitly with 「建议回看 #N」;\n"
                    + "   - break it into points and sections with a clear shape; over-long beats leaving something out.\n");
            sys.append("D. When something has to be confirmed by the user (do not take turns asking one by one):\n"
                    + "   ⚠️ **Keep two things apart here:**\n"
                    + "     ① **The discussion itself is written for the other experts, not for the user.** "
                    + "Its value lies precisely in your picking holes in each other, filling each other's gaps and grinding the plan down until it can be built - "
                    + "**so this part must stay as thorough, concrete and detailed as ever**, and must never be written short because \"the user may not read it\". "
                    + "The reverse is true: exactly because he does not read the discussion, the plan has to be made solid inside it, "
                    + "since what finally reaches him is the discussion's **product** (that plan), not the process that made it.\n"
                    + "     ② **With one exception: anything needing the user's decision can only be asked through the ask_user tool.** "
                    + "He will not read the discussion message by message (it comes too fast and too thick to follow), "
                    + "so everything that needs him to decide (choosing a direction, setting a boundary, confirming budget or qualifications, picking a platform...) "
                    + "cannot be left for him to spot in the discussion, and cannot be written only there. "
                    + "**You must call ask_user**, or he will never know you are waiting on him and the discussion will spin in place.\n"
                    + "   In one line: **keep the discussion as deep and thorough as ever; anything for the user to decide goes through ask_user.**\n"
                    + "   - Ask only the questions whose answer would change where the plan goes; "
                    + "do not trouble him to confirm something you can already infer from the context;\n"
                    + "   - **The only way to ask is to call the ask_user tool**: one question per call, "
                    + "the question parameter holding the full question (readable without the discussion), and options filled in whenever choices can be offered (separated by vertical bars);\n"
                    + "   - ⚠️ **Never write the words 「【提问】」 in your speech** - not when asking, not when restating the group rules, "
                    + "not when explaining how you mean to ask: the system **recognises the ask_user tool alone**, "
                    + "so those words in the text never turn into a question and are read as ordinary discussion. "
                    + "The same goes for 「【单独提问】」.\n"
                    + "   - ⚠️ **Do not ask again what another role has already asked.** Questions and answers are both in the group transcript and all of it is visible to you: "
                    + "what others asked is inside their speeches, and once the user answers, his answer appears in the transcript as a 「【我对提问的回复】」 message "
                    + "that **every role can see from the next round on**. So look through the transcript first to see whether your question has already been put - "
                    + "asked but not yet answered: do not ask it again, simply wait for the reply (you will see it when it arrives; there is no need to ask twice out of fear of missing the answer); "
                    + "asked and answered: use that answer, and never ask again;\n"

                    + "   - If the user explicitly **skips** a question (writing 「这题我暂时跳过」 in his reply), "
                    + "he does not want to decide it right now: **do not press him on the same thing straight away**. "
                    + "But if your judgment is that the question is **genuinely critical** to the conclusion (without it your part of the plan cannot be settled), "
                    + "you may ask once more in a later round **from a narrower angle** (word it smaller and easier to answer - with a few definite options, say);\n"
                    + "   - When you want to hurry the user along on certain questions (perhaps you are summarising or wrapping up), **cite them by number in your speech** "
                    + "(「仍在等 #12、#15 的答案」). Do not paste other people's questions out in full again - "
                    + "a re-paste is counted by the system as a fresh question from you, and the user ends up seeing the same question twice and answering it twice;\n"
                    + "   - Questions are **gathered into a single question sheet** and handed to the user together, so **there is no hurry** - "
                    + "say what you want to ask, clearly, in one pass;\n"
                    + "   - **Whenever options can be given, give options** (the user's own requirement: picking is far easier than typing out an answer to every question);\n"
                    + "   - **Let the detail in your options match the nature of the question**: "
                    + "the options by themselves must let the user **decide without reading the discussion** (「按月 29.9 元 / 按次 0.5 元」 "
                    + "is worth far more than 「方案A / 方案B」); "
                    + "and where an option only makes sense with some explaining, add the shortest possible note in brackets right after it;\n"
                    + "   - Options must be **concrete, workable and different ways of doing it** - "
                    + "never a bare “是/否” that carries no information, and never two options amounting to the same thing;\n"
                    + "   - For a question where several answers can stand together (“which platforms should it go on”, “which features are needed”) "
                    + "**write `选项(多选)：`** rather than forcing the user to pick just one;\n"
                    + "     Note: the user may always ignore your options and type his own answer, so an incomplete list does no harm - "
                    + "but that is no reason to leave options out. For a genuinely open question that cannot be listed (“你的预算多少”), write no options line at all;\n"
                    + "   - If you suspect others may have questions too, add 「（其他人如有疑问请一并提出）」 after yours, "
                    + "so your peers round the list out in the same pass and the user is not disturbed twice over.\n"
                    + "     (There is no such thing as an \"urgent question\": however urgent, it goes through the same ask_user tool, "
                    + "and at the end of the round the system gathers everybody's questions into the sheet for the user.)\n"
                    + "E. 【Feedback to the user (the report_to_user tool) - non-blocking, available at any time】\n"
                    + "   Besides questions that need the user's decision, you have one **one-way feedback** channel: report_to_user.\n"
                    + "   · **It does not pause the discussion**; it only carries your words in front of him (he reads them under 「💬 反馈」). "
                    + "So do not use it to ask what should be done - that is what ask_user is for.\n"
                    + "   · When it is the right tool:\n"
                    + "     ① you notice **something plainly wrong with the system or with the discussion itself** (a \"待答提问\" in the context that is obviously no question at all, "
                    + "two figures contradicting each other, a mechanism visibly not working, someone repeating the same pointless thing over and over);\n"
                    + "     ② you can see an **improvement** that would make this system work better;\n"
                    + "     ③ you are **missing a tool or capability** and the work cannot go on without it (say what you need and what you would use it for).\n"
                    + "   · category, one of four: bug (a definite fault) / issue (something is off but you are not certain) / suggestion (an improvement) / tool (a capability you want).\n"
                    + "   · ⚠️ Feedback must be **specific**: what happened, where (cite it with #N), what it affects, and how you suggest fixing it - "
                    + "written so the user understands it without reading the discussion. Never report the same thing twice.\n");
        }

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", sys.toString()));
        messages.put(new JSONObject().put("role", "user").put("content",
                "【当前群聊记录】\n" + (roomContext == null ? "" : roomContext)
                        + "\n\nGive your 思考 and 发言 in the required format (or choose not to speak)."));

        String toolLog = "";
        boolean viaNewSearch = false;

        int toolFailCount = 0;

        StringBuilder reasoningAll = new StringBuilder();

        final java.util.List<GmQuestion> toolQuestions = new java.util.ArrayList<>();

        final java.util.List<String> toolPulled = new java.util.ArrayList<>();

        final java.util.Map<String, String> pullReasons = new java.util.HashMap<>();

        final java.util.List<GmFeedbackItem> toolFeedback = new java.util.ArrayList<>();

        final boolean[] toolConfirmed = new boolean[]{false};

        final boolean[] toolPassedRound = new boolean[]{false};
        final String[] toolPassReason = new String[]{""};

        final int maxIter = Math.max(1, Math.min(100, lim.toolMaxIter));

        final int fetchMax = 200;
        int fetchToolCount = 0;

        int fetchInThisRound = 0;
        boolean roundHadOtherTool = false;

        final RepeatGuard repeatGuard = new RepeatGuard(guardParams);

        boolean repeatTripped = false;

        final java.util.concurrent.atomic.AtomicBoolean repeatAbort =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        boolean toolsUnsupportedThisIter = false;

        ReasoningNegotiator.Style gcStyle = null;

        int gcValueIndex = 0;
        int gcAttempt = 0;

        int accPromptTokens = 0;
        int accCompletionTokens = 0;
        int accTotalTokens = 0;

        boolean sawUsage = false;

        boolean usageWanted = true;

        int lastPromptTokens = 0;

        long accGenMs = 0L;

        StringBuilder allSpeech = new StringBuilder();
        StringBuilder allThought = new StringBuilder();
        for (int iter = 0; iter < maxIter; iter++) {
            if ((stopFlag != null && stopFlag.get()) || repeatAbort.get()) break;

            toolsUnsupportedThisIter = false;

            long reqStartedAt = -1L;
            long reqFirstTokenAt = -1L;
            long reqLastTokenAt = -1L;
            JSONObject body = new JSONObject();
            body.put("model", isBlank(model) ? "deepseek-v4-flash-0731" : model.trim());
            body.put("messages", messages);
            body.put("stream", true);
            body.put("max_tokens", maxOutputTokens > 0 ? maxOutputTokens : 110000);
            body.put("temperature", 0.7);

            if (usageWanted) {
                JSONObject streamOpts = new JSONObject();
                try { streamOpts.put("include_usage", true); } catch (Exception ignored) { }
                body.put("stream_options", streamOpts);
            }

            ReasoningNegotiator.Applied gcApplied =
                    ReasoningNegotiator.applyReasoning(body, base, model, thinkLevel, gcAttempt);
            gcStyle = gcApplied == null ? null : gcApplied.style;
            gcValueIndex = gcApplied == null ? 0 : gcApplied.valueIndex;

            boolean wantAskUser = goalBudgetTokens > 0;

            boolean wantPull = pullableNames != null && !pullableNames.trim().isEmpty();
            boolean wantWeb = canSearch && searchToolHandler != null;

            boolean sendTools = (wantAskUser || wantWeb || wantPull) && !toolsUnsupportedThisIter;
            if (sendTools) {
                JSONArray toolArr = new JSONArray();
                if (wantAskUser) {
                    toolArr.put(buildAskUserTool());

                    toolArr.put(buildReportTool());

                    JSONObject rq = buildReadQuestionsTool();
                    if (rq != null) toolArr.put(rq);

                    JSONObject sua = buildSearchUserArchiveTool();
                    if (sua != null) toolArr.put(sua);
                    JSONObject fua = buildFetchUserArchiveTool();
                    if (fua != null) toolArr.put(fua);

                    JSONObject rmt = buildRememberResearchTool();
                    if (rmt != null) toolArr.put(rmt);

                    JSONObject smp = buildSearchMaterialPoolTool();
                    if (smp != null) toolArr.put(smp);
                    JSONObject fmp = buildFetchMaterialPoolTool();
                    if (fmp != null) toolArr.put(fmp);

                    JSONObject cc = buildConfirmConvergenceTool();
                    if (cc != null) toolArr.put(cc);

                    JSONObject pr = buildPassRoundTool();
                    if (pr != null) toolArr.put(pr);
                }
                if (wantWeb) {
                    JSONArray web = buildWebTools();
                    for (int ti = 0; ti < web.length(); ti++) toolArr.put(web.get(ti));
                }
                if (wantPull) {
                    JSONObject pt = buildPullExpertTool(pullableNames);
                    if (pt != null) toolArr.put(pt);
                }
                body.put("tools", toolArr);
                body.put("tool_choice", "auto");
            }

            reqStartedAt = System.currentTimeMillis();
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            StringBuilder contentBuf = new StringBuilder();
            java.util.TreeMap<Integer, JSONObject> toolAcc = new java.util.TreeMap<>();

            int reqPromptTokens = 0;
            int reqCompletionTokens = 0;
            int reqTotalTokens = 0;
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(60000);
                connection.setReadTimeout(180000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + (isBlank(apiKey) ? "" : apiKey.trim()));
                OutputStream output = connection.getOutputStream();
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();

                int code = connection.getResponseCode();
                if (code >= 400) {
                    String err = readAll(connection.getErrorStream());

                    if (usageWanted && code == 400 && !isRateLimited(code, err)) {
                        android.util.Log.w("VC_GROUP", "中转不接受 stream_options，摘掉重试（用量回退为估算）: HTTP " + code);
                        usageWanted = false;
                        iter--;
                        connection.disconnect();
                        continue;
                    }

                    if (sendTools && !isRateLimited(code, err) && isToolsRejection(code, err)) {
                        android.util.Log.w("VC_GROUP", "端点拒绝 tools 参数，本轮降级为纯对话: HTTP " + code);
                        toolsUnsupportedThisIter = true;
                        iter--;
                        connection.disconnect();
                        continue;
                    }

                    int cooldown = parseCooldownSeconds(err);
                    if (cooldown > 0) {
                        throw new RuntimeException("HTTP " + code + " @@CD:" + cooldown + " " + safeSnippet(err));
                    }
                    throw new RuntimeException("HTTP " + code + " " + safeSnippet(err));
                }

                java.io.BufferedReader sseReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                String sseLine;
                while ((sseLine = sseReader.readLine()) != null) {
                    if ((stopFlag != null && stopFlag.get()) || repeatAbort.get()) break;
                    sseLine = sseLine.trim();
                    if (sseLine.isEmpty() || !sseLine.startsWith("data:")) continue;
                    String payload = sseLine.substring(5).trim();
                    if ("[DONE]".equals(payload)) break;
                    JSONObject chunk;
                    try { chunk = new JSONObject(payload); } catch (Exception ignore) { continue; }

                    if (chunk.optJSONObject("error") != null) {
                        JSONObject eo = chunk.optJSONObject("error");
                        String em = safeOptString(eo, "message");
                        if (em.isEmpty()) em = eo.toString();
                        throw new RuntimeException("上游返回错误：" + em);
                    }
                    JSONArray chs = chunk.optJSONArray("choices");
                    if (chs != null && chs.length() > 0) {
                        JSONObject delta = chs.getJSONObject(0).optJSONObject("delta");
                        if (delta != null) {

                            String rd = safeOptString(delta, "reasoning_content");
                            if (rd.isEmpty()) rd = safeOptString(delta, "reasoning");
                            if (!rd.isEmpty()) {
                                reasoningAll.append(rd);
                                if (listener != null) listener.onReasoningDelta(rd);
                            }
                            String cd = safeOptString(delta, "content");
                            if (!cd.isEmpty()) {
                                contentBuf.append(cd);
                                if (listener != null) listener.onContentDelta(cd);
                            }

                            if (repeatGuard != null && !repeatGuard.isTripped()) {
                                boolean justTripped = false;
                                if (!rd.isEmpty()) justTripped = repeatGuard.append(rd);
                                if (!justTripped && !cd.isEmpty()) justTripped = repeatGuard.append(cd);
                                if (justTripped) {

                                    repeatTripped = true;
                                    repeatAbort.set(true);
                                    android.util.Log.w("VC_GROUP",
                                            "检测到退化重复：" + repeatGuard.hitCount() + " 次 × "
                                                    + repeatGuard.hitSample().replace("\n", " "));
                                }
                            }

                            if (!rd.isEmpty() || !cd.isEmpty()) {
                                long nowMs = System.currentTimeMillis();
                                if (reqFirstTokenAt < 0L) reqFirstTokenAt = nowMs;
                                reqLastTokenAt = nowMs;
                            }
                            JSONArray tcs = delta.optJSONArray("tool_calls");
                            if (tcs != null) {
                                for (int i = 0; i < tcs.length(); i++) {
                                    JSONObject tc = tcs.getJSONObject(i);
                                    int idx = tc.optInt("index", i);
                                    JSONObject acc = toolAcc.get(idx);
                                    if (acc == null) { acc = new JSONObject(); toolAcc.put(idx, acc); }
                                    String id = safeOptString(tc, "id");
                                    if (!id.isEmpty()) acc.put("id", id);
                                    JSONObject fn = tc.optJSONObject("function");
                                    if (fn != null) {
                                        String nm = safeOptString(fn, "name");
                                        if (!nm.isEmpty()) acc.put("name", nm);
                                        String args = safeOptString(fn, "arguments");
                                        if (!args.isEmpty()) acc.put("args", acc.optString("args", "") + args);
                                    }
                                }
                            }
                        }
                    }

                    JSONObject usageChunk = chunk.optJSONObject("usage");
                    if (usageChunk != null) {
                        int pt = usageChunk.optInt("prompt_tokens", 0);
                        int ct = usageChunk.optInt("completion_tokens", 0);
                        int tt = usageChunk.optInt("total_tokens", 0);
                        if (pt > 0) reqPromptTokens = pt;
                        if (ct > 0) reqCompletionTokens = ct;
                        if (tt > 0) {
                            reqTotalTokens = tt;
                        } else if (pt > 0 || ct > 0) {
                            reqTotalTokens = pt + ct;
                        }
                        if (pt > 0 || ct > 0 || tt > 0) sawUsage = true;
                    }
                }
            } finally {
                connection.disconnect();
            }

            accPromptTokens += reqPromptTokens;
            accCompletionTokens += reqCompletionTokens;
            accTotalTokens += reqTotalTokens;

            if (reqPromptTokens > 0) lastPromptTokens = reqPromptTokens;

            if (reqStartedAt > 0L && reqLastTokenAt > reqStartedAt) {
                accGenMs += (reqLastTokenAt - reqStartedAt);
            }

            final String gcReasoning = reasoningAll.toString().trim();
            if (contentBuf.length() == 0 && toolAcc.isEmpty()
                    && ReasoningNegotiator.shouldRetry(thinkLevel, gcReasoning.length(),
                            gcAttempt, base, model)) {
                ReasoningNegotiator.Applied next = ReasoningNegotiator.applyReasoning(
                        body, base, model, thinkLevel, gcAttempt + 1);
                if (next != null) {
                    android.util.Log.i("VC_REASON",
                            "群聊思考为空且无正文 → 换参数风格重试: " + next.style.id + "（第 " + (gcAttempt + 2) + " 种）");
                    gcAttempt++;
                    gcStyle = next.style;
                    gcValueIndex = next.valueIndex;
                    reasoningAll.setLength(0);
                    continue;
                }
            }
            if (contentBuf.length() > 0 && gcReasoning.isEmpty()
                    && thinkLevel >= ReasoningNegotiator.LEVEL_LOW) {
                android.util.Log.i("VC_REASON",
                        "群聊本轮有正文但无思考 → 不动界面内容，改为后台探测正确参数");

                probeReasoningAsync(base, apiKey, model, thinkLevel, true);
            }

            if (gcReasoning.length() > 0 && gcStyle != null) {
                ReasoningNegotiator.rememberSuccess(base, model, gcStyle, gcValueIndex);
            }

            if (sendTools && !toolAcc.isEmpty()) {
                JSONArray toolCalls = new JSONArray();
                for (JSONObject acc : toolAcc.values()) {
                    JSONObject call = new JSONObject();
                    call.put("id", acc.optString("id", "call_" + toolCalls.length()));
                    call.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", acc.optString("name", ""));
                    fn.put("arguments", acc.optString("args", "{}"));
                    call.put("function", fn);
                    toolCalls.put(call);
                }
                JSONObject assistantMsg = new JSONObject();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", contentBuf.toString());
                assistantMsg.put("tool_calls", toolCalls);
                messages.put(assistantMsg);
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject call = toolCalls.getJSONObject(i);
                    String id = call.optString("id", "call_" + i);
                    JSONObject fn = call.optJSONObject("function");
                    final String name = fn == null ? "" : fn.optString("name", "");
                    String argStr = fn == null ? "{}" : fn.optString("arguments", "{}");
                    JSONObject args = parseToolArgs(argStr);
                    final JSONObject fargs = args;
                    if (listener != null) listener.onToolStart(name, argSummary(name, args));

                    if ("fetch_url".equals(name)) { ++fetchToolCount; ++fetchInThisRound; }

                    else if ("search_user_archive".equals(name) || "fetch_user_archive".equals(name)) {
                        ++fetchToolCount; ++fetchInThisRound;
                    }
                    else { roundHadOtherTool = true; }
                    String result;
                    if (args.has(ARGS_BROKEN_KEY)) {

                        result = argsBrokenJson(args);
                    } else if ("pull_expert".equals(name)) {

                        String pName = stripAsides(safeOptString(args, "name")).trim();
                        String pReason = stripAsides(safeOptString(args, "reason")).trim();
                        if (pName.isEmpty()) {
                            result = "{\"success\":false,\"error\":\"name 参数不能为空，请填要拉回的专家名字\"}";
                        } else if (!nameInList(pullableNames, pName)) {
                            result = "{\"success\":false,\"error\":\"名单里没有叫「" + pName
                                    + "」的专家。只能用工具描述里列出的名字（他可能已经回来了，或本来就在讨论）\"}";
                        } else if (toolPulled.size() >= 5) {
                            result = "{\"success\":false,\"error\":\"本次发言拉人过多（上限 5 位），只拉真正需要的\"}";
                        } else if (toolPulled.contains(pName)) {
                            result = "{\"success\":true,\"message\":\"" + pName + " 已经在你要拉的名单里了\"}";
                        } else {
                            toolPulled.add(pName);
                            if (!pReason.isEmpty()) {
                                pullReasons.put(pName, pReason);
                            }
                            result = "{\"success\":true,\"message\":\"已安排把 " + pName
                                    + " 拉回讨论（从下一轮开始他会重新参与）。请继续按格式输出你的正式发言。\"}";
                        }
                    } else if ("ask_user".equals(name)) {

                        String qText = stripAsides(safeOptString(args, "question"));
                        String opts = safeOptString(args, "options");
                        boolean multi = args.optBoolean("multi", false);
                        if (qText.isEmpty()) {
                            result = "{\"success\":false,\"error\":\"question 参数不能为空，请填写要问用户的完整问题文字\"}";
                        } else if (toolQuestions.size() >= 8) {
                            result = "{\"success\":false,\"error\":\"本轮提问过多（上限 8 条），只保留真正需要用户拍板的问题\"}";
                        } else {
                            GmQuestion gq = new GmQuestion(qText);
                            if (!opts.isEmpty()) applyOptions(gq, multi, opts);
                            toolQuestions.add(gq);
                            result = "{\"success\":true,\"message\":\"问题已记录，将汇总进询问稿统一问用户。发言里不用再写「【提问】」字样，请继续按格式输出正式发言。\"}";
                        }
                    } else if ("confirm_convergence".equals(name)) {

                        String rr;
                        try {
                            final AiClient.ToolHandler h = searchToolHandler;
                            rr = (h == null) ? toolErrorJson("当前环境不支持收敛确认（没有可用的群工具通道）")
                                    : h.handle(name, fargs);
                        } catch (Exception e) {
                            rr = toolErrorJson(e.getMessage());
                        }
                        if (rr == null || rr.trim().isEmpty()) rr = toolErrorJson("收敛确认未返回结果");
                        boolean okConf = false;
                        try { okConf = new JSONObject(rr).optBoolean("success", false); } catch (Exception ignored) { }
                        if (okConf) toolConfirmed[0] = true;
                        result = rr;
                    } else if ("pass_round".equals(name)) {

                        toolPassedRound[0] = true;
                        String prReason = stripAsides(safeOptString(args, "reason")).trim();
                        if (!prReason.isEmpty()) toolPassReason[0] = prReason;
                        result = "{\"success\":true,\"message\":\"已记为沉默轮次（其他角色看不到这条）。"
                                + "如果你其实有实质内容要补充，现在就在发言里写出来并不要调用本工具。\"}";
                    } else if ("report_to_user".equals(name)) {

                        String cat = safeOptString(args, "category").trim().toLowerCase(java.util.Locale.ROOT);

                        if (cat.isEmpty()) cat = "issue";
                        if (cat.contains("bug") || cat.contains("故障") || cat.contains("错误")) cat = "bug";
                        else if (cat.contains("tool") || cat.contains("工具") || cat.contains("功能")) cat = "tool";
                        else if (cat.contains("suggest") || cat.contains("建议") || cat.contains("改进")) cat = "suggestion";
                        else if (cat.contains("order") || cat.contains("顺序") || cat.contains("位置")) cat = "order";
                        else if (cat.contains("issue") || cat.contains("异常") || cat.contains("问题")) cat = "issue";
                        else cat = "issue";
                        String title = stripAsides(safeOptString(args, "title")).trim();
                        String detail = stripAsides(safeOptString(args, "detail")).trim();
                        String ctxTag = safeOptString(args, "context").trim();
                        if (title.isEmpty() && detail.isEmpty()) {
                            result = "{\"success\":false,\"error\":\"title 与 detail 不能同时为空，请说清你要反馈什么\"}";
                        } else if (toolFeedback.size() >= 5) {
                            result = "{\"success\":false,\"error\":\"本轮反馈过多（上限 5 条），请只报最重要的\"}";
                        } else {
                            if (title.isEmpty()) title = detail.length() > 30 ? detail.substring(0, 30) + "…" : detail;
                            toolFeedback.add(new GmFeedbackItem(cat, title, detail, ctxTag));
                            result = "{\"success\":true,\"message\":\"已记录，用户会在「💬 反馈」里看到。"
                                    + "这**不会暂停讨论**，请继续按格式输出正式发言。\"}";
                        }
                    } else {
                        if ("tokeniq".equals(toolSource(name))) viaNewSearch = true;
                        try {
                            final AiClient.ToolHandler h = searchToolHandler;
                            String rr = h == null ? toolErrorJson("该角色没有配置联网工具")
                                    : withSearchLimit(() -> {
                                        try { return h.handle(name, fargs); }
                                        catch (Exception e) { return toolErrorJson(e.getMessage()); }
                                    }, stopFlag);
                            result = rr == null ? "{\"error\":\"已取消\"}" : rr;
                        } catch (Exception e) {
                            result = toolErrorJson(e.getMessage());
                        }
                    }
                    if (listener != null) listener.onToolEnd(name, resultSummary(name, result));

                    if (result != null && (result.contains("\"error\"") || result.contains("已取消"))
                            && !"ask_user".equals(name) && !"report_to_user".equals(name)
                            && !"read_questions".equals(name)
                            && !"search_user_archive".equals(name)
                            && !"fetch_user_archive".equals(name)
                            && !"remember_research".equals(name)
                            && !"search_material_pool".equals(name)
                            && !"fetch_material_pool".equals(name)

                            && !"confirm_convergence".equals(name)
                            && !"pass_round".equals(name)) {
                        ++toolFailCount;
                    }
                    toolLog = appendToolLog(toolLog, name, argSummary(name, args), resultSummary(name, result),
                            toolSource(name), webDetail(name, result));
                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", id);
                    toolMsg.put("content", result == null ? "" : result);
                    messages.put(toolMsg);
                }

                if (fetchInThisRound > 0 && !roundHadOtherTool && fetchToolCount < fetchMax) {
                    iter--;
                } else if (fetchToolCount >= fetchMax) {
                    messages.put(new JSONObject().put("role", "user")
                            .put("content", "（本轮抓取次数已达上限 " + fetchMax
                                    + " 次，请基于已取回的资料给出结论。）"));
                }
                fetchInThisRound = 0;
                roundHadOtherTool = false;

                {
                    RoundText midRt = parseRoundText(contentBuf.toString().trim());
                    if (midRt.speech != null && !midRt.speech.trim().isEmpty()) {
                        if (allSpeech.length() > 0) allSpeech.append("\n\n");
                        allSpeech.append(midRt.speech.trim());
                    }
                    if (midRt.thought != null && !midRt.thought.trim().isEmpty()) {
                        if (allThought.length() > 0) allThought.append("\n\n");
                        allThought.append(midRt.thought.trim());
                    }
                }
                continue;
            }

            String rawContent = contentBuf.toString().trim();
            RoundText lastRt = parseRoundText(rawContent);
            if (lastRt.speech != null && !lastRt.speech.trim().isEmpty()) {
                if (allSpeech.length() > 0) allSpeech.append("\n\n");
                allSpeech.append(lastRt.speech.trim());
            }
            if (lastRt.thought != null && !lastRt.thought.trim().isEmpty()) {
                if (allThought.length() > 0) allThought.append("\n\n");
                allThought.append(lastRt.thought.trim());
            }
            GmSpeakResult r = new GmSpeakResult();

            r.speech = allSpeech.toString().trim();
            r.thought = allThought.toString().trim();

            String nativeThought = reasoningAll.toString().trim();
            if (!nativeThought.isEmpty()) {
                r.thought = r.thought.isEmpty() ? nativeThought : (nativeThought + "\n" + r.thought);
            }

            r.declaredSilent = r.speech.isEmpty() && !r.thought.isEmpty();

            r.promptTokens = accPromptTokens;
            r.completionTokens = accCompletionTokens;
            r.tokens = accTotalTokens;
            r.contextTokens = lastPromptTokens;
            r.genMs = accGenMs;
            if (!sawUsage) {

                if (r.promptTokens <= 0) {
                    r.promptTokens = estimateTokensText(sys.toString())
                            + estimateTokensText(roomContext == null ? "" : roomContext);
                }
                if (r.completionTokens <= 0) {
                    r.completionTokens = estimateTokensText(
                            (r.speech == null ? "" : r.speech)
                                    + (r.thought == null ? "" : r.thought));
                }
                r.tokens = r.promptTokens + r.completionTokens;
                if (r.contextTokens <= 0) r.contextTokens = r.promptTokens;
            }
            r.toolLog = toolLog;
            r.viaNewSearch = viaNewSearch;

            if (toolConfirmed[0]) r.finalConfirmed = true;

            if (toolPassedRound[0]) {
                r.declaredSilent = true;
                r.speech = "";
                if (!toolPassReason[0].isEmpty()) {

                    r.thought = r.thought.isEmpty() ? toolPassReason[0]
                            : (toolPassReason[0] + "\n" + r.thought);
                }
            }

            r.pulledExperts.clear();
            r.pulledExperts.addAll(toolPulled);
            r.pulledReasons.clear();
            r.pulledReasons.putAll(pullReasons);
            if (!toolQuestions.isEmpty()) {
                r.questions.clear();
                r.questions.addAll(toolQuestions);
                StringBuilder qb = new StringBuilder();
                for (int qi = 0; qi < toolQuestions.size(); qi++) {
                    if (qi > 0) qb.append("\n");
                    if (toolQuestions.size() > 1) qb.append(qi + 1).append(". ");
                    qb.append(toolQuestions.get(qi).text);
                }
                r.question = qb.toString().trim();
                GmQuestion headQ = r.questions.get(0);
                r.questionOptions = new java.util.ArrayList<>(headQ.options);
                r.questionMulti = headQ.multi;
            }

            if (!toolFeedback.isEmpty()) {
                r.feedback.clear();
                r.feedback.addAll(toolFeedback);
            }

            r.repeatAborted = repeatTripped;
            r.toolFailCount = toolFailCount;
            if (repeatTripped) {
                r.repeatSample = repeatGuard.hitSample();
                r.repeatCount = repeatGuard.hitCount();
            }
            return r;
        }

        GmSpeakResult empty = new GmSpeakResult();

        empty.speech = allSpeech.toString().trim();
        if (empty.speech.isEmpty()) {
            empty.thought = "（本轮搜索后未给出发言）";
        } else {

            String accThought = allThought.toString().trim();
            empty.thought = accThought.isEmpty()
                    ? "（本轮工具轮次用尽，未再补充）"
                    : (accThought + "\n\n（本轮工具轮次用尽，未再补充）");
        }

        empty.declaredSilent = empty.speech.isEmpty();

        empty.repeatAborted = repeatTripped;
        empty.toolFailCount = toolFailCount;
        if (repeatTripped) {
            empty.repeatSample = repeatGuard.hitSample();
            empty.repeatCount = repeatGuard.hitCount();
        }

        empty.promptTokens = accPromptTokens;
        empty.completionTokens = accCompletionTokens;
        empty.tokens = accTotalTokens;
        empty.contextTokens = lastPromptTokens;
        empty.genMs = accGenMs;
        empty.toolLog = toolLog;
        empty.viaNewSearch = viaNewSearch;

        if (!toolQuestions.isEmpty()) {
            empty.questions.addAll(toolQuestions);
            StringBuilder qb = new StringBuilder();
            for (int qi = 0; qi < toolQuestions.size(); qi++) {
                if (qi > 0) qb.append("\n");
                qb.append(toolQuestions.get(qi).text);
            }
            empty.question = qb.toString().trim();

            GmQuestion headQ = empty.questions.get(0);
            empty.questionOptions = new java.util.ArrayList<>(headQ.options);
            empty.questionMulti = headQ.multi;
        }

        if (!toolFeedback.isEmpty()) empty.feedback.addAll(toolFeedback);

        empty.pulledExperts.addAll(toolPulled);
        empty.pulledReasons.putAll(pullReasons);
        return empty;
    }

    public static JSONArray buildAdminTools() {
        JSONArray arr = new JSONArray();
        try {

            JSONObject t1 = new JSONObject();
            t1.put("type", "function");
            JSONObject f1 = new JSONObject();
            f1.put("name", "list_experts");
            f1.put("description", "List every expert (role) in this group: name, role summary, current speaking order, provider, model, "
                    + "thinking level, whether they currently participate in the discussion, messages spoken, and tokens used. Call this first to see the current state before reordering anyone or changing models.");
            f1.put("parameters", new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()));
            t1.put("function", f1);
            arr.put(t1);

            JSONObject t2 = new JSONObject();
            t2.put("type", "function");
            JSONObject f2 = new JSONObject();
            f2.put("name", "read_discussion");
            f2.put("description", "Read this group's recent discussion messages (public speech text only — no private thinking, no tool materials). "
                    + "The user asked that the admin \"can also see the discussion content in the group chat\"; use it to judge whether the speaking order is sensible and whether experts are complaining about their positions. "
                    + "By default it returns full message bodies (a single expert message can run to tens of thousands of characters — trimming to just the opening would make you judge content you cannot see); "
                    + "when context cost is a concern, use limit to cap the **number of messages** instead of using maxChars to truncate each one.");
            JSONObject p2 = new JSONObject();
            p2.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "How many recent messages to read. Default 60, max 300 (this is the knob for saving context)."));
            p2.put("seq", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Read only the message numbered #N (cheapest on context)."));
            p2.put("full", new JSONObject().put("type", "boolean")
                    .put("description", "Optional. Defaults to true (full bodies); pass false to truncate by maxChars."));
            p2.put("maxChars", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Truncation cap per message body; takes effect only when full=false (omit = no truncation)."));
            f2.put("parameters", new JSONObject().put("type", "object").put("properties", p2));
            t2.put("function", f2);
            arr.put(t2);

            JSONObject t3 = new JSONObject();
            t3.put("type", "function");
            JSONObject f3 = new JSONObject();
            f3.put("name", "read_plans");
            f3.put("description", "Read the implementation plans this group has exported (title + body summary). The user asked that the admin "
                    + "\"can also see the content inside my plans\".");
            f3.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            t3.put("function", f3);
            arr.put(t3);

            JSONObject t4 = new JSONObject();
            t4.put("type", "function");
            JSONObject f4 = new JSONObject();
            f4.put("name", "read_usage");
            f4.put("description", "Read this group's usage statistics: how many tokens each expert has used and how many messages they have sent, aggregated by model, "
                    + "plus context occupancy. The user asked that the admin \"can also see all the data in the usage statistics\".");
            f4.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            t4.put("function", f4);
            arr.put(t4);

            JSONObject t5 = new JSONObject();
            t5.put("type", "function");
            JSONObject f5 = new JSONObject();
            f5.put("name", "read_feedback");
            f5.put("description", "Read the feedback the group's experts have submitted to you (bugs / anomalies / suggestions / tools they want, including their opinions on the speaking order). "
                    + "The user explicitly asked that experts can report ordering issues to you so you can adjust accordingly.");
            f5.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            t5.put("function", f5);
            arr.put(t5);

            JSONObject t6 = new JSONObject();
            t6.put("type", "function");
            JSONObject f6 = new JSONObject();
            f6.put("name", "propose_order");
            f6.put("description", "Submit a **speaking-order change proposal**; it takes effect only after the user taps 「✔ 允许」(Allow) in the UI. "
                    + "You only list the order — you don't change any data. Pass expert names in order in `order` (use the exact names from list_experts, "
                    + "character for character). List only the experts that need placing; anyone not listed keeps their current position.");
            JSONObject p6 = new JSONObject();
            p6.put("order", new JSONObject().put("type", "string")
                    .put("description", "Expert names in speaking order, separated by commas or newlines"));
            p6.put("summary", new JSONObject().put("type", "string")
                    .put("description", "One sentence explaining why you ordered it this way (shown to the user, within 30 characters)"));
            f6.put("parameters", new JSONObject().put("type", "object").put("properties", p6)
                    .put("required", new JSONArray().put("order")));
            t6.put("function", f6);
            arr.put(t6);

            JSONObject t7 = new JSONObject();
            t7.put("type", "function");
            JSONObject f7 = new JSONObject();
            f7.put("name", "propose_member_config");
            f7.put("description", "Submit an **expert configuration change proposal** (provider / model / thinking level / whether they participate in the discussion); "
                    + "it takes effect only after the user taps 「✔ 允许」(Allow). One proposal can carry multiple changes for multiple experts.");
            JSONObject p7 = new JSONObject();
            p7.put("changes", new JSONObject().put("type", "string")
                    .put("description", "Change list, one per line, format: expertName|field|newValue. "
                            + "Allowed fields: provider (the provider name, see the provider list), "
                            + "model (model name), think (thinking level 0-4: 0=off 1=low 2=medium 3=high 4=highest), "
                            + "active (participates in discussion=1, does not=0). Example:\n"
                            + "需求分析师|model|deepseek-v4-flash-0731\n"
                            + "创意专家|think|4\n"
                            + "法务顾问|active|0"));
            p7.put("summary", new JSONObject().put("type", "string")
                    .put("description", "One sentence explaining why you're making these changes (shown to the user, within 30 characters)"));
            f7.put("parameters", new JSONObject().put("type", "object").put("properties", p7)
                    .put("required", new JSONArray().put("changes")));
            t7.put("function", f7);
            arr.put(t7);

            JSONObject t8 = new JSONObject();
            t8.put("type", "function");
            JSONObject f8 = new JSONObject();
            f8.put("name", "read_role_library");
            f8.put("description", "Read the **role library**. "
                    + "**Calling it with no parameters returns the complete list of every role at once** (name + one-line summary; "
                    + "about 140 roles, all included, not batched or abridged — and it costs very little context). "
                    + "☠️ **Correct workflow: first read the full list with no parameters** → shortlist candidates → "
                    + "use name=<role name> to read a candidate's full prompt text → then decide whom to add. "
                    + "**Do not start by guessing with keyword**: a missed keyword makes you wrongly conclude \"the library has no such role\" "
                    + "when the role may well be sitting in the list — use keyword to narrow down only when the user has explicitly named a category. "
                    + "Custom roles already in this group can also be looked up by name (their prompt is returned along with it).");
            JSONObject p8 = new JSONObject();
            p8.put("keyword", new JSONObject().put("type", "string")
                    .put("description", "Optional. Use it to narrow down only when the user has explicitly named a direction; "
                            + "**never use it in place of reading the full list first** (a wrong guess silently misses roles)."));
            p8.put("name", new JSONObject().put("type", "string")
                    .put("description", "Optional: look up one specific role's **full prompt** (built-in library or this group's custom roles alike). "
                            + "After shortlisting from the full list, use this to see what the role actually does."));
            p8.put("withPrompt", new JSONObject().put("type", "boolean")
                    .put("description", "Optional: whether the list also includes each role's full prompt (default false; it is very long)."));
            f8.put("parameters", new JSONObject().put("type", "object").put("properties", p8));
            t8.put("function", f8);
            arr.put(t8);

            JSONObject t9 = new JSONObject();
            t9.put("type", "function");
            JSONObject f9 = new JSONObject();
            f9.put("name", "propose_add_member");
            f9.put("description", "Submit a proposal to **add a role** (takes effect after the user taps 「✔ 允许」(Allow)). "
                    + "To add from the role library: fill in name only (the exact name from read_role_library) — "
                    + "the avatar, role summary and prompt are taken from the library entry automatically, you don't handle them. "
                    + "To add a custom role: fill in all three of name + roleDesc + prompt. "
                    + "You can propose one role at a time, or put several into members and propose them together (use your judgment). "
                    + "Optional: thinking level, model, provider, whether web access is allowed "
                    + "(defaults when omitted: level 2 / follows the main chat model / web access on). "
                    + "⚠️ For provider, use the exact name from the global provider table (the provider parameter); never invent an address.");
            JSONObject p9 = new JSONObject();
            p9.put("name", new JSONObject().put("type", "string")
                    .put("description", "Role name (the exact name returned by read_role_library)"));
            p9.put("roleDesc", new JSONObject().put("type", "string")
                    .put("description", "Role summary (required for custom roles; may be omitted when adding from the library)"));
            p9.put("prompt", new JSONObject().put("type", "string")
                    .put("description", "Full role prompt (required for custom roles; may be omitted when adding from the library)"));
            p9.put("members", new JSONObject().put("type", "array")
                    .put("description", "Optional: add **multiple** roles at once, one object per item (same fields as the top level). "
                            + "For a single role the top-level name suffices; for several, this saves round trips.")
                    .put("items", new JSONObject().put("type", "object")));
            p9.put("thinkLevel", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Thinking level: 0=off 1=lowest 2=low 3=medium 4=high (default 2)"));
            p9.put("model", new JSONObject().put("type", "string")
                    .put("description", "Optional. Model name. If omitted, it follows the main chat model"));
            p9.put("provider", new JSONObject().put("type", "string")
                    .put("description", "Optional. Provider name (use the provider names shown by list_experts). When set, the address and API key come along automatically"));
            p9.put("canSearch", new JSONObject().put("type", "boolean")
                    .put("description", "Optional. Whether web search is allowed (default true)"));
            p9.put("summary", new JSONObject().put("type", "string")
                    .put("description", "One sentence explaining why you're adding it (shown to the user)"));
            f9.put("parameters", new JSONObject().put("type", "object").put("properties", p9));

            t9.put("function", f9);
            arr.put(t9);

            JSONObject t9b = new JSONObject();
            t9b.put("type", "function");
            JSONObject f9b = new JSONObject();
            f9b.put("name", "propose_remove_member");
            f9b.put("description", "Submit a proposal to **remove a role** (takes effect after the user taps 「✔ 允许」(Allow)). "
                    + "List the roles to remove in names (this group's member names — get the exact names from list_experts); "
                    + "several at once are supported (separate with newlines or commas). "
                    + "⚠️ **Built-in roles cannot be removed** (the ones that came with the template/default group — the role library itself is fixed) — "
                    + "passing a built-in role's name is explicitly rejected, and you'll be told which ones cannot be removed. "
                    + "Use this only when the user has said they no longer want someone / want a role dropped; never trim the roster on your own initiative.");
            JSONObject p9b = new JSONObject();
            p9b.put("names", new JSONObject().put("type", "string")
                    .put("description", "Names of the roles to remove; separate multiple names with newlines or commas"));
            p9b.put("summary", new JSONObject().put("type", "string")
                    .put("description", "One sentence explaining why you're removing them (shown to the user)"));
            f9b.put("parameters", new JSONObject().put("type", "object").put("properties", p9b));
            t9b.put("function", f9b);
            arr.put(t9b);

            JSONObject t10 = new JSONObject();
            t10.put("type", "function");
            JSONObject f10 = new JSONObject();
            f10.put("name", "read_questions");
            f10.put("description", "Read the **question ledger**: the authoritative list of real questions this group's experts have submitted to the user via ask_user "
                    + "(including who asked, the question text, the current status — 待答 (pending) / 已进询问稿 (rolled into the inquiry draft) / 已回答 (answered) — and the user's verbatim answers). "
                    + "⚠️ This is the **authoritative data** in the system. When experts claim in their speeches \"I raised N questions\", that is their subjective account "
                    + "and often disagrees with one another; **for question counts and answer status, always go by what this tool returns**.");
            f10.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            t10.put("function", f10);
            arr.put(t10);

            JSONObject t11 = new JSONObject();
            t11.put("type", "function");
            JSONObject f11 = new JSONObject();
            f11.put("name", "read_expert_detail");
            f11.put("description", "Read one expert's **per-message detail**: each message's length (in characters), tokens consumed, "
                    + "whether it was a speech or thinking-only, generation time, and whether a generation anomaly is suspected (large stretches of self-repetition in the content). "
                    + "Use it to figure out \"why did this expert burn so many tokens — is it malfunctioning?\". "
                    + "When name is not passed, it returns a summary for the whole group.");
            JSONObject p11 = new JSONObject();
            p11.put("name", new JSONObject().put("type", "string")
                    .put("description", "Expert name (the exact name from list_experts); omit = whole-group summary"));
            f11.put("parameters", new JSONObject().put("type", "object").put("properties", p11));
            t11.put("function", f11);
            arr.put(t11);

            JSONObject t13 = new JSONObject();
            t13.put("type", "function");
            JSONObject f13 = new JSONObject();
            f13.put("name", "read_anomalies");
            f13.put("description", "Read this group's **anomaly log**: when a given expert hit a generation loop, "
                    + "how many times it was rerun, tool-call failures, role-call failures (network/upstream errors), etc. "
                    + "Sorted newest first. Use it to investigate \"why is this expert behaving abnormally\" or \"why did this round get stuck\". "
                    + "Note: these are **historical traces**, not the current state.");
            JSONObject p13 = new JSONObject();
            p13.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "How many recent entries to read. Default 30, max 200"));
            f13.put("parameters", new JSONObject().put("type", "object").put("properties", p13));
            t13.put("function", f13);
            arr.put(t13);

            JSONObject t15 = new JSONObject();
            t15.put("type", "function");
            JSONObject f15 = new JSONObject();
            f15.put("name", "retry_failed_role");
            f15.put("description", "**Trigger a retry** — let the expert who just kept failing try once more; the discussion picks back up from them. "
                    + "This is a write operation (it actually changes the discussion state), not a proposal. "
                    + "☠️ Call this only after you have **investigated first** and judged the failure to be more like a transient blip (rather than a misconfiguration / persistent rate-limiting). "
                    + "After calling it you'll receive the result, and then you **must** write the user a message explaining the situation. "
                    + "If there is no pending failure to handle, this tool refuses outright (that is normal — don't keep retrying).");
            f15.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            t15.put("function", f15);
            arr.put(t15);

            JSONObject t15b = new JSONObject();
            t15b.put("type", "function");
            JSONObject f15b = new JSONObject();
            f15b.put("name", "request_reenroll");
            f15b.put("description", "**Let the named experts rejoin the discussion** (write operation). "
                    + "When to use: the user sent a **fairly important message** (a new requirement / a change of direction) and wants the experts who had already confirmed "
                    + "to **take another look** — revise what needs revising, and if all is well, confirm once more. "
                    + "It resets their confirmation status to 未确认 (unconfirmed) and logs a system message explaining why; "
                    + "they rejoin once the user next opens 「▶ 讨论」(the Discussion view). "
                    + "☠️ **The name list must come from the user's own words** — first hear whom the user wants (or read the discussion to tell who owns the changed parts), "
                    + "then call this tool; never decide on your own whom to pull back in for the user. "
                    + "☠️ Names must match character for character (use the names returned by list_experts / read_progress).");
            JSONObject p15b = new JSONObject();
            p15b.put("names", new JSONObject().put("type", "string")
                    .put("description", "Names of the experts to rejoin; separate multiple names with newlines or ideographic commas (must match the member roster exactly)"));
            p15b.put("reason", new JSONObject().put("type", "string")
                    .put("description", "One sentence explaining why they're being brought back (written into a system message in the group for all roles to see, "
                            + "so whoever is pulled back knows why they were triggered — e.g.: the user added a requirement for an operations back-office)"));
            f15b.put("parameters", new JSONObject().put("type", "object").put("properties", p15b)
                    .put("required", new JSONArray().put("names")));
            t15b.put("function", f15b);
            arr.put(t15b);

            JSONArray web = buildWebTools();
            for (int i = 0; i < web.length(); i++) arr.put(web.get(i));

            JSONObject t18 = new JSONObject();
            t18.put("type", "function");
            JSONObject f18 = new JSONObject();
            f18.put("name", "read_basic_info");
            f18.put("description", "Read the **user basic-info store** and the **user profile store** (read-only).\n"
                    + "· basic_info: the user's basic facts (server / domain / ICP filing / business license / qualifications…), "
                    + "each with an id and already_injected (whether it has been injected into this group).\n"
                    + "· qa_archive: questions the user has already answered **in other groups** (with the asking role and the verbatim answer); "
                    + "use it to judge which old Q&As are worth bringing into this group, so experts don't ask them again.\n"
                    + "When to use: ① when the user asks you to judge/pick basic info to inject into this group; "
                    + "② when you want to see what the user has already answered in other groups while preparing to create a new group or adjust this group's announcement.");
            f18.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            t18.put("function", f18);
            arr.put(t18);

            JSONObject t19 = new JSONObject();
            t19.put("type", "function");
            JSONObject f19 = new JSONObject();
            f19.put("name", "apply_basic_info");
            f19.put("description", "Set the selected **basic-info items** as this group's standing announcement (write operation, takes effect immediately; all roles see it from the next round on).\n"
                    + "Once injected, these facts appear at the top of every role's context, and roles **won't ask about them again** "
                    + "(that's the whole point: to keep even basic questions like \"do you have a business license\" from eating up discussion rounds).\n"
                    + "⚠️ **Don't inject everything**: pick only the few items that genuinely steer this group's discussion; items already injected needn't be set again.\n"
                    + "After calling it you'll receive the result, and then you **must** write the user a message explaining which items you chose and why.");
            JSONObject p19 = new JSONObject();
            p19.put("ids", new JSONObject().put("type", "string")
                    .put("description", "Ids of the basic-info items to inject, separated by commas (e.g. \"1,3,5\"). "
                            + "**Replacement semantics**: the set you pass this time becomes the group's entire final injected set; passing an empty string = clear this group's announcement. "
                            + "Take ids from basic_info returned by read_basic_info."));
            f19.put("parameters", new JSONObject().put("type", "object").put("properties", p19)
                    .put("required", new JSONArray().put("ids")));
            t19.put("function", f19);
            arr.put(t19);

            JSONObject t21 = new JSONObject();
            t21.put("type", "function");
            JSONObject f21 = new JSONObject();
            f21.put("name", "read_progress");
            f21.put("description", "Read the **authoritative data on this group's convergence-confirmation progress** (read-only). Returns:\n"
                    + "· for each participating expert: whether they have confirmed convergence;\n"
                    + "· a summary: N of M participants confirmed, plus the names not yet confirmed;\n"
                    + "· a stage estimate (探索 exploring / 深化 deepening / 收敛 converging / 定稿 finalizing).\n"
                    + "**For any question like \"who's still missing / can we wrap up\", always call this tool first** — "
                    + "don't read experts' self-reports from the discussion text (they'll say \"I've delivered\", but that only means they think they did).");
            f21.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            t21.put("function", f21);
            arr.put(t21);

            JSONObject sua = buildSearchUserArchiveTool();
            if (sua != null) arr.put(sua);
            JSONObject fua = buildFetchUserArchiveTool();
            if (fua != null) arr.put(fua);
        } catch (Exception ignored) { }
        return arr;
    }

    public static String buildAdminSystemPrompt(String roomTitle, int expertCount, int activeCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the **administrator** of this group chat. You are in charge of the speaking order and the running configuration of ")
                .append(expertCount).append(" experts (").append(activeCount).append(" of them taking part in the discussion right now).\n");
        sb.append("Group name: ").append(roomTitle == null ? "群聊" : roomTitle).append("\n\n");
        sb.append("【What you are here for】\n");
        sb.append("1. **Set the speaking order**: judge what each expert is capable of and responsible for, then arrange an order that makes the discussion most efficient.\n");
        sb.append("   A good order usually runs: the proposers and specialists first (spreading the plan out) → the challengers and checkers next (picking it apart) → "
                + "the convergers and summarisers last (closing it off). But judge by the **actual make-up** of this group of experts; do not apply the pattern mechanically.\n");
        sb.append("2. **Set providers and models**: according to what each expert needs in capability and cost, suggest which provider and which model they should run on.\n");
        sb.append("3. **Set thinking levels**: roles that have to think hard (architect, auditor, legal) suit a high level; "
                + "execution and summarising roles can sit lower and save tokens.\n");
        sb.append("4. **Listen to the experts**: they will complain in the discussion that \"this position of mine is not quite right\", and they will also send you feedback through report_to_user. "
                + "Go and look with read_feedback / read_discussion, then adjust the order - that is exactly the job.\n\n");
        sb.append("【Hard rules - these you must keep】\n");
        sb.append("· **You may only submit proposals; you cannot change anything directly.** Use propose_order for the order "
                + "and propose_member_config for configuration. The user sees the proposal in the interface and taps 「允许」; only then does it take effect.\n");
        sb.append("· So **never say \"I have already changed it\"** - say \"I have submitted a proposal, please confirm it\".\n");
        sb.append("· **Look at the data before proposing**: run list_experts before ordering anything (the existing order above all), "
                + "and check the current values before changing configuration, so that no proposal is pointless.\n");
        sb.append("· Expert names must match **character for character** (use the names list_experts returns), or the proposal will not match anything.\n\n");
        sb.append("【What you can see】\n");
        sb.append("list_experts (where every expert stands), read_progress (**the convergence confirmation progress** - "
                + "who has confirmed and who is still missing; for anything of the form \"who is left / can we wrap up\", look here first), "
                + "read_discussion (what the group has been discussing), "
                + "read_plans (the exported plans), read_usage (usage figures), read_feedback (what the experts have said back), "
                + "read_questions (**the question ledger** - the authoritative record of the questions put to the user and his answers), "
                + "read_expert_detail (one expert's speech lengths, spend, and whether generation looks abnormal), "
                + "read_anomalies (**the anomaly log** - who fell into a generation loop and when, how many reruns, tool and call failures), "
                + "read_role_library (which roles the library holds, with each one's full prompt), "
                + "retry_failed_role (**trigger a retry** - give an expert who keeps failing another go; this writes), "
                + "request_reenroll (**bring experts who already confirmed back into the discussion** - for when the user has posted something important "
                + "and you want certain experts to look again before judging; this writes; the list must come from the user's own words, never from your own initiative), "
                + "read_basic_info (**the user's basic information file plus the user archive** - which servers, domains, filings and licences he has, "
                + "and what requirements he raised in other groups and which questions he has already answered), "
                + "apply_basic_info (pin selected basic information as this group's standing announcement, so the experts stop asking about the fundamentals), "
                + "search_user_archive / fetch_user_archive (the two-step search of the user archive: the list of requirements first, "
                + "then the relevant Q&A pulled into your context), "
                + "web_search / fetch_url (looking things up on the web).\n");
        sb.append("You **cannot** see the experts' private reasoning or the raw text of their tool material (neither goes into the shared context), "
                + "but the discussion itself, the plans, the usage, the feedback, the question ledger and the role library are all open to you - enough to judge by.\n\n");

        sb.append("【For anything that involves counting, the tool's answer is the only authority (important)】\n");
        sb.append("Experts often claim in their speeches \"I raised N questions\" or \"M are still unanswered\" - that is their **own impression**, "
                + "and it is normal for those claims not to agree (in one real discussion the same thing was reported as 4, 5, 9 and 11). "
                + "So whenever you are about to give the user a number (questions asked, questions waiting, spend, counts of anything), "
                + "**call the matching tool first** (read_questions / read_usage / list_experts / **read_progress**) and take the authoritative figure. "
                + "Do not treat what an expert said as data. If you cannot get the number, say so plainly - never make one up.\n"
                + "☠️ One warning in particular: **questions of the form \"who has confirmed, who is still missing\" can only be answered by read_progress**. "
                + "An expert writing \"I have confirmed\" in his own speech is **his own account of it**, "
                + "which may not match what the system has registered (the system recognises only what the confirm_convergence tool wrote). "
                + "So when the user asks how many confirmations are still missing, call read_progress - do not count sentences in the transcript.\n\n");

        sb.append("One more thing: the numbers in read_usage come in two kinds, \"cumulative spend\" and \"current context occupancy\". "
                + "**Do not mix them.** Cumulative spend is the historical bill and says nothing about how much room is left; "
                + "to judge how close to full you are, look at current occupancy.\n\n");
        sb.append("【About the experts asking you to change the order (important)】\n");
        sb.append("The experts **cannot talk to you directly**; they can only put their requests in the discussion (which you see through read_feedback).\n");
        sb.append("The system hands those requests to you for assessment **once, after each round of discussion** - not several times inside a round.\n");
        sb.append("What you do: look at the current state → judge whether the request is reasonable (if not, say why you are not changing it; you are not obliged to oblige) → "
                + "propose_order where a change is due → then report your conclusion to the **user** in a short paragraph.\n\n");
        sb.append("【Adding people】\n");
        sb.append("The role library holds over 100 built-in roles (development roles, experts, personas), each with a complete prompt. "
                + "When it is time to add someone, **first call read_role_library with no parameters and read the whole list through** "
                + "(about 140 of them; one call shows you all of it and it costs very little context), "
                + "pick your candidates from there, look at a candidate's full prompt with name=角色名, and then propose with propose_add_member "
                + "(one at a time or several at once - your call). "
                + "**Do not use keyword to guess whether some role exists** - a wrong guess makes you miss a role that is in fact on the list. "
                + "When the user says \"we do not want this role / take so-and-so out\", propose with propose_remove_member "
                + "(built-in roles cannot be deleted, and the tool will tell you plainly which ones those are). "
                + "If the library has nothing suitable, define one yourself (name + roleDesc + prompt).\n\n");

        sb.append("【Report the situation, not just the action (important)】\n");
        sb.append("When you write to the user on your own initiative, it is **not acceptable** to give only a running log like \"I have tallied X\" or \"I checked Y\". "
                + "What the user wants is the situation itself. A report that passes says at least four things:\n");
        sb.append("1. **What happened** - specific as to person, event, time and number (who, which role, which round, how many times or how many items).\n");
        sb.append("2. **What you found** - which data you read and the key facts in it (not just \"I looked into it\").\n");
        sb.append("3. **Your judgment** - what those facts mean, why you reached that conclusion, and where you are not certain.\n");
        sb.append("4. **What happens next** - what you have already done (or proposed), and whether the user needs to do anything.\n");
        sb.append("Bad example: 「我已经把这个专家的情况统计了一下。」 - the user finishes reading it and still does not know what happened.\n");
        sb.append("Good example: 「第 3 轮的「数据核查员」连续 3 次请求失败，最近两次都是上游 503。"
                + "我查了异常日志，这个角色过去 24 小时失败 5 次、都在同一服务商；"
                + "另外两位用同一服务商的角色也偶发失败。判断是服务商侧不稳定，不是配置错。"
                + "我已提了换服务商的提案，等你点允许；在那之前先没让它重试，避免白烧额度。」\n\n");
        sb.append(timeLineForPrompt());
        sb.append("\nKeep your answers short and direct, in Chinese. When the user tells you to do something, just do it (read the data first, then propose) - "
                + "no long explanations of methodology.");
        return sb.toString();
    }

    private static JSONObject buildReportTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "report_to_user");
            fn.put("description", "Report a problem, anomaly, improvement suggestion, or a new tool/feature you want the user to build for you, to the user. "
                    + "**It does not pause the discussion**; the user sees it under 「💬 反馈」(Feedback) (unlike ask_user, which stops and waits for their answer). "
                    + "When to use: ① you notice something clearly wrong with the discussion or the system (e.g. a 'pending question' in the context that obviously isn't a real question, "
                    + "a self-contradictory piece of data, a mechanism that has clearly failed); ② you think an improvement would make the whole system more usable; "
                    + "③ you're missing a tool/feature and can't get the work done without it (state clearly what you need and what it's for). "
                    + "Don't use it to chat, don't use it to ask questions (use ask_user when you need the user's decision), and don't report the same thing repeatedly.");
            JSONObject props = new JSONObject();
            props.put("category", new JSONObject().put("type", "string")
                    .put("description", "Feedback type, one of four: bug (a definite fault/crash/data error), "
                            + "issue (something anomalous or off but you're not sure it's a bug), "
                            + "suggestion (an improvement idea), tool (a new tool/feature you want the user to build for you), "
                            + "order (you think your own speaking order/position is unsuitable, and where it should be)")
                    .put("enum", new JSONArray().put("bug").put("issue").put("suggestion")
                            .put("tool").put("order")));
            props.put("title", new JSONObject().put("type", "string")
                    .put("description", "A one-line summary, within 30 characters, so the user knows what it's about at a glance."));
            props.put("detail", new JSONObject().put("type", "string")
                    .put("description", "Full explanation: what the phenomenon/suggestion is, why you think it matters, "
                            + "how it relates to your expertise, and how you'd like it resolved. Make it understandable to the user without reading the discussion."));
            props.put("context", new JSONObject().put("type", "string")
                    .put("description", "Optional: the relevant message number or location (e.g. \"round #123\", \"midway through round 2\"), so the user can look back."));
            fn.put("parameters", new JSONObject()
                    .put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("category").put("title").put("detail")));
        } catch (Exception ignored) { }
        JSONObject tool = new JSONObject();
        try {
            tool.put("type", "function");
            tool.put("function", fn);
        } catch (Exception ignored) { }
        return tool;
    }

    private static boolean nameInList(String list, String name) {
        if (list == null || list.isEmpty() || name == null || name.isEmpty()) return false;
        for (String ln : list.split("\n")) {
            if (ln.trim().equals(name)) return true;
        }
        return false;
    }

    private static JSONObject buildPullExpertTool(String pullableNames) {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "pull_expert");
            fn.put("description", "Pull an expert who has **already confirmed and currently left** back into the discussion to continue alongside you.\n"
                    + "When to use: a few of you are still wrapping up, but an expert who already confirmed —\n"
                    + "  · owns exactly the part that's now stuck,\n"
                    + "  · or whose earlier conclusion conflicts with the direction the discussion has now taken, and needs to confirm or correct it,\n"
                    + "  · or needs to supply a piece of information only their part has.\n"
                    + "Don't pull someone back just because you 'want more company': a re-pulled expert consumes quota and context again.\n"
                    + "A re-pulled expert can likewise pull others back (it can chain).\n"
                    + "Available experts (you may only use these names):\n" + pullableNames);
            JSONObject props = new JSONObject();
            props.put("name", new JSONObject().put("type", "string")
                    .put("description", "Name of the expert to pull back; must match the roster's spelling exactly."));
            props.put("reason", new JSONObject().put("type", "string")
                    .put("description", "Optional but recommended: why you need them back (one sentence); it's recorded in the discussion log,\n"
                            + "so the user and the expert themselves can judge whether this pull-back was reasonable."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("name")));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildSearchUserArchiveTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "search_user_archive");

            fn.put("description", "Look up the requirements the user has raised across past group chats — each item is **a whole bundle**: "
                    + "that group's requirement + the Q&A bound to it (which experts asked what, and how the user answered).\n"
                    + "Usage: you may pass keyword to filter by keyword (omit = list all); it returns demand_id, the requirement text, "
                    + "and how many Q&A pairs are in that bundle.\n"
                    + "To read the actual Q&A content, call fetch_user_archive with the demand_id.");
            JSONObject props = new JSONObject();
            props.put("keyword", new JSONObject().put("type", "string")
                    .put("description", "Optional. Filter requirements by keyword (matches both the requirement title and its full text). "
                            + "Omit = list all requirements."));
            props.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Max number of requirements to return (default 30, cap 100)."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildFetchUserArchiveTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "fetch_user_archive");

            fn.put("description", "Fetch the Q&A bound to a given requirement bundle (\"which expert asked what + how the user answered\").\n"
                    + "These are the user's answers as of some past point in time — treat them as reference only, not current fact (things change).\n"
                    + "Usage: demand_id is required (obtained from search_user_archive); "
                    + "you may pass keyword to fetch only those whose question/answer contains that word; "
                    + "you may pass offset + limit to page (the response states how many entries remain unfetched).");
            JSONObject props = new JSONObject();
            props.put("demand_id", new JSONObject().put("type", "integer")
                    .put("description", "The requirement id to fetch (= the demand_id returned by search_user_archive)."));
            props.put("keyword", new JSONObject().put("type", "string")
                    .put("description", "Optional. Fetch only Q&A whose question or the user's answer contains this word (recommended to narrow the scope)."));
            props.put("offset", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Which entry to start from (default 0). The response states how many more can be fetched."));
            props.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Max entries to fetch this time (default 20, cap 100)."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("demand_id")));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildRememberResearchTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "remember_research");

            fn.put("description", "Compress the **materials you looked up** into a single conclusion, replacing the raw text in your materials "
                    + "(overwriting your previous compressed conclusion).\n"
                    + "It only affects your own materials (the bodies you searched/fetched yourself); "
                    + "it can't touch your speeches, others' speeches, or the discussion log — those were never part of your materials to begin with.\n"
                    + "Usage: pass what you want to keep in content, merging \"your existing compressed conclusion + the new materials\" into one freshly written summary — "
                    + "keep the conclusions, key numbers, and fact sources, and drop the verbose raw text. How much to compress and what to keep is your call.\n"
                    + "The compressed conclusion, like the raw text you fetched, is part of your materials and gets injected every round; "
                    + "so compressing away the raw text loses no information — if you need the raw details later, you can search/fetch them again.\n"
                    + "(Materials have a total-size cap; exceeding it is rejected and you're told the number. "
                    + "Raw text that gets compressed away automatically moves into the group's shared material pool, retrievable when needed.)");
            JSONObject props = new JSONObject();
            props.put("content", new JSONObject().put("type", "string")
                    .put("description", "The full compressed conclusion (recommended to include: conclusions / key numbers and facts / source links)."));
            props.put("drop_raw_materials", new JSONObject().put("type", "boolean")
                    .put("description", "Optional. true = the raw text of these materials has already been compressed into memory and needn't keep taking up context "
                            + "(**the raw text moves into the group's shared material pool**, where you or others can search and retrieve it later; "
                            + "this only affects material bodies — the discussion log and others' speeches are unaffected). Default false (raw text is kept)."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("content")));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildSearchMaterialPoolTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "search_material_pool");
            fn.put("description", "Search the **shared material pool**: all materials that any role in the group has compressed away/retired end up here.\n"
                    + "Usage: you may pass keyword to filter by keyword (omit = list the most recent); "
                    + "it returns each material's pool_id, original owning role, size, and a short opening excerpt.\n"
                    + "To read a material's body, call fetch_material_pool with its pool_id (you can fetch it in segments; no need to take it all).");
            JSONObject props = new JSONObject();
            props.put("keyword", new JSONObject().put("type", "string")
                    .put("description", "Optional. Filter material bodies by keyword."));
            props.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Max entries to return (default 30, cap 200)."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildFetchMaterialPoolTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "fetch_material_pool");
            fn.put("description", "Fetch the body of a given material from the shared material pool (supports fetching just one segment).\n"
                    + "Usage: pool_id is required (obtained from search_material_pool); "
                    + "offset_chars specifies which character to start from (default 0); "
                    + "max_chars specifies the max characters to fetch this time (default 6000, cap 20000).\n"
                    + "The response states this material's total length and which segment was fetched this time; you can fetch repeatedly until you have it all.");
            JSONObject props = new JSONObject();
            props.put("pool_id", new JSONObject().put("type", "integer")
                    .put("description", "The material id to fetch (= the pool_id returned by search_material_pool)."));
            props.put("offset_chars", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Which character of the body to start from (default 0)."));
            props.put("max_chars", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Max characters to fetch this time (default 6000, cap 20000)."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("pool_id")));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildReadQuestionsTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "read_questions");
            fn.put("description", "Read the **question ledger** (the official authoritative data): the questions all experts in this group have formally "
                    + "submitted to the user via the ask_user tool, and each one's current status (待答 pending / 已回答 answered).\n"
                    + "When to use: ① you want to confirm \"are there really any unanswered questions left in the group\", and who asked each; "
                    + "② you see a role claim in its speech \"I raised a question\" but can't find it in the context, and doubt whether it was actually submitted.\n"
                    + "⚠️ **Any 'question' that does not appear in this tool's result is not a real question** — "
                    + "even if some role's speech text says \"I want to ask\" or \"this question needs the user's decision\". "
                    + "Only questions that truly invoked the ask_user tool get logged here.\n"
                    + "When unsure, ask it; don't guess by reading others' speech text. This tool is read-only and changes nothing.");
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildConfirmConvergenceTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "confirm_convergence");
            fn.put("description", "Declare that you endorse the current plan and have no remaining objections (**the sole criterion for whether the discussion ends**).\n"
                    + "Call it only when you genuinely believe the plan is complete.\n"
                    + "A confirmation stays **valid across rounds**: once you've called it you needn't repeat it every round; "
                    + "but if you later raise new substantive content, your confirmation is treated as withdrawn and you must call it again.\n"
                    + "After the user answers the 询问稿 (inquiry draft), everyone's confirmation is automatically voided (the new information may change the plan); re-examine, then confirm again.\n"
                    + "If you judge that your role genuinely has nothing to deliver on this topic, just state 「本角色无需交付，理由：…」 in your speech and then call this tool directly.");
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildPassRoundTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "pass_round");
            fn.put("description", "Stay silent this round (use it when you have no new substantive content to add).\n"
                    + "After calling it you enter a silent round: other roles don't see you speak (your reason isn't sent to them either), "
                    + "and the system records it as one silence.\n"
                    + "⚠️ This is the only way to opt out of speaking — writing \"不发言\" (staying silent) in your speech text is not recognized by the system; "
                    + "that would be broadcast to the whole group as your formal speech.");
            JSONObject props = new JSONObject();
            props.put("reason", new JSONObject().put("type", "string")
                    .put("description", "Optional. One sentence on why you're staying silent this round (e.g. \"my domain is delivered and nothing new has changed\"). It goes only into your own thinking record, not shown to other roles."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject buildAskUserTool() {
        JSONObject fn = new JSONObject();
        try {
            fn.put("name", "ask_user");
            fn.put("description", "Ask the user a question that needs their decision. Questions are gathered into an 询问稿 (inquiry draft) and given to the user together "
                    + "(they don't read the discussion item by item; this is their only channel to know you're waiting on their decision). "
                    + "Call it only when a decision genuinely needs the user (choosing a direction / setting boundaries / confirming budget or qualifications / picking a platform, etc.); "
                    + "don't ask about things you can settle among yourselves or infer from the context. At most 3 questions per round. "
                    + "The question text must be **understandable to the user without reading the discussion**. "
                    + "After calling this tool, don't write the literal 「【提问】」 in your speech text — just continue outputting your formal speech per the format.");
            JSONObject props = new JSONObject();
            props.put("question", new JSONObject().put("type", "string")
                    .put("description", "The question text: state in one sentence what you're asking and why it matters, understandable to the user without reading the discussion. "
                            + "Don't include background exposition, and don't include remarks addressed to other roles."));
            props.put("options", new JSONObject().put("type", "string")
                    .put("description", "Optional. Options on one line, separated by vertical bars, 2-5 of them, e.g.: \"只做最小可用 | 加基础统计 | 一步做完整\". "
                            + "Make options concrete enough that the user can decide without reading the discussion (\"按月 29.9 元\" is far more useful than \"方案A\"). Don't pass this parameter for open-ended questions."));
            props.put("multi", new JSONObject().put("type", "boolean")
                    .put("description", "Optional. Whether the options allow multiple selection (e.g. questions like \"which platforms to launch on\" where several answers can coexist). Default false."));
            fn.put("parameters", new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("question")));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONArray buildWebTools() {
        JSONArray tools = new JSONArray();
        try {
            JSONObject wsf = new JSONObject();
            wsf.put("name", "web_search");
            wsf.put("description", "Search the web to get up-to-date information (news, real-time data, policies, prices, and other facts you don't know). "
                    + "It's backed by a multi-API search pool that spreads out concurrency/rate-limiting, so you can call it multiple times as needed; search only when you genuinely need external facts, not to pad out content; "
                    + "it returns several web results (title / link / snippet / publish date `date`). When you get results, check `date` first to judge how fresh they are.");
            JSONObject wsProps = new JSONObject();
            wsProps.put("query", new JSONObject().put("type", "string").put("description", "Search keywords (concise, stripped of conversational filler)"));
            wsProps.put("freshness", new JSONObject().put("type", "string")
                    .put("description", "Optional, restricts the time range of sources. **Format: dN = last N days (d1~d30), mN = last N months (m1~m12), yN = last N years (y1~y5)**, "
                            + "omit = no time limit. "
                            + "For things that go stale — policies, prices, rates, platform rules, market conditions — use d7 or m1; "
                            + "for things that don't go stale — principles, methods, history, case studies — leave it out (no limit by default)."));
            wsf.put("parameters", new JSONObject().put("type", "object").put("properties", wsProps)
                    .put("required", new JSONArray().put("query")));
            tools.put(new JSONObject().put("type", "function").put("function", wsf));

            JSONObject ff = new JSONObject();
            ff.put("name", "fetch_url");
            ff.put("description", "Fetch the main text of a given web page (ads/navigation stripped). The response is headed with \"[Fetched characters X~Y of N total]\"; "
                    + "if the tail of the result notes there's more, use the offset parameter to **continue with the next segment** (segmented reading, not bound by the per-call cap). "
                    + "When you only want a certain part, use offset to jump near that segment directly instead of fetching from the start.");
            JSONObject fProps = new JSONObject();
            fProps.put("url", new JSONObject().put("type", "string").put("description", "The web page's full link (http/https)"));
            fProps.put("offset", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Which character of the body to start fetching from (default 0 = from the beginning). Use it to skip a large irrelevant stretch up front, "
                            + "or to continue from the segment hint in the previous response."));
            fProps.put("maxChars", new JSONObject().put("type", "integer")
                    .put("description", "Optional. Max characters to fetch this time (default 6000, cap 20000). **This is a per-call cap, not a total** — "
                            + "for body text beyond 6000 characters, just keep fetching with offset; you can call repeatedly until you have it all. "
                            + "To save context, first fetch only the most relevant segment."));
            ff.put("parameters", new JSONObject().put("type", "object").put("properties", fProps)
                    .put("required", new JSONArray().put("url")));
            tools.put(new JSONObject().put("type", "function").put("function", ff));
        } catch (Exception ignored) {}
        return tools;
    }

    public static int estimateTokensText(String text) {
        if (text == null || text.isEmpty()) return 0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) ascii++;
        }
        int nonAscii = text.length() - ascii;
        return Math.max(1, (int) Math.ceil(ascii / 4.0 + nonAscii * 1.5));
    }

    private static String fmtWan(long n) {
        if (n >= 10000L) return (n / 10000L) + " 万";
        return String.valueOf(n);
    }

    public static String testModel(String baseUrl, String apiKey, String model) {
        try {
            String base = isBlank(baseUrl) ? "" : baseUrl.trim();
            if (base.isEmpty()) return "❌ 没填请求地址";
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String endpoint = endpointFor(base, "/chat/completions");
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", "hi"));
            body.put("messages", msgs);
            body.put("max_tokens", 5);
            body.put("stream", false);
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(45000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + (isBlank(apiKey) ? "" : apiKey.trim()));
                OutputStream output = connection.getOutputStream();
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();
                int code = connection.getResponseCode();
                if (code == 200) return "✅ 可用";
                InputStream err = connection.getErrorStream();
                String t = err == null ? "" : readAll(err);
                String snip = safeSnippet(t);
                if (code == 429) return "⚠️ 限流（稍后重试）";
                return "❌ HTTP " + code + (snip.isEmpty() ? "" : " " + snip);
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    public static java.util.List<String> fetchModelList(String baseUrl, String apiKey) throws Exception {
        String base = isBlank(baseUrl) ? "" : baseUrl.trim();
        if (base.isEmpty()) throw new RuntimeException("请先填请求地址");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(endpointFor(base, "/models"));
        String raw = base + "/models";
        if (!candidates.contains(raw)) candidates.add(raw);

        for (String tail : new String[]{"/chat/completions", "/completions", "/models"}) {
            if (base.endsWith(tail)) {
                String root = base.substring(0, base.length() - tail.length());
                if (root.isEmpty()) break;
                String fix = endpointFor(root, "/models");
                if (!candidates.contains(fix)) candidates.add(fix);
                break;
            }
        }

        String lastErr = "";
        int lastCode = 0;

        int authCode = 0;
        String authEndpoint = "";
        String authDetail = "";
        for (String endpoint : candidates) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + (isBlank(apiKey) ? "" : apiKey.trim()));
                int code = connection.getResponseCode();
                InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = readAll(input);
                if (code >= 400) {
                    lastCode = code;
                    lastErr = "HTTP " + code + " " + safeSnippet(text);

                    if ((code == 401 || code == 403) && authCode == 0) {
                        authCode = code;
                        authEndpoint = endpoint;
                        authDetail = text;
                    }
                    continue;
                }
                JSONObject root;
                try {
                    root = new JSONObject(text);
                } catch (Exception notJson) {

                    lastErr = "返回的不是接口数据（像是网页）";
                    lastCode = -1;
                    continue;
                }
                JSONArray data = root.optJSONArray("data");
                if (data == null) data = root.optJSONArray("models");
                java.util.List<String> out = new java.util.ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        Object o = data.opt(i);
                        String id = null;
                        if (o instanceof JSONObject) {
                            id = ((JSONObject) o).optString("id", "");
                            if (id.isEmpty()) id = ((JSONObject) o).optString("name", "");
                        } else if (o != null) {
                            id = String.valueOf(o);
                        }
                        if (id != null && !id.trim().isEmpty()) out.add(id.trim());
                    }
                }
                java.util.Collections.sort(out);
                if (out.isEmpty()) { lastErr = "接口没返回模型列表"; continue; }
                return out;
            } catch (Exception e) {
                lastErr = String.valueOf(e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        if (authCode != 0) return throwHint(authCode, authEndpoint, authDetail);
        return throwHint(lastCode, candidates.get(0), lastErr);
    }

    private static java.util.List<String> throwHint(int code, String endpoint, String detail) {
        throw new RuntimeException(hintFor(code, endpoint, detail));
    }

    private static String hintFor(int code, String endpoint, String detail) {
        if (code == 401 || code == 403) {
            return "HTTP " + code + "：密钥无效或没权限。\n"
                    + "· 请确认 API Key 完整（不要带引号/空格）且该账号已开通\n"
                    + "· 地址： " + endpoint;
        }
        if (code == -1) {
            return "这个地址返回的是网页、不是接口数据。\n"
                    + "· 你填的多半是「控制台/网页版」地址，不是 API 地址\n"
                    + "· DeepSeek 官方 API 地址应填： https://api.deepseek.com/v1\n"
                    + "· 别填 chat.deepseek.com 或 platform.deepseek.com";
        }
        if (code == 404) {
            return "HTTP 404：地址路径不对。\n"
                    + "· 试过的地址： " + endpoint + "\n"
                    + "· 多数服务商要带 /v1，例如 https://api.deepseek.com/v1\n"
                    + "· 别把 /chat/completions 一起填进「请求地址」";
        }
        if (code == 429) {

            return "HTTP 429：被限流，或这个地址不是 API 接口。\n"
                    + "· 稍等再试；若一直这样，检查地址对不对\n"
                    + "· DeepSeek 官方应填： https://api.deepseek.com/v1";
        }
        if (code == 0) {
            return "连不上服务器：请检查网络/地址拼写。\n"
                    + "· 试过的地址： " + endpoint + "\n"
                    + "· " + detail;
        }
        return "HTTP " + code + " " + detail;
    }
}
