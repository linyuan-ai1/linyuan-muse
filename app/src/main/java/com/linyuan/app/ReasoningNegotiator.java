package com.linyuan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReasoningNegotiator {

    /**
     * 本 App 自己的 6 个思考档位（用户 2026-10-14 定）：
     * 关 / 轻度 / 中 / 高 / 极高 / Ultra。
     *
     * 这 6 档是"我们的刻度"，服务商那边支持几档由它自己决定（常见 none/low/medium/high/xhigh/max）。
     * 换算规则见 {@link #rungFor(int)}：按比例落到对方支持的档位上，对方没有这个档位就
     * 逐级往下试（{@link #ladderChain(int)}），最终一定落在它给得出的最高档。
     */
    public static final int LEVEL_OFF = 0;
    public static final int LEVEL_LIGHT = 1;
    public static final int LEVEL_LOW = 1;
    public static final int LEVEL_MEDIUM = 2;
    public static final int LEVEL_HIGH = 3;
    public static final int LEVEL_VERY_HIGH = 4;
    public static final int LEVEL_ULTRA = 5;
    public static final int LEVEL_MAX = 5;

    public static final int LEVEL_COUNT = 6;

    /** 标准思考参数阶梯，从"完全不思考"到"最深入"（用户列举的常见取值）。 */
    public static final String[] LADDER = {"none", "low", "medium", "high", "xhigh", "max"};

    public static String levelName(int lv) {
        String[] n = new String[]{"关", "轻度", "中", "高", "极高", "Ultra"};
        return n[clampLevel(lv)];
    }

    public static String[] levelNames() {
        return new String[]{"关（不思考，最快）", "轻度", "中", "高", "极高", "Ultra（最深入）"};
    }

    public static int clampLevel(int lv) {
        if (lv < LEVEL_OFF) return LEVEL_OFF;
        if (lv > LEVEL_MAX) return LEVEL_MAX;
        return lv;
    }

    /**
     * 我们的档位 -> 参数阶梯上的首选档（等比例映射）。
     *
     * 例：服务商只支持 low/medium/high 三档时，我们的
     * 关→low（它没有关，最低就是低）、轻度→low、中→medium、高→medium、
     * 极高/Ultra→high。这样"档位多出来"不会把请求参数发到对方不认的值上。
     */
    public static String rungFor(int level) {
        int lv = clampLevel(level);
        int idx = Math.round(lv * (LADDER.length - 1) / (float) (LEVEL_COUNT - 1));
        return LADDER[Math.max(0, Math.min(LADDER.length - 1, idx))];
    }

    /**
     * 首选档 + 逐级降档。
     * 服务商不认 "max" 就试 "xhigh"、再试 "high"…… 一路往下，
     * 所以"没有最顶档"的服务商会自动落到它能给的最高档。
     */
    /** 这一档在阶梯上会用到几个取值（探测时要逐个试，直到服务商认一个为止）。 */
    public static int ladderDepth(int level) {
        return ladderChain(level).length;
    }

    private static String[] ladderChain(int level) {
        int lv = clampLevel(level);
        if (lv <= LEVEL_OFF) return new String[]{"none"};
        int top = Math.round(lv * (LADDER.length - 1) / (float) (LEVEL_COUNT - 1));
        top = Math.max(1, Math.min(LADDER.length - 1, top));
        String[] out = new String[top];
        for (int i = 0; i < top; i++) out[i] = LADDER[top - i];
        return out;
    }

    private static String[] effortChain(int level) {
        return ladderChain(level);
    }

    private static String[] anthropicEffortChain(int level) {
        return ladderChain(level);
    }

    public static int budgetFor(int level) {
        switch (clampLevel(level)) {
            case LEVEL_LIGHT:      return 4000;
            case LEVEL_MEDIUM:     return 16000;
            case LEVEL_HIGH:       return 32000;
            case LEVEL_VERY_HIGH:  return 48000;
            case LEVEL_ULTRA:      return 64000;
            default:               return 0;
        }
    }

    public static class Style {
        public final String id;
        public final String desc;

        public final boolean numeric;
        Style(String id, String desc, boolean numeric) {
            this.id = id; this.desc = desc; this.numeric = numeric;
        }

        public void apply(JSONObject body, int level, int valueIndex) throws Exception { }
    }

    private static String pick(String[] chain, int idx) {
        if (chain.length == 0) return null;
        return chain[Math.min(Math.max(0, idx), chain.length - 1)];
    }

    private static final Style STYLE_ENABLE_THINKING = new Style("enable_thinking",
            "enable_thinking + thinking_budget（阿里百炼 / 火山方舟）", true) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            boolean on = level >= LEVEL_LOW;
            body.put("enable_thinking", on);
            if (on) body.put("thinking_budget", budgetFor(level));
        }
    };

    private static final Style STYLE_REASONING_EFFORT = new Style("reasoning_effort",
            "reasoning_effort + reasoning:{enabled}（OpenRouter / OpenAI 标准）", false) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            if (level < LEVEL_LOW) {
                JSONObject r = new JSONObject();
                r.put("enabled", false);
                body.put("reasoning", r);
                body.put("reasoning_effort", "none");
                return;
            }
            String v = pick(effortChain(level), valueIndex);
            if (v != null) body.put("reasoning_effort", v);
            JSONObject r = new JSONObject();
            r.put("enabled", true);
            r.put("max_tokens", budgetFor(level));
            body.put("reasoning", r);
        }
    };

    private static final Style STYLE_THINKING_OBJECT = new Style("thinking_object",
            "thinking:{type,budget_tokens} + effort（Anthropic 系）", true) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            JSONObject t = new JSONObject();
            if (level < LEVEL_LOW) {
                t.put("type", "disabled");
                body.put("thinking", t);
                return;
            }
            t.put("type", "enabled");
            t.put("budget_tokens", budgetFor(level));
            body.put("thinking", t);

            String v = pick(anthropicEffortChain(level), valueIndex);
            if (v != null) body.put("effort", v);
        }
    };

    private static final Style STYLE_THINKING_TYPE = new Style("thinking_type",
            "thinking:{type} + reasoning_effort（智谱 GLM / 部分中转）", false) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            boolean on = level >= LEVEL_LOW;
            JSONObject t = new JSONObject();
            t.put("type", on ? "enabled" : "disabled");
            body.put("thinking", t);
            if (on) {
                String v = pick(effortChain(level), valueIndex);
                if (v != null) body.put("reasoning_effort", v);
            }
        }
    };

    private static final Style STYLE_INCLUDE_REASONING = new Style("include_reasoning",
            "include_reasoning + reasoning_effort（部分中转）", false) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            boolean on = level >= LEVEL_LOW;
            body.put("include_reasoning", on);
            if (on) {
                String v = pick(effortChain(level), valueIndex);
                if (v != null) body.put("reasoning_effort", v);
            }
        }
    };

    private static final Style STYLE_REASONING_EFFORT_PLAIN = new Style("reasoning_effort_plain",
            "reasoning_effort（裸传，部分中转不接受 reasoning 对象）", false) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            String v = pick(effortChain(level), valueIndex);
            if (v != null) body.put("reasoning_effort", v);
        }
    };

    private static final Style STYLE_REASONING_NESTED = new Style("reasoning_nested",
            "reasoning:{effort}（把档位名嵌在对象里，Groq / Together 系）", false) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            if (level < LEVEL_LOW) return;
            JSONObject r = new JSONObject();
            String v = pick(effortChain(level), valueIndex);
            if (v != null) r.put("effort", v);
            r.put("max_tokens", budgetFor(level));
            body.put("reasoning", r);
        }
    };

    private static final Style STYLE_THINKING_BUDGET_PLAIN = new Style("thinking_budget_plain",
            "thinking_budget（顶层整数，0 表示关闭）", true) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            body.put("thinking_budget", level < LEVEL_LOW ? 0 : budgetFor(level));
        }
    };

    private static final Style STYLE_CHAT_TEMPLATE = new Style("chat_template_kwargs",
            "chat_template_kwargs:{enable_thinking,budget}（vLLM / Qwen 自建）", true) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            JSONObject k = new JSONObject();
            boolean on = level >= LEVEL_LOW;
            k.put("enable_thinking", on);
            if (on) k.put("thinking_budget", budgetFor(level));
            body.put("chat_template_kwargs", k);
        }
    };

    private static final Style STYLE_OUTPUT_CONFIG = new Style("output_config_effort",
            "output_config:{effort}（Anthropic 新版 / 部分中转）", false) {
        @Override public void apply(JSONObject body, int level, int valueIndex) throws Exception {
            if (level < LEVEL_LOW) return;
            JSONObject o = new JSONObject();
            String v = pick(anthropicEffortChain(level), valueIndex);
            if (v != null) o.put("effort", v);
            body.put("output_config", o);
        }
    };

    private static final Style[] CANDIDATES = new Style[]{
            STYLE_REASONING_EFFORT,
            STYLE_REASONING_EFFORT_PLAIN,
            STYLE_ENABLE_THINKING,
            STYLE_THINKING_OBJECT,
            STYLE_THINKING_TYPE,
            STYLE_INCLUDE_REASONING,
            STYLE_REASONING_NESTED,
            STYLE_THINKING_BUDGET_PLAIN,
            STYLE_CHAT_TEMPLATE,
            STYLE_OUTPUT_CONFIG,
    };

    private static final int MAX_ATTEMPTS = 3;

    public static int maxAttempts() { return MAX_ATTEMPTS; }

    public static void probeReasoningAsync(final String baseUrl, final String apiKey,
                                           final String model, final int level) {
        probeReasoningAsync(baseUrl, apiKey, model, level, false);
    }

    public static void probeReasoningAsync(final String baseUrl, final String apiKey,
                                           final String model, final int level,
                                           final boolean suspectCachedStyle) {
        if (level < LEVEL_LOW) return;
        if (failureFresh(baseUrl, model)) return;
        if (!suspectCachedStyle) {
            CacheEntry cached = CACHE.get(cacheKey(baseUrl, model));
            if (cached != null && !cached.isFailure()) return;
        } else {

            forget(baseUrl, model);
            android.util.Log.i("VC_REASON", "缓存里的思考写法实测拿不到思考，已作废并重新探测");
        }

        if (!probeInFlight.compareAndSet(false, true)) return;

        Thread t = new Thread(() -> {
            try {
                int depth = Math.max(1, ladderDepth(level));
                int tried = 0;
                for (int vi = 0; vi < depth; vi++) {
                    for (Style s : CANDIDATES) {
                        if (tried++ >= PROBE_MAX_ATTEMPTS) break;
                        if (tryStyle(baseUrl, apiKey, model, s, level, vi)) {
                            remember(baseUrl, model, s, vi);
                            android.util.Log.i("VC_REASON", "后台探测成功：" + s.id
                                    + "（阶梯第 " + (vi + 1) + "/" + depth + " 级，已缓存，下次生效）");
                            return;
                        }
                    }
                }
                rememberFailure(baseUrl, model);
                android.util.Log.w("VC_REASON", "后台探测：所有思考参数写法都拿不到思考，标记失败缓存");
            } catch (Exception e) {
                android.util.Log.w("VC_REASON", "后台探测异常: " + e.getMessage());
            } finally {
                probeInFlight.set(false);
            }
        }, "reason-probe");
        t.setDaemon(true);
        t.start();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean probeInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static int candidateCount() {
        return CANDIDATES.length * 3;
    }

    /** 后台探测的尝试上限：写法多了之后必须封顶，否则全失败时要跑几十分钟。 */
    private static final int PROBE_MAX_ATTEMPTS = 12;

    public static int probeMaxAttempts() { return PROBE_MAX_ATTEMPTS; }

    private static class CacheEntry {
        final Style style;
        final int valueIndex;
        final long at;
        CacheEntry(Style s, int vi, long t) { style = s; valueIndex = vi; at = t; }
        boolean isFailure() { return style == null; }
    }

    private static final int CACHE_MAX = 64;

    private static final long FAIL_TTL_MS = 10 * 60 * 1000L;

    private static final Map<String, CacheEntry> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) {
                    return size() > CACHE_MAX;
                }
            });

    private static final String PREFS_NAME = "reasoning_negotiator";
    private static final String KEY_PREFIX = "style_";
    private static android.content.Context appCtx;

    public static synchronized void init(android.content.Context ctx) {
        if (ctx == null) return;
        appCtx = ctx.getApplicationContext();
        migrateCacheIfNeeded();
        loadFromDisk();
    }

    private static final int CACHE_FORMAT_VERSION = 3;
    private static final String KEY_VERSION = "cache_format_version";

    private static void migrateCacheIfNeeded() {
        if (appCtx == null) return;
        try {
            android.content.SharedPreferences sp =
                    appCtx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
            int v = sp.getInt(KEY_VERSION, 1);
            if (v >= CACHE_FORMAT_VERSION) return;

            android.content.SharedPreferences.Editor ed = sp.edit();
            for (String k : sp.getAll().keySet()) {
                if (k != null && k.startsWith(KEY_PREFIX)) ed.remove(k);
            }
            ed.putInt(KEY_VERSION, CACHE_FORMAT_VERSION).apply();
            android.util.Log.i("VC_REASON",
                    "思考参数缓存格式从 v" + v + " 升到 v" + CACHE_FORMAT_VERSION + "，旧结论已全部作废");
        } catch (Exception e) {
            android.util.Log.w("VC_REASON", "思考参数缓存迁移失败: " + e.getMessage());
        }
    }

    private static String diskKey(String baseUrl, String model) {
        return KEY_PREFIX + Integer.toHexString(cacheKey(baseUrl, model).hashCode());
    }

    private static void loadFromDisk() {
        if (appCtx == null) return;
        try {
            android.content.SharedPreferences sp =
                    appCtx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
            int n = 0;
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String k = e.getKey();
                if (k == null || !k.startsWith(KEY_PREFIX)) continue;

                String v = String.valueOf(e.getValue());
                String[] parts = v.split("\n", -1);
                if (parts.length != 4) continue;
                int valueIndex;
                try { valueIndex = Integer.parseInt(parts[3]); }
                catch (Exception ex) { continue; }
                Style s = styleById(parts[2]);
                if (s == null) continue;
                CACHE.put(cacheKey(parts[0], parts[1]),
                        new CacheEntry(s, valueIndex, System.currentTimeMillis()));
                ++n;
            }
            android.util.Log.i("VC_REASON", "已从磁盘恢复 " + n + " 条思考参数记忆");
        } catch (Exception ex) {
            android.util.Log.w("VC_REASON", "恢复思考参数记忆失败: " + ex.getMessage());
        }
    }

    private static void saveToDisk(String baseUrl, String model, Style style, int valueIndex) {
        if (appCtx == null || style == null) return;
        try {
            String raw = cacheKey(baseUrl, model);
            appCtx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PREFIX + Integer.toHexString(raw.hashCode()),
                            (baseUrl == null ? "" : baseUrl.trim()) + "\n"
                                    + (model == null ? "" : model.trim()) + "\n"
                                    + style.id + "\n" + valueIndex)
                    .apply();
        } catch (Exception ignored) { }
    }

    private static Style styleById(String id) {
        for (Style s : CANDIDATES) {
            if (s != null && s.id != null && s.id.equals(id)) return s;
        }
        return null;
    }

    private static String cacheKey(String baseUrl, String model) {
        return (baseUrl == null ? "" : baseUrl.trim()) + "|" + (model == null ? "" : model.trim());
    }

    public static void remember(String baseUrl, String model, Style style, int valueIndex) {
        CACHE.put(cacheKey(baseUrl, model), new CacheEntry(style, valueIndex, System.currentTimeMillis()));
        saveToDisk(baseUrl, model, style, valueIndex);
    }

    public static void rememberFailure(String baseUrl, String model) {
        CACHE.put(cacheKey(baseUrl, model), new CacheEntry(null, 0, System.currentTimeMillis()));
    }

    public static void forget(String baseUrl, String model) {
        CACHE.remove(cacheKey(baseUrl, model));

        if (appCtx != null) {
            try {
                appCtx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        .edit().remove(diskKey(baseUrl, model)).apply();
            } catch (Exception ignored) { }
        }
    }

    public static void clearAll() {
        CACHE.clear();

        if (appCtx != null) {
            try {
                android.content.SharedPreferences sp =
                        appCtx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
                android.content.SharedPreferences.Editor ed = sp.edit();
                for (String k : sp.getAll().keySet()) {
                    if (k != null && k.startsWith(KEY_PREFIX)) ed.remove(k);
                }
                ed.apply();
            } catch (Exception ignored) { }
        }
    }

    private static boolean failureFresh(String baseUrl, String model) {
        CacheEntry e = CACHE.get(cacheKey(baseUrl, model));
        return e != null && e.isFailure() && (System.currentTimeMillis() - e.at) < FAIL_TTL_MS;
    }

    public static Applied applyReasoning(JSONObject body, String baseUrl, String model,
                                         int level, int attempt) throws Exception {
        int lv = clampLevel(level);
        if (lv < LEVEL_LOW) return null;

        Style style;
        int valueIndex = 0;

        if (attempt <= 0) {
            CacheEntry cached = CACHE.get(cacheKey(baseUrl, model));
            if (cached != null && !cached.isFailure()) {
                style = cached.style;
                valueIndex = cached.valueIndex;
            } else {
                style = CANDIDATES[0];
            }
        } else {

            CacheEntry cached = CACHE.get(cacheKey(baseUrl, model));
            int baseStyle = 0;
            int baseValue = 0;
            if (cached != null && !cached.isFailure()) {
                baseStyle = indexOf(cached.style);
                baseValue = cached.valueIndex;
            }
            int flat = baseStyle + baseValue * CANDIDATES.length + attempt;
            int flatMax = CANDIDATES.length * Math.max(1, ladderDepth(lv));
            if (flat >= flatMax) return null;
            style = CANDIDATES[flat % CANDIDATES.length];
            valueIndex = flat / CANDIDATES.length;
        }

        style.apply(body, lv, valueIndex);
        return new Applied(style, valueIndex);
    }

    public static final class Applied {
        public final Style style;
        public final int valueIndex;
        Applied(Style s, int vi) { this.style = s; this.valueIndex = vi; }
    }

    private static int indexOf(Style s) {
        if (s == null) return 0;
        for (int i = 0; i < CANDIDATES.length; i++) {
            if (CANDIDATES[i] == s) return i;
        }
        return 0;
    }

    public static boolean shouldRetry(int level, int reasoningLength, int attempt, String baseUrl, String model) {
        if (clampLevel(level) < LEVEL_LOW) return false;
        if (reasoningLength > 0) return false;
        if (attempt + 1 >= MAX_ATTEMPTS) return false;
        return !failureFresh(baseUrl, model);
    }

    public static void rememberSuccess(String baseUrl, String model, Style usedStyle, int usedValueIndex) {
        if (usedStyle == null) return;
        remember(baseUrl, model, usedStyle, usedValueIndex);
    }

    public static boolean probeOnce(String baseUrl, String apiKey, String model, int level) {
        int depth = Math.max(1, ladderDepth(level));
        int tried = 0;
        for (int vi = 0; vi < depth; vi++) {
            for (Style s : CANDIDATES) {
                if (tried++ >= PROBE_MAX_ATTEMPTS) break;
                if (tryStyle(baseUrl, apiKey, model, s, level, vi)) {
                    remember(baseUrl, model, s, vi);
                    return true;
                }
            }
        }
        rememberFailure(baseUrl, model);
        return false;
    }

    private static boolean tryStyle(String baseUrl, String apiKey, String model, Style style, int level) {
        return tryStyle(baseUrl, apiKey, model, style, level, 0);
    }

    private static boolean tryStyle(String baseUrl, String apiKey, String model, Style style, int level,
                                    int valueIndex) {
        HttpURLConnection conn = null;
        try {
            String base = baseUrl == null ? "" : baseUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            JSONObject body = new JSONObject();
            body.put("model", model == null ? "" : model.trim());
            JSONArray msgs = new JSONArray();

            msgs.put(new JSONObject().put("role", "user")
                    .put("content", "A farmer must take a wolf, a sheep and a cabbage across a river. The boat can carry only one of them at a time, "
                            + "and in the farmer's absence the wolf eats the sheep and the sheep eats the cabbage. Give the complete sequence of crossings and explain why each step must be done that way."));
            body.put("messages", msgs);
            body.put("stream", true);

            body.put("max_tokens", 4096);
            style.apply(body, level, valueIndex);

            conn = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + (apiKey == null ? "" : apiKey.trim()));
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            if (conn.getResponseCode() >= 400) return false;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean found = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) break;
                try {
                    JSONObject chunk = new JSONObject(payload);
                    JSONArray chs = chunk.optJSONArray("choices");
                    if (chs == null || chs.length() == 0) continue;
                    JSONObject delta = chs.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    String rd = delta.optString("reasoning_content", "");
                    if (rd.isEmpty()) rd = delta.optString("reasoning", "");
                    if (!rd.isEmpty()) { found = true; break; }
                } catch (Exception ignored) { }
            }
            reader.close();
            return found;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
