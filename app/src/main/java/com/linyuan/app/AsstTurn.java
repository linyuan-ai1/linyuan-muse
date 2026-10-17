package com.linyuan.app;

import android.content.Context;
import org.json.JSONArray;

/**
 * 「让主助手跑一轮」的入口。
 *
 * <p>用户 2026-10-16 定案：后台触发（定时任务 / 定时激活 / 子智能体回报）必须用主助手完整版，
 * 不做"残缺版助手"兜底 —— 残缺版连任务都派不出去，等于白跑。所以这里只有一条路：
 * 有活着的主助手就用它，没有就 {@link MainActivity#wake} 拉起来，拉不起来抛异常由调用方如实报失败。
 *
 * <p>串行：同一时刻只放一轮后台对话（两条后台活儿同时写同一个会话会互相踩）。
 * 面板里用户自己说话那一轮不走这里，不受这把锁影响。
 */
public final class AsstTurn {

    private AsstTurn() { }

    private static final Object LOCK = new Object();

    /** 后台跑一轮主助手：必要时把主助手拉起来。返回上游原始结果，正文可能为空。 */
    public static AiClient.ChatResult run(Context ctx, String model, String baseUrl, String apiKey,
                                          int thinkLevel, String sys,
                                          java.util.List<AppDatabase.MessageItem> hist,
                                          String userText, int maxIter) throws Exception {
        synchronized (LOCK) {
            MainActivity act = MainActivity.current();
            if (act == null) {
                android.util.Log.i("VC_ASST", "主助手不在，拉起它");
                act = MainActivity.wake(ctx);
            }
            if (act == null) throw new Exception("主助手起不来（后台拉 Activity 被拦），这一轮没跑");
            return runOn(act, model, baseUrl, apiKey, thinkLevel, sys, hist, userText, maxIter);
        }
    }

    /** 已经拿着活着的主助手实例时的短路径（不拉起、不加锁）。 */
    public static AiClient.ChatResult runOn(MainActivity act, String model, String baseUrl, String apiKey,
                                           int thinkLevel, String sys,
                                           java.util.List<AppDatabase.MessageItem> hist,
                                           String userText, int maxIter) throws Exception {
        JSONArray tools = SubAgentRunner.fullAssistantTools(act, "main");
        android.util.Log.i("VC_ASST", "走主助手完整工具集（" + tools.length() + " 个工具）");
        return AiClient.chatWithToolsStream(
                act.buildAssistantSettings(model, baseUrl, apiKey, thinkLevel), sys, hist, userText,
                tools, new AssistantToolHandler(act, "main"),
                maxIter > 0 ? maxIter : SubAgentRunner.maxIters(act), null, null, null);
    }
}
