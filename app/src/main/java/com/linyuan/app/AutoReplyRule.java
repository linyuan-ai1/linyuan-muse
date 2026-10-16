package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AutoReplyRule {

    private static final String PREFS = "auto_reply";
    private static final String K_RULES = "rules";

    public long id;

    public String name = "";

    public int dayMask = 0x7F;

    public int startMinute = 1380;

    public int endMinute = 180;

    public boolean enabled = true;

    public String model = "";
    public String baseUrl = "";
    public String apiKey = "";
    public int thinkLevel = -1;

    public boolean containsMinute(int minuteOfDay) {
        if (startMinute == endMinute) return true;
        if (startMinute < endMinute) {
            return minuteOfDay >= startMinute && minuteOfDay <= endMinute;
        }

        return minuteOfDay >= startMinute || minuteOfDay <= endMinute;
    }

    public boolean matchesDay(int calendarDow) {
        int idx;
        switch (calendarDow) {
            case java.util.Calendar.MONDAY:    idx = 0; break;
            case java.util.Calendar.TUESDAY:   idx = 1; break;
            case java.util.Calendar.WEDNESDAY: idx = 2; break;
            case java.util.Calendar.THURSDAY:  idx = 3; break;
            case java.util.Calendar.FRIDAY:    idx = 4; break;
            case java.util.Calendar.SATURDAY:  idx = 5; break;
            case java.util.Calendar.SUNDAY:    idx = 6; break;
            default: return false;
        }
        return (dayMask & (1 << idx)) != 0;
    }

    public static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (dayMask == 0x7F) {
            sb.append("每天");
        } else if (dayMask == 0x1F) {
            sb.append("工作日");
        } else if (dayMask == 0x60) {
            sb.append("周末");
        } else {
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if ((dayMask & (1 << i)) != 0) {
                    if (d.length() > 0) d.append("、");
                    d.append("周").append(DAY_NAMES[i]);
                }
            }
            sb.append(d.length() == 0 ? "（未选星期）" : d.toString());
        }
        sb.append(" ").append(fmtMinute(startMinute)).append("–").append(fmtMinute(endMinute));
        if (startMinute > endMinute) sb.append("（次日）");
        return sb.toString();
    }

    public static String fmtMinute(int m) {
        return String.format(java.util.Locale.CHINA, "%02d:%02d", (m / 60) % 24, m % 60);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<AutoReplyRule> list(Context c, boolean onlyEnabled) {
        List<AutoReplyRule> out = new ArrayList<>();
        try {
            String raw = prefs(c).getString(K_RULES, "");
            if (raw == null || raw.trim().isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                AutoReplyRule r = fromJson(o);
                if (onlyEnabled && !r.enabled) continue;
                out.add(r);
            }
        } catch (Throwable t) {
            android.util.Log.w("VC_AUTOREPLY", "读时段规则失败: " + t);
        }
        return out;
    }

    public static void saveAll(Context c, List<AutoReplyRule> rules) {
        try {
            JSONArray arr = new JSONArray();
            for (AutoReplyRule r : rules) arr.put(toJson(r));
            prefs(c).edit().putString(K_RULES, arr.toString()).apply();
        } catch (Throwable t) {
            android.util.Log.w("VC_AUTOREPLY", "写时段规则失败: " + t);
        }
    }

    public static void upsert(Context c, AutoReplyRule rule) {
        List<AutoReplyRule> all = list(c, false);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == rule.id) { all.set(i, rule); replaced = true; break; }
        }
        if (!replaced) {
            if (rule.id <= 0) rule.id = System.currentTimeMillis();
            all.add(rule);
        }
        saveAll(c, all);
    }

    public static void delete(Context c, long id) {
        List<AutoReplyRule> all = list(c, false);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id == id) all.remove(i);
        }
        saveAll(c, all);
    }

    private static JSONObject toJson(AutoReplyRule r) throws Exception {
        return new JSONObject()
                .put("id", r.id)
                .put("name", r.name == null ? "" : r.name)
                .put("dayMask", r.dayMask)
                .put("start", r.startMinute)
                .put("end", r.endMinute)
                .put("enabled", r.enabled)

                .put("model", r.model == null ? "" : r.model)
                .put("baseUrl", r.baseUrl == null ? "" : r.baseUrl)
                .put("apiKey", r.apiKey == null ? "" : r.apiKey)
                .put("thinkLevel", r.thinkLevel);
    }

    private static AutoReplyRule fromJson(JSONObject o) {
        AutoReplyRule r = new AutoReplyRule();
        r.id = o.optLong("id", 0);
        r.name = o.optString("name", "");
        r.dayMask = o.optInt("dayMask", 0x7F);
        r.startMinute = o.optInt("start", 1380);
        r.endMinute = o.optInt("end", 180);
        r.enabled = o.optBoolean("enabled", true);

        r.model = o.optString("model", "");
        r.baseUrl = o.optString("baseUrl", "");
        r.apiKey = o.optString("apiKey", "");
        r.thinkLevel = o.optInt("thinkLevel", -1);
        return r;
    }
}
