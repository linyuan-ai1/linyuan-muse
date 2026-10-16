package com.linyuan.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 记忆整理调度器。
 *
 * 设计（用户定的）：
 *   · 和主对话完全分开：主对话聊天/通话时不跑任何记忆任务，不占它的线程、不抢它的额度
 *   · 触发时机：**空闲 N 秒**（默认 30）没有人说话，或**切换对话**时
 *   · **串行排队**：一次只处理一个对话，处理完再下一个 —— 不并发（并发容易报错）
 *   · 整理时带**全量长期记忆**，让模型判断是新增/更新/合并，从源头避免重复
 *   · 模型只能 add/update/merge，**不能删除**
 *
 * 兜底（用户明确要求）：
 *   处理成功才把消息标记为"已处理"。中途被杀进程/清后台/请求失败，
 *   消息仍是"未处理"，下次触发会重扫 —— 不会漏。
 */
public final class MemoryConsolidator {

    /** 单次最多整理一个对话里的多少条消息（防止一次拉太多）。*/
    private static final int MAX_MSGS_PER_RUN = 300;
    /** 已有记忆清单最多带多少条（全量，但给个硬上限防爆）。*/
    private static final int MAX_EXISTING_LINES = 5000;

    private final Context ctx;
    private final AppDatabase db;

    /** 串行队列：待整理的对话 id（去重、保持顺序）。*/
    private final LinkedHashSet<Long> queue = new LinkedHashSet<>();
    private boolean running = false;

    private static MemoryConsolidator sInstance;

    /** 记忆真正入库时的回调（主对话注册，用来弹顶部小公告）。 */
    public interface StoreListener {
        /**
         * @param added   本次新增条数
         * @param updated 本次更新/合并条数
         */
        void onMemoriesStored(int added, int updated);
    }

    private static volatile StoreListener sStoreListener;

    public static void setStoreListener(StoreListener l) { sStoreListener = l; }

