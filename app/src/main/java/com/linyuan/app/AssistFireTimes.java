package com.linyuan.app;

import java.util.Calendar;
import java.util.List;

/**
 * 定时激活「下一次什么时候响」的纯计算。
 *
 * <p>刻意不碰任何 Android API —— 这样它能脱离设备直接跑单元验证
 * （调度算错就会整个功能不可用，必须能测）。
 *
 * <p>规则（用户 2026-10-13 定）：
 *   · 用户手动设定的时间点是周期性的：每天、或每星期固定的几天；
 *   · 助手自己设的是一次性的：响过即消失，最远只能定到 15 天后；
 *   · 两者取最近的一个。
 */
public final class AssistFireTimes {

    private AssistFireTimes() { }

    /** 助手自排：最早不能早于 5 分钟后（"5 分钟一档"）。 */
    public static final int SELF_MIN_LEAD_MIN = 5;
    /** 助手自排：最远 15 天。 */
    public static final int SELF_MAX_DAYS = 15;
    /** 助手自排：**创建节流** —— 每 5 分钟最多创建 1 个，不能一口气排十几个（用户 2026-10-13 定的核心限制）。 */
    public static final int SELF_CREATE_MIN_INTERVAL_MIN = 5;
    /** 助手自排：跟已排的任意一个至少隔开 5 分钟。 */
    public static final int SELF_MIN_GAP_MIN = 5;

    /**
     * 助手要新排一个时间点时的准入检查。**这是唯一的关卡** ——
     * 面板 UI、工具调用都走这里，规则不写在提示词里，写在系统里。
     *
     * @param now          现在
     * @param at           想排的时刻
     * @param existing     已有的自排时间点
     * @param lastCreateAt 上一次**成功创建**的时刻（0 = 从没建过）
     * @return null = 可以排；否则是给模型看的原因（人话，说明为什么不行、当前什么状态）
     */
    public static String checkSelfAdd(long now, long at, List<Long> existing, long lastCreateAt) {
        long createGap = SELF_CREATE_MIN_INTERVAL_MIN * 60_000L;
        if (lastCreateAt > 0 && now - lastCreateAt < createGap) {
            long left = (createGap - (now - lastCreateAt) + 59_999L) / 60_000L;
            return "Too frequent: you may create at most one wake-up every "
                    + SELF_CREATE_MIN_INTERVAL_MIN + " minutes. About " + left
                    + " minute(s) before you can set another. (This one was not created.)";
        }
        if (at < now + SELF_MIN_LEAD_MIN * 60_000L) {
            return "Too soon: a wake-up has to be at least " + SELF_MIN_LEAD_MIN
                    + " minutes ahead. Right now is " + fmt(now) + ".";
        }
        long limit = now + SELF_MAX_DAYS * 24L * 3600_000L;
        if (at > limit) {
            return "Too far: the furthest you can schedule is " + SELF_MAX_DAYS + " days ahead.";
        }
        if (existing != null) {
            long gap = SELF_MIN_GAP_MIN * 60_000L;
            for (Long e : existing) {
                if (e == null) continue;
                if (Math.abs(e - at) < gap) {
                    return "Too close to another wake-up you already have (" + fmt(e)
                            + ") - two of yours must be at least " + SELF_MIN_GAP_MIN + " minutes apart.";
                }
            }
        }
        return null;
    }

    static String fmt(long t) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(t));
    }

    /** 周一=1 … 周日=7（ISO）。Calendar 里周日=1、周六=7，这里换算。 */
    public static int isoDow(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
    }

    /**
     * 这一天是否符合用户选的星期。
     * days 为空 = 每天都算；否则是 "1,3,5" 这样的 ISO 星期列表。
     */
    public static boolean dayMatches(Calendar day, String days) {
        if (days == null || days.trim().isEmpty()) return true;
        int iso = isoDow(day);
        for (String s : days.split(",")) {
            try {
                if (Integer.parseInt(s.trim()) == iso) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** 把 "HH:mm" 落到某一天上，返回那一天的毫秒时间戳。解析不了就当 00:00。 */
    public static long atOnDay(Calendar day, String hhmm) {
        int h = 0, m = 0;
        try {
            String[] p = hhmm.split(":");
            h = Math.max(0, Math.min(23, Integer.parseInt(p[0].trim())));
            m = Math.max(0, Math.min(59, Integer.parseInt(p[1].trim())));
        } catch (Exception ignored) { }
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 下一次该在什么时候响。
     *
     * @param now     现在
     * @param on      用户的总开关是否打开
     * @param times   用户设的时间点，形如 ["00:00","03:00"]
     * @param days    用户选的星期（空 = 每天）
     * @param selfAts 助手自己设的一次性时间点（毫秒时间戳）
     * @return 下一个触发时刻；没有任何待触发项时返回 0
     */
    public static long next(long now, boolean on, List<String> times, String days, List<Long> selfAts) {
        // 总开关关着 = 整个定时激活功能停用，连助手自己排的时间点也不触发
        // （用户定：默认关闭，必须他选好并保存）
        if (!on) return 0;

        long best = Long.MAX_VALUE;

        if (times != null && !times.isEmpty()) {
            // 往后找 8 天必然覆盖「按星期」的任意组合（一周 7 天，多找一天防跨天边界）
            for (int d = 0; d < 8; d++) {
                Calendar day = Calendar.getInstance();
                day.setTimeInMillis(now);
                day.add(Calendar.DAY_OF_YEAR, d);
                if (!dayMatches(day, days)) continue;
                for (String t : times) {
                    long at = atOnDay(day, t);
                    if (at > now && at < best) best = at;
                }
                // 这一天里已经找到最早的时间点，就不用再往后看了
                if (best != Long.MAX_VALUE) break;
            }
        }

        if (selfAts != null) {
            for (Long at : selfAts) {
                if (at == null || at <= now) continue;
                if (at < best) best = at;
            }
        }

        return best == Long.MAX_VALUE ? 0 : best;
    }
}
