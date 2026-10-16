package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SubAgentLog {

    private static final String PREFS = "assistant_panel";
    private static final String KEY_PREFIX = "subagent_log_";
    private static final int MAX_RUNS = 60;

    public static class Node {
        public String type = "";
        public String judge = "";
        public String action = "";
        public String result = "";
        public String remark = "";
    }

    public static class Run {
        public String id = "";
        public long at = 0L;
        public long costMs = 0L;
        public String caller = "";
        public String task = "";
        public String outcome = "";
        public String summary = "";
        public int nodes = 0;
        public String lesson = "";
        public List<Node> steps = new ArrayList<>();

        public boolean ok() {
            return "ok".equals(outcome);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String subId) {
        return KEY_PREFIX + (subId == null ? "" : subId);
    }

    public static List<Run> list(Context c, String subId) {
        List<Run> out = new ArrayList<>();
        String raw = prefs(c).getString(key(subId), "");
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Run r = new Run();
                r.id = o.optString("id", "");
                r.at = o.optLong("at", 0L);
                r.costMs = o.optLong("costMs", 0L);
                r.caller = o.optString("caller", "");
                r.task = o.optString("task", "");
                r.outcome = o.optString("outcome", "");
                r.summary = o.optString("summary", "");
                r.nodes = o.optInt("nodes", 0);
                r.lesson = o.optString("lesson", "");
                JSONArray st = o.optJSONArray("steps");
                if (st != null) {
                    for (int j = 0; j < st.length(); j++) {
                        JSONObject so = st.optJSONObject(j);
                        if (so == null) continue;
                        Node n = new Node();
                        n.type = so.optString("t", "");
                        n.judge = so.optString("j", "");
                        n.action = so.optString("a", "");
                        n.result = so.optString("r", "");
                        n.remark = so.optString("e", "");
                        r.steps.add(n);
                    }
                }
                out.add(r);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_SUBAGENT", "子智能体记录读不出来: " + e.getMessage());
        }
        return out;
    }

    public static void add(Context c, String subId, Run r) {
        if (r == null) return;
        if (r.id == null || r.id.trim().isEmpty()) {
            r.id = "run_" + System.currentTimeMillis();
        }
        List<Run> all = list(c, subId);
        all.add(0, r);
        while (all.size() > MAX_RUNS) all.remove(all.size() - 1);
        write(c, subId, all);
    }

    public static void clear(Context c, String subId) {
        write(c, subId, new ArrayList<Run>());
    }

    private static void write(Context c, String subId, List<Run> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Run r : all) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("at", r.at);
                o.put("costMs", r.costMs);
                o.put("caller", r.caller);
                o.put("task", r.task);
                o.put("outcome", r.outcome);
                o.put("summary", r.summary);
                o.put("nodes", r.nodes);
                o.put("lesson", r.lesson);
                JSONArray st = new JSONArray();
                for (Node n : r.steps) {
                    JSONObject so = new JSONObject();
                    so.put("t", n.type);
                    so.put("j", n.judge);
                    so.put("a", n.action);
                    so.put("r", n.result);
                    so.put("e", n.remark);
                    st.put(so);
                }
                o.put("steps", st);
                arr.put(o);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_SUBAGENT", "子智能体记录写不进去: " + e.getMessage());
        }
        prefs(c).edit().putString(key(subId), arr.toString()).apply();
    }

    static String fmtTime(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    static String fmtCost(long ms) {
        if (ms <= 0) return "";
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m" + (s % 60) + "s";
    }

    static String typeName(String code) {
        if ("DECIDE".equals(code)) return "判断";
        if ("ACT".equals(code)) return "动作";
        if ("OBSERVE".equals(code)) return "获知";
        if ("MUTATE".equals(code)) return "改动";
        if ("EXCEPTION".equals(code)) return "异常";
        if ("OUTPUT".equals(code)) return "产出";
        if ("LEARN".equals(code)) return "教训";
        return code == null || code.isEmpty() ? "动作" : code;
    }
}
