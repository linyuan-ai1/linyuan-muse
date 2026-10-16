package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class WorkSound {

    private static final String TAG = "VC_WORKSOUND";
    private static final String PREFS = "voice_companion";

    private WorkSound() { }

    public static final String[][] TONES = {

            {"叮（清脆）",         "tone_ding",         "单音 A5，最简洁"},
            {"叮咚（愉快）",       "tone_dingdong",     "E5→C6 双音上行"},
            {"三音上行（正式）",    "tone_chime3",       "C5-E5-G5，像系统提示"},
            {"啵（极轻）",         "tone_pop",          "0.28 秒，几乎不打扰"},
            {"柔和（安静）",       "tone_soft",         "低沉温柔，适合夜间"},
            {"完成（四音）",       "tone_done",         "C-E-G-C 上行，明确做完"},
            {"提醒（两短一长）",    "tone_alert",        "有「请看这里」意味"},
            {"极简（最短）",       "tone_minimal",      "0.22 秒最轻一声"},

            {"★ 噔噔噔（参考 Reasonix）", "tone_rx_success",   "E6→G6→C7 三音上行，就是那种连续感"},
            {"★ 询问（参考 Reasonix）",   "tone_rx_attention", "A6→E6 两音下行，需要你回应"},
            {"★ 干活（轻点）",            "tone_rx_tool",      "C7 单音极短，工具调用用"},
            {"★ 思考（最轻）",            "tone_rx_think",     "E6 极短，思考用（频繁触发）"},
    };

    public static int toneCount() { return TONES.length; }

    public static String toneLabel(int idx) { return TONES[clampIdx(idx)][0]; }

    public static String toneDesc(int idx) { return TONES[clampIdx(idx)][2]; }

    private static int clampIdx(int i) { return (i < 0 || i >= TONES.length) ? 0 : i; }

    static int toneResId(Context ctx, int idx) {
        return ctx.getResources().getIdentifier(TONES[clampIdx(idx)][1], "raw", ctx.getPackageName());
    }

    private static final int DEF_TOOL = 10;
    private static final int DEF_THINK = 11;
    private static final int DEF_REPLY = 9;
    private static final int DEF_DONE = 5;
    private static final int DEF_ASK = 1;

    public static final int EV_TOOL = 0;
    public static final int EV_THINK = 1;
    public static final int EV_REPLY = 2;
    public static final int EV_DONE = 3;
    public static final int EV_ASK = 4;

    public static final int EV_ALERT = 5;

    public static final int EV_COUNT = 6;

    public static String evName(int ev) {
        switch (ev) {
            case EV_TOOL: return "工具调用";
            case EV_THINK: return "深度思考";
            case EV_REPLY: return "生成回复";
            case EV_DONE: return "本轮完成";
            case EV_ASK: return "需要你回答";
            case EV_ALERT: return "异常告警";
            default: return "事件";
        }
    }

    public static boolean isStreamEvent(int ev) {
        return ev == EV_THINK || ev == EV_REPLY;
    }

    private static int defTone(int ev) {
        switch (ev) {
            case EV_TOOL: return DEF_TOOL;
            case EV_THINK: return DEF_THINK;
            case EV_REPLY: return DEF_REPLY;
            case EV_DONE: return DEF_DONE;
            case EV_ASK: return DEF_ASK;

            case EV_ALERT: return 6;
            default: return 0;
        }
    }

    private static boolean defEnabled(int ev) {
        return ev == EV_DONE || ev == EV_ASK || ev == EV_ALERT;
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx, int ev) {
        return sp(ctx).getBoolean("workSound_ev" + ev + "_on", defEnabled(ev));
    }

    public static void setEnabled(Context ctx, int ev, boolean on) {
        sp(ctx).edit().putBoolean("workSound_ev" + ev + "_on", on).apply();
        if (on) ensureLoaded(ctx, toneOf(ctx, ev));
    }

    public static int toneOf(Context ctx, int ev) {
        return clampIdx(sp(ctx).getInt("workSound_ev" + ev + "_tone", defTone(ev)));
    }

    public static void setTone(Context ctx, int ev, int idx) {
        sp(ctx).edit().putInt("workSound_ev" + ev + "_tone", clampIdx(idx)).apply();
        ensureLoaded(ctx, clampIdx(idx));
    }

    public static boolean masterOn(Context ctx) {
        return sp(ctx).getBoolean("workSoundMaster", true);
    }

    public static void setMaster(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("workSoundMaster", on).apply();
    }

    public static int volume(Context ctx) {
        return Math.max(0, Math.min(100, sp(ctx).getInt("workSoundVolume", 70)));
    }

    public static void setVolume(Context ctx, int v) {
        sp(ctx).edit().putInt("workSoundVolume", Math.max(0, Math.min(100, v))).apply();
    }

    private static int minGapMs(int ev) {
        return ev == EV_TOOL ? 120 : 0;
    }

    private static final Object LOCK = new Object();
    private static SoundPool pool;

    private static final Map<Integer, Integer> loaded = new HashMap<>();

    private static final Map<Integer, Boolean> loading = new HashMap<>();

    private static final Map<Integer, Integer> resBySample = new HashMap<>();

    private static final Map<Integer, Float> replayAfterLoad = new HashMap<>();

    private static final long[] lastPlayAt = new long[EV_COUNT];
    private static final boolean[] playedThisTurn = new boolean[EV_COUNT];

    private static SoundPool pool(Context ctx) {
        synchronized (LOCK) {
            if (pool == null) {
                pool = new SoundPool.Builder()
                        .setMaxStreams(8)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .build();
                pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
                    Integer resId;
                    synchronized (LOCK) { resId = resBySample.remove(sampleId); }
                    if (status != 0 || resId == null) {
                        Log.w(TAG, "音效加载失败 sampleId=" + sampleId + " status=" + status);
                        if (resId != null) {
                            synchronized (LOCK) { loading.remove(resId); }
                        }
                        return;
                    }
                    Float vol;
                    synchronized (LOCK) {
                        loaded.put(resId, sampleId);
                        loading.remove(resId);
                        vol = replayAfterLoad.remove(resId);
                    }

                    if (vol != null) {
                        try { sp.play(sampleId, vol, vol, 1, 0, 1f); } catch (Exception ignored) { }
                    }
                });
            }
            return pool;
        }
    }

    private static void ensureLoaded(Context ctx, int toneIdx) {
        try {
            int resId = toneResId(ctx, toneIdx);
            if (resId == 0) {
                Log.w(TAG, "音效资源缺失: " + TONES[clampIdx(toneIdx)][1]);
                return;
            }
            SoundPool p = pool(ctx);
            synchronized (LOCK) {
                if (loaded.containsKey(resId) || Boolean.TRUE.equals(loading.get(resId))) return;
                loading.put(resId, true);
                int sid = p.load(ctx.getApplicationContext(), resId, 1);
                resBySample.put(sid, resId);
            }
        } catch (Exception e) {
            Log.w(TAG, "预加载失败: " + e.getMessage());
        }
    }

    public static void warmUp(Context ctx) {
        for (int ev = 0; ev < EV_COUNT; ev++) {
            if (isEnabled(ctx, ev)) ensureLoaded(ctx, toneOf(ctx, ev));
        }
    }

    public static void play(Context ctx, int ev, boolean force) {
        try {
            if (!masterOn(ctx)) return;
            if (!isEnabled(ctx, ev)) {

                Log.i(TAG, "跳过「" + evName(ev) + "」：该事件开关未开启");
                return;
            }

            if (isStreamEvent(ev) && !force) {
                synchronized (LOCK) {
                    if (playedThisTurn[ev]) {
                        Log.i(TAG, "跳过「" + evName(ev) + "」：本轮已响过（流式事件每轮一次）");
                        return;
                    }
                    playedThisTurn[ev] = true;
                }
            }

            int gap = minGapMs(ev);
            if (gap > 0) {
                long now = System.currentTimeMillis();
                synchronized (LOCK) {
                    long delta = now - lastPlayAt[ev];
                    if (delta < gap) {
                        Log.i(TAG, "跳过「" + evName(ev) + "」：距上次仅 " + delta + "ms（<" + gap + "ms 防重叠）");
                        return;
                    }
                    lastPlayAt[ev] = now;
                }
            }

            Log.i(TAG, "播放「" + evName(ev) + "」音效=" + toneLabel(toneOf(ctx, ev)));
            playTone(ctx, toneOf(ctx, ev));
        } catch (Exception e) {
            Log.w(TAG, "播放失败(" + evName(ev) + "): " + e.getMessage());
        }
    }

    public static void playTone(Context ctx, int toneIdx) {
        final Context app = ctx.getApplicationContext();
        final int resId = toneResId(app, toneIdx);
        if (resId == 0) return;

        SoundPool p = pool(app);
        float vol = volume(app) / 100f;

        Integer soundId;
        synchronized (LOCK) { soundId = loaded.get(resId); }

        if (soundId != null) {

            p.play(soundId, vol, vol, 1, 0, 1f);
            return;
        }

        synchronized (LOCK) {
            if (!Boolean.TRUE.equals(loading.get(resId))) {
                loading.put(resId, true);
                int sid = p.load(app, resId, 1);
                resBySample.put(sid, resId);
            }
            replayAfterLoad.put(resId, vol);
        }
    }

    public static void preview(Context ctx, int toneIdx) {
        playTone(ctx, toneIdx);
    }

    public static void resetTurn() {
        synchronized (LOCK) {
            for (int i = 0; i < EV_COUNT; i++) playedThisTurn[i] = false;
        }
    }

    public static void resetThrottle() { resetTurn(); }

    public static void releaseAll() {
        synchronized (LOCK) {
            try {
                if (pool != null) { pool.release(); pool = null; }
            } catch (Exception ignored) { }
            loaded.clear();
            loading.clear();
            resBySample.clear();
            replayAfterLoad.clear();
        }
    }
}
