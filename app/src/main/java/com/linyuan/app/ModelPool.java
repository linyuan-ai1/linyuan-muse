package com.linyuan.app;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.os.SystemClock;

public class ModelPool {

    public static final String PURPOSE_ASR = "asr";
    public static final String PURPOSE_TTS = "tts";

    public static class Entry {
        public final String purpose;
        public final String model;
        public final String voice;
        public final boolean monthly;
        public final String expiry;
        public final String quota;

        Entry(String purpose, String model, String voice, boolean monthly, String expiry, String quota) {
            this.purpose = purpose;
            this.model = model;
            this.voice = voice;
            this.monthly = monthly;
            this.expiry = expiry;
            this.quota = quota;
        }

        public boolean isLimited() { return !monthly; }
    }

    private static final Entry[] CATALOG = {

            new Entry(PURPOSE_ASR, "qwen-audio-3.1-asr-flash", null, false, "2026-12-21", "1M tokens（限时 77 天）"),
            new Entry(PURPOSE_ASR, "qwen-audio-3.0-asr-flash", null, false, "2026-10-27", "36K tokens（限时 22 天）"),

            new Entry(PURPOSE_TTS, "cosyvoice-v1", "longxiaochun", true, null, "1 万字符/月"),
            new Entry(PURPOSE_TTS, "cosyvoice-clone-v1", "longxiaochun", true, null, "1 万字符/月"),
            new Entry(PURPOSE_TTS, "sambert-zhichu-v1", "zhichu", true, null, "3 万字符/月"),
            new Entry(PURPOSE_TTS, "sambert-zhide-v1", "zhide", true, null, "3 万字符/月"),
            new Entry(PURPOSE_TTS, "qwen-audio-3.1-tts-next", "Cherry", false, "2026-12-21", "1M tokens（限时 77 天，较慢）"),
    };

    private static final String ST_OK = "OK";
    private static final String ST_EXHAUSTED = "EXHAUSTED";

