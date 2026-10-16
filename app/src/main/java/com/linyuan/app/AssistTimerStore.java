package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 主助手「定时激活」的状态存储（全部落 SharedPreferences，进程被杀也不丢）。
 *
 * <p>两类触发：
 *   · 用户设的周期时间点（每天 / 每星期固定几天）—— 助手不能动它；
 *   · 助手自己设的一次性时间点 —— 响过即消失，最远 15 天。
 *
 * <p>失败也记在这里：最终失败记一条日志（供用户在弹窗里看原因），
 * 并累加「未读失败数」给卡片上的红气泡用（类似微信小红点，读了清掉）。
 */
public final class AssistTimerStore {

    private AssistTimerStore() { }

    /** 跟助手面板同一份 prefs，面板和后台都用它。 */
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(AssistantPanel.PREFS, Context.MODE_PRIVATE);
    }

    /** 助手自己设的时间点最多能排到未来这么多天。 */
    public static final int SELF_MAX_DAYS = AssistFireTimes.SELF_MAX_DAYS;
    /** 助手自排的最短提前量（5 分钟一档）。 */
    public static final int SELF_MIN_LEAD_MIN = AssistFireTimes.SELF_MIN_LEAD_MIN;
    /** 失败日志最多留多少条。 */
    private static final int FAIL_LOG_CAP = 50;

    // ---------- 用户设的周期时间点 ----------

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean("at_enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("at_enabled", on).apply();
    }

    /** 形如 ["00:00","03:00"]。 */
    public static String timesRaw(Context c) {
        return prefs(c).getString("at_times", "");
    }

    public static List<String> times(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : timesRaw(c).split(",")) {
            String t = s.trim();
            if (t.matches("\\d{1,2}:\\d{2}")) out.add(norm(t));
        }
        return out;
    }

    public static void setTimes(Context c, List<String> list) {
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (String t : list) {
                if (t == null || !t.matches("\\d{1,2}:\\d{2}")) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(norm(t));
            }
        }
        prefs(c).edit().putString("at_times", sb.toString()).apply();
    }

    /** ISO 星期（1=周一 … 7=周日），空 = 每天。 */
    public static String daysRaw(Context c) {
        return prefs(c).getString("at_days", "");
    }

    public static void setDays(Context c, String days) {
        prefs(c).edit().putString("at_days", days == null ? "" : days.trim()).apply();
    }

    /** 把 "9:5" 之类补成 "09:05"。 */
    public static String norm(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            int h = Math.max(0, Math.min(23, Integer.parseInt(p[0].trim())));
            int m = Math.max(0, Math.min(59, Integer.parseInt(p[1].trim())));
            return String.format(java.util.Locale.ROOT, "%02d:%02d", h, m);
        } catch (Exception e) {
            return "00:00";
        }
    }

    // ---------- 助手自己设的一次性时间点 ----------

    public static class SelfAt {
        public long id;
        public long at;
        public String note;
    }

    public static List<SelfAt> selfList(Context c) {
        List<SelfAt> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("at_self", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                SelfAt s = new SelfAt();
                s.id = o.optLong("id", 0);
                s.at = o.optLong("at", 0);
                s.note = o.optString("note", "");
                if (s.at > 0) out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void writeSelf(Context c, List<SelfAt> list) {
        try {
            JSONArray arr = new JSONArray();
            for (SelfAt s : list) {
                arr.put(new JSONObject().put("id", s.id).put("at", s.at)
                        .put("note", s.note == null ? "" : s.note));
            }
            prefs(c).edit().putString("at_self", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    /**
     * 助手排一个一次性时间点。
     *
     * <p>准入规则全部在 {@link AssistFireTimes#checkSelfAdd}（**每 5 分钟最多创建 1 个**、
     * 最早 5 分钟后、最远 15 天、彼此至少隔 5 分钟）—— **规则在系统里，不在提示词里**。
     * 写之前顺手清掉已经过去、再也响不了的条目。
     */
    public static AddResult addSelf(Context c, long atMillis, String note) {
        List<Long> one = new ArrayList<>();
        one.add(atMillis);
        return addSelfMany(c, one, note);
    }

    /**
     * 一次排多个时间点（用户允许它"设某一天的某几个时间段"）。
     *
     * <p>整批算**一次创建**（不违反"每 5 分钟最多创建一个"）；但每个点自己仍然要过
     * {@link AssistFireTimes#checkSelfAdd}，所以它们之间、以及跟已排的之间都必须
     * 至少隔 5 分钟——"一下排十几个都在同一分钟"依然是不可能的。
     * 只要其中一个不合格，整批都不写入（避免半成功、状态不清）。
     */
    public static AddResult addSelfMany(Context c, List<Long> atList, String note) {
        long now = System.currentTimeMillis();
        List<SelfAt> list = selfList(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).at <= now) list.remove(i);
        }
        List<Long> exist = new ArrayList<>();
        for (SelfAt e : list) exist.add(e.at);

        AddResult r = new AddResult();
        if (atList == null || atList.isEmpty()) {
            r.why = "no time given";
            return r;
        }
        // 逐个校验；已接受的也进 exist，这样同批之间也受 5 分钟间隔约束
        long lastCreate = lastCreateAt(c);
        for (Long at : atList) {
            if (at == null) continue;
            String why = AssistFireTimes.checkSelfAdd(now, at, exist, lastCreate);
            if (why != null) {
                r.why = why;
                writeSelf(c, list);   // 清掉的旧条目仍要落盘
                return r;
            }
            exist.add(at);
            lastCreate = 0;           // 同一批里后续的点不再重复算"创建节流"
        }

        java.util.Collections.sort(exist);
        // 重建列表：保留没过去的旧的 + 新加的
        List<SelfAt> out = new ArrayList<>();
        List<Long> newOnes = new ArrayList<>(atList);
        java.util.Collections.sort(newOnes);
        long idBase = System.currentTimeMillis();
        java.util.Set<Long> usedIds = new java.util.HashSet<>();
        for (SelfAt e : list) usedIds.add(e.id);
        for (Long at : newOnes) {
            if (at == null) continue;
            SelfAt s = new SelfAt();
            s.id = idBase++;
            while (usedIds.contains(s.id)) s.id++;
            usedIds.add(s.id);
            s.at = at;
            s.note = note == null ? "" : note.trim();
            out.add(s);
        }
        r.ids = new ArrayList<>();
        List<SelfAt> all = new ArrayList<>(list);
        all.addAll(out);
        // 不设条数上限：用户定的规则只有「每 5 分钟最多创建 1 个」+「最远 15 天」，
        // 15 天内的容量按规则自己就是有界的，不需要我再加一层。
        writeSelf(c, all);
        for (SelfAt s : out) r.ids.add(s.id);
        r.id = r.ids.isEmpty() ? -1 : r.ids.get(0);
        prefs(c).edit().putLong("at_self_last_create", now).apply();
        return r;
    }

    /** 上一次成功创建自排时间点的时刻（0 = 从没建过）。 */
    public static long lastCreateAt(Context c) {
        return prefs(c).getLong("at_self_last_create", 0);
    }

    /** 排一个时间点的结果：成功看 id / ids，失败看 why。 */
    public static class AddResult {
        public long id = -1;
        /** 批量创建时，本次新建的全部 id。 */
        public List<Long> ids;
        public String why;
    }

    public static boolean removeSelf(Context c, long id) {
        List<SelfAt> list = selfList(c);
        boolean hit = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) { list.remove(i); hit = true; }
        }
        if (hit) writeSelf(c, list);
        return hit;
    }

    public static void clearSelf(Context c) {
        prefs(c).edit().putString("at_self", "[]").apply();
    }

    // ---------- 失败记录（红气泡） ----------

    public static int failUnseen(Context c) {
        return prefs(c).getInt("at_fail_unseen", 0);
    }

    public static void clearFailUnseen(Context c) {
        prefs(c).edit().putInt("at_fail_unseen", 0).apply();
    }

    public static class FailRec {
        public long at;
        public String reason;
        public String src;
    }

    public static List<FailRec> failLog(Context c) {
        List<FailRec> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("at_fail_log", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                FailRec f = new FailRec();
                f.at = o.optLong("at", 0);
                f.reason = o.optString("reason", "");
                f.src = o.optString("src", "");
                out.add(f);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 记一次「激活彻底失败」。 */
    public static void recordFail(Context c, String reason, String src) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("at_fail_log", "[]"));
            // 新的在前
            JSONArray next = new JSONArray();
            next.put(new JSONObject().put("at", System.currentTimeMillis())
                    .put("reason", reason == null ? "" : reason)
                    .put("src", src == null ? "" : src));
            for (int i = 0; i < arr.length() && i < FAIL_LOG_CAP - 1; i++) {
                next.put(arr.opt(i));
            }
            SharedPreferences.Editor ed = prefs(c).edit();
            ed.putString("at_fail_log", next.toString());
            ed.putInt("at_fail_unseen", failUnseen(c) + 1);
            ed.apply();
        } catch (Exception ignored) { }
    }

    public static void clearFailLog(Context c) {
        SharedPreferences.Editor ed = prefs(c).edit();
        ed.putString("at_fail_log", "[]");
        ed.putInt("at_fail_unseen", 0);
        ed.apply();
    }
}