    private static void notifyStored(int added, int updated) {
        StoreListener l = sStoreListener;
        if (l == null || added + updated <= 0) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { l.onMemoriesStored(added, updated); } catch (Throwable ignored) { }
        });
    }

    private MemoryConsolidator(Context c, AppDatabase d) {
        this.ctx = c.getApplicationContext();
        this.db = d;
    }

    public static synchronized MemoryConsolidator get(Context c, AppDatabase d) {
        if (sInstance == null) sInstance = new MemoryConsolidator(c, d);
        return sInstance;
    }

    /** 空闲计时器：有新消息就重置，连续 N 秒没动静才真正触发。*/
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private Runnable idleTask;

    /**
     * 用户又说话了 → 重置空闲计时。N 秒内没有新动静才触发整理。
     * 每轮对话结束后调这个。
     */
    public void noteActivity(long conversationId, AppSettings s) {
        enqueue(conversationId);
        int idleSec = s != null && s.memoryIdleSeconds > 0 ? s.memoryIdleSeconds : 30;
        android.util.Log.i("VC_MEMCONS", "记一次活动，将在 " + idleSec + " 秒空闲后整理对话 " + conversationId);
        if (idleTask != null) idleHandler.removeCallbacks(idleTask);
        idleTask = () -> pump(null, s);
        idleHandler.postDelayed(idleTask, idleSec * 1000L);
    }

    /** 切换对话时触发（把刚离开的那个对话排队，并立刻尝试处理）。*/
    public void onSwitchConversation(long leftConversationId, AppSettings s) {
        if (leftConversationId > 0) enqueue(leftConversationId);
        if (idleTask != null) idleHandler.removeCallbacks(idleTask);
        pump(null, s);
    }

    /** 手动补扫全部历史对话（设置页按钮）。按对话逐个排队，不并发。*/
    public void scanAllHistory(AppSettings s, Runnable onDone) {
        for (Long cid : db.conversationsWithUnprocessed()) enqueue(cid);
        pump(onDone, s);
    }

    public synchronized void enqueue(long conversationId) {
        if (conversationId > 0) queue.add(conversationId);
    }

    public synchronized int pendingCount() {
        return queue.size() + (running ? 1 : 0);
    }

    /**
     * 开始跑（如果没在跑）。串行：一次一个对话，跑完自动看还有没有下一个。
     */
    public synchronized void pump(final Runnable onAllDone, final AppSettings s) {
        if (running) return;
        if (queue.isEmpty()) {
            if (onAllDone != null) new Handler(Looper.getMainLooper()).post(onAllDone);
            return;
        }
        running = true;
        final long cid = queue.iterator().next();
        queue.remove(cid);

        Thread t = new Thread(() -> {
            try {
                consolidateOne(cid, s);
            } catch (Throwable e) {
                android.util.Log.w("VC_MEMCONS", "整理对话 " + cid + " 失败：" + e.getMessage());
            } finally {
                synchronized (MemoryConsolidator.this) { running = false; }
                // 继续下一个（串行）
                if (queue.isEmpty()) {
                    if (onAllDone != null) new Handler(Looper.getMainLooper()).post(onAllDone);
                } else {
                    pump(onAllDone, s);
                }
            }
        }, "mem-consolidate");
        t.setDaemon(true);
        t.start();
    }

    /** 整理一个对话：拉未处理消息 → 带全量记忆请求模型 → 执行 → 标记。*/
    private void consolidateOne(long conversationId, AppSettings s) {
        if (s == null || !s.memoryEnabled) return;

        List<AppDatabase.MessageItem> msgs = db.listUnprocessedMessages(conversationId, MAX_MSGS_PER_RUN);
        if (msgs.isEmpty()) return;

        // 1) 把这一段对话拼成给模型看的原文
        StringBuilder dump = new StringBuilder();
        List<Long> ids = new ArrayList<>();
        for (AppDatabase.MessageItem m : msgs) {
            if (m == null || m.content == null) continue;
            String text = cleanForConsolidate(m.content);
            if (text.isEmpty()) {
                ids.add(m.id);   // 空内容（纯标记）直接算处理过，不发给模型
                continue;
            }
            dump.append("user".equals(m.role) ? "[User] " : "[AI] ").append(text).append("\n");
            ids.add(m.id);
        }
        if (dump.length() == 0) {
            db.markMessagesProcessed(ids);
            return;
        }

        // 2) 全量长期记忆清单（id | store | category | content）
        List<String> existing = new ArrayList<>();
        List<AppDatabase.MemoryItem> all = db.listMemories("", false, MAX_EXISTING_LINES);
        for (AppDatabase.MemoryItem it : all) {
            if (it == null || it.content == null) continue;
            existing.add(it.id + " | " + (it.store == null ? "memory" : it.store)
                    + " | " + (it.category == null ? "" : it.category)
                    + " | " + it.content.replace("\n", " "));
        }

        // 3) 请求记忆模型
        AiClient.ConsolidateResult r = AiClient.consolidateMemories(s, existing, dump.toString());
        if (!r.ok) {
            android.util.Log.w("VC_MEMCONS", "对话 " + conversationId + " 整理失败（保持未处理，下次重扫）：" + r.error);
            return;   // ★ 失败不标记 → 兜底
        }

        // 4) 执行 add / update / merge（没有 delete）
        int added = 0, updated = 0;
        for (AiClient.MemoryOp op : r.ops) {
            try {
                if ("add".equals(op.op)) {
                    byte[] emb = null;
                    if (s.memoryUseVector) {
                        try {
                            emb = EmbeddingClient.floatsToBytes(EmbeddingClient.embed(s, op.content));
                        } catch (Exception ignored) { }
                    }
                    double imp = "重要".equals(op.category) ? 0.8 : 0.5;
                    // ★ 必须带上模型判定的 store（memory=关于用户 / self=关于你自己）。
                    //   漏传会走 autoStoreFor() 兜底，而它恒定返回 "memory" ——
                    //   于是"自述"全部落进用户记忆，提示词里那块"你自己的笔记"永远是空的
                    //   （2026-10-13 查实并修复）。
                    db.insertMemory(op.content, op.category, emb, imp, op.store);
                    added++;
                } else {
                    // update / merge：都是"用新内容覆盖这一条"，保留 id 与召回数据
                    AppDatabase.MemoryItem old = db.getMemory(op.id);
                    if (old == null) continue;   // 模型给了不存在的 id → 跳过
                    db.updateMemoryContent(op.id, op.content);
                    // 顺手把向量更新到最新（内容变了，旧向量就不准了）
                    if (s.memoryUseVector) {
                        try {
                            db.updateMemoryEmbedding(op.id,
                                    EmbeddingClient.floatsToBytes(EmbeddingClient.embed(s, op.content)));
                        } catch (Exception ignored) { }
                    }
                    updated++;
                }
            } catch (Exception e) {
                android.util.Log.w("VC_MEMCONS", "执行 " + op.op + " 失败：" + e.getMessage());
            }
        }

        // 5) 顺便写实体卡片（人物/地点/项目/技术/失败经验）。
        //    按名字 upsert：同名覆盖更新，所以重复出现不会堆垃圾，只会把属性补全。
        int entCount = 0;
        if (s.entityEnabled) {
            for (AiClient.EntityOp eo : r.entities) {
                try {
                    String attrs = eo.attributes == null || eo.attributes.isEmpty() ? null : eo.attributes;
                    db.upsertEntity(eo.name, eo.type, attrs);
                    entCount++;
                } catch (Exception e) {
                    android.util.Log.w("VC_MEMCONS", "写实体失败：" + e.getMessage());
                }
            }
        }

        // 6) 成功才标记（失败保持未处理，下次重扫）
        db.markMessagesProcessed(ids);
        // 只有真的写进记忆库才通知（added+updated>0；只触发了模型、没存 → 不弹）。
        notifyStored(added, updated);
        android.util.Log.i("VC_MEMCONS", "对话 " + conversationId + " 整理完成："
                + msgs.size() + " 条消息 → 新增 " + added + "、更新 " + updated
                + "、实体 " + entCount);
    }

    /**
     * 清掉消息里的系统标记，只留人话 —— 记忆模型不该看到 @@THINKING/@ @TOOLS 这些。
     * 同时丢掉空壳（比如只有标记的通话时长行）。
     */
    static String cleanForConsolidate(String content) {
        if (content == null) return "";
        String[] parts = AiClient.splitSpeechAndTools(content);
        String speech = parts[0] == null ? "" : parts[0].trim();
        if (speech.isEmpty()) return "";
        // 通话时长这种纯记录不进记忆
        if (speech.startsWith("📞 本次通话")) return "";
        return speech;
    }
}