    private static Map<String, String> parseState(AppSettings s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.modelPoolState == null) return m;
        for (String line : s.modelPoolState.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq > 0) m.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return m;
    }

    private static void saveState(AppSettings s, Map<String, String> m) {
        if (s == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        s.modelPoolState = sb.toString();
    }

    private static volatile long netEpochMs = 0L;
    private static volatile long netAtElapsed = 0L;
    private static final long NET_CACHE_MS = 60 * 60 * 1000L;

    public static long nowMs() {
        long elapsed = SystemClock.elapsedRealtime();
        if (netEpochMs > 0 && elapsed - netAtElapsed < NET_CACHE_MS) {
            return netEpochMs + (elapsed - netAtElapsed);
        }
        long fetched = fetchNetworkTime();
        if (fetched > 0) {
            netEpochMs = fetched;
            netAtElapsed = elapsed;
            return fetched;
        }

        android.util.Log.w("VC_POOL", "网络授时失败，暂用手机本地时间兜底");
        return System.currentTimeMillis();
    }

    private static long fetchNetworkTime() {
        for (String host : new String[]{"https://dashscope.aliyuncs.com/", "https://www.aliyun.com/"}) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(host).openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                if (code > 0) {
                    long date = c.getDate();
                    if (date > 0) return date;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return 0;
    }

    public static void invalidateNetworkTime() {
        netEpochMs = 0L;
        netAtElapsed = 0L;
    }

    public static void refreshNetworkTimeAsync() {
        if (netEpochMs > 0 && SystemClock.elapsedRealtime() - netAtElapsed < NET_CACHE_MS) return;
        new Thread(() -> {
            try { nowMs(); } catch (Exception ignored) { }
        }, "vc-net-time").start();
    }

    private static String monthKey(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
    }

    private static String dateKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    public static List<Entry> available(String purpose, AppSettings s) {
        long now = nowMs();
        String today = dateKey(now);
        String thisMonth = monthKey(now);
        Map<String, String> state = parseState(s);

        List<Entry> out = new ArrayList<>();
        for (Entry e : CATALOG) {
            if (!e.purpose.equals(purpose)) continue;

            if (e.isLimited() && e.expiry != null && today.compareTo(e.expiry) > 0) {
                state.remove(e.model);
                continue;
            }

            String st = state.get(e.model);
            if (st != null && st.startsWith(ST_EXHAUSTED)) {
                String markedMonth = st.contains("|") ? st.substring(st.indexOf('|') + 1) : "";
                if (e.monthly) {

                    if (!thisMonth.equals(markedMonth)) {
                        state.remove(e.model);
                        android.util.Log.i("VC_POOL", e.model + " 跨月自动重置（" + markedMonth + " → " + thisMonth + "）");
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }
            out.add(e);
        }
        saveState(s, state);
        return out;
    }

    public static void markExhausted(String purpose, String model, AppSettings s) {
        Map<String, String> state = parseState(s);
        state.put(model, ST_EXHAUSTED + "|" + monthKey(nowMs()));
        saveState(s, state);
        android.util.Log.w("VC_POOL", model + " 额度用尽，已标记（" +
                (isMonthly(model) ? "下月 1 号自动恢复" : "限时额度已用完，永久剔除") + "）");

        if (onStateChanged != null) onStateChanged.run();
    }

    public static void markOk(String model, AppSettings s) {
        Map<String, String> state = parseState(s);
        if (state.containsKey(model)) {
            state.remove(model);
            saveState(s, state);
            if (onStateChanged != null) onStateChanged.run();
        }
    }

    private static boolean isMonthly(String model) {
        for (Entry e : CATALOG) if (e.model.equals(model)) return e.monthly;
        return false;
    }

    public static volatile Runnable onStateChanged;

    public static boolean isQuotaError(Throwable e) {
        if (e == null || e.getMessage() == null) return false;
        String m = e.getMessage().toLowerCase(Locale.US);
        return m.contains("allocationquota")
                || m.contains("freetieronly")
                || m.contains("free quota")
                || m.contains("quota")
                || m.contains("throttling")
                || m.contains("arrears")
                || m.contains("欠费")
                || m.contains("额度");
    }

    public static String statusText(AppSettings s) {
        Map<String, String> state = parseState(s);
        long now = nowMs();
        String today = dateKey(now);
        String thisMonth = monthKey(now);
        StringBuilder sb = new StringBuilder();

        sb.append("🎛️ 语音模型额度池（用完一个自动换下一个）\n");
        sb.append("授时：网络时间 ").append(today)
          .append("（本地 ").append(dateKey(System.currentTimeMillis())).append("）\n\n");

        sb.append("【转文字】\n");
        for (Entry e : CATALOG) {
            if (!e.purpose.equals(PURPOSE_ASR)) continue;
            sb.append("  ").append(entryLine(e, state, today, thisMonth)).append('\n');
        }
        sb.append("\n【转语音】\n");
        for (Entry e : CATALOG) {
            if (!e.purpose.equals(PURPOSE_TTS)) continue;
            sb.append("  ").append(entryLine(e, state, today, thisMonth)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String entryLine(Entry e, Map<String, String> state, String today, String thisMonth) {
        String st = state.get(e.model);
        boolean exhausted = st != null && st.startsWith(ST_EXHAUSTED);
        String markedMonth = (st != null && st.contains("|")) ? st.substring(st.indexOf('|') + 1) : "";

        if (e.isLimited() && e.expiry != null && today.compareTo(e.expiry) > 0) {
            return "🗑 " + e.model + "（" + e.quota + "）已到期 " + e.expiry + "，自动清理";
        }
        if (exhausted) {
            if (e.monthly) {
                if (!thisMonth.equals(markedMonth)) {
                    return "♻️ " + e.model + "（" + e.quota + "）上月用尽，本月已自动恢复";
                }
                return "⏳ " + e.model + "（" + e.quota + "）本月额度用尽，下月 1 号自动恢复";
            }
            return "🗑 " + e.model + "（" + e.quota + "）限时额度已用完，永久剔除";
        }
        if (e.isLimited()) {
            long days = daysBetween(today, e.expiry);
            return "⭐ " + e.model + "（" + e.quota + "）限时 · 至 " + e.expiry + "（剩 " + days + " 天）【打头阵】";
        }
        return "✅ " + e.model + "（" + e.quota + "）每月 1 日重置 · 可用";
    }

    private static long daysBetween(String from, String to) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long a = f.parse(from).getTime(), b = f.parse(to).getTime();
            return Math.max(0, Math.round((b - a) / 86400000.0));
        } catch (Exception e) {
            return 0;
        }
    }
}
