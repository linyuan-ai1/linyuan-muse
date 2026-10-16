package com.linyuan.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TtsPool {

    public static class Entry {

        public final String platform;
        public final String baseUrl;
        public final String apiKey;
        public final String model;
        public final String voice;
        public final String rawLine;

        Entry(String platform, String baseUrl, String apiKey, String model, String voice, String rawLine) {
            this.platform = platform;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.voice = voice;
            this.rawLine = rawLine;
        }

        public String label() {
            return platform + "/" + com.linyuan.app.AiClient.maskKey(apiKey);
        }
    }

    private static final List<Entry> pool = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final Map<String, Long> lastUsedAt = new ConcurrentHashMap<>();

    private static final Map<String, Long> disabledUntil = new ConcurrentHashMap<>();

    private static final AtomicInteger cursor = new AtomicInteger(0);

    public static volatile long cooldownMs = 10000L;

    public static volatile long disableMs = 30 * 60 * 1000L;

    public static int size() { return pool.size(); }

    public static boolean isEmpty() { return pool.isEmpty(); }

    public static List<Entry> entries() { return Collections.unmodifiableList(pool); }

    public static void refresh(AppSettings settings) {
        pool.clear();
        if (settings == null) return;
        cooldownMs = Math.max(0, settings.ttsPoolCooldownSec) * 1000L;
        disableMs = Math.max(60, settings.ttsPoolDisableMin) * 60 * 1000L;

        String raw = settings.ttsKeyPool;
        if (raw == null || raw.trim().isEmpty()) return;

        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            Entry e = parseLine(s);
            if (e != null) pool.add(e);
        }
        android.util.Log.i("VC_TTS", "TTS 密钥池已加载 " + pool.size() + " 个密钥，冷却 "
                + (cooldownMs / 1000) + " 秒，禁用时长 " + (disableMs / 60000) + " 分钟");
    }

    private static Entry parseLine(String s) {
        try {
            int bar = s.indexOf('|');
            if (bar <= 0) {

                return new Entry("", null, s, null, null, s);
            }
            String[] parts = s.split("\\|", -1);
            String platform = parts[0].trim().toLowerCase();
            String base = parts.length > 1 ? emptyToNull(parts[1]) : null;
            String key = parts.length > 2 ? parts[2].trim() : "";
            String model = parts.length > 3 ? emptyToNull(parts[3]) : null;
            String voice = parts.length > 4 ? emptyToNull(parts[4]) : null;

            if (parts.length == 2) {
                String second = parts[1].trim();
                if (!second.startsWith("http")) {
                    key = second;
                    base = null;
                }
            }
            if (key.isEmpty()) return null;

            if (platform.contains("dashscope") || platform.contains("bailian") || platform.contains("aliyun")) {
                platform = "aliyun";
            } else if (platform.contains("doubao") || platform.contains("volc") || platform.contains("huoshan")) {
                platform = "doubao";
            } else if (platform.isEmpty()) {
                platform = "";
            } else {
                platform = "openai";
            }
            return new Entry(platform, base, key, model, voice, s);
        } catch (Exception e) {
            android.util.Log.w("VC_TTS", "TTS 池解析失败: " + s);
            return null;
        }
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static Entry next() {
        if (pool.isEmpty()) return null;
        long now = System.currentTimeMillis();
        int start = Math.floorMod(cursor.getAndIncrement(), pool.size());

        Entry fallback = null;
        long fallbackUsedAt = Long.MAX_VALUE;

        for (int i = 0; ; i++) {
            int cur = pool.size();
            if (cur == 0) return null;
            if (i >= cur) break;
            Entry e = pool.get(Math.floorMod(start + i, cur));
            Long dis = disabledUntil.get(e.apiKey);
            if (dis != null && now < dis) continue;
            Long last = lastUsedAt.get(e.apiKey);
            long usedAt = last == null ? 0L : last;
            if (now - usedAt >= cooldownMs) {
                lastUsedAt.put(e.apiKey, now);
                return e;
            }
            if (usedAt < fallbackUsedAt) {
                fallbackUsedAt = usedAt;
                fallback = e;
            }
        }
        if (fallback != null) {
            lastUsedAt.put(fallback.apiKey, now);
        }
        return fallback;
    }

    public static int readyCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Entry e : pool) {
            Long dis = disabledUntil.get(e.apiKey);
            if (dis != null && now < dis) continue;
            Long last = lastUsedAt.get(e.apiKey);
            if (last == null || now - last >= cooldownMs) n++;
        }
        return n;
    }

    public static int disabledCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Entry e : pool) {
            Long dis = disabledUntil.get(e.apiKey);
            if (dis != null && now < dis) n++;
        }
        return n;
    }

    public static void markDisabled(Entry e, String reason) {
        if (e == null) return;
        disabledUntil.put(e.apiKey, System.currentTimeMillis() + disableMs);
        android.util.Log.w("VC_TTS", "密钥 " + e.label() + " 已停用 "
                + (disableMs / 60000) + " 分钟：" + reason);
    }

    public static void resetDisabled() {
        disabledUntil.clear();
        android.util.Log.i("VC_TTS", "TTS 密钥池：已重置全部禁用状态");
    }

    public static void clear() {
        pool.clear();
        lastUsedAt.clear();
        disabledUntil.clear();
        cursor.set(0);
    }

    public static boolean isKeyUnusable(Throwable t) {
        if (t == null) return false;
        String m = String.valueOf(t.getMessage());
        if (m == null) return false;
        String s = m.toLowerCase();
        String[] signals = {
                "quota", "insufficient", "balance", "arrears", "overdue",
                "额度", "欠费", "余额不足", "已达上限", "超出", "用尽", "耗尽",
                "invalid api key", "invalidapikey", "unauthorized", "forbidden",
                "invalid_api_key", "authentication", "鉴权", "无效的密钥", "未授权",
                "suspended", "disabled", "not activated", "未开通", "未激活",
                "401", "403",
        };
        for (String sig : signals) {
            if (s.contains(sig)) return true;
        }
        return false;
    }
}
