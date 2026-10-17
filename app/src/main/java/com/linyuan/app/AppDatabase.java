package com.linyuan.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppDatabase extends SQLiteOpenHelper {

    public static class MessageItem {
        public long id;
        public String role;
        public String content;
        public String audioPath;
        public String note;

        public String model;

        public int totalTokens;
        public long createdAt;

        public MessageItem(long id, String role, String content, String audioPath, long createdAt) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.audioPath = audioPath;
            this.note = null;
            this.model = null;
            this.totalTokens = 0;
            this.createdAt = createdAt;
        }
    }

    public static class MemoryItem {
        public long id;
        public String content;
        public String category;
        public boolean pinned;
        public long createdAt;
        public long lastUsedAt;
        public int useCount;
        public byte[] embedding;
        public double importance;

        public String store;

        public MemoryItem(long id, String content, String category, boolean pinned, long createdAt,
                          long lastUsedAt, int useCount, byte[] embedding, double importance) {
            this(id, content, category, pinned, createdAt, lastUsedAt, useCount, embedding, importance, null);
        }

        public MemoryItem(long id, String content, String category, boolean pinned, long createdAt,
                          long lastUsedAt, int useCount, byte[] embedding, double importance, String store) {
            this.id = id;
            this.content = content;
            this.category = category;
            this.pinned = pinned;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.useCount = useCount;
            this.embedding = embedding;
            this.importance = importance;
            this.store = store;
        }
    }

    public static class EntityItem {
        public long id;
        public String name;
        public String type;
        public String attributes;
        public long createdAt;
        public long lastUsedAt;
        public int useCount;

        public EntityItem(long id, String name, String type, String attributes, long createdAt, long lastUsedAt, int useCount) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.attributes = attributes;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.useCount = useCount;
        }
    }

    public static class KnowledgeItem {
        public long id;
        public String title;
        public String chunk;
        public String source;
        public int chunkIndex;
        public byte[] embedding;
        public long createdAt;

        public KnowledgeItem(long id, String title, String chunk, String source, int chunkIndex, byte[] embedding, long createdAt) {
            this.id = id;
            this.title = title;
            this.chunk = chunk;
            this.source = source;
            this.chunkIndex = chunkIndex;
            this.embedding = embedding;
            this.createdAt = createdAt;
        }
    }

    private static final String DB_NAME = "voice_companion.db";

    private static final int DB_VERSION = 60;

    public AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.dbContext = context.getApplicationContext();
    }

    private static volatile int sFtsState = 0;

    private boolean ftsReady() {
        int st = sFtsState;
        if (st != 0) return st == 1;
        synchronized (AppDatabase.class) {
            if (sFtsState != 0) return sFtsState == 1;
            boolean ok;
            try {
                Cursor c = getReadableDatabase().rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='messages_fts'", null);
                ok = c.moveToFirst();
                c.close();
            } catch (Exception e) {
                ok = false;
            }
            sFtsState = ok ? 1 : 2;
            return ok;
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);

        try { db.enableWriteAheadLogging(); } catch (Exception ignored) {}
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "role TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "audio_path TEXT,"
                + "note TEXT,"

                + "model TEXT,"

                + "total_tokens INTEGER DEFAULT 0,"

                + "asst INTEGER DEFAULT 0,"
                + "mem_done INTEGER DEFAULT 0,"
                + "conversation_id INTEGER,"
                + "created_at INTEGER NOT NULL)");
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, id)"); } catch (Exception ignored) {}

        createPhoneEventsTable(db);

        createPhoneAggregateTables(db);
        createPhoneNotificationsTable(db);

        createUserDocsTable(db);

        createTapScriptsTable(db);

        createPerceptionTables(db);
        db.execSQL("CREATE TABLE memories ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "content TEXT NOT NULL,"
                + "category TEXT,"
                + "pinned INTEGER DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "last_used_at INTEGER DEFAULT 0,"
                + "use_count INTEGER DEFAULT 0,"
                + "embedding BLOB,"
                + "importance REAL DEFAULT 0.5,"
                + "store TEXT DEFAULT 'memory',"
                + "archived INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE entities ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "type TEXT NOT NULL,"
                + "attributes TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "last_used_at INTEGER DEFAULT 0,"
                + "use_count INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entities_name ON entities(name)");

        try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(content, category, tokenize='unicode61')"); } catch (Exception ignored) {}
        try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(content, tokenize='unicode61')"); } catch (Exception ignored) {}
        db.execSQL("CREATE TABLE IF NOT EXISTS knowledge ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT,"
                + "chunk TEXT NOT NULL,"
                + "source TEXT,"
                + "chunk_index INTEGER DEFAULT 0,"
                + "embedding BLOB,"
                + "created_at INTEGER NOT NULL,"
                // sent_at = 用户**发送这份文档**的时间（跟对话解耦：删对话也不影响它）。
                // created_at 是"切块入库"的时间（补录时会变成今天），两者不是一回事。
                + "sent_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_knowledge_title ON knowledge(title)");
        try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts USING fts5(chunk, tokenize='unicode61')"); } catch (Exception ignored) {}

        db.execSQL("CREATE TABLE IF NOT EXISTS gm_rooms ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT,"
                + "context_limit INTEGER DEFAULT 1000000,"
                + "max_output_tokens INTEGER DEFAULT 110000,"
                + "max_total_msgs INTEGER DEFAULT 0,"
                + "speak_interval_sec INTEGER DEFAULT 30,"
                + "blocked_until INTEGER DEFAULT 0,"
                + "global_prompt TEXT,"
                + "started INTEGER DEFAULT 0,"
                + "export_state TEXT DEFAULT 'idle',"
                + "export_note TEXT,"
                + "last_round INTEGER DEFAULT 0,"
                + "last_member_id INTEGER DEFAULT 0,"
                + "speak_interval_max_sec INTEGER DEFAULT 120,"

                + "no_interval INTEGER DEFAULT 0,"

                + "goal_mode INTEGER DEFAULT 1,"
                + "goal_text TEXT,"
                + "lang_mode INTEGER DEFAULT 1,"

                + "injected_basic_info TEXT,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_members ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "room_id INTEGER NOT NULL,"
                + "name TEXT NOT NULL,"
                + "avatar TEXT,"
                + "role_desc TEXT,"
                + "prompt TEXT,"
                + "model TEXT,"
                + "base_url TEXT,"
                + "api_key TEXT,"
                + "can_search INTEGER DEFAULT 0,"
                + "max_msgs INTEGER DEFAULT 0,"
                + "used_msgs INTEGER DEFAULT 0,"
                + "used_tokens INTEGER DEFAULT 0,"
                + "last_spoke_at INTEGER DEFAULT 0,"
                + "injected INTEGER DEFAULT 0,"
                + "sort_order INTEGER DEFAULT 0,"
                + "think_level INTEGER DEFAULT 2,"
                + "active INTEGER DEFAULT 1,"
                + "phase INTEGER DEFAULT 0,"
                + "manual_standby INTEGER DEFAULT 0,"

                + "confirmed_round INTEGER DEFAULT 0,"
                + "is_default INTEGER DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_members_room ON gm_members(room_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "room_id INTEGER NOT NULL,"
                + "member_id INTEGER DEFAULT 0,"
                + "speaker TEXT,"
                + "avatar TEXT,"
                + "content TEXT NOT NULL,"
                + "is_thought INTEGER DEFAULT 0,"
                + "kind TEXT DEFAULT 'chat',"
                + "tokens INTEGER DEFAULT 0,"

                + "prompt_tokens INTEGER DEFAULT 0,"

                + "completion_tokens INTEGER DEFAULT 0,"

                + "gen_ms INTEGER DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_messages_room ON gm_messages(room_id, id)");

        db.execSQL("CREATE TABLE IF NOT EXISTS gm_docs ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "room_id INTEGER NOT NULL,"
                + "title TEXT,"
                + "content TEXT NOT NULL,"
                + "model_name TEXT,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_docs_room ON gm_docs(room_id, id)");

        createGmInquiryTable(db);

        try { createScheduledTasksTable(db); } catch (Exception e) {
            android.util.Log.w("VC_DB", "建 scheduled_tasks 表失败: " + e.getMessage());
        }

        db.execSQL("CREATE TABLE IF NOT EXISTS gm_providers ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "base_url TEXT NOT NULL,"
                + "api_key TEXT,"
                + "models TEXT,"
                + "created_at INTEGER NOT NULL)");

        createGmBasicInfoTable(db);
        createGmQaArchiveTable(db);

        createGmAgentMemoryTable(db);

        createGmMaterialPoolTable(db);

        createGmCitationsTable(db);
    }

    private void createGmCitationsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_citations ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "room_id INTEGER NOT NULL,"
                + "member_name TEXT,"
                + "demand_room_id INTEGER,"
                + "demand_title TEXT,"
                + "demand_goal TEXT,"
                + "qa_archive_id INTEGER,"
                + "asker TEXT,"
                + "question TEXT,"
                + "answer TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "is_read INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_cite_room ON gm_citations(room_id, is_read)");
    }

    private void createGmMaterialPoolTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_material_pool ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "room_id INTEGER NOT NULL,"
                + "owner TEXT,"
                + "source_tag TEXT,"
                + "content TEXT NOT NULL,"
                + "content_len INTEGER DEFAULT 0,"
                + "tokens INTEGER DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_pool_room ON gm_material_pool(room_id, id)");
    }

    public static final int MATERIAL_POOL_MAX_ITEMS = 500;

    private void createGmAgentMemoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_agent_memory ("
                + "room_id INTEGER NOT NULL,"
                + "member_id INTEGER NOT NULL,"
                + "content TEXT,"

                + "drop_raw INTEGER DEFAULT 0,"
                + "updated_at INTEGER DEFAULT 0,"
                + "PRIMARY KEY (room_id, member_id))");
    }

    private void createGmBasicInfoTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_basic_info ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "category TEXT,"
                + "sort_order INTEGER DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER DEFAULT 0)");
    }

    private void createGmQaArchiveTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_qa_archive ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "room_id INTEGER,"
                + "room_title TEXT,"
                + "room_goal TEXT,"
                + "asker TEXT,"
                + "question TEXT NOT NULL,"
                + "options TEXT,"
                + "answer TEXT,"
                + "answered INTEGER DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "answered_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_qa_room ON gm_qa_archive(room_id, id)");
    }

    private android.content.Context dbContext;

    private void upgradeCelebrityPrompts(SQLiteDatabase db) {
        android.content.Context ctx = dbContext;
        if (ctx == null) {
            android.util.Log.w("VC_PERSONA", "迁移时没有 context，跳过名人档案升级");
            return;
        }
        for (String[] p : PersonaLibrary.CELEBRITY_PERSONAS) {
            String archive = PersonaLibrary.loadPersona(ctx, p[3]);
            if (archive.isEmpty()) continue;
            String prompt = PersonaLibrary.buildPersonaPrompt(p[0], archive);
            db.execSQL("UPDATE gm_members SET prompt = ? WHERE name = ?", new Object[]{prompt, p[0]});
        }
        android.util.Log.i("VC_PERSONA", "名人思维角色提示词已升级为真蒸馏档案");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        if (oldVersion < 60) {
            // 知识库文档的"发送时间"（2026-10-17）：卡片上要显示用户**发文档**的时间，
            // 而不是切块入库的时间（补录后入库时间会变成今天，看着像"我刚发的"）。
            // 存在 knowledge 自己这一列里 —— 不从 messages 取，删对话也不会丢。
            //
            // 这里**只加列，不做回填**。曾经在这里写过
            // "UPDATE knowledge SET sent_at = (关联子查询扫 messages)"，那是
            // 1077 行 × 全表 LIKE 扫描（messages 正文 3.5 MB）≈ 3.8 GB 的读：
            // 真机跑满 60 秒 → 把 MY_PACKAGE_REPLACED 广播拖成 ANR 超时 → 进程被杀 →
            // 整个 onUpgrade 事务回滚 → user_version 永远停在 59，列永远加不上。
            // 升级路径上只能有 O(1) 的 DDL。回填在 MainActivity 的后台任务里做
            //（见 backfillKnowledgeSentAtOnce：5 条消息、每份文档一条 UPDATE，走 title 索引）。
            addColumnIfMissing(db, "knowledge", "sent_at", "INTEGER DEFAULT 0");
        }

        if (oldVersion < 59) {
            createPerceptionTables(db);
        }

        if (oldVersion < 58) {
            // 记忆整理标记：这条消息有没有被"记忆整理模型"看过。
            // 旧消息一律标成"已处理" —— 用户明确不要升级后自动全量补扫
            //（想补扫去设置里点按钮，按对话逐个来）。
            try { db.execSQL("ALTER TABLE messages ADD COLUMN mem_done INTEGER DEFAULT 0"); } catch (Exception ignored) { }
            try { db.execSQL("UPDATE messages SET mem_done = 1"); } catch (Exception ignored) { }
        }

        if (oldVersion < 57) {
            try { db.execSQL("ALTER TABLE messages ADD COLUMN asst INTEGER DEFAULT 0"); } catch (Exception ignored) { }
        }

        if (oldVersion < 56) {

            try { db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN model TEXT"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN base_url TEXT"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN api_key TEXT"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN think_level INTEGER DEFAULT -1"); }
            catch (Exception ignored) { }
            android.util.Log.i("VC_DB", "v56：定时任务已支持独立模型/服务商/思考档位");
        }
        if (oldVersion < 55) {

            try { createTapScriptsTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v55 建点击脚本表失败: " + e.getMessage());
            }
        }
        if (oldVersion < 44) {

            try { createGmBasicInfoTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v44 建基础信息库失败: " + e.getMessage());
            }
            try { createGmQaArchiveTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v44 建用户信息库失败: " + e.getMessage());
            }

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN injected_basic_info TEXT"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 45) {

            try { createGmAgentMemoryTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v45 建资料记忆表失败: " + e.getMessage());
            }
        }
        if (oldVersion < 46) {

            try { db.execSQL("ALTER TABLE gm_agent_memory ADD COLUMN drop_raw INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 47) {

            try { createGmMaterialPoolTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v47 建资料池失败: " + e.getMessage());
            }
        }
        if (oldVersion < 48) {

            try { createGmCitationsTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v48 建引用台账失败: " + e.getMessage());
            }
        }
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE memories ADD COLUMN last_used_at INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE memories ADD COLUMN use_count INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE memories ADD COLUMN embedding BLOB"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE memories ADD COLUMN importance REAL DEFAULT 0.5"); } catch (Exception ignored) {}
        }
        if (oldVersion < 4) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS entities ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "attributes TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "last_used_at INTEGER DEFAULT 0,"
                    + "use_count INTEGER DEFAULT 0)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_entities_name ON entities(name)"); } catch (Exception ignored) {}
        }
        if (oldVersion < 5) {
            try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(content, category, tokenize='unicode61')"); } catch (Exception ignored) {}
            try {
                Cursor c = db.rawQuery("SELECT id, content, category FROM memories", null);
                while (c.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    cv.put("rowid", c.getLong(0));
                    cv.put("content", c.getString(1));
                    cv.put("category", c.getString(2));
                    try { db.insert("memories_fts", null, cv); } catch (Exception ignored) {}
                }
                c.close();
            } catch (Exception ignored) {}
        }
        if (oldVersion < 6) {
            try { db.execSQL("ALTER TABLE memories ADD COLUMN store TEXT DEFAULT 'memory'"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(content, tokenize='unicode61')"); } catch (Exception ignored) {}
            try {
                Cursor mc = db.rawQuery("SELECT id, content FROM messages", null);
                while (mc.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    cv.put("rowid", mc.getLong(0));
                    cv.put("content", mc.getString(1));
                    try { db.insert("messages_fts", null, cv); } catch (Exception ignored) {}
                }
                mc.close();
            } catch (Exception ignored) {}
        }
        if (oldVersion < 7) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS knowledge ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT,"
                    + "chunk TEXT NOT NULL,"
                    + "source TEXT,"
                    + "chunk_index INTEGER DEFAULT 0,"
                    + "embedding BLOB,"
                    + "created_at INTEGER NOT NULL)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_knowledge_title ON knowledge(title)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_fts USING fts5(chunk, tokenize='unicode61')"); } catch (Exception ignored) {}
        }
        if (oldVersion < 8) {
            try { db.execSQL("ALTER TABLE messages ADD COLUMN note TEXT"); } catch (Exception ignored) {}
        }
        if (oldVersion < 9) {

            try { db.execSQL("CREATE TABLE IF NOT EXISTS gm_rooms ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT,"
                    + "context_limit INTEGER DEFAULT 1000000,"
                    + "max_output_tokens INTEGER DEFAULT 110000,"
                    + "max_total_msgs INTEGER DEFAULT 0,"
                    + "speak_interval_sec INTEGER DEFAULT 30,"
                    + "blocked_until INTEGER DEFAULT 0,"
                    + "global_prompt TEXT,"
                    + "created_at INTEGER NOT NULL)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS gm_members ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "room_id INTEGER NOT NULL,"
                    + "name TEXT NOT NULL,"
                    + "avatar TEXT,"
                    + "role_desc TEXT,"
                    + "prompt TEXT,"
                    + "model TEXT,"
                    + "base_url TEXT,"
                    + "api_key TEXT,"
                    + "can_search INTEGER DEFAULT 0,"
                    + "max_msgs INTEGER DEFAULT 0,"
                    + "used_msgs INTEGER DEFAULT 0,"
                    + "used_tokens INTEGER DEFAULT 0,"
                    + "last_spoke_at INTEGER DEFAULT 0,"
                    + "injected INTEGER DEFAULT 0,"
                    + "sort_order INTEGER DEFAULT 0,"
                    + "created_at INTEGER NOT NULL)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_members_room ON gm_members(room_id)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS gm_messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "room_id INTEGER NOT NULL,"
                    + "member_id INTEGER DEFAULT 0,"
                    + "speaker TEXT,"
                    + "avatar TEXT,"
                    + "content TEXT NOT NULL,"
                    + "is_thought INTEGER DEFAULT 0,"
                    + "kind TEXT DEFAULT 'chat',"
                    + "tokens INTEGER DEFAULT 0,"
                    + "prompt_tokens INTEGER DEFAULT 0,"
                    + "completion_tokens INTEGER DEFAULT 0,"
                    + "gen_ms INTEGER DEFAULT 0,"
                    + "created_at INTEGER NOT NULL)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_messages_room ON gm_messages(room_id, id)"); } catch (Exception ignored) {}
        }
        if (oldVersion < 10) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN global_prompt TEXT"); } catch (Exception ignored) {}
        }
        if (oldVersion < 30) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN lang_mode INTEGER DEFAULT 1"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 29) {

            try { db.execSQL("ALTER TABLE gm_members ADD COLUMN manual_standby INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 28) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN speak_interval_max_sec INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 27) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN last_round INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN last_member_id INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 24) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN goal_mode INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN goal_text TEXT"); }
            catch (Exception ignored) { }
            try { createGmInquiryTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v24 建询问稿表失败: " + e.getMessage());
            }
        }
        if (oldVersion < 23) {

            try { db.execSQL("ALTER TABLE memories ADD COLUMN archived INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 22) {

            try { createScheduledTasksTable(db); } catch (Exception e) {
                android.util.Log.w("VC_DB", "v22 建表失败: " + e.getMessage());
            }
        }
        if (oldVersion < 21) {

            ensureGmDocsTable(db);
        }
        if (oldVersion < 20) {

            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS gm_docs ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "room_id INTEGER NOT NULL,"
                        + "title TEXT,"
                        + "content TEXT NOT NULL,"
                        + "model_name TEXT,"
                        + "created_at INTEGER NOT NULL)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_docs_room ON gm_docs(room_id, id)");
            } catch (Exception e) {
                android.util.Log.w("VC_DB", "v20 建表失败: " + e.getMessage());
            }
        }
        if (oldVersion < 19) {

            try { db.execSQL("ALTER TABLE gm_members ADD COLUMN phase INTEGER DEFAULT 0"); } catch (Exception ignored) { }
        }
        if (oldVersion < 18) {

            try {
                upgradeCelebrityPrompts(db);
            } catch (Exception e) {
                android.util.Log.w("VC_PERSONA", "v18 迁移失败: " + e.getMessage());
            }
        }
        if (oldVersion < 15) {

            try { db.execSQL("ALTER TABLE gm_members ADD COLUMN is_default INTEGER DEFAULT 0"); } catch (Exception ignored) {}

            try {
                String[] builtinNames = {
                    "总协调人","风险质疑者","数据核查员","理财规划师","副业变现顾问","职场参谋",
                    "健康顾问","生活管家","情感顾问","心理疏导师","文案写手","内容策划师",
                    "学习教练","科普翻译官","消费参谋","法律顾问",

                    "商业战略专家","财务风控专家","增长营销专家","内容运营策划专家","心理学家","数学家",
                    "连续创业者","法律合规专家","AI商业化落地专家","资料员","战略专家","反方质疑者","总结人"
                };
                for (String n : builtinNames) {
                    db.execSQL("UPDATE gm_members SET is_default = 1 WHERE name = ?", new Object[]{n});
                }
            } catch (Exception ignored) {}
        }
        if (oldVersion < 17) {

            try {
                String[] builtinNames = {

                    "总协调人","风险质疑者","数据核查员","理财规划师","副业变现顾问","职场参谋",
                    "健康顾问","生活管家","情感顾问","心理疏导师","文案写手","内容策划师",
                    "学习教练","科普翻译官","消费参谋","法律顾问",
                    "选品分析师","供应链采购","小红书运营","闲鱼卖家","电商运营","投流投放师",
                    "直播带货策划","私域社群运营","客服话术师","定价策略师",

                    "芒格式思维","费曼式思维","贝索斯式思维","乔布斯式思维",
                    "马斯克式思维","稻盛和夫式思维","巴菲特式思维","塔勒布式思维",

                    "商业战略专家","财务风控专家","增长营销专家","内容运营策划专家","心理学家","数学家",
                    "连续创业者","法律合规专家","AI商业化落地专家","资料员","战略专家","反方质疑者","总结人"
                };
                for (String n : builtinNames) {
                    db.execSQL("UPDATE gm_members SET is_default = 1 WHERE name = ?", new Object[]{n});
                }
            } catch (Exception ignored) {}
        }
        if (oldVersion < 16) {

            try {
                String[] builtinNames = {
                    "总协调人","风险质疑者","数据核查员","理财规划师","副业变现顾问","职场参谋",
                    "健康顾问","生活管家","情感顾问","心理疏导师","文案写手","内容策划师",
                    "学习教练","科普翻译官","消费参谋","法律顾问",
                    "商业战略专家","财务风控专家","增长营销专家","内容运营策划专家","心理学家","数学家",
                    "连续创业者","法律合规专家","AI商业化落地专家","资料员","战略专家","反方质疑者","总结人"
                };
                for (String n : builtinNames) {
                    db.execSQL("UPDATE gm_members SET is_default = 1 WHERE name = ?", new Object[]{n});
                }
            } catch (Exception ignored) {}
        }
        if (oldVersion < 14) {
            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN started INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE gm_members ADD COLUMN active INTEGER DEFAULT 1"); } catch (Exception ignored) {}
        }
        if (oldVersion < 13) {
            try { db.execSQL("ALTER TABLE gm_members ADD COLUMN think_level INTEGER DEFAULT 2"); } catch (Exception ignored) {}
        }
        if (oldVersion < 12) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN max_output_tokens INTEGER DEFAULT 110000"); } catch (Exception ignored) {}
        }
        if (oldVersion < 11) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS gm_providers ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "base_url TEXT NOT NULL,"
                    + "api_key TEXT,"
                    + "models TEXT,"
                    + "created_at INTEGER NOT NULL)"); } catch (Exception ignored) {}
        }

        if (oldVersion < 26) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN export_state TEXT DEFAULT 'idle'"); } catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN export_note TEXT"); } catch (Exception ignored) { }
        }
        if (oldVersion < 31) {

            try { db.execSQL("DROP TABLE IF EXISTS gm_role_memory"); } catch (Exception ignored) { }
        }
        if (oldVersion < 32) {

            try {
                db.execSQL("UPDATE gm_members SET think_level = CASE think_level"
                        + " WHEN 1 THEN 2 WHEN 2 THEN 4 ELSE think_level END");
            } catch (Exception e) {
                android.util.Log.w("VC_DB", "v32 思考档位迁移失败: " + e.getMessage());
            }

            try {
                db.execSQL("UPDATE gm_rooms SET lang_mode=3 WHERE lang_mode=1");
            } catch (Exception e) {
                android.util.Log.w("VC_DB", "v32 语言模式迁移失败: " + e.getMessage());
            }
        }
        if (oldVersion < 33) {

            try { db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT"); }
            catch (Exception e) { android.util.Log.w("VC_DB", "v33 加 model 列失败: " + e.getMessage()); }
        }
        if (oldVersion < 34) {

            try { db.execSQL("UPDATE gm_rooms SET goal_mode=1"); }
            catch (Exception e) { android.util.Log.w("VC_DB", "v34 默认开启目标模式失败: " + e.getMessage()); }
        }
        if (oldVersion < 35) {

            try { db.execSQL("UPDATE gm_rooms SET speak_interval_max_sec=120 WHERE speak_interval_max_sec<=0"); }
            catch (Exception e) { android.util.Log.w("VC_DB", "v35 发言间隔默认值迁移失败: " + e.getMessage()); }
        }
        if (oldVersion < 36) {

            try {
                int n = db.delete("gm_pending_questions", "status='pending'", null);
                if (n > 0) {
                    android.util.Log.w("VC_DB", "v36：清理 " + n + " 条旧文本解析遗留的假提问"
                            + "（它们永远不会被回答，会一直挂在待答列表里）");
                }
            } catch (Exception e) {
                android.util.Log.w("VC_DB", "v36 清理假提问失败: " + e.getMessage());
            }
        }
        if (oldVersion < 37) {

            try { db.execSQL("ALTER TABLE gm_members ADD COLUMN sort_order INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }

            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS gm_admin_messages ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "room_id INTEGER NOT NULL,"
                        + "role TEXT,"
                        + "content TEXT,"
                        + "reasoning TEXT,"
                        + "created_at INTEGER NOT NULL)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_admin_room ON gm_admin_messages(room_id, id)");
            } catch (Exception ignored) { }

            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS gm_admin_proposals ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "room_id INTEGER NOT NULL,"
                        + "kind TEXT,"
                        + "payload TEXT,"
                        + "summary TEXT,"
                        + "status TEXT DEFAULT 'pending',"
                        + "created_at INTEGER NOT NULL)");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_ap_room ON gm_admin_proposals(room_id, status)");
            } catch (Exception ignored) { }
        }
        if (oldVersion < 38) {

            try { db.execSQL("ALTER TABLE gm_admin_messages ADD COLUMN tool_log TEXT"); }
            catch (Exception ignored) { }
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS gm_admin_config ("
                        + "room_id INTEGER PRIMARY KEY,"
                        + "base_url TEXT,"
                        + "api_key TEXT,"
                        + "model TEXT,"
                        + "context_limit INTEGER DEFAULT 0,"
                        + "think_level INTEGER DEFAULT -1)");
            } catch (Exception ignored) { }
        }
        if (oldVersion < 39) {

            try { db.execSQL("ALTER TABLE gm_admin_config ADD COLUMN repeat_min_chars INTEGER DEFAULT 3000"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_admin_config ADD COLUMN repeat_len INTEGER DEFAULT 500"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_admin_config ADD COLUMN repeat_window INTEGER DEFAULT 2000"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_admin_config ADD COLUMN repeat_max INTEGER DEFAULT 3"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_admin_config ADD COLUMN tool_max_iter INTEGER DEFAULT 37"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE gm_admin_config ADD COLUMN repeat_retry INTEGER DEFAULT 3"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 40) {

            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS gm_anomalies ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "room_id INTEGER NOT NULL,"
                        + "round INTEGER DEFAULT 0,"
                        + "member_id INTEGER DEFAULT 0,"
                        + "member_name TEXT,"
                        + "kind TEXT,"
                        + "detail TEXT,"
                        + "sample TEXT,"
                        + "created_at INTEGER NOT NULL)");
            } catch (Exception ignored) { }
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_anomaly_room ON gm_anomalies(room_id, id)"); }
            catch (Exception ignored) { }
        }
        if (oldVersion < 41) {

            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("tool_max_iter", 37);
                int n = db.update("gm_admin_config", v, "tool_max_iter IN (20, 35)", null);
                if (n > 0) {
                    android.util.Log.i("VC_DB", "v41：把 " + n
                            + " 个房间的工具轮数从老默认值抬到 37");
                }
            } catch (Exception ignored) { }
        }
        if (oldVersion < 43) {

            try { db.execSQL("ALTER TABLE gm_rooms ADD COLUMN no_interval INTEGER DEFAULT 0"); }
            catch (Exception e) { android.util.Log.w("VC_DB", "v43 加 no_interval 列失败: " + e.getMessage()); }
        }
        if (oldVersion < 42) {

            try { db.execSQL("ALTER TABLE messages ADD COLUMN total_tokens INTEGER DEFAULT 0"); }
            catch (Exception e) { android.util.Log.w("VC_DB", "v42 加 total_tokens 列失败: " + e.getMessage()); }
        }
        if (oldVersion < 49) {

            try { db.execSQL("ALTER TABLE messages ADD COLUMN conversation_id INTEGER"); }
            catch (Exception e) { android.util.Log.w("VC_DB", "v49 加 conversation_id 列失败: " + e.getMessage()); }
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, id)"); }
            catch (Exception e) { android.util.Log.w("VC_DB", "v49 建会话索引失败: " + e.getMessage()); }
        }
        if (oldVersion < 50) {

            createPhoneEventsTable(db);
        }
        if (oldVersion < 51) {

            createPhoneAggregateTables(db);
        }
        if (oldVersion < 52) {

            createPhoneNotificationsTable(db);
        }
        if (oldVersion < 53) {

            createUserDocsTable(db);
        }
        if (oldVersion < 54) {

            addColumnIfMissing(db, "gm_members", "confirmed_round", "INTEGER DEFAULT 0");
            backfillConfirmedRounds(db);
        }
    }

    private void backfillConfirmedRounds(SQLiteDatabase db) {
        try {
            db.execSQL("UPDATE gm_members SET confirmed_round=1 WHERE id IN ("
                    + "  SELECT m.id FROM gm_members m"
                    + "  WHERE EXISTS ("
                    + "    SELECT 1 FROM gm_messages g"
                    + "    WHERE g.member_id = m.id AND g.room_id = m.room_id"
                    + "      AND g.is_thought = 0 AND (g.kind IS NULL OR g.kind='chat')"
                    + "      AND g.id = (SELECT MAX(g2.id) FROM gm_messages g2"
                    + "                   WHERE g2.member_id = m.id AND g2.room_id = m.room_id"
                    + "                     AND g2.is_thought = 0 AND (g2.kind IS NULL OR g2.kind='chat'))"
                    + "      AND g.content LIKE '%@@CONFIRM:1%'"
                    + "  ))");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "backfillConfirmedRounds 失败: " + e.getMessage());
        }
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String decl) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + decl);
        } catch (Exception e) {

        }
    }

    private void createUserDocsTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS user_docs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT,"
                    + "content TEXT NOT NULL,"
                    + "source TEXT,"
                    + "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_docs_created ON user_docs(created_at)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "createUserDocsTable 失败: " + e.getMessage());
        }
    }

    private void ensureUserDocsTable() {
        createUserDocsTable(getWritableDatabase());
    }

    private void createTapScriptsTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS tap_scripts ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "label TEXT NOT NULL,"
                    + "package_name TEXT,"
                    + "activity_name TEXT,"
                    + "screen_hint TEXT,"
                    + "x_percent REAL NOT NULL,"
                    + "y_percent REAL NOT NULL,"
                    + "step_index INTEGER DEFAULT 0,"
                    + "flow_name TEXT,"
                    + "description TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "used_count INTEGER DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tap_scripts_pkg ON tap_scripts(package_name)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tap_scripts_flow ON tap_scripts(flow_name, step_index)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "createTapScriptsTable 失败: " + e.getMessage());
        }
    }

    private void ensureTapScriptsTable() {
        createTapScriptsTable(getWritableDatabase());
    }

    public static class TapScript {
        public long id;
        public String label;
        public String packageName;
        public String activityName;
        public String screenHint;
        public double xPercent;
        public double yPercent;
        public int stepIndex;
        public String flowName;
        public String description;
        public long createdAt;
        public int usedCount;
    }

    private TapScript readTapScript(Cursor c) {
        TapScript t = new TapScript();
        t.id = c.getLong(c.getColumnIndexOrThrow("id"));
        t.label = c.getString(c.getColumnIndexOrThrow("label"));
        t.packageName = c.getString(c.getColumnIndex("package_name"));
        t.activityName = c.getString(c.getColumnIndex("activity_name"));
        t.screenHint = c.getString(c.getColumnIndex("screen_hint"));
        t.xPercent = c.getDouble(c.getColumnIndexOrThrow("x_percent"));
        t.yPercent = c.getDouble(c.getColumnIndexOrThrow("y_percent"));
        t.stepIndex = c.getInt(c.getColumnIndex("step_index"));
        t.flowName = c.getString(c.getColumnIndex("flow_name"));
        t.description = c.getString(c.getColumnIndex("description"));
        t.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        t.usedCount = c.getInt(c.getColumnIndex("used_count"));
        return t;
    }

    public long insertTapScript(String label, String pkg, String activity, String screenHint,
                               double xPercent, double yPercent, int stepIndex,
                               String flowName, String description) {
        try {
            ensureTapScriptsTable();
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("label", label == null ? "" : label);
            v.put("package_name", pkg);
            v.put("activity_name", activity);
            v.put("screen_hint", screenHint);
            v.put("x_percent", xPercent);
            v.put("y_percent", yPercent);
            v.put("step_index", stepIndex);
            v.put("flow_name", flowName);
            v.put("description", description);
            v.put("created_at", System.currentTimeMillis());
            return getWritableDatabase().insert("tap_scripts", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "insertTapScript 失败: " + e.getMessage());
            return -1L;
        }
    }

    public List<TapScript> listTapScripts(String pkg, int limit) {
        List<TapScript> out = new ArrayList<>();
        Cursor c = null;
        try {
            ensureTapScriptsTable();
            int lim = limit <= 0 ? 50 : Math.min(limit, 300);
            if (pkg == null || pkg.trim().isEmpty()) {
                c = getReadableDatabase().rawQuery(
                        "SELECT * FROM tap_scripts ORDER BY id DESC LIMIT " + lim, null);
            } else {
                c = getReadableDatabase().rawQuery(
                        "SELECT * FROM tap_scripts WHERE package_name = ? ORDER BY id DESC LIMIT " + lim,
                        new String[]{pkg.trim()});
            }
            while (c.moveToNext()) out.add(readTapScript(c));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listTapScripts 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public TapScript getTapScript(long id) {
        Cursor c = null;
        try {
            ensureTapScriptsTable();
            c = getReadableDatabase().query("tap_scripts", null, "id = ?",
                    new String[]{String.valueOf(id)}, null, null, null, "1");
            if (c.moveToFirst()) return readTapScript(c);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return null;
    }

    public List<TapScript> listTapScriptsByFlow(String flowName) {
        List<TapScript> out = new ArrayList<>();
        Cursor c = null;
        try {
            ensureTapScriptsTable();
            c = getReadableDatabase().query("tap_scripts", null, "flow_name = ?",
                    new String[]{flowName}, null, null, "step_index ASC, id ASC", null);
            while (c.moveToNext()) out.add(readTapScript(c));
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void updateTapScript(long id, String label, String description) {
        try {
            ensureTapScriptsTable();
            android.content.ContentValues v = new android.content.ContentValues();
            if (label != null) v.put("label", label);
            if (description != null) v.put("description", description);
            if (v.size() == 0) return;
            getWritableDatabase().update("tap_scripts", v, "id = ?",
                    new String[]{String.valueOf(id)});
        } catch (Exception ignored) {
        }
    }

    public void updateTapScriptPos(long id, double xPercent, double yPercent) {
        try {
            ensureTapScriptsTable();
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("x_percent", xPercent);
            v.put("y_percent", yPercent);
            getWritableDatabase().update("tap_scripts", v, "id = ?",
                    new String[]{String.valueOf(id)});
        } catch (Exception ignored) {
        }
    }

    public void touchTapScript(long id) {
        try {
            getWritableDatabase().execSQL(
                    "UPDATE tap_scripts SET used_count = used_count + 1 WHERE id = ?",
                    new Object[]{id});
        } catch (Exception ignored) {
        }
    }

    public void deleteTapScript(long id) {
        try {
            ensureTapScriptsTable();
            getWritableDatabase().delete("tap_scripts", "id = ?", new String[]{String.valueOf(id)});
        } catch (Exception ignored) {
        }
    }

    public void deleteTapScriptFlow(String flowName) {
        if (flowName == null || flowName.trim().isEmpty()) return;
        try {
            ensureTapScriptsTable();
            getWritableDatabase().delete("tap_scripts", "flow_name = ?",
                    new String[]{flowName.trim()});
        } catch (Exception ignored) {
        }
    }

    public static class UserDoc {
        public long id;
        public String title;
        public String content;
        public String source;
        public long createdAt;

        public long contentLength;

        public String preview;
    }

    public long insertUserDoc(String title, String content, String source) {
        ensureUserDocsTable();
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("content", content == null ? "" : content);
        v.put("source", source);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("user_docs", null, v);
    }

    public UserDoc getUserDoc(long id) {
        ensureUserDocsTable();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, title, source, created_at, LENGTH(content), substr(content,1,150000)"
                            + " FROM user_docs WHERE id=?", new String[]{String.valueOf(id)});
            if (!c.moveToFirst()) return null;
            UserDoc d = new UserDoc();
            d.id = c.getLong(0);
            d.title = c.getString(1);
            d.source = c.getString(2);
            d.createdAt = c.getLong(3);
            d.contentLength = c.getLong(4);
            d.content = c.getString(5);
            return d;
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "getUserDoc 失败: " + e.getMessage());
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    public java.util.List<UserDoc> listUserDocs() {
        ensureUserDocsTable();
        java.util.List<UserDoc> out = new java.util.ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, title, source, created_at, LENGTH(content), substr(content,1,120)"
                            + " FROM user_docs ORDER BY id DESC", null);
            while (c.moveToNext()) {
                UserDoc d = new UserDoc();
                d.id = c.getLong(0);
                d.title = c.getString(1);
                d.source = c.getString(2);
                d.createdAt = c.getLong(3);
                d.contentLength = c.getLong(4);
                d.preview = c.getString(5);
                d.content = null;
                out.add(d);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listUserDocs 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public String getUserDocContent(long id) {
        ensureUserDocsTable();
        final int CHUNK = 150000;
        StringBuilder sb = new StringBuilder();
        long total = 0L;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT LENGTH(content) FROM user_docs WHERE id=?", new String[]{String.valueOf(id)});
            if (c.moveToFirst()) total = c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        if (total <= 0L) return "";
        for (long off = 1; off <= total; off += CHUNK) {
            Cursor cc = null;
            try {
                cc = getReadableDatabase().rawQuery(
                        "SELECT substr(content," + off + "," + CHUNK + ") FROM user_docs WHERE id=?",
                        new String[]{String.valueOf(id)});
                if (cc.moveToFirst()) {
                    String part = cc.getString(0);
                    if (part != null) sb.append(part);
                }
            } catch (Exception e) {
                android.util.Log.w("VC_DB", "getUserDocContent 分块读取失败 off=" + off + ": " + e.getMessage());
                break;
            } finally {
                if (cc != null) try { cc.close(); } catch (Exception ignored) { }
            }
        }
        return sb.toString();
    }

    public void deleteUserDoc(long id) {
        ensureUserDocsTable();
        try {
            getWritableDatabase().delete("user_docs", "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "deleteUserDoc 失败: " + e.getMessage());
        }
    }

    public void updateUserDoc(long id, String title, String content) {
        ensureUserDocsTable();
        try {
            ContentValues v = new ContentValues();
            if (title != null && !title.trim().isEmpty()) v.put("title", title.trim());
            if (content != null) v.put("content", content);
            getWritableDatabase().update("user_docs", v, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "updateUserDoc 失败: " + e.getMessage());
        }
    }

    public java.util.List<UserDoc> findUserDocsByTitle(String title) {
        ensureUserDocsTable();
        java.util.List<UserDoc> out = new java.util.ArrayList<>();
        if (title == null || title.trim().isEmpty()) return out;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, title, source, created_at, LENGTH(content), substr(content,1,120)"
                            + " FROM user_docs WHERE title LIKE ? ORDER BY id DESC",
                    new String[]{"%" + title.trim() + "%"});
            while (c.moveToNext()) {
                UserDoc d = new UserDoc();
                d.id = c.getLong(0);
                d.title = c.getString(1);
                d.source = c.getString(2);
                d.createdAt = c.getLong(3);
                d.contentLength = c.getLong(4);
                d.preview = c.getString(5);
                out.add(d);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "findUserDocsByTitle 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    private void createPhoneAggregateTables(SQLiteDatabase db) {
        try {

            db.execSQL("CREATE TABLE IF NOT EXISTS phone_buckets ("
                    + "bucket_ts INTEGER NOT NULL,"
                    + "app TEXT NOT NULL,"
                    + "fg_ms INTEGER DEFAULT 0,"
                    + "switches INTEGER DEFAULT 0,"
                    + "clicks INTEGER DEFAULT 0,"
                    + "scrolls INTEGER DEFAULT 0,"
                    + "PRIMARY KEY(bucket_ts, app))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_phone_buckets_ts ON phone_buckets(bucket_ts)");

            db.execSQL("CREATE TABLE IF NOT EXISTS phone_snapshots ("
                    + "bucket_ts INTEGER PRIMARY KEY,"
                    + "battery INTEGER,"
                    + "charging INTEGER DEFAULT 0,"
                    + "foreground TEXT,"
                    + "lat REAL,"
                    + "lon REAL,"
                    + "place TEXT)");

            db.execSQL("CREATE TABLE IF NOT EXISTS phone_calls ("
                    + "ts INTEGER PRIMARY KEY,"
                    + "name TEXT,"
                    + "number TEXT,"
                    + "type INTEGER,"
                    + "dur INTEGER DEFAULT 0)");

            db.execSQL("CREATE TABLE IF NOT EXISTS phone_days ("
                    + "day TEXT NOT NULL,"
                    + "app TEXT NOT NULL,"
                    + "fg_ms INTEGER DEFAULT 0,"
                    + "switches INTEGER DEFAULT 0,"
                    + "clicks INTEGER DEFAULT 0,"
                    + "scrolls INTEGER DEFAULT 0,"
                    + "PRIMARY KEY(day, app))");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "建手机感知聚合表失败: " + e.getMessage());
        }
    }

    private void createPhoneNotificationsTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS phone_notifications ("
                    + "ts INTEGER NOT NULL,"
                    + "app TEXT,"
                    + "title TEXT,"
                    + "text TEXT,"
                    + "category TEXT,"
                    + "ongoing INTEGER DEFAULT 0,"
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_phone_notif_ts ON phone_notifications(ts)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "建 phone_notifications 失败: " + e.getMessage());
        }
    }

    private void createPhoneEventsTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS phone_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts INTEGER NOT NULL,"
                    + "app TEXT,"
                    + "kind TEXT,"
                    + "detail TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_phone_events_ts ON phone_events(ts)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "建 phone_events 失败: " + e.getMessage());
        }
    }

        public List<MessageItem> listRecentMessagesSince(long sinceId, int limit) {
        List<MessageItem> result = new ArrayList<>();

        Cursor cursor = getReadableDatabase().query(
                "messages", null, "id >= ?", new String[]{String.valueOf(sinceId)},
                null, null, "id DESC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(new MessageItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("role")),
                    cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))));
        }
        cursor.close();
        Collections.reverse(result);
        return result;
    }

    public long insertMessage(String role, String content, String audioPath, String model,
                              int totalTokens, long conversationId) {
        return insertMessage(role, content, audioPath, model, totalTokens, conversationId, false);
    }

    public long insertMessage(String role, String content, String audioPath, String model,
                              int totalTokens, long conversationId, boolean asst) {
        ContentValues values = new ContentValues();
        values.put("role", role);
        values.put("content", content);
        values.put("audio_path", audioPath);
        if (model != null && !model.trim().isEmpty()) values.put("model", model.trim());
        if (totalTokens > 0) values.put("total_tokens", totalTokens);
        values.put("conversation_id", conversationId);
        values.put("asst", asst ? 1 : 0);
        values.put("created_at", System.currentTimeMillis());
        long mid = getWritableDatabase().insert("messages", null, values);
        if (mid > 0 && ftsReady()) {
            ContentValues fts = new ContentValues();
            fts.put("rowid", mid);
            fts.put("content", content);
            try { getWritableDatabase().insert("messages_fts", null, fts); } catch (Exception ignored) {}
        }
        return mid;
    }

    public void updateAudioPath(long id, String path) {
        ContentValues values = new ContentValues();
        values.put("audio_path", path);
        getWritableDatabase().update("messages", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateMessageContent(long id, String content) {
        ContentValues values = new ContentValues();
        values.put("content", content);
        getWritableDatabase().update("messages", values, "id=?", new String[]{String.valueOf(id)});
        if (!ftsReady()) return;
        try {
            getWritableDatabase().delete("messages_fts", "rowid=?", new String[]{String.valueOf(id)});
            ContentValues fts = new ContentValues();
            fts.put("rowid", id);
            fts.put("content", content);
            getWritableDatabase().insert("messages_fts", null, fts);
        } catch (Exception ignored) {}
    }
    public List<MessageItem> listMessages(int limit) {
        List<MessageItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "messages", null, null, null, null, null, "id ASC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(new MessageItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("role")),
                    cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))));

            int mdlIdx = cursor.getColumnIndex("model");
            if (mdlIdx >= 0) result.get(result.size() - 1).model = cursor.getString(mdlIdx);
        }
        cursor.close();
        return result;
    }

    public List<MessageItem> listRecentMessages(int limit) {
        List<MessageItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "messages", null, null, null, null, null, "id DESC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(new MessageItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("role")),
                    cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))));

            int mdlIdx = cursor.getColumnIndex("model");
            if (mdlIdx >= 0) result.get(result.size() - 1).model = cursor.getString(mdlIdx);
        }
        cursor.close();
        return result;
    }

    public long getMaxMessageId() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT MAX(id) FROM messages", null);
        long maxId = 0;
        if (cursor.moveToFirst() && !cursor.isNull(0)) {
            maxId = cursor.getLong(0);
        }
        cursor.close();
        return maxId;
    }

    public String getMessageRole(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT role FROM messages WHERE id=?", new String[]{String.valueOf(id)});
        String role = null;
        if (c.moveToFirst()) role = c.getString(0);
        c.close();
        return role;
    }

    /**
     * 会话归属过滤。主对话与主动式助手可能共用同一个 conversation_id
     * （历史上主对话的 id 分配没有上限，长进了助手的 90 万号段），
     * 所以凡是按会话号读写的地方都必须带上这一条，否则两边互相看得见。
     */
    private static String asstWhere(boolean assistant) {
        return assistant ? "COALESCE(asst,0)=1" : "COALESCE(asst,0)=0";
    }

    public List<MessageItem> listMessagesInConversation(long conversationId) {
        return listMessagesInConversation(conversationId, false);
    }

    public List<MessageItem> listMessagesInConversation(long conversationId, boolean assistant) {
        List<MessageItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "messages", null, "conversation_id = ? AND " + asstWhere(assistant),
                new String[]{String.valueOf(conversationId)},
                null, null, "id ASC", null);
        while (cursor.moveToNext()) result.add(readMessageItem(cursor));
        cursor.close();
        return result;
    }

    public List<MessageItem> listRecentMessagesInConversation(long conversationId, int limit) {
        List<MessageItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "messages", null, "conversation_id = ? AND " + asstWhere(false),
                new String[]{String.valueOf(conversationId)},
                null, null, "id DESC", String.valueOf(limit));
        while (cursor.moveToNext()) result.add(readMessageItem(cursor));
        cursor.close();
        Collections.reverse(result);
        return result;
    }

    public List<MessageItem> listMessagesInConversationBefore(long conversationId, long beforeId, int limit) {
        List<MessageItem> result = new ArrayList<>();
        if (limit <= 0) return result;
        String sel = "conversation_id = ? AND " + asstWhere(false);
        String[] args;
        if (beforeId > 0) {
            sel += " AND id < ?";
            args = new String[]{String.valueOf(conversationId), String.valueOf(beforeId)};
        } else {
            args = new String[]{String.valueOf(conversationId)};
        }
        Cursor cursor = getReadableDatabase().query(
                "messages", null, sel, args, null, null, "id DESC", String.valueOf(limit));
        while (cursor.moveToNext()) result.add(readMessageItem(cursor));
        cursor.close();
        Collections.reverse(result);
        return result;
    }

    public int countMessagesInConversationBefore(long conversationId, long beforeId) {
        Cursor c = null;
        try {
            String sel = "conversation_id = ? AND " + asstWhere(false);
            String[] args;
            if (beforeId > 0) {
                sel += " AND id < ?";
                args = new String[]{String.valueOf(conversationId), String.valueOf(beforeId)};
            } else {
                args = new String[]{String.valueOf(conversationId)};
            }
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE " + sel, args);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public long lastUserMessageIdInConversation(long conversationId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query("messages", new String[]{"id"},
                    "conversation_id = ? AND role = ? AND " + asstWhere(false),
                    new String[]{String.valueOf(conversationId), "user"},
                    null, null, "id DESC", "1");
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return -1L;
    }
    private MessageItem readMessageItem(Cursor cursor) {
        MessageItem item = new MessageItem(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("role")),
                cursor.getString(cursor.getColumnIndexOrThrow("content")),
                cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
        int noteIdx = cursor.getColumnIndex("note");
        if (noteIdx >= 0 && !cursor.isNull(noteIdx)) item.note = cursor.getString(noteIdx);

        int modelIdx = cursor.getColumnIndex("model");
        if (modelIdx >= 0 && !cursor.isNull(modelIdx)) item.model = cursor.getString(modelIdx);

        int tokIdx = cursor.getColumnIndex("total_tokens");
        if (tokIdx >= 0 && !cursor.isNull(tokIdx)) item.totalTokens = cursor.getInt(tokIdx);
        return item;
    }

    public static class ConversationSummary {
        public long conversationId;
        public int count;
        public String preview = "";
        /** 该会话**第一条**消息的时间。列表显示它、也按它排序 —— 两处必须同一个口径，
         *  否则跨天会话会出现"显示 10-12、却排在 10-13 前面"这种看起来没排好的列表。 */
        public long firstCreatedAt;
        public String note;
    }

    public List<ConversationSummary> listConversations() {
        // 按"最后一条消息的时间"排，**最新在前**（用户 2026-10-16 定）。
        // 旧写法是 ORDER BY conversation_id —— 那是"会话号的分配顺序"，不是时间：
        // 会话号是按消息行号 +1 发的，助手面板那批 90 万号段（10-09~10-14）
        // 数值上比 10-16 的新会话还大，于是新会话被排到了最底下、看着像"混在中间"。
        // 另外旧写法是升序 = 最旧的在最上面，与"打开就该看到最近的"正好相反。
        // 这里必须把会话号原样带出来，所以不用 ORDER BY 表达式，改成取回后按时间排序。
        List<ConversationSummary> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT conversation_id, COUNT(*) FROM messages"
                            + " WHERE conversation_id IS NOT NULL AND COALESCE(asst,0)=0"
                            + " GROUP BY conversation_id", null);
            while (c.moveToNext()) {
                ConversationSummary s = new ConversationSummary();
                s.conversationId = c.getLong(0);
                s.count = c.getInt(1);
                out.add(s);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }

        fillSummaryPreviews(out, false);
        sortNewestFirst(out);
        return out;
    }

    /** 最新在前，按**界面显示的那个时间**排（firstCreatedAt）。时间相同的用会话号兜底，保证顺序稳定。 */
    private static void sortNewestFirst(List<ConversationSummary> out) {
        java.util.Collections.sort(out, (a, b) -> {
            if (a.firstCreatedAt != b.firstCreatedAt) {
                return Long.compare(b.firstCreatedAt, a.firstCreatedAt);
            }
            return Long.compare(b.conversationId, a.conversationId);
        });
    }

    /**
     * 给会话摘要补预览、首条时间和备注。取的是该会话**本侧**的第一条消息：
     * 主对话与主动式助手可能共用同一个会话号，不加归属过滤就会把对方的备注和预览显示过来。
     */
    private void fillSummaryPreviews(List<ConversationSummary> out, boolean assistant) {
        for (ConversationSummary s : out) {
            Cursor f = null;
            try {
                f = getReadableDatabase().rawQuery(
                        "SELECT content, created_at, note FROM messages WHERE conversation_id = ?"
                                + " AND " + asstWhere(assistant) + " ORDER BY id ASC LIMIT 1",
                        new String[]{String.valueOf(s.conversationId)});
                if (f.moveToFirst()) {
                    String content = f.getString(0);
                    if (content != null) {
                        s.preview = content.length() > 200 ? content.substring(0, 200) : content;
                    }
                    s.firstCreatedAt = f.getLong(1);
                    if (!f.isNull(2)) s.note = f.getString(2);
                }
            } catch (Exception ignored) {
            } finally {
                if (f != null) try { f.close(); } catch (Exception ignored) { }
            }
        }
    }

    // ===== 记忆整理（mem_done）=====

    /**
     * 某对话里"还没被记忆整理模型看过"的消息，按时间正序。
     * 只取用户和 AI 的正文，跳过 @@ 标记之类由调用方自己清洗。
     * 只取主对话侧（asst=0）—— 整理模型只负责主对话；助手面板的记忆由它自己的
     * 工具写 asst:* 三本，不进这条链路（用户 2026-10-13 定案）。
     */
    public List<MessageItem> listUnprocessedMessages(long conversationId, int limit) {
        List<MessageItem> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, role, content, audio_path, created_at FROM messages"
                            + " WHERE conversation_id = ? AND " + asstWhere(false)
                            + " AND COALESCE(mem_done,0) = 0"
                            + " ORDER BY id ASC LIMIT ?",
                    new String[]{String.valueOf(conversationId), String.valueOf(limit)});
            while (c.moveToNext()) {
                out.add(new MessageItem(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getLong(4)));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    /** 把一批消息标记为"已被记忆模型处理过"。整理成功后调用，失败不要调用（下次重扫）。 */
    public void markMessagesProcessed(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        StringBuilder in = new StringBuilder();
        for (Long id : ids) {
            if (in.length() > 0) in.append(",");
            in.append(id);
        }
        try {
            getWritableDatabase().execSQL(
                    "UPDATE messages SET mem_done = 1 WHERE id IN (" + in + ")");
        } catch (Exception ignored) { }
    }

    /**
     * 某个对话**最新一条主对话消息**是否已被记忆模型处理过。
     * 历史列表的「记忆」小徽章用它决定显不显示。
     * 只看主对话侧：整理模型只处理主对话，助手面板的消息永远不会被打标。
     */
    public boolean isLatestMessageProcessed(long conversationId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COALESCE(mem_done,0) FROM messages WHERE conversation_id = ?"
                            + " AND " + asstWhere(false)
                            + " ORDER BY id DESC LIMIT 1",
                    new String[]{String.valueOf(conversationId)});
            return c.moveToFirst() && c.getInt(0) == 1;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 还有未处理消息的对话 id 列表（给"全量扫描"和定时整理用）。
     * 只列主对话侧：助手面板的会话不进这个清单，全量扫描碰不到它（用户 2026-10-13 定案）。
     */
    public List<Long> conversationsWithUnprocessed() {
        List<Long> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT DISTINCT conversation_id FROM messages"
                            + " WHERE conversation_id IS NOT NULL AND " + asstWhere(false)
                            + " AND COALESCE(mem_done,0) = 0"
                            + " ORDER BY conversation_id ASC", null);
            while (c.moveToNext()) out.add(c.getLong(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void deleteConversation(long conversationId) {
        String[] args = new String[]{String.valueOf(conversationId)};
        try {
            getWritableDatabase().execSQL(
                    "DELETE FROM messages_fts WHERE rowid IN (SELECT id FROM messages"
                            + " WHERE conversation_id = ? AND " + asstWhere(false) + ")",
                    args);
        } catch (Exception ignored) {}
        getWritableDatabase().delete("messages",
                "conversation_id = ? AND " + asstWhere(false), args);
    }

    /** 主动式助手的会话列表（只含 asst=1 的对话），最新的在前。 */
    public List<ConversationSummary> listAssistantConversations() {
        // 与 listConversations 同一口径：按最后一条消息的时间，**最新在前**
        // （用户 2026-10-16 定）。旧写法 ORDER BY conversation_id DESC 只是
        // "号大的在前"，助手号段内部碰巧跟时间同序，但一旦有会话跨号段就错。
        List<ConversationSummary> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT conversation_id, COUNT(*) FROM messages"
                            + " WHERE conversation_id IS NOT NULL AND asst=1"
                            + " GROUP BY conversation_id", null);
            while (c.moveToNext()) {
                ConversationSummary s = new ConversationSummary();
                s.conversationId = c.getLong(0);
                s.count = c.getInt(1);
                out.add(s);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        fillSummaryPreviews(out, true);
        sortNewestFirst(out);
        return out;
    }

    /**
     * 助手的会话 id 分配：从 900001 起，避开主对话的 id 空间。
     * 这里取的是 90 万号段里**所有**已用过的会话号（不看 asst）：主对话历史上曾经
     * 分配进这个号段，只按 asst=1 取 MAX 会撞上已被主对话占用的会话号，
     * 表现就是"新建对话没反应，还是旧对话"。
     */
    public long getFreshAssistantConversationId() {
        long next = 900001L;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MAX(conversation_id) FROM messages WHERE conversation_id >= 900000", null);
            if (c.moveToFirst() && !c.isNull(0)) next = Math.max(next, c.getLong(0) + 1);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return next;
    }

    public void deleteAssistantConversation(long conversationId) {
        String[] args = new String[]{String.valueOf(conversationId)};
        try {
            getWritableDatabase().execSQL(
                    "DELETE FROM messages_fts WHERE rowid IN (SELECT id FROM messages"
                            + " WHERE conversation_id = ? AND " + asstWhere(true) + ")",
                    args);
        } catch (Exception ignored) {}
        getWritableDatabase().delete("messages",
                "conversation_id = ? AND " + asstWhere(true), args);
    }

    public void deleteMessagesInConversationFrom(long conversationId, long fromId) {
        deleteMessagesInConversationFrom(conversationId, fromId, false);
    }

    public void deleteMessagesInConversationFrom(long conversationId, long fromId, boolean assistant) {
        Object[] args = new Object[]{conversationId, fromId};
        try {
            getWritableDatabase().execSQL(
                    "DELETE FROM messages_fts WHERE rowid IN"
                            + " (SELECT id FROM messages WHERE conversation_id = ? AND id >= ?"
                            + " AND " + asstWhere(assistant) + ")", args);
        } catch (Exception ignored) {}
        getWritableDatabase().delete("messages",
                "conversation_id = ? AND id >= ? AND " + asstWhere(assistant),
                new String[]{String.valueOf(conversationId), String.valueOf(fromId)});
    }

    public void updateConversationNote(long conversationId, String note) {
        updateConversationNote(conversationId, note, false);
    }

    public void updateConversationNote(long conversationId, String note, boolean assistant) {
        try {
            getWritableDatabase().execSQL(
                    "UPDATE messages SET note = ? WHERE id ="
                            + " (SELECT MIN(id) FROM messages WHERE conversation_id = ?"
                            + " AND " + asstWhere(assistant) + ")",
                    new Object[]{note, conversationId});
        } catch (Exception ignored) {}
    }

    /** 主对话的会话号：只在 90 万以下分配，90 万起是主动式助手的号段。 */
    public long getFreshConversationId() {
        long next = 1;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MAX(v) FROM (SELECT MAX(id) AS v FROM messages WHERE " + asstWhere(false)
                            + " UNION ALL SELECT MAX(conversation_id) AS v FROM messages WHERE "
                            + asstWhere(false) + " AND conversation_id < 900000)", null);
            if (c.moveToFirst() && !c.isNull(0)) next = c.getLong(0) + 1;
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        if (next >= 900000L) {
            // 消息行数逼近 90 万时 MAX(id) 会顶进助手号段，退回只用会话号推导
            next = 1;
            Cursor c2 = null;
            try {
                c2 = getReadableDatabase().rawQuery(
                        "SELECT MAX(conversation_id) FROM messages WHERE " + asstWhere(false)
                                + " AND conversation_id < 900000", null);
                if (c2.moveToFirst() && !c2.isNull(0)) next = c2.getLong(0) + 1;
            } catch (Exception ignored) {
            } finally {
                if (c2 != null) try { c2.close(); } catch (Exception ignored) { }
            }
        }
        return next;
    }

    public int backfillConversationIds(List<long[]> ranges) {
        int total = 0;
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            if (ranges != null) {
                for (long[] r : ranges) {
                    if (r == null || r.length < 3) continue;
                    ContentValues v = new ContentValues();
                    v.put("conversation_id", r[0]);
                    total += d.update("messages", v, "conversation_id IS NULL AND id >= ? AND id <= ?",
                            new String[]{String.valueOf(r[1]), String.valueOf(r[2])});
                }
            }

            ContentValues v0 = new ContentValues();
            v0.put("conversation_id", 0L);
            total += d.update("messages", v0, "conversation_id IS NULL", null);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return total;
    }

    public int backfillProfileStore() {
        return 0;
    }

    public int countMessagesWithoutConversation() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM messages WHERE conversation_id IS NULL AND COALESCE(asst,0)=0", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public java.util.Map<Long, MemoryItem> getMemoriesByIds(java.util.Collection<Long> ids) {
        java.util.Map<Long, MemoryItem> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        StringBuilder ph = new StringBuilder();
        java.util.List<String> args = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) continue;
            if (ph.length() > 0) ph.append(',');
            ph.append('?');
            args.add(String.valueOf(id));
        }
        if (ph.length() == 0) return out;
        Cursor c = null;
        try {
            c = getReadableDatabase().query("memories", null, "id IN (" + ph + ")",
                    args.toArray(new String[0]), null, null, null, null);
            while (c.moveToNext()) {
                MemoryItem m = cursorToMemoryItem(c);
                if (m != null) out.put(m.id, m);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public long insertMemory(String content, String category) {
        return insertMemory(content, category, null, 0.5);
    }

    public long insertMemory(String content, String category, byte[] embedding, double importance) {
        return insertMemory(content, category, embedding, importance, null);
    }

    public long insertMemory(String content, String category, byte[] embedding, double importance, String store) {
        ContentValues values = new ContentValues();
        values.put("content", content);
        values.put("category", category == null ? "其他" : category);
        values.put("pinned", 0);
        values.put("created_at", System.currentTimeMillis());
        if (embedding != null) values.put("embedding", embedding);
        values.put("importance", importance);
        values.put("store", store == null ? autoStoreFor(category) : store);
        long id = getWritableDatabase().insert("memories", null, values);
        if (id > 0 && ftsReady()) {
            ContentValues fts = new ContentValues();
            fts.put("rowid", id);
            fts.put("content", content);
            fts.put("category", category == null ? "其他" : category);
            try { getWritableDatabase().insert("memories_fts", null, fts); } catch (Exception ignored) {}
        }
        return id;
    }

    private static String autoStoreFor(String category) {
        return "memory";
    }

    public void updateMemoryEmbedding(long id, byte[] embedding) {
        ContentValues values = new ContentValues();
        values.put("embedding", embedding);
        getWritableDatabase().update("memories", values, "id=?", new String[]{String.valueOf(id)});
    }

    public List<MemoryItem> listMemoriesWithoutEmbedding(int limit) {
        List<MemoryItem> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query("memories", null,
                    "COALESCE(archived,0)=0 AND embedding IS NULL",
                    null, null, null, "created_at DESC",
                    String.valueOf(Math.max(1, limit)));
            while (cursor.moveToNext()) result.add(cursorToMemoryItem(cursor));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listMemoriesWithoutEmbedding 失败: " + e.getMessage());
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Exception ignored) { }
        }
        return result;
    }

    public int countMemoriesWithoutEmbedding() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM memories WHERE COALESCE(archived,0)=0 AND embedding IS NULL", null);
            if (cursor.moveToFirst()) return cursor.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    private MemoryItem cursorToMemoryItem(Cursor cursor) {
        int embIdx = cursor.getColumnIndex("embedding");
        int impIdx = cursor.getColumnIndex("importance");
        int storeIdx = cursor.getColumnIndex("store");
        return new MemoryItem(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("content")),
                cursor.getString(cursor.getColumnIndexOrThrow("category")),
                cursor.getInt(cursor.getColumnIndexOrThrow("pinned")) == 1,
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at")),
                cursor.getInt(cursor.getColumnIndexOrThrow("use_count")),
                embIdx >= 0 && !cursor.isNull(embIdx) ? cursor.getBlob(embIdx) : null,
                impIdx >= 0 && !cursor.isNull(impIdx) ? cursor.getDouble(impIdx) : 0.5,
                storeIdx >= 0 && !cursor.isNull(storeIdx) ? cursor.getString(storeIdx) : null);
    }

    public List<MemoryItem> listMemories(String query, boolean pinnedOnly, int limit) {
        return listMemoriesFiltered(query, pinnedOnly, null, limit);
    }

    public int migrateUserDimToLongTerm() {
        SQLiteDatabase d = getWritableDatabase();
        int moved = 0;
        d.beginTransaction();
        try {
            Cursor c = d.rawQuery(
                    "SELECT id, COALESCE(category,'') FROM memories "
                            + "WHERE COALESCE(store,'memory')='user' OR (COALESCE(store,'memory')='memory' "
                            + "AND COALESCE(category,'') IN ('画像','工作','经验'))", null);
            List<long[]> ids = new ArrayList<>();
            List<String> cats = new ArrayList<>();
            while (c.moveToNext()) {
                ids.add(new long[]{c.getLong(0)});
                cats.add(c.getString(1));
            }
            c.close();
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("store", "memory");
                v.put("category", MemoryDim.normalizeUser(cats.get(i)));
                d.update("memories", v, "id=?", new String[]{String.valueOf(ids.get(i)[0])});
                moved++;
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return moved;
    }

    public int migrateSelfDim() {
        SQLiteDatabase d = getWritableDatabase();
        int moved = 0;
        d.beginTransaction();
        try {
            Cursor c = d.rawQuery(
                    "SELECT id FROM memories WHERE COALESCE(category,'')='自述'", null);
            List<Long> ids = new ArrayList<>();
            while (c.moveToNext()) ids.add(c.getLong(0));
            c.close();
            for (Long id : ids) {
                ContentValues v = new ContentValues();
                v.put("category", "身份");
                d.update("memories", v, "id=?", new String[]{String.valueOf(id)});
                moved++;
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return moved;
    }

    /**
     * 把**错落进用户记忆的自述条目**归位到 store='self'。
     *
     * <p>成因：MemoryConsolidator 落库时漏传模型的 store 判定，4 参 insertMemory 走
     * autoStoreFor() 兜底恒定返回 "memory"，于是"关于你自己"的内容全进了用户记忆池，
     * 提示词里那块"你自己的笔记"永远是空的（2026-10-13 修复代码，这里补历史数据）。
     *
     * <p>判据只认**无歧义**的自述维度名：成长/关系/世界观/好奇/创意/价值/情感/预测/存在。
     * 这 9 个在用户侧不可达 —— normalizeUser() 会把它们全部映射成"笔记"，不可能被写成 category；
     * "身份"两边同名（既可能是用户身份也可能是它自己的身份），无法判断，**不动**。
     * 只动 store='memory' 的行，asst:* 与 user 一概不碰。
     */
    public int migrateMisstoredSelfMemories() {
        int moved = 0;
        SQLiteDatabase d = getWritableDatabase();
        String[] selfOnly = {"成长", "关系", "世界观", "好奇", "创意", "价值", "情感", "预测", "存在"};
        StringBuilder ph = new StringBuilder();
        for (String s : selfOnly) {
            if (ph.length() > 0) ph.append(',');
            ph.append('?');
        }
        d.beginTransaction();
        try {
            List<Long> ids = new ArrayList<>();
            Cursor c = d.rawQuery(
                    "SELECT id FROM memories WHERE COALESCE(store,'memory')='memory'"
                            + " AND COALESCE(category,'') IN (" + ph + ")", selfOnly);
            while (c.moveToNext()) ids.add(c.getLong(0));
            c.close();
            for (Long id : ids) {
                ContentValues v = new ContentValues();
                v.put("store", "self");
                d.update("memories", v, "id=?", new String[]{String.valueOf(id)});
                moved++;
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return moved;
    }

    public List<MemoryItem> listMemoriesFiltered(String query, boolean pinnedOnly, String store, int limit) {
        return listMemoriesFiltered(query, pinnedOnly, store, limit, false);
    }

    public List<MemoryItem> listMemoriesFiltered(String query, boolean pinnedOnly, String store,
                                                 int limit, boolean assistantOnly) {
        return listMemoriesFiltered(query, pinnedOnly, store, null, limit, assistantOnly);
    }

    public List<MemoryItem> listMemoriesFiltered(String query, boolean pinnedOnly, String store,
                                                 String category, int limit, boolean assistantOnly) {
        List<MemoryItem> result = new ArrayList<>();
        boolean useFts = query != null && !query.trim().isEmpty() && ftsReady();
        if (useFts) {
            try {
                String ftsQuery = query.trim();
                StringBuilder sql = new StringBuilder(
                        "SELECT m.* FROM memories m JOIN memories_fts f ON m.id = f.rowid WHERE memories_fts MATCH ?");
                List<String> args = new ArrayList<>();
                args.add(ftsQuery + "*");
                sql.append(" AND COALESCE(m.archived,0)=0");
                sql.append(storeClassWhere("m", assistantOnly));
                if (store != null) { sql.append(" AND COALESCE(m.store,'memory') = ?"); args.add(store); }
                if (category != null) { sql.append(" AND COALESCE(m.category,'') = ?"); args.add(category); }
                if (pinnedOnly) { sql.append(" AND m.pinned = 1"); }
                sql.append(" ORDER BY m.created_at DESC LIMIT ?");
                args.add(String.valueOf(limit));
                Cursor cursor = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
                while (cursor.moveToNext()) result.add(cursorToMemoryItem(cursor));
                cursor.close();
                return result;
            } catch (Exception e) {

            }
        }
        StringBuilder where = new StringBuilder("COALESCE(archived,0)=0");
        List<String> args = new ArrayList<>();
        where.append(storeClassWhere("", assistantOnly));
        if (query != null && !query.trim().isEmpty()) {
            where.append(" AND content LIKE ?");
            args.add("%" + query.trim() + "%");
        }
        if (store != null) {
            where.append(" AND COALESCE(store,'memory') = ?");
            args.add(store);
        }
        if (category != null) {
            where.append(" AND COALESCE(category,'') = ?");
            args.add(category);
        }
        if (pinnedOnly) {
            where.append(" AND pinned = 1");
        }
        Cursor cursor = getReadableDatabase().query(
                "memories", null, where.toString(),
                args.toArray(new String[0]),
                null, null, "created_at DESC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(cursorToMemoryItem(cursor));
        }
        cursor.close();
        return result;
    }

    private static String storeClassWhere(String alias, boolean assistantOnly) {
        String col = "COALESCE(" + (alias == null || alias.isEmpty() ? "" : alias + ".") + "store,'memory')";

        return assistantOnly
                ? " AND " + col + " LIKE 'asst:%'"
                : " AND " + col + " NOT LIKE 'asst:%'";
    }

    public List<MemoryItem> listAllMemoriesWithEmbeddings(int limit) {
        return listAllMemoriesWithEmbeddings(limit, false);
    }

    public List<MemoryItem> listAllMemoriesWithEmbeddings(int limit, boolean assistantOnly) {
        List<MemoryItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "memories", null,
                "COALESCE(archived,0)=0" + storeClassWhere("", assistantOnly), null, null, null,
                "created_at DESC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(cursorToMemoryItem(cursor));
        }
        cursor.close();
        return result;
    }

    public MemoryItem getMemory(long id) {
        Cursor c = getReadableDatabase().query("memories", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        MemoryItem m = c.moveToFirst() ? cursorToMemoryItem(c) : null;
        c.close();
        return m;
    }

    public void touchMemory(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE memories SET last_used_at = ?, use_count = use_count + 1 WHERE id = ?",
                new Object[]{System.currentTimeMillis(), id});
    }

    public List<MemoryItem> listOldestMemories(int count) {
        List<MemoryItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "memories", null, "pinned = 0", null, null, null,
                "last_used_at ASC, created_at ASC", String.valueOf(count));
        while (cursor.moveToNext()) {
            result.add(cursorToMemoryItem(cursor));
        }
        cursor.close();
        return result;
    }

    public int countMemories() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories", null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    public void togglePin(long id, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("memories", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteMemory(long id) {
        getWritableDatabase().delete("memories", "id=?", new String[]{String.valueOf(id)});
        try { getWritableDatabase().delete("memories_fts", "rowid=?", new String[]{String.valueOf(id)}); } catch (Exception ignored) {}
    }

    public void clearAll() {
        getWritableDatabase().delete("messages", null, null);
        getWritableDatabase().delete("memories", null, null);
        getWritableDatabase().delete("entities", null, null);
        getWritableDatabase().delete("knowledge", null, null);
        // 感知树也一起清（这是用户手动点「清空数据」+二次确认的路径；
        // 助手侧的删除永远只进归档，只有用户能走到这里）。
        try { getWritableDatabase().delete("perception_nodes", null, null); } catch (Exception ignored) { }
        try { getWritableDatabase().delete("perception_archive", null, null); } catch (Exception ignored) { }
        try { getWritableDatabase().execSQL("DELETE FROM memories_fts"); } catch (Exception ignored) {}
        try { getWritableDatabase().execSQL("DELETE FROM messages_fts"); } catch (Exception ignored) {}
        try { getWritableDatabase().execSQL("DELETE FROM knowledge_fts"); } catch (Exception ignored) {}
    }

    // 备份是"统一所有数据"（用户 2026-10-13：备份/发微信按钮管全部，不只记忆库）。
    // 感知树（含归档）也在里面——档案本身就是数据，丢了不可逆。
    private static final String[] BACKUP_TABLES = {"memories", "knowledge", "messages", "user_docs",
            "perception_nodes", "perception_archive"};

    public int[] exportBackupTo(java.io.OutputStream os) throws Exception {
        int[] counts = new int[BACKUP_TABLES.length];
        android.util.JsonWriter w = new android.util.JsonWriter(new java.io.OutputStreamWriter(os, "UTF-8"));
        try {

            SQLiteDatabase d = getWritableDatabase();
            d.beginTransactionNonExclusive();
            try {
                w.beginObject();
                w.name("app").value("linyuan");
                w.name("backup_version").value(1);
                w.name("exported_at").value(System.currentTimeMillis());
                w.name("db_version").value(DB_VERSION);
                w.name("tables").beginObject();
                for (int t = 0; t < BACKUP_TABLES.length; t++) {
                    if ("user_docs".equals(BACKUP_TABLES[t])) counts[t] = dumpUserDocsTable(w);
                    else counts[t] = dumpTableToBackup(d, w, BACKUP_TABLES[t]);
                }
                w.endObject();
                w.endObject();
                w.flush();
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        } finally {
            try { w.close(); } catch (Exception ignored) {}
        }
        return counts;
    }

    private int dumpTableToBackup(SQLiteDatabase d, android.util.JsonWriter w, String table) throws Exception {
        int count = 0;
        Cursor c = null;
        try {
            c = d.rawQuery("SELECT * FROM " + table, null);
            String[] names = c.getColumnNames();
            w.name(table).beginArray();
            while (c.moveToNext()) {
                w.beginObject();
                for (int i = 0; i < names.length; i++) {
                    w.name(names[i]);
                    switch (c.getType(i)) {
                        case android.database.Cursor.FIELD_TYPE_NULL:
                            w.nullValue();
                            break;
                        case android.database.Cursor.FIELD_TYPE_BLOB:
                            byte[] b = c.getBlob(i);
                            w.value(b == null ? null
                                    : android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP));
                            break;
                        case android.database.Cursor.FIELD_TYPE_INTEGER:
                            w.value(c.getLong(i));
                            break;
                        case android.database.Cursor.FIELD_TYPE_FLOAT:
                            w.value(c.getDouble(i));
                            break;
                        default:
                            w.value(c.getString(i));
                    }
                }
                w.endObject();
                count++;
            }
            w.endArray();
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        return count;
    }

    private int dumpUserDocsTable(android.util.JsonWriter w) throws Exception {
        int count = 0;
        java.util.List<UserDoc> list = listUserDocs();
        w.name("user_docs").beginArray();
        for (UserDoc d : list) {
            w.beginObject();
            w.name("id").value(d.id);
            w.name("title").value(d.title);
            w.name("content").value(getUserDocContent(d.id));
            w.name("source").value(d.source);
            w.name("created_at").value(d.createdAt);
            w.endObject();
            count++;
        }
        w.endArray();
        return count;
    }

    public int[] restoreBackupFrom(java.io.InputStream is) throws Exception {
        // out[0..N-1] = 每张表的恢复条数；out[N] = 跳过（已存在）；out[N+1] = 失败。
        final int N = BACKUP_TABLES.length;
        int[] out = new int[N + 2];
        android.util.JsonReader r = new android.util.JsonReader(new java.io.InputStreamReader(is, "UTF-8"));
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransactionNonExclusive();
        try {
            r.beginObject();
            while (r.hasNext()) {
                String top = r.nextName();
                if ("tables".equals(top)) {
                    r.beginObject();
                    while (r.hasNext()) {
                        String name = r.nextName();
                        int idx = -1;
                        for (int t = 0; t < BACKUP_TABLES.length; t++) {
                            if (BACKUP_TABLES[t].equals(name)) idx = t;
                        }

                        String table = idx >= 0 ? BACKUP_TABLES[idx] : null;
                        java.util.Map<String, String> colTypes = table == null ? null : tableColumnTypes(table);
                        r.beginArray();
                        while (r.hasNext()) {
                            java.util.Map<String, Object> row = readBackupRow(r);
                            if (table == null || colTypes.isEmpty()) continue;
                            int res = insertBackupRow(d, table, colTypes, row);
                            if (res == 1) out[idx]++;
                            else if (res == 0) out[N]++;
                            else out[N + 1]++;
                        }
                        r.endArray();
                    }
                    r.endObject();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            rebuildFtsAfterRestore();
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
            try { r.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private int insertBackupRow(SQLiteDatabase d, String table, java.util.Map<String, String> colTypes,
                                java.util.Map<String, Object> row) {
        try {
            Object idv = row.get("id");
            if (idv != null) {
                Cursor c = null;
                try {
                    c = d.rawQuery("SELECT 1 FROM " + table + " WHERE id=?",
                            new String[]{String.valueOf(idv)});
                    if (c.moveToFirst()) return 0;
                } finally {
                    if (c != null) try { c.close(); } catch (Exception ignored) {}
                }
            }
            android.content.ContentValues cv = new android.content.ContentValues();
            for (java.util.Map.Entry<String, Object> e : row.entrySet()) {
                String col = e.getKey();
                if (!colTypes.containsKey(col)) continue;
                Object v = e.getValue();
                if (v == null) { cv.putNull(col); continue; }
                String type = colTypes.get(col);
                if (type != null && type.toUpperCase(java.util.Locale.US).contains("BLOB") && v instanceof String) {
                    try {
                        cv.put(col, android.util.Base64.decode((String) v, android.util.Base64.NO_WRAP));
                        continue;
                    } catch (Exception ignored) {  }
                }
                cv.put(col, String.valueOf(v));
            }
            if (cv.size() == 0) return -1;
            long rid = d.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            return rid > 0 ? 1 : -1;
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "恢复 " + table + " 一行失败: " + e.getMessage());
            return -1;
        }
    }

    private java.util.Map<String, String> tableColumnTypes(String table) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) m.put(c.getString(1), c.getString(2) == null ? "" : c.getString(2));
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        return m;
    }

    private java.util.Map<String, Object> readBackupRow(android.util.JsonReader r) throws Exception {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            android.util.JsonToken t = r.peek();
            if (t == android.util.JsonToken.NULL) { r.nextNull(); m.put(k, null); }
            else if (t == android.util.JsonToken.BOOLEAN) m.put(k, r.nextBoolean() ? "1" : "0");
            else m.put(k, r.nextString());
        }
        r.endObject();
        return m;
    }

    private void rebuildFtsAfterRestore() {
        if (!ftsReady()) return;
        try {
            getWritableDatabase().execSQL("DELETE FROM memories_fts");
            getWritableDatabase().execSQL(
                    "INSERT INTO memories_fts(rowid, content, category) SELECT id, content, category FROM memories");
        } catch (Exception ignored) {}
        try {
            getWritableDatabase().execSQL("DELETE FROM messages_fts");
            getWritableDatabase().execSQL(
                    "INSERT INTO messages_fts(rowid, content) SELECT id, content FROM messages");
        } catch (Exception ignored) {}
        try {
            getWritableDatabase().execSQL("DELETE FROM knowledge_fts");
            getWritableDatabase().execSQL(
                    "INSERT INTO knowledge_fts(rowid, chunk) SELECT id, chunk FROM knowledge");
        } catch (Exception ignored) {}
    }

    public long upsertEntity(String name, String type, String attributes) {
        Cursor cursor = getReadableDatabase().query(
                "entities", null, "name = ?", new String[]{name}, null, null, null);
        long existingId = -1;
        if (cursor.moveToFirst()) {
            existingId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        }
        cursor.close();
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("type", type);
        values.put("attributes", attributes);
        if (existingId > 0) {
            getWritableDatabase().update("entities", values, "id=?", new String[]{String.valueOf(existingId)});
            return existingId;
        } else {
            values.put("created_at", System.currentTimeMillis());
            return getWritableDatabase().insert("entities", null, values);
        }
    }

    public List<EntityItem> listEntities(int limit) {
        List<EntityItem> result = new ArrayList<>();

        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT * FROM entities ORDER BY MAX(created_at, COALESCE(last_used_at, 0)) DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        while (cursor.moveToNext()) {
            result.add(new EntityItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    cursor.getString(cursor.getColumnIndexOrThrow("attributes")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("use_count"))));
        }
        cursor.close();
        return result;
    }

    public void touchEntity(long id) {
        getWritableDatabase().execSQL("UPDATE entities SET last_used_at = ?, use_count = use_count + 1 WHERE id = ?",
                new Object[]{System.currentTimeMillis(), id});
    }

    public List<MemoryItem> listMemoriesByStore(String store) {
        List<MemoryItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "memories", null, "COALESCE(store,'memory') = ? AND COALESCE(archived,0)=0",
                new String[]{store}, null, null, "pinned DESC, created_at DESC", null);
        while (cursor.moveToNext()) result.add(cursorToMemoryItem(cursor));
        cursor.close();
        return result;
    }

    public void setMemoryArchived(long id, boolean archived) {
        ContentValues v = new ContentValues();
        v.put("archived", archived ? 1 : 0);
        getWritableDatabase().update("memories", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void reassignMemoryStore(long id, String store, String category) {
        ContentValues v = new ContentValues();
        v.put("store", store);
        if (category != null && !category.trim().isEmpty()) v.put("category", category.trim());
        getWritableDatabase().update("memories", v, "id=?", new String[]{String.valueOf(id)});
    }

    public int restoreRecentlyArchived(long newerThanMs) {
        try {
            int before = countArchivedMemories();
            getWritableDatabase().execSQL(
                    "UPDATE memories SET archived=0 "
                            + "WHERE COALESCE(archived,0)=1 "
                            + "AND MAX(created_at, COALESCE(last_used_at,0)) >= ?",
                    new Object[]{newerThanMs});
            return before - countArchivedMemories();
        } catch (Exception e) {
            android.util.Log.w("VC_MEM", "恢复误归档失败: " + e.getMessage());
            return 0;
        }
    }

    public List<MemoryItem> listArchivedMemories(int limit) {
        return listArchivedMemories(limit, false);
    }

    public List<MemoryItem> listArchivedMemories(int limit, boolean assistantOnly) {
        List<MemoryItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("memories", null,
                "COALESCE(archived,0)=1" + storeClassWhere("", assistantOnly),
                null, null, null, "created_at DESC", String.valueOf(limit));
        while (c.moveToNext()) out.add(cursorToMemoryItem(c));
        c.close();
        return out;
    }

    public int countArchivedMemories() {
        return countArchivedMemories(false);
    }

    public int countArchivedMemories(boolean assistantOnly) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM memories WHERE COALESCE(archived,0)=1" + storeClassWhere("", assistantOnly), null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public List<MemoryItem> findArchiveCandidates(long olderThanMs, int minUseCount, int limit) {
        return findArchiveCandidates(olderThanMs, minUseCount, limit, false);
    }

    public List<MemoryItem> findArchiveCandidates(long olderThanMs, int minUseCount, int limit,
                                                 boolean assistantOnly) {
        List<MemoryItem> out = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - olderThanMs;

        Cursor c = getReadableDatabase().query("memories", null,
                "COALESCE(pinned,0)=0 AND COALESCE(archived,0)=0 AND COALESCE(use_count,0)<=? "
                        + "AND COALESCE(store,'memory') <> ? "
                        + storeClassWhere("", assistantOnly)
                        + " AND MAX(created_at, COALESCE(last_used_at,0)) < ?",
                new String[]{String.valueOf(minUseCount), assistantOnly ? "asst:self" : "self", String.valueOf(cutoff)},
                null, null, "MAX(created_at, COALESCE(last_used_at,0)) ASC", String.valueOf(limit));
        while (c.moveToNext()) out.add(cursorToMemoryItem(c));
        c.close();
        return out;
    }

    public long insertMemoryStore(String content, String category, String store) {
        ContentValues values = new ContentValues();
        values.put("content", content);
        values.put("category", category == null ? "其他" : category);
        values.put("pinned", 0);
        values.put("created_at", System.currentTimeMillis());
        values.put("importance", 0.5);
        values.put("store", store == null ? "memory" : store);
        long id = getWritableDatabase().insert("memories", null, values);
        if (id > 0 && ftsReady()) {
            ContentValues fts = new ContentValues();
            fts.put("rowid", id);
            fts.put("content", content);
            fts.put("category", category == null ? "其他" : category);
            try { getWritableDatabase().insert("memories_fts", null, fts); } catch (Exception ignored) {}
        }
        return id;
    }

    public List<Long> findMemoryIdsBySubstring(String store, String sub) {
        List<Long> ids = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "memories", new String[]{"id"}, "COALESCE(store,'memory') = ? AND content LIKE ?",
                new String[]{store, "%" + sub + "%"}, null, null, "created_at DESC", null);
        while (cursor.moveToNext()) ids.add(cursor.getLong(0));
        cursor.close();
        return ids;
    }

    public void updateMemoryContent(long id, String content) {
        ContentValues values = new ContentValues();
        values.put("content", content);
        getWritableDatabase().update("memories", values, "id=?", new String[]{String.valueOf(id)});
        try {
            ContentValues fts = new ContentValues();
            fts.put("content", content);
            if (ftsReady()) getWritableDatabase().update("memories_fts", fts, "rowid=?", new String[]{String.valueOf(id)});
        } catch (Exception ignored) {}
    }

    public List<MessageItem> searchMessages(String query, int limit) {
        return searchMessages(query, limit, false);
    }

    /**
     * 按侧搜聊天记录。
     *
     * <p>2026-10-17 用户定案：两侧的记录**互不相通**——
     * 「他们不能相互缠着对方的意思对话……不然的话，混在一起就会搞混」。
     * 所以 includeAssistantSide=true 表示"这是助手侧在搜"，**只搜 asst=1 那半边**；
     * false 表示"主对话侧在搜"，只搜 asst=0 那半边。
     * （改之前 true 是不加过滤、两侧全搜，助手会读到主对话的内容。）
     */
    public List<MessageItem> searchMessages(String query, int limit, boolean includeAssistantSide) {

        List<MessageItem> result = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return result;

        java.util.LinkedHashSet<String> termSet = new java.util.LinkedHashSet<>();
        termSet.add(q);
        String clean = q.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        for (int n = 4; n >= 2; n--) {
            for (int i = 0; i + n <= clean.length(); i++) termSet.add(clean.substring(i, i + n));
        }
        java.util.List<String> terms = new ArrayList<>();
        for (String t : termSet) {
            if (t.length() < 2) continue;
            terms.add(t);
            if (terms.size() >= 60) break;
        }
        if (terms.isEmpty()) return result;

        java.util.LinkedHashMap<Long, Integer> score = new java.util.LinkedHashMap<>();
        java.util.HashMap<Long, MessageItem> byId = new java.util.HashMap<>();
        for (String t : terms) {
            Cursor c = getReadableDatabase().query(
                    "messages", null,
                    "content LIKE ? AND " + asstWhere(includeAssistantSide),
                    new String[]{"%" + t + "%"}, null, null, "id DESC", "30");
            while (c.moveToNext()) {
                long id = c.getLong(c.getColumnIndexOrThrow("id"));
                if (!byId.containsKey(id)) {
                    byId.put(id, new MessageItem(
                            id,
                            c.getString(c.getColumnIndexOrThrow("role")),
                            c.getString(c.getColumnIndexOrThrow("content")),
                            c.getString(c.getColumnIndexOrThrow("audio_path")),
                            c.getLong(c.getColumnIndexOrThrow("created_at"))));
                }
                Integer old = score.get(id);
                score.put(id, (old == null ? 0 : old) + t.length() * t.length());
            }
            c.close();
        }
        java.util.List<Long> ids = new ArrayList<>(score.keySet());
        ids.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        for (int i = 0; i < Math.min(limit, ids.size()); i++) result.add(byId.get(ids.get(i)));
        return result;
    }

    /**
     * 主对话侧的记忆（关于用户 store=memory + 主对话自己的笔记 store=self），只读检索。
     *
     * <p>给助手端的 read_memory_library 用（用户 2026-10-13 定：助手可以看用户侧记忆、
     * 不能改）。中文没有空格，整串 LIKE 会漏，所以用"整串 + 2~4 字片段"子串匹配打分，
     * 与 searchMessages 的做法一致；条目量不大（千级），在内存里筛足够快。
     * 这里**没有任何写入口** —— 只读保证靠"只管读"实现，不靠调用方自律。
     */
    public List<MemoryItem> searchUserSideMemories(String query, int limit) {
        List<MemoryItem> out = new ArrayList<>();
        List<MemoryItem> all = listMemoriesFiltered(null, false, null, null, 2000, false);
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            for (int i = 0; i < all.size() && i < limit; i++) out.add(all.get(i));
            return out;
        }
        java.util.LinkedHashSet<String> termSet = new java.util.LinkedHashSet<>();
        termSet.add(q);
        String clean = q.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        for (int n = 4; n >= 2; n--) {
            for (int i = 0; i + n <= clean.length(); i++) termSet.add(clean.substring(i, i + n));
        }
        java.util.List<String> terms = new ArrayList<>();
        for (String t : termSet) {
            if (t.length() < 2) continue;
            terms.add(t);
            if (terms.size() >= 60) break;
        }
        if (terms.isEmpty()) terms.add(q);
        java.util.List<long[]> score = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            String content = all.get(i).content == null ? "" : all.get(i).content;
            int s = 0;
            for (String t : terms) if (content.contains(t)) s += t.length() * t.length();
            if (s > 0) score.add(new long[]{i, s});
        }
        score.sort((a, b) -> Long.compare(b[1], a[1]));
        for (int i = 0; i < score.size() && out.size() < limit; i++) out.add(all.get((int) score.get(i)[0]));
        return out;
    }

    public long insertKnowledgeChunk(String title, String chunk, String source, int chunkIndex, byte[] embedding) {
        return insertKnowledgeChunk(title, chunk, source, chunkIndex, embedding, 0L);
    }

    /** sentAt = 用户发送这份文档的时间（0 = 没给，退回入库时间）。 */
    public long insertKnowledgeChunk(String title, String chunk, String source, int chunkIndex,
                                     byte[] embedding, long sentAt) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("chunk", chunk);
        values.put("source", source);
        values.put("chunk_index", chunkIndex);
        values.put("embedding", embedding);
        values.put("created_at", System.currentTimeMillis());
        if (sentAt > 0) values.put("sent_at", sentAt);
        long id = getWritableDatabase().insert("knowledge", null, values);
        if (id > 0 && ftsReady()) {
            try {
                ContentValues fts = new ContentValues();
                fts.put("rowid", id);
                fts.put("chunk", chunk);
                getWritableDatabase().insert("knowledge_fts", null, fts);
            } catch (Exception ignored) {}
        }
        return id;
    }

    /**
     * 回填某份文档的"发送时间"（2026-10-17）。
     *
     * <p>只动 sent_at 还是 0 的行；已经有值的不覆盖（同一份文档被助手回显过时，
     * 早的那条会先命中，正好是用户真正发送的那次）。由 MainActivity 的后台一次性任务驱动。
     */
    public int setKnowledgeSentAtByTitle(String title, long sentAt) {
        if (title == null || title.trim().isEmpty() || sentAt <= 0) return 0;
        ContentValues cv = new ContentValues();
        cv.put("sent_at", sentAt);
        try {
            return getWritableDatabase().update("knowledge", cv,
                    "title = ? AND COALESCE(sent_at,0) = 0", new String[]{title});
        } catch (Exception e) {
            android.util.Log.w("VC_DOC", "回填文档发送时间失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 列名固定，**不 SELECT embedding**（2026-10-17 修）。
     *
     * <p>原来传 null = SELECT 所有列，会把 4096 字节的向量 blob 一起拖出来：
     * 1000 行 ≈ 4.42 MB，超过 CursorWindow 的 2 MB 上限 → 抛异常 → 文档库页签显示"还没有文档"。
     */
    private static final String[] KNOWLEDGE_LIST_COLS = {
            "id", "title", "chunk", "source", "chunk_index", "created_at"};

    public List<KnowledgeItem> listKnowledgeChunks(int limit) {
        List<KnowledgeItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "knowledge", KNOWLEDGE_LIST_COLS, null, null, null, null,
                "created_at DESC, id DESC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(cursorToKnowledgeItem(cursor));
        }
        cursor.close();
        return result;
    }

    /** 一份文档在列表里需要的元信息：标题 + 块数 + 最早入库时间。 */
    public static class KnowledgeDocMeta {
        public String title;
        public int chunks;
        public long createdAt;
        /** 用户发送这份文档的时间；0 表示当时没记（显示时退回 createdAt）。 */
        public long sentAt;
    }

    /**
     * 知识库文档列表（按标题聚合，2026-10-17 新增）。
     *
     * <p>为什么不用 {@link #listKnowledgeChunks}：那个是"取最近 N 块"，块数一多（1077 块）
     * 就会把最早的文档整份切掉（实测 9-25 那份 15 块 + 病情咨询组 62 块被截掉）。
     * 列表只要"每份文档几块、什么时候入库"，交给 SQL 聚合，多少块都不会漏。
     */
    public List<KnowledgeDocMeta> listKnowledgeDocs() {
        List<KnowledgeDocMeta> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT title, COUNT(*) AS n, MIN(created_at) AS t,"
                            + " MIN(CASE WHEN COALESCE(sent_at,0) > 0 THEN sent_at ELSE created_at END) AS s"
                            + " FROM knowledge GROUP BY title"
                            + " ORDER BY s DESC, title ASC", null);
            while (c.moveToNext()) {
                KnowledgeDocMeta m = new KnowledgeDocMeta();
                String t = c.getString(0);
                m.title = (t == null || t.trim().isEmpty()) ? "未命名文档" : t;
                m.chunks = c.getInt(1);
                m.createdAt = c.getLong(2);
                m.sentAt = c.getLong(3);
                out.add(m);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DOC", "列知识库文档失败: " + e.getMessage());
        } finally {
            if (c != null) { try { c.close(); } catch (Exception ignored) { } }
        }
        return out;
    }

    /** 同一张表同一类坑：导出全文用，同样不拖 embedding（2026-10-17 修）。 */
    public List<KnowledgeItem> listKnowledgeByTitle(String title) {
        List<KnowledgeItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "knowledge", KNOWLEDGE_LIST_COLS, "title = ?", new String[]{title == null ? "" : title},
                null, null, "chunk_index ASC", null);
        while (cursor.moveToNext()) {
            result.add(cursorToKnowledgeItem(cursor));
        }
        cursor.close();
        return result;
    }

    public List<KnowledgeItem> listKnowledgeWithEmbeddings(int limit) {
        List<KnowledgeItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "knowledge", null, null, null, null, null, "created_at DESC, id DESC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(cursorToKnowledgeItem(cursor));
        }
        cursor.close();
        return result;
    }

    public List<KnowledgeItem> searchKnowledgeLike(String query, int limit) {
        List<KnowledgeItem> result = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return result;

        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        terms.add(q);

        String clean = q.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        for (int n = 4; n >= 2; n--) {
            for (int i = 0; i + n <= clean.length(); i++) {
                terms.add(clean.substring(i, i + n));
            }
        }

        java.util.List<String> termList = new ArrayList<>();
        int max = 60;
        for (String t : terms) {
            if (t.length() < 2) continue;
            termList.add(t);
            if (termList.size() >= max) break;
        }
        if (termList.isEmpty()) return result;

        java.util.LinkedHashMap<Long, Integer> score = new java.util.LinkedHashMap<>();
        java.util.HashMap<Long, KnowledgeItem> byId = new java.util.HashMap<>();
        for (String t : termList) {
            Cursor c = getReadableDatabase().query(
                    "knowledge", null, "chunk LIKE ?",
                    new String[]{"%" + t + "%"}, null, null, "created_at DESC, id DESC", "20");
            while (c.moveToNext()) {
                KnowledgeItem it = cursorToKnowledgeItem(c);
                byId.put(it.id, it);
                Integer old = score.get(it.id);

                score.put(it.id, (old == null ? 0 : old) + t.length() * t.length());
            }
            c.close();
        }
        if (byId.isEmpty()) return result;

        java.util.List<Long> ids = new ArrayList<>(score.keySet());
        ids.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        for (int i = 0; i < Math.min(limit, ids.size()); i++) {
            result.add(byId.get(ids.get(i)));
        }
        return result;
    }

    public int countKnowledge() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM knowledge", null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    public List<MessageItem> listMessagesWithDocMarker(int limit) {
        List<MessageItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "messages", null, "content LIKE ?",
                new String[]{"%@@DOC:%"}, null, null, "id ASC", String.valueOf(limit));
        while (cursor.moveToNext()) {
            result.add(new MessageItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("role")),
                    cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))));

            int mdlIdx = cursor.getColumnIndex("model");
            if (mdlIdx >= 0) result.get(result.size() - 1).model = cursor.getString(mdlIdx);
        }
        cursor.close();
        return result;
    }

    public int countKnowledgeTitles() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT title) FROM knowledge", null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    public void deleteKnowledgeByTitle(String title) {
        List<Long> ids = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "knowledge", new String[]{"id"}, "title = ?", new String[]{title == null ? "" : title},
                null, null, null, null);
        while (cursor.moveToNext()) ids.add(cursor.getLong(0));
        cursor.close();
        for (Long id : ids) {
            getWritableDatabase().delete("knowledge", "id=?", new String[]{String.valueOf(id)});
            try { getWritableDatabase().delete("knowledge_fts", "rowid=?", new String[]{String.valueOf(id)}); } catch (Exception ignored) {}
        }
    }

    public void clearKnowledge() {
        getWritableDatabase().delete("knowledge", null, null);
        try { getWritableDatabase().delete("knowledge_fts", null, null); } catch (Exception ignored) {}
    }

    private KnowledgeItem cursorToKnowledgeItem(Cursor cursor) {
        int embIdx = cursor.getColumnIndex("embedding");
        return new KnowledgeItem(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("chunk")),
                cursor.getString(cursor.getColumnIndexOrThrow("source")),
                cursor.getInt(cursor.getColumnIndexOrThrow("chunk_index")),
                embIdx >= 0 && !cursor.isNull(embIdx) ? cursor.getBlob(embIdx) : null,
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
    }

    public static class GmRoom {
        public long id;
        public String title;
        public int contextLimit;
        public int maxOutputTokens;
        public int maxTotalMsgs;
        public int speakIntervalSec;
        public long blockedUntil;
        public String globalPrompt;
        public boolean started;

        public boolean goalMode;

        public String goalText;

        public String exportState = "idle";

        public String exportNote;

        public int lastRound;

        public long lastMemberId;

        public int speakIntervalMaxSec;

        public boolean noInterval;

        public int langMode;
        public long createdAt;
    }

    public static class GmMember {
        public long id;
        public long roomId;
        public String name;
        public String avatar;
        public String roleDesc;
        public String prompt;
        public String model;
        public String baseUrl;
        public String apiKey;
        public boolean canSearch;
        public int maxMsgs;
        public int usedMsgs;
        public long usedTokens;
        public long lastSpokeAt;
        public boolean injected;
        public int thinkLevel = 2;
        public boolean active = true;
        public boolean isDefault;
        public int phase = 0;

        public int sortOrder = 0;

        public boolean manualStandby;

        public int confirmedRound = 0;
        public long createdAt;
    }

    public static class GmMessage {
        public long id;
        public long roomId;
        public long memberId;
        public String speaker;
        public String avatar;
        public String content;
        public boolean isThought;
        public String kind;
        public int tokens;

        public int promptTokens;

        public int completionTokens;

        public long genMs;
        public long durationMs;
        public long createdAt;

        public boolean collapsed = true;

        public int seq;

    }

    public long insertGmRoom(String title, int contextLimit, int maxTotalMsgs, int speakIntervalSec) {
        return insertGmRoom(title, contextLimit, maxTotalMsgs, speakIntervalSec, null);
    }

    public long insertGmRoom(String title, int contextLimit, int maxTotalMsgs, int speakIntervalSec, String globalPrompt) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("context_limit", contextLimit);
        v.put("max_output_tokens", 110000);
        v.put("max_total_msgs", maxTotalMsgs);
        v.put("speak_interval_sec", speakIntervalSec);

        v.put("speak_interval_max_sec", 60);
        v.put("blocked_until", 0);
        v.put("global_prompt", globalPrompt);

        v.put("goal_mode", 1);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_rooms", null, v);
    }

    public void setGmRoomGlobalPrompt(long roomId, String globalPrompt) {
        ContentValues v = new ContentValues();
        v.put("global_prompt", globalPrompt);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public GmRoom getGmRoom(long roomId) {
        Cursor c = getReadableDatabase().query("gm_rooms", null, "id=?",
                new String[]{String.valueOf(roomId)}, null, null, null);
        GmRoom r = null;
        if (c.moveToFirst()) {
            r = new GmRoom();
            r.id = c.getLong(c.getColumnIndexOrThrow("id"));
            r.title = c.getString(c.getColumnIndexOrThrow("title"));
            r.contextLimit = c.getInt(c.getColumnIndexOrThrow("context_limit"));
            int moIdx = c.getColumnIndex("max_output_tokens");
            r.maxOutputTokens = moIdx >= 0 ? c.getInt(moIdx) : 110000;
            r.maxTotalMsgs = c.getInt(c.getColumnIndexOrThrow("max_total_msgs"));
            r.speakIntervalSec = c.getInt(c.getColumnIndexOrThrow("speak_interval_sec"));
            r.blockedUntil = c.getLong(c.getColumnIndexOrThrow("blocked_until"));
            int gpIdx = c.getColumnIndex("global_prompt");
            r.globalPrompt = gpIdx >= 0 ? c.getString(gpIdx) : null;
            int stIdx = c.getColumnIndex("started");
            r.started = stIdx >= 0 && c.getInt(stIdx) == 1;
            int gmIdx = c.getColumnIndex("goal_mode");
            r.goalMode = gmIdx >= 0 && c.getInt(gmIdx) == 1;
            int gtIdx = c.getColumnIndex("goal_text");
            r.goalText = gtIdx >= 0 ? c.getString(gtIdx) : null;
            int esIdx = c.getColumnIndex("export_state");
            r.exportState = esIdx >= 0 && c.getString(esIdx) != null ? c.getString(esIdx) : "idle";
            int enIdx = c.getColumnIndex("export_note");
            r.exportNote = enIdx >= 0 ? c.getString(enIdx) : null;
            int lrIdx = c.getColumnIndex("last_round");
            r.lastRound = lrIdx >= 0 ? c.getInt(lrIdx) : 0;
            int lmIdx = c.getColumnIndex("last_member_id");
            r.lastMemberId = lmIdx >= 0 ? c.getLong(lmIdx) : 0L;
            int simIdx = c.getColumnIndex("speak_interval_max_sec");
            r.speakIntervalMaxSec = simIdx >= 0 ? c.getInt(simIdx) : 0;
            int ninIdx = c.getColumnIndex("no_interval");
            r.noInterval = ninIdx >= 0 && c.getInt(ninIdx) == 1;
            int lmIdx2 = c.getColumnIndex("lang_mode");
            r.langMode = lmIdx2 >= 0 ? c.getInt(lmIdx2) : 1;
            r.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        }
        c.close();
        return r;
    }

    public void setGmRoomGoalMode(long roomId, boolean on, String goalText) {
        ContentValues v = new ContentValues();
        v.put("goal_mode", on ? 1 : 0);
        if (goalText != null) v.put("goal_text", goalText);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomExportState(long roomId, String state, String note) {
        ContentValues v = new ContentValues();
        v.put("export_state", state == null ? "idle" : state);
        v.put("export_note", note);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public List<GmRoom> listGmRooms() {
        List<GmRoom> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("gm_rooms", null, null, null, null, null, "id DESC");
        while (c.moveToNext()) {
            GmRoom r = new GmRoom();
            r.id = c.getLong(c.getColumnIndexOrThrow("id"));
            r.title = c.getString(c.getColumnIndexOrThrow("title"));
            r.contextLimit = c.getInt(c.getColumnIndexOrThrow("context_limit"));
            int moIdx = c.getColumnIndex("max_output_tokens");
            r.maxOutputTokens = moIdx >= 0 ? c.getInt(moIdx) : 110000;
            r.maxTotalMsgs = c.getInt(c.getColumnIndexOrThrow("max_total_msgs"));
            r.speakIntervalSec = c.getInt(c.getColumnIndexOrThrow("speak_interval_sec"));
            r.blockedUntil = c.getLong(c.getColumnIndexOrThrow("blocked_until"));
            int gpIdx = c.getColumnIndex("global_prompt");
            r.globalPrompt = gpIdx >= 0 ? c.getString(gpIdx) : null;
            int stIdx = c.getColumnIndex("started");
            r.started = stIdx >= 0 && c.getInt(stIdx) == 1;
            int lrIdx = c.getColumnIndex("last_round");
            r.lastRound = lrIdx >= 0 ? c.getInt(lrIdx) : 0;
            int lmIdx = c.getColumnIndex("last_member_id");
            r.lastMemberId = lmIdx >= 0 ? c.getLong(lmIdx) : 0L;
            int simIdx = c.getColumnIndex("speak_interval_max_sec");
            r.speakIntervalMaxSec = simIdx >= 0 ? c.getInt(simIdx) : 0;
            int ninIdx = c.getColumnIndex("no_interval");
            r.noInterval = ninIdx >= 0 && c.getInt(ninIdx) == 1;
            int lmIdx2 = c.getColumnIndex("lang_mode");
            r.langMode = lmIdx2 >= 0 ? c.getInt(lmIdx2) : 1;
            r.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
            out.add(r);
        }
        c.close();
        return out;
    }

    public void updateGmRoomSettings(long roomId, int contextLimit, int maxTotalMsgs, int speakIntervalSec) {
        updateGmRoomSettings(roomId, contextLimit, maxTotalMsgs, speakIntervalSec, 110000);
    }

    public void updateGmRoomSettings(long roomId, int contextLimit, int maxTotalMsgs, int speakIntervalSec, int maxOutputTokens) {
        ContentValues v = new ContentValues();
        v.put("max_output_tokens", maxOutputTokens);
        v.put("context_limit", contextLimit);
        v.put("max_total_msgs", maxTotalMsgs);
        v.put("speak_interval_sec", speakIntervalSec);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomNoInterval(long roomId, boolean on) {
        ContentValues v = new ContentValues();
        v.put("no_interval", on ? 1 : 0);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomIntervalRange(long roomId, int minSec, int maxSec) {
        ContentValues v = new ContentValues();
        v.put("speak_interval_sec", Math.max(1, minSec));
        v.put("speak_interval_max_sec", Math.max(0, maxSec));
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomBlockedUntil(long roomId, long until) {
        ContentValues v = new ContentValues();
        v.put("blocked_until", until);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomLangMode(long roomId, int mode) {
        ContentValues v = new ContentValues();
        v.put("lang_mode", Math.max(0, Math.min(3, mode)));
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomTitle(long roomId, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomStarted(long roomId, boolean started) {
        ContentValues v = new ContentValues();
        v.put("started", started ? 1 : 0);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void setGmRoomProgress(long roomId, int round, long memberId) {
        ContentValues v = new ContentValues();
        v.put("last_round", round);
        v.put("last_member_id", memberId);
        getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
    }

    public void clearGmRoomProgress(long roomId) {
        setGmRoomProgress(roomId, 0, 0L);
    }

    public int syncGmMembersToProvider(long roomId, String baseUrl, String apiKey, String model) {
        ContentValues v = new ContentValues();
        if (baseUrl != null) v.put("base_url", baseUrl);
        if (apiKey != null) v.put("api_key", apiKey);
        if (model != null && !model.trim().isEmpty()) v.put("model", model.trim());
        return getWritableDatabase().update("gm_members", v, "room_id=?", new String[]{String.valueOf(roomId)});
    }

    public int syncAllGmMembersToProvider(String baseUrl, String apiKey) {
        ContentValues v = new ContentValues();
        if (baseUrl != null) v.put("base_url", baseUrl);
        if (apiKey != null) v.put("api_key", apiKey);
        return getWritableDatabase().update("gm_members", v, null, null);
    }

    public int syncGmMembersByBaseUrl(String oldBaseUrl, String newBaseUrl, String newApiKey) {
        if (oldBaseUrl == null || oldBaseUrl.trim().isEmpty()) return 0;
        ContentValues v = new ContentValues();
        if (newBaseUrl != null) v.put("base_url", newBaseUrl);
        if (newApiKey != null) v.put("api_key", newApiKey);
        return getWritableDatabase().update("gm_members", v,
                "base_url=?", new String[]{oldBaseUrl.trim()});
    }

    public int countGmMembersByBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return 0;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_members WHERE base_url=?", new String[]{baseUrl.trim()});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public void setGmMemberActive(long memberId, boolean active) {
        ContentValues v = new ContentValues();
        v.put("active", active ? 1 : 0);

        v.put("manual_standby", active ? 0 : 1);
        getWritableDatabase().update("gm_members", v, "id=?", new String[]{String.valueOf(memberId)});
    }

    public void setGmMemberActiveBySystem(long memberId, boolean active) {
        ContentValues v = new ContentValues();
        v.put("active", active ? 1 : 0);
        getWritableDatabase().update("gm_members", v, "id=?", new String[]{String.valueOf(memberId)});
    }

    public void setAllGmMembersActive(long roomId, boolean active) {
        ContentValues v = new ContentValues();
        v.put("active", active ? 1 : 0);
        v.put("manual_standby", active ? 0 : 1);
        getWritableDatabase().update("gm_members", v, "room_id=?", new String[]{String.valueOf(roomId)});
    }

   public void deleteGmRoom(long roomId) {

      SQLiteDatabase db = getWritableDatabase();
      db.beginTransaction();
      try {
         db.delete("gm_messages", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_members", "room_id=?", new String[]{String.valueOf(roomId)});

         db.delete("gm_inquiries", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_docs", "room_id=?", new String[]{String.valueOf(roomId)});
         clearGmPendingQuestions(roomId);

         db.delete("gm_feedback", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_admin_messages", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_anomalies", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_admin_proposals", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_admin_config", "room_id=?", new String[]{String.valueOf(roomId)});

         db.delete("gm_agent_memory", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_material_pool", "room_id=?", new String[]{String.valueOf(roomId)});

         db.delete("gm_citations", "room_id=?", new String[]{String.valueOf(roomId)});
         db.delete("gm_rooms", "id=?", new String[]{String.valueOf(roomId)});
         db.setTransactionSuccessful();
      } finally {
         db.endTransaction();
      }
   }

   public int cleanupOrphanGmData() {
      int total = 0;
      try {

         java.util.HashSet<Long> alive = new java.util.HashSet<>();
         Cursor c = null;
         try {
            c = getReadableDatabase().rawQuery("SELECT id FROM gm_rooms", null);
            while (c.moveToNext()) alive.add(c.getLong(0));
         } finally { if (c != null) try { c.close(); } catch (Exception ignored) { } }

         String[] tables = {"gm_messages", "gm_members", "gm_inquiries", "gm_docs",
                 "gm_pending_questions", "gm_feedback", "gm_admin_messages",
                 "gm_anomalies", "gm_admin_proposals", "gm_admin_config",

                 "gm_agent_memory", "gm_material_pool", "gm_citations"};
         java.util.HashSet<Long> orphans = new java.util.HashSet<>();
         for (String t : tables) {
            Cursor cur = null;
            try {
               cur = getReadableDatabase().rawQuery("SELECT DISTINCT room_id FROM " + t, null);
               while (cur.moveToNext()) {
                  long rid = cur.getLong(0);
                  if (!alive.contains(rid)) orphans.add(rid);
               }
            } catch (Exception ignored) {

            } finally { if (cur != null) try { cur.close(); } catch (Exception ignored) { } }
         }
         if (orphans.isEmpty()) return 0;

         android.database.sqlite.SQLiteDatabase db = getWritableDatabase();
         db.beginTransaction();
         try {
            for (long rid : orphans) {
               String[] arg = new String[]{String.valueOf(rid)};
               for (String t : tables) {
                  try { total += db.delete(t, "room_id=?", arg); } catch (Exception ignored) { }
               }
            }
            db.setTransactionSuccessful();
         } finally {
            try { db.endTransaction(); } catch (Exception ignored) { }
         }
         android.util.Log.i("VC_DB", "cleanupOrphanGmData: 清理孤儿房间 " + orphans.size()
                 + " 个，共删除 " + total + " 行");
      } catch (Exception e) {
         android.util.Log.w("VC_DB", "cleanupOrphanGmData 失败: " + e.getMessage());
      }
      return total;
   }

    public long duplicateGmRoom(long srcRoomId, String newTitle) {
        GmRoom src = getGmRoom(srcRoomId);
        if (src == null) return -1;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long newId = insertGmRoom(newTitle, src.contextLimit, src.maxTotalMsgs,
                    src.speakIntervalSec, src.globalPrompt);

            for (GmMember m : listGmMembers(srcRoomId)) {
                long mid = insertGmMember(newId, m.name, m.avatar, m.roleDesc, m.prompt,
                        m.model, m.baseUrl, m.apiKey, m.canSearch, m.maxMsgs, m.isDefault);

                db.execSQL(
                        "UPDATE gm_members SET think_level = ?, active = ?, phase = ?, manual_standby = ?, sort_order = ? WHERE id = ?",
                        new Object[]{m.thinkLevel, m.active ? 1 : 0, m.phase, m.manualStandby ? 1 : 0, m.sortOrder, mid});
            }
            db.setTransactionSuccessful();
            return newId;
        } finally {
            db.endTransaction();
        }
    }

    public long insertGmMember(long roomId, String name, String avatar, String roleDesc, String prompt,
                              String model, String baseUrl, String apiKey, boolean canSearch, int maxMsgs) {
        return insertGmMember(roomId, name, avatar, roleDesc, prompt, model, baseUrl, apiKey, canSearch, maxMsgs, false);
    }

    public long insertGmMember(long roomId, String name, String avatar, String roleDesc, String prompt,
                              String model, String baseUrl, String apiKey, boolean canSearch, int maxMsgs,
                              boolean isDefault, int phase) {
        long id = insertGmMember(roomId, name, avatar, roleDesc, prompt, model, baseUrl, apiKey, canSearch, maxMsgs, isDefault);
        if (id > 0 && phase != 0) {
            ContentValues pv = new ContentValues();
            pv.put("phase", phase);
            getWritableDatabase().update("gm_members", pv, "id=?", new String[]{String.valueOf(id)});
        }
        return id;
    }

    public long insertGmMember(long roomId, String name, String avatar, String roleDesc, String prompt,
                              String model, String baseUrl, String apiKey, boolean canSearch, int maxMsgs,
                              boolean isDefault) {
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("name", name);
        v.put("avatar", avatar);
        v.put("role_desc", roleDesc);
        v.put("prompt", prompt);
        v.put("model", model);
        v.put("base_url", baseUrl);
        v.put("api_key", apiKey);
        v.put("can_search", canSearch ? 1 : 0);
        v.put("max_msgs", maxMsgs);
        v.put("used_msgs", 0);
        v.put("used_tokens", 0);
        v.put("last_spoke_at", 0);
        v.put("injected", 0);

        v.put("think_level", 2);

        v.put("is_default", isDefault ? 1 : 0);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_members", null, v);
    }

    public void updateGmMember(GmMember m) {
        ContentValues v = new ContentValues();
        v.put("name", m.name);
        v.put("avatar", m.avatar);
        v.put("role_desc", m.roleDesc);
        v.put("prompt", m.prompt);
        v.put("model", m.model);
        v.put("base_url", m.baseUrl);
        v.put("api_key", m.apiKey);
        v.put("can_search", m.canSearch ? 1 : 0);
        v.put("max_msgs", m.maxMsgs);
        v.put("think_level", m.thinkLevel);
        v.put("phase", m.phase);
        v.put("sort_order", m.sortOrder);
        getWritableDatabase().update("gm_members", v, "id=?", new String[]{String.valueOf(m.id)});
    }

    public void setAllGmMembersThinkLevel(long roomId, int thinkLevel) {
        ContentValues v = new ContentValues();
        v.put("think_level", ReasoningNegotiator.clampLevel(thinkLevel));
        getWritableDatabase().update("gm_members", v, "room_id=?", new String[]{String.valueOf(roomId)});
    }

    public int enableSearchForAllMembers() {
        ContentValues v = new ContentValues();
        v.put("can_search", 1);
        return getWritableDatabase().update("gm_members", v, "COALESCE(can_search,0)=0", null);
    }

    public void setAllGmMembersMaxMsgs(long roomId, int maxMsgs) {
        ContentValues v = new ContentValues();
        v.put("max_msgs", maxMsgs);
        getWritableDatabase().update("gm_members", v, "room_id=?", new String[]{String.valueOf(roomId)});
    }

    public void markGmMemberInjected(long memberId) {
        ContentValues v = new ContentValues();
        v.put("injected", 1);
        getWritableDatabase().update("gm_members", v, "id=?", new String[]{String.valueOf(memberId)});
    }

    public void addGmMemberUsage(long memberId, int msgs, long tokens) {
        getWritableDatabase().execSQL(
                "UPDATE gm_members SET used_msgs = used_msgs + ?, used_tokens = used_tokens + ?, last_spoke_at = ? WHERE id = ?",
                new Object[]{msgs, tokens, System.currentTimeMillis(), memberId});
    }

    public void deleteGmMember(long memberId) {
        getWritableDatabase().delete("gm_members", "id=?", new String[]{String.valueOf(memberId)});
    }

    public List<GmMember> listGmMembers(long roomId) {
        List<GmMember> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("gm_members", null, "room_id=?",
                new String[]{String.valueOf(roomId)}, null, null, "id ASC");
        while (c.moveToNext()) out.add(cursorToGmMember(c));
        c.close();
        return out;
    }

    public GmMember getGmMember(long memberId) {
        Cursor c = getReadableDatabase().query("gm_members", null, "id=?",
                new String[]{String.valueOf(memberId)}, null, null, null);
        GmMember m = c.moveToFirst() ? cursorToGmMember(c) : null;
        c.close();
        return m;
    }

    private GmMember cursorToGmMember(Cursor c) {
        GmMember m = new GmMember();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
        m.name = c.getString(c.getColumnIndexOrThrow("name"));
        m.avatar = c.getString(c.getColumnIndexOrThrow("avatar"));
        m.roleDesc = c.getString(c.getColumnIndexOrThrow("role_desc"));
        m.prompt = c.getString(c.getColumnIndexOrThrow("prompt"));
        m.model = c.getString(c.getColumnIndexOrThrow("model"));
        m.baseUrl = c.getString(c.getColumnIndexOrThrow("base_url"));
        m.apiKey = c.getString(c.getColumnIndexOrThrow("api_key"));
        m.canSearch = c.getInt(c.getColumnIndexOrThrow("can_search")) == 1;
        m.maxMsgs = c.getInt(c.getColumnIndexOrThrow("max_msgs"));
        m.usedMsgs = c.getInt(c.getColumnIndexOrThrow("used_msgs"));
        m.usedTokens = c.getLong(c.getColumnIndexOrThrow("used_tokens"));
        m.lastSpokeAt = c.getLong(c.getColumnIndexOrThrow("last_spoke_at"));
        m.injected = c.getInt(c.getColumnIndexOrThrow("injected")) == 1;
        int tlIdx = c.getColumnIndex("think_level");
        m.thinkLevel = tlIdx >= 0 ? c.getInt(tlIdx) : 2;
        int acIdx = c.getColumnIndex("active");
        m.active = acIdx < 0 || c.getInt(acIdx) == 1;
        int dfIdx = c.getColumnIndex("is_default");
        m.isDefault = dfIdx >= 0 && c.getInt(dfIdx) == 1;
        int phIdx = c.getColumnIndex("phase");
        m.phase = phIdx >= 0 ? c.getInt(phIdx) : 0;
        int soIdx = c.getColumnIndex("sort_order");
        m.sortOrder = soIdx >= 0 ? c.getInt(soIdx) : 0;
        int msIdx = c.getColumnIndex("manual_standby");
        m.manualStandby = msIdx >= 0 && c.getInt(msIdx) == 1;
        int crIdx = c.getColumnIndex("confirmed_round");
        m.confirmedRound = crIdx >= 0 ? c.getInt(crIdx) : 0;
        m.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return m;
    }

    public void setGmMemberConfirmedRound(long memberId, int round) {
        try {
            ContentValues v = new ContentValues();
            v.put("confirmed_round", Math.max(0, round));
            getWritableDatabase().update("gm_members", v, "id=?", new String[]{String.valueOf(memberId)});
        } catch (Exception e) {
            android.util.Log.w("VC_GM", "setGmMemberConfirmedRound 失败: " + e.getMessage());
        }
    }

    public long insertGmMessage(long roomId, long memberId, String speaker, String avatar,
                                String content, boolean isThought, String kind, int tokens) {
        return insertGmMessage(roomId, memberId, speaker, avatar, content, isThought, kind, tokens, 0L);
    }

    public long insertGmMessage(long roomId, long memberId, String speaker, String avatar,
                                String content, boolean isThought, String kind, int tokens, long durationMs) {
        return insertGmMessage(roomId, memberId, speaker, avatar, content, isThought, kind, tokens, durationMs, 0);
    }

    public long insertGmMessage(long roomId, long memberId, String speaker, String avatar,
                                String content, boolean isThought, String kind, int tokens, long durationMs,
                                int promptTokens) {
        return insertGmMessage(roomId, memberId, speaker, avatar, content, isThought, kind,
                tokens, durationMs, promptTokens, 0, 0L);
    }

    public long insertGmMessage(long roomId, long memberId, String speaker, String avatar,
                                String content, boolean isThought, String kind, int tokens, long durationMs,
                                int promptTokens, int completionTokens, long genMs) {
        ensureGmMsgDurationCol(getWritableDatabase());
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("member_id", memberId);
        v.put("speaker", speaker);
        v.put("avatar", avatar);
        v.put("content", content);
        v.put("is_thought", isThought ? 1 : 0);
        v.put("kind", kind);
        v.put("tokens", tokens);
        v.put("prompt_tokens", promptTokens);
        v.put("completion_tokens", completionTokens);
        v.put("gen_ms", genMs);
        v.put("duration_ms", durationMs);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_messages", null, v);
    }

    private void ensureGmMsgDurationCol(SQLiteDatabase db) {
        try { db.execSQL("ALTER TABLE gm_messages ADD COLUMN duration_ms INTEGER DEFAULT 0"); } catch (Exception ignored) { }

        try { db.execSQL("ALTER TABLE gm_messages ADD COLUMN prompt_tokens INTEGER DEFAULT 0"); } catch (Exception ignored) { }

        try { db.execSQL("ALTER TABLE gm_messages ADD COLUMN completion_tokens INTEGER DEFAULT 0"); } catch (Exception ignored) { }
        try { db.execSQL("ALTER TABLE gm_messages ADD COLUMN gen_ms INTEGER DEFAULT 0"); } catch (Exception ignored) { }
    }

    public List<GmMessage> listGmMessages(long roomId, int limit) {
        List<GmMessage> out = new ArrayList<>();

        Cursor c = getReadableDatabase().query("gm_messages", null, "room_id=?",
                new String[]{String.valueOf(roomId)}, null, null, "id DESC",
                limit > 0 ? String.valueOf(limit) : null);
        while (c.moveToNext()) out.add(0, cursorToGmMessage(c));
        c.close();

        if (!out.isEmpty()) {

            GmMessage first = out.get(0);
            int base = countGmSpeechesUpTo(roomId, first.id) - (isGmSpeech(first.kind) ? 1 : 0);
            for (int i = 0; i < out.size(); i++) {
               GmMessage g = out.get(i);
               g.seq = isGmSpeech(g.kind) ? ++base : 0;
            }
        }
        return out;
    }

    public static boolean isGmSpeech(String kind) {
        return kind == null || "chat".equals(kind);
    }

    public int countGmSpeechesUpTo(long roomId, long msgId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_messages WHERE room_id=? AND (kind IS NULL OR kind='chat') AND id<=?",
                new String[]{String.valueOf(roomId), String.valueOf(msgId)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public int countGmMessagesUpTo(long roomId, long msgId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_messages WHERE room_id=? AND id<=?",
                new String[]{String.valueOf(roomId), String.valueOf(msgId)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public List<GmMessage> listGmMessagesBySpeaker(long roomId, String speaker) {
        List<GmMessage> out = new ArrayList<>();
        if (speaker == null) return out;
        Cursor ids = null;
        Cursor c = null;
        try {

            java.util.HashMap<Long, Integer> seqOf = new java.util.HashMap<>();
            ids = getReadableDatabase().rawQuery(
                    "SELECT id, kind FROM gm_messages WHERE room_id=? ORDER BY id ASC",
                    new String[]{String.valueOf(roomId)});
            int seq = 0;
            while (ids.moveToNext()) {
                String kind = ids.isNull(1) ? null : ids.getString(1);
                seqOf.put(ids.getLong(0), isGmSpeech(kind) ? ++seq : 0);
            }
            c = getReadableDatabase().query("gm_messages", null, "room_id=? AND speaker=?",
                    new String[]{String.valueOf(roomId), speaker}, null, null, "id ASC", null);
            while (c.moveToNext()) {
                GmMessage m = cursorToGmMessage(c);
                Integer s = seqOf.get(m.id);
                m.seq = s == null ? 0 : s;
                out.add(m);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmMessagesBySpeaker 失败: " + e.getMessage());
        } finally {
            if (ids != null) try { ids.close(); } catch (Exception ignored) { }
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public long sumGmMessageChars(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COALESCE(SUM(LENGTH(content)),0) + COALESCE(SUM(LENGTH(speaker)),0) + COUNT(*)*4"
                            + " FROM gm_messages WHERE room_id=? AND (kind IS NULL OR kind <> 'system')",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "sumGmMessageChars 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    public long sumGmPublicTokens(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(

                    "SELECT COALESCE(SUM((5*(pub_bytes+sp_bytes) - 3*(pub_chars+sp_chars) + 4)/8),0)"
                    + " FROM (SELECT CASE WHEN MIN("
                    + "   COALESCE(NULLIF(instr(content,'@@THINKING:'),0),2147483647),"
                    + "   COALESCE(NULLIF(instr(content,'@@TOOLS:'),0),2147483647)"
                    + " ) >= 2147483647 THEN LENGTH(COALESCE(content,'')) ELSE MIN("
                    + "   COALESCE(NULLIF(instr(content,'@@THINKING:'),0),2147483647),"
                    + "   COALESCE(NULLIF(instr(content,'@@TOOLS:'),0),2147483647)"
                    + " ) - 1 END AS pub_chars,"
                    + " LENGTH(COALESCE(speaker,'')) AS sp_chars,"
                    + " LENGTH(CAST(CASE WHEN MIN("
                    + "   COALESCE(NULLIF(instr(content,'@@THINKING:'),0),2147483647),"
                    + "   COALESCE(NULLIF(instr(content,'@@TOOLS:'),0),2147483647)"
                    + " ) >= 2147483647 THEN COALESCE(content,'') ELSE SUBSTR(content,1,MIN("
                    + "   COALESCE(NULLIF(instr(content,'@@THINKING:'),0),2147483647),"
                    + "   COALESCE(NULLIF(instr(content,'@@TOOLS:'),0),2147483647)"
                    + " ) - 1) END AS BLOB)) AS pub_bytes,"
                    + " LENGTH(CAST(COALESCE(speaker,'') AS BLOB)) AS sp_bytes"
                    + " FROM gm_messages WHERE room_id=? AND (kind IS NULL OR kind <> 'system'))",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "sumGmPublicTokens 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    public long sumGmPublicChars(long roomId) {
        Cursor c = null;
        try {

            c = getReadableDatabase().rawQuery(
                    "SELECT COALESCE(SUM(pub),0) + COALESCE(SUM(sp),0) + COUNT(*)*4 FROM ("
                    + " SELECT CASE WHEN MIN("
                    + "   COALESCE(NULLIF(instr(content,'@@THINKING:'),0),2147483647),"
                    + "   COALESCE(NULLIF(instr(content,'@@TOOLS:'),0),2147483647)"
                    + " ) >= 2147483647 THEN LENGTH(COALESCE(content,'')) ELSE MIN("
                    + "   COALESCE(NULLIF(instr(content,'@@THINKING:'),0),2147483647),"
                    + "   COALESCE(NULLIF(instr(content,'@@TOOLS:'),0),2147483647)"
                    + " ) - 1 END AS pub, LENGTH(COALESCE(speaker,'')) AS sp"
                    + " FROM gm_messages WHERE room_id=? AND (kind IS NULL OR kind <> 'system'))",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "sumGmPublicChars 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    public long sumGmMemberChars(long roomId, long memberId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COALESCE(SUM(LENGTH(content)),0) + COALESCE(SUM(LENGTH(speaker)),0) + COUNT(*)*4"
                            + " FROM gm_messages WHERE room_id=? AND member_id=? AND (kind IS NULL OR kind <> 'system')",
                    new String[]{String.valueOf(roomId), String.valueOf(memberId)});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "sumGmMemberChars 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    public java.util.HashMap<Long, Integer> mapGmMemberLastPromptTokens(long roomId) {
        java.util.HashMap<Long, Integer> out = new java.util.HashMap<>();
        Cursor c = null;
        try {

            ensureGmMsgDurationCol(getWritableDatabase());

            c = getReadableDatabase().rawQuery(
                    "SELECT member_id, prompt_tokens FROM gm_messages m"
                            + " WHERE room_id=? AND prompt_tokens > 0"
                            + "   AND id = (SELECT MAX(id) FROM gm_messages"
                            + "             WHERE room_id=m.room_id AND member_id=m.member_id AND prompt_tokens > 0)",
                    new String[]{String.valueOf(roomId)});
            while (c.moveToNext()) {
                out.put(c.getLong(0), c.getInt(1));
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "mapGmMemberLastPromptTokens 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public java.util.HashMap<Long, Long> mapGmMemberToolTokens(long roomId) {
        java.util.HashMap<Long, Long> out = new java.util.HashMap<>();
        Cursor c = null;
        try {
            ensureGmMsgDurationCol(getWritableDatabase());

            c = getReadableDatabase().rawQuery(
                    "SELECT member_id, COALESCE(SUM(CASE WHEN instr(content,'@@TOOLS:')=0 THEN 0"
                    + "   ELSE (5*LENGTH(CAST(SUBSTR(content,instr(content,'@@TOOLS:')) AS BLOB))"
                    + "         - 3*LENGTH(SUBSTR(content,instr(content,'@@TOOLS:'))) + 4)/8 END),0)"
                    + " FROM gm_messages WHERE room_id=? AND member_id>0"
                    + "   AND (kind IS NULL OR kind <> 'system')"
                    + " GROUP BY member_id",
                    new String[]{String.valueOf(roomId)});
            while (c.moveToNext()) {
                out.put(c.getLong(0), c.getLong(1));
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "mapGmMemberToolTokens 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public java.util.HashMap<Long, Long> mapGmMemberPrivateTokens(long roomId) {
        java.util.HashMap<Long, Long> out = new java.util.HashMap<>();
        Cursor c = null;
        try {
            ensureGmMsgDurationCol(getWritableDatabase());

            try {
                Cursor mc = getReadableDatabase().rawQuery(
                        "SELECT id,"
                        + " (5*LENGTH(CAST(COALESCE(prompt,'') AS BLOB))"
                        + "  - 3*LENGTH(COALESCE(prompt,'')) + 4)/8"
                        + " FROM gm_members WHERE room_id=?",
                        new String[]{String.valueOf(roomId)});
                while (mc.moveToNext()) {
                    out.put(mc.getLong(0), mc.getLong(1));
                }
                mc.close();
            } catch (Exception ignored) { }

            c = getReadableDatabase().rawQuery(
                    "SELECT member_id, COALESCE(SUM(CASE WHEN instr(content,'@@TOOLS:')=0 THEN 0"
                    + "   ELSE (5*LENGTH(CAST(SUBSTR(content,instr(content,'@@TOOLS:')) AS BLOB))"
                    + "         - 3*LENGTH(SUBSTR(content,instr(content,'@@TOOLS:'))) + 4)/8 END),0)"
                    + " FROM gm_messages WHERE room_id=? AND member_id>0"
                    + "   AND (kind IS NULL OR kind <> 'system')"
                    + " GROUP BY member_id",
                    new String[]{String.valueOf(roomId)});
            while (c.moveToNext()) {
                long mid = c.getLong(0);
                Long base = out.get(mid);
                out.put(mid, (base == null ? 0L : base) + c.getLong(1));
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "mapGmMemberPrivateTokens 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public java.util.HashMap<Long, Long> mapGmMemberPrivateChars(long roomId) {
        java.util.HashMap<Long, Long> out = new java.util.HashMap<>();
        Cursor c = null;
        try {
            ensureGmMsgDurationCol(getWritableDatabase());

            try {
                Cursor mc = getReadableDatabase().rawQuery(
                        "SELECT id, LENGTH(COALESCE(prompt,'')) FROM gm_members WHERE room_id=?",
                        new String[]{String.valueOf(roomId)});
                while (mc.moveToNext()) {
                    out.put(mc.getLong(0), mc.getLong(1));
                }
                mc.close();
            } catch (Exception ignored) { }

            c = getReadableDatabase().rawQuery(
                    "SELECT member_id, COALESCE(SUM("
                    + "  CASE WHEN instr(content,'@@TOOLS:')=0 THEN 0"
                    + "       ELSE LENGTH(content) - instr(content,'@@TOOLS:') + 1 END),0)"
                    + " FROM gm_messages WHERE room_id=? AND member_id>0"
                    + "   AND (kind IS NULL OR kind <> 'system')"
                    + " GROUP BY member_id",
                    new String[]{String.valueOf(roomId)});
            while (c.moveToNext()) {
                long mid = c.getLong(0);
                Long base = out.get(mid);
                out.put(mid, (base == null ? 0L : base) + c.getLong(1));
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "mapGmMemberPrivateChars 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public java.util.HashMap<Long, Long> mapGmMemberOwnTokens(long roomId) {
        java.util.HashMap<Long, Long> out = new java.util.HashMap<>();
        Cursor c = null;
        try {
            ensureGmMsgDurationCol(getWritableDatabase());
            c = getReadableDatabase().rawQuery(
                    "SELECT member_id, COALESCE(SUM(tokens),0) FROM gm_messages"
                            + " WHERE room_id=? AND member_id>0 AND (kind IS NULL OR kind <> 'system')"
                            + " GROUP BY member_id",
                    new String[]{String.valueOf(roomId)});
            while (c.moveToNext()) {
                out.put(c.getLong(0), c.getLong(1));
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "mapGmMemberOwnTokens 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void reconcileGmMemberUsage(long roomId) {
        Cursor c = null;
        try {

            c = getReadableDatabase().rawQuery(
                    "SELECT member_id, COUNT(*), COALESCE(SUM(tokens),0) FROM gm_messages"
                            + " WHERE room_id=? AND member_id>0 AND (kind IS NULL OR kind <> 'system')"
                            + " GROUP BY member_id",
                    new String[]{String.valueOf(roomId)});
            java.util.List<long[]> facts = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                facts.add(new long[]{c.getLong(0), c.getLong(1), c.getLong(2)});
            }
            c.close();
            c = null;
            for (long[] f : facts) {

                getWritableDatabase().execSQL(
                        "UPDATE gm_members SET used_msgs=?, used_tokens=?"
                                + " WHERE id=? AND (used_msgs<>? OR used_tokens<>?)",
                        new Object[]{f[1], f[2], f[0], f[1], f[2]});
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "reconcileGmMemberUsage 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    public GmMessage getFirstGmUserMessage(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_messages", null, "room_id=? AND speaker=?",
                    new String[]{String.valueOf(roomId), "我"}, null, null, "id ASC", "1");
            if (c.moveToFirst()) return cursorToGmMessage(c);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return null;
    }

    private GmMessage cursorToGmMessage(Cursor c) {
        GmMessage m = new GmMessage();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
        m.memberId = c.getLong(c.getColumnIndexOrThrow("member_id"));
        m.speaker = c.getString(c.getColumnIndexOrThrow("speaker"));
        m.avatar = c.getString(c.getColumnIndexOrThrow("avatar"));
        m.content = c.getString(c.getColumnIndexOrThrow("content"));
        m.isThought = c.getInt(c.getColumnIndexOrThrow("is_thought")) == 1;
        m.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        m.tokens = c.getInt(c.getColumnIndexOrThrow("tokens"));
        int ptIdx = c.getColumnIndex("prompt_tokens");
        m.promptTokens = ptIdx >= 0 ? c.getInt(ptIdx) : 0;
        int ctIdx2 = c.getColumnIndex("completion_tokens");
        m.completionTokens = ctIdx2 >= 0 ? c.getInt(ctIdx2) : 0;
        int gmIdx = c.getColumnIndex("gen_ms");
        m.genMs = gmIdx >= 0 ? c.getLong(gmIdx) : 0L;
        int durIdx = c.getColumnIndex("duration_ms");
        m.durationMs = durIdx >= 0 ? c.getLong(durIdx) : 0L;
        m.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return m;
    }

    public List<GmMessage> listGmUserMessages(long roomId) {
        return listGmMessagesBySpeaker(roomId, "我");
    }

    public long getLastGmUserMessageId(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COALESCE(MAX(id),0) FROM gm_messages WHERE room_id=? AND speaker=?",
                    new String[]{String.valueOf(roomId), "我"});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    public GmMessage getLastGmSpeech(long roomId, long memberId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_messages", null,
                    "room_id=? AND member_id=? AND is_thought=0 AND (kind IS NULL OR kind='chat')",
                    new String[]{String.valueOf(roomId), String.valueOf(memberId)}, null, null, "id DESC", "1");
            if (c.moveToFirst()) return cursorToGmMessage(c);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return null;
    }

    public int countGmTrailingThoughts(long roomId, long memberId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_messages", new String[]{"is_thought"},
                    "room_id=? AND member_id=? AND (kind IS NULL OR kind='chat')",
                    new String[]{String.valueOf(roomId), String.valueOf(memberId)}, null, null, "id DESC", "8");
            int n = 0;
            while (c.moveToNext()) {
                if (c.getInt(0) == 1) n++; else break;
            }
            return n;
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public void updateGmMessageContent(long msgId, String content) {
        ContentValues v = new ContentValues();
        v.put("content", content);
        getWritableDatabase().update("gm_messages", v, "id=?", new String[]{String.valueOf(msgId)});
    }

    public List<Long> listGmMessageIdsWithThinking() {
        List<Long> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id FROM gm_messages WHERE content LIKE '%@@THINKING:%'", null);
            while (c.moveToNext()) out.add(c.getLong(0));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读取含思考的消息失败: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public String getGmMessageContent(long msgId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT content FROM gm_messages WHERE id=?", new String[]{String.valueOf(msgId)});
            if (c.moveToNext()) return c.isNull(0) ? null : c.getString(0);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读取群聊消息失败: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public List<GmMessage> listRecentGmMessages(int limit) {
        List<GmMessage> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("gm_messages", null, null, null, null, null,
                "id DESC", String.valueOf(Math.max(1, limit)));
        while (c.moveToNext()) out.add(0, cursorToGmMessage(c));
        c.close();
        return out;
    }

    public int countAllGmMessages() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM gm_messages", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int countGmMessages(long roomId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_messages WHERE room_id=?", new String[]{String.valueOf(roomId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public static final class PhoneEvent {
        public long ts;
        public String app;
        public String kind;
        public String detail;
    }

    public void insertPhoneEvent(long ts, String app, String kind, String detail) {
        try {
            ContentValues v = new ContentValues();
            v.put("ts", ts);
            v.put("app", app);
            v.put("kind", kind);
            v.put("detail", detail == null ? "" : detail);
            getWritableDatabase().insert("phone_events", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "写 phone_events 失败: " + e.getMessage());
        }
    }

    public void prunePhoneEvents(int keep) {
        try {
            getWritableDatabase().execSQL("DELETE FROM phone_events WHERE id NOT IN "
                    + "(SELECT id FROM phone_events ORDER BY id DESC LIMIT " + Math.max(1, keep) + ")");
        } catch (Exception ignored) { }
    }

    public List<PhoneEvent> listRecentPhoneEvents(int limit) {
        List<PhoneEvent> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, app, kind, detail FROM phone_events ORDER BY id DESC LIMIT " + Math.max(1, limit),
                    null);
            while (c.moveToNext()) {
                PhoneEvent e = new PhoneEvent();
                e.ts = c.getLong(0);
                e.app = c.getString(1);
                e.kind = c.getString(2);
                e.detail = SecretBox.open(dbContext, c.getString(3));
                out.add(e);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public int[] countPhoneEventsToday() {
        int[] out = new int[3];
        Cursor c = null;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            c = getReadableDatabase().rawQuery(
                    "SELECT kind, COUNT(*) FROM phone_events WHERE ts>=? GROUP BY kind",
                    new String[]{String.valueOf(cal.getTimeInMillis())});
            while (c.moveToNext()) {
                String k = c.getString(0);
                int n = c.getInt(1);
                if ("switch".equals(k)) out[0] = n;
                else if ("click".equals(k)) out[1] = n;
                else if ("scroll".equals(k)) out[2] = n;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public void clearPhoneEvents() {
        try {
            getWritableDatabase().execSQL("DELETE FROM phone_events");
        } catch (Exception ignored) { }
    }

    public int countPhoneEvents() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM phone_events", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    public static final class PhoneBucketCell {
        public long bucketTs;
        public String app;
        public long fgMs;
        public int switches;
        public int clicks;
        public int scrolls;
    }

    public static final class PhoneAppTotal {
        public String app;
        public long fgMs;
        public int switches;
        public int clicks;
        public int scrolls;
    }

    public static final class PhoneSnapshot {
        public long ts;
        public int battery;
        public int charging;
        public String foreground;
        public double lat;
        public double lon;
        public String place;
    }

    public static final class PhoneCall {
        public long ts;
        public String name;
        public String number;
        public int type;
        public long dur;
    }

    public void upsertPhoneBucket(long bucketTs, String app, long fgMs, long sw, long cl, long sc) {
        final String name = (app == null || app.trim().isEmpty()) ? "未知应用" : app.trim();
        try {
            SQLiteDatabase wdb = getWritableDatabase();
            wdb.beginTransaction();
            try {
                Cursor c = wdb.rawQuery("SELECT 1 FROM phone_buckets WHERE bucket_ts=? AND app=? LIMIT 1",
                        new String[]{String.valueOf(bucketTs), name});
                boolean exists = c.moveToFirst();
                c.close();
                if (exists) {
                    wdb.execSQL("UPDATE phone_buckets SET fg_ms=fg_ms+?, switches=switches+?,"
                                    + " clicks=clicks+?, scrolls=scrolls+? WHERE bucket_ts=? AND app=?",
                            new Object[]{Long.valueOf(fgMs), Long.valueOf(sw), Long.valueOf(cl),
                                    Long.valueOf(sc), Long.valueOf(bucketTs), name});
                } else {
                    ContentValues v = new ContentValues();
                    v.put("bucket_ts", bucketTs);
                    v.put("app", name);
                    v.put("fg_ms", fgMs);
                    v.put("switches", sw);
                    v.put("clicks", cl);
                    v.put("scrolls", sc);
                    wdb.insert("phone_buckets", null, v);
                }
                wdb.setTransactionSuccessful();
            } finally {
                wdb.endTransaction();
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "写 phone_buckets 失败: " + e.getMessage());
        }
    }

    public void insertPhoneSnapshot(long bucketTs, int battery, int charging, String foreground,
                                    Double lat, Double lon, String place) {
        try {
            ContentValues v = new ContentValues();
            v.put("bucket_ts", bucketTs);
            if (battery >= 0) v.put("battery", battery);
            v.put("charging", charging);
            if (foreground != null) v.put("foreground", foreground);
            if (lat != null) v.put("lat", lat);
            if (lon != null) v.put("lon", lon);
            if (place != null) v.put("place", place);
            getWritableDatabase().insertWithOnConflict("phone_snapshots", null, v,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "写 phone_snapshots 失败: " + e.getMessage());
        }
    }

    public String latestPhonePlace() {
        Cursor c = null;
        try {
            long since = System.currentTimeMillis() - 12L * 3600L * 1000L;
            c = getReadableDatabase().rawQuery(
                    "SELECT place FROM phone_snapshots WHERE place IS NOT NULL AND place<>'' AND bucket_ts>=?"
                            + " ORDER BY bucket_ts DESC LIMIT 1",
                    new String[]{String.valueOf(since)});
            if (c.moveToFirst()) return SecretBox.open(dbContext, c.getString(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return null;
    }

    public double[] latestPhoneCoords() {
        Cursor c = null;
        try {
            long since = System.currentTimeMillis() - 12L * 3600L * 1000L;
            c = getReadableDatabase().rawQuery(
                    "SELECT lat, lon FROM phone_snapshots WHERE lat IS NOT NULL AND bucket_ts>=?"
                            + " ORDER BY bucket_ts DESC LIMIT 1",
                    new String[]{String.valueOf(since)});
            if (c.moveToFirst()) return new double[]{c.getDouble(0), c.getDouble(1)};
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return null;
    }

    public List<PhoneBucketCell> listPhoneBuckets(long fromTs, long toTs, int limit) {
        List<PhoneBucketCell> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT bucket_ts, app, fg_ms, switches, clicks, scrolls FROM phone_buckets"
                            + " WHERE bucket_ts>=? AND bucket_ts<?"
                            + " ORDER BY bucket_ts DESC, fg_ms DESC LIMIT " + Math.max(1, limit),
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            while (c.moveToNext()) out.add(bucketCellOf(c));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读 phone_buckets 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public List<PhoneBucketCell> aggregatePhoneBuckets(long fromTs, long toTs, long chunkMs,
                                                       String appFilter, int limit) {
        List<PhoneBucketCell> out = new ArrayList<>();
        Cursor c = null;
        long chunk = Math.max(chunkMs, 60000L);
        try {
            String where = "bucket_ts>=? AND bucket_ts<?";
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(fromTs));
            args.add(String.valueOf(toTs));
            if (appFilter != null && !appFilter.trim().isEmpty()) {
                where += " AND app LIKE ?";
                args.add("%" + appFilter.trim() + "%");
            }

            c = getReadableDatabase().rawQuery(
                    "SELECT (bucket_ts/" + chunk + ")*" + chunk + " AS ch, app,"
                            + " SUM(fg_ms), SUM(switches), SUM(clicks), SUM(scrolls)"
                            + " FROM phone_buckets WHERE " + where
                            + " GROUP BY ch, app ORDER BY ch DESC, SUM(fg_ms) DESC LIMIT " + Math.max(1, limit),
                    args.toArray(new String[0]));
            while (c.moveToNext()) {
                PhoneBucketCell cell = new PhoneBucketCell();
                cell.bucketTs = c.getLong(0);
                cell.app = c.getString(1);
                cell.fgMs = c.getLong(2);
                cell.switches = c.getInt(3);
                cell.clicks = c.getInt(4);
                cell.scrolls = c.getInt(5);
                out.add(cell);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "聚合 phone_buckets 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public List<PhoneAppTotal> phoneAppTotals(long fromTs, long toTs, String appFilter) {
        java.util.LinkedHashMap<String, long[]> agg = new java.util.LinkedHashMap<>();
        String filter = (appFilter == null || appFilter.trim().isEmpty()) ? null : "%" + appFilter.trim() + "%";
        long bucketFrom = Math.max(fromTs, System.currentTimeMillis() - 7L * 24L * 3600L * 1000L);
        Cursor c = null;
        try {
            String where = "bucket_ts>=? AND bucket_ts<?";
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(bucketFrom));
            args.add(String.valueOf(toTs));
            if (filter != null) { where += " AND app LIKE ?"; args.add(filter); }
            c = getReadableDatabase().rawQuery(
                    "SELECT app, SUM(fg_ms), SUM(switches), SUM(clicks), SUM(scrolls)"
                            + " FROM phone_buckets WHERE " + where + " GROUP BY app",
                    args.toArray(new String[0]));
            while (c.moveToNext()) {
                addTotal(agg, c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4));
            }
            c.close();
            c = null;
            if (fromTs < bucketFrom) {
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                String dFrom = df.format(new java.util.Date(fromTs));
                String dTo = df.format(new java.util.Date(bucketFrom));
                String w2 = "day>=? AND day<?";
                List<String> a2 = new ArrayList<>();
                a2.add(dFrom);
                a2.add(dTo);
                if (filter != null) { w2 += " AND app LIKE ?"; a2.add(filter); }
                c = getReadableDatabase().rawQuery(
                        "SELECT app, SUM(fg_ms), SUM(switches), SUM(clicks), SUM(scrolls)"
                                + " FROM phone_days WHERE " + w2 + " GROUP BY app",
                        a2.toArray(new String[0]));
                while (c.moveToNext()) {
                    addTotal(agg, c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4));
                }
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "统计 App 合计失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        List<PhoneAppTotal> out = new ArrayList<>();
        for (java.util.Map.Entry<String, long[]> e : agg.entrySet()) {
            PhoneAppTotal t = new PhoneAppTotal();
            t.app = e.getKey();
            t.fgMs = e.getValue()[0];
            t.switches = (int) e.getValue()[1];
            t.clicks = (int) e.getValue()[2];
            t.scrolls = (int) e.getValue()[3];
            out.add(t);
        }
        java.util.Collections.sort(out, new java.util.Comparator<PhoneAppTotal>() {
            @Override public int compare(PhoneAppTotal a, PhoneAppTotal b) {
                return Long.compare(b.fgMs, a.fgMs);
            }
        });
        if (out.size() > 30) return new ArrayList<>(out.subList(0, 30));
        return out;
    }

    private static void addTotal(java.util.LinkedHashMap<String, long[]> agg, String app,
                                long fgMs, long sw, long cl, long sc) {
        String key = (app == null || app.trim().isEmpty()) ? "未知应用" : app;
        long[] v = agg.get(key);
        if (v == null) { v = new long[4]; agg.put(key, v); }
        v[0] += fgMs; v[1] += sw; v[2] += cl; v[3] += sc;
    }

    private static PhoneBucketCell bucketCellOf(Cursor c) {
        PhoneBucketCell cell = new PhoneBucketCell();
        cell.bucketTs = c.getLong(0);
        cell.app = c.getString(1);
        cell.fgMs = c.getLong(2);
        cell.switches = c.getInt(3);
        cell.clicks = c.getInt(4);
        cell.scrolls = c.getInt(5);
        return cell;
    }

    public List<PhoneSnapshot> listPhoneSnapshots(long fromTs, long toTs, int limit) {
        List<PhoneSnapshot> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT bucket_ts, battery, charging, foreground, lat, lon, place FROM phone_snapshots"
                            + " WHERE bucket_ts>=? AND bucket_ts<? ORDER BY bucket_ts DESC LIMIT " + Math.max(1, limit),
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            while (c.moveToNext()) {
                PhoneSnapshot s = new PhoneSnapshot();
                s.ts = c.getLong(0);
                s.battery = c.isNull(1) ? -1 : c.getInt(1);
                s.charging = c.getInt(2);
                s.foreground = c.getString(3);
                s.lat = c.isNull(4) ? 0 : c.getDouble(4);
                s.lon = c.isNull(5) ? 0 : c.getDouble(5);

                s.place = SecretBox.open(dbContext, c.getString(6));
                out.add(s);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读 phone_snapshots 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void insertPhoneCall(long ts, String name, String number, int type, long dur) {
        try {
            ContentValues v = new ContentValues();
            v.put("ts", ts);
            v.put("name", name);
            v.put("number", number);
            v.put("type", type);
            v.put("dur", dur);
            getWritableDatabase().insertWithOnConflict("phone_calls", null, v,
                    SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "写 phone_calls 失败: " + e.getMessage());
        }
    }

    public List<PhoneCall> listPhoneCalls(long fromTs, int limit) {
        List<PhoneCall> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, name, number, type, dur FROM phone_calls WHERE ts>=?"
                            + " ORDER BY ts DESC LIMIT " + Math.max(1, limit),
                    new String[]{String.valueOf(fromTs)});
            while (c.moveToNext()) {
                PhoneCall cl = new PhoneCall();
                cl.ts = c.getLong(0);
                cl.name = SecretBox.open(dbContext, c.getString(1));
                cl.number = SecretBox.open(dbContext, c.getString(2));
                cl.type = c.getInt(3);
                cl.dur = c.getLong(4);
                out.add(cl);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读 phone_calls 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void rollupAndPrunePhone(long bucketsKeepMs, long daysKeepMs, long callsKeepMs,
                                    long notifKeepMs, int keepEvents, int notifMaxRows) {
        try {
            long now = System.currentTimeMillis();
            long bucketCut = now - Math.max(bucketsKeepMs, 3600000L);
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            SQLiteDatabase wdb = getWritableDatabase();
            wdb.beginTransaction();
            try {

                java.util.LinkedHashMap<String, long[]> agg = new java.util.LinkedHashMap<>();
                Cursor c = wdb.rawQuery("SELECT bucket_ts, app, fg_ms, switches, clicks, scrolls"
                        + " FROM phone_buckets WHERE bucket_ts<?", new String[]{String.valueOf(bucketCut)});
                while (c.moveToNext()) {
                    String day = df.format(new java.util.Date(c.getLong(0)));
                    String app = c.getString(1);
                    String key = day + "" + (app == null ? "未知应用" : app);
                    long[] v = agg.get(key);
                    if (v == null) { v = new long[4]; agg.put(key, v); }
                    v[0] += c.getLong(2); v[1] += c.getInt(3); v[2] += c.getInt(4); v[3] += c.getInt(5);
                }
                c.close();
                for (java.util.Map.Entry<String, long[]> e : agg.entrySet()) {
                    int cut = e.getKey().indexOf('');
                    String day = e.getKey().substring(0, cut);
                    String app = e.getKey().substring(cut + 1);
                    long[] v = e.getValue();
                    Cursor ex = wdb.rawQuery("SELECT 1 FROM phone_days WHERE day=? AND app=? LIMIT 1",
                            new String[]{day, app});
                    boolean has = ex.moveToFirst();
                    ex.close();
                    if (has) {
                        wdb.execSQL("UPDATE phone_days SET fg_ms=fg_ms+?, switches=switches+?,"
                                        + " clicks=clicks+?, scrolls=scrolls+? WHERE day=? AND app=?",
                                new Object[]{Long.valueOf(v[0]), Long.valueOf(v[1]), Long.valueOf(v[2]),
                                        Long.valueOf(v[3]), day, app});
                    } else {
                        ContentValues cv = new ContentValues();
                        cv.put("day", day);
                        cv.put("app", app);
                        cv.put("fg_ms", v[0]);
                        cv.put("switches", v[1]);
                        cv.put("clicks", v[2]);
                        cv.put("scrolls", v[3]);
                        wdb.insert("phone_days", null, cv);
                    }
                }
                wdb.delete("phone_buckets", "bucket_ts<?", new String[]{String.valueOf(bucketCut)});

                wdb.delete("phone_days", "day<?", new String[]{df.format(new java.util.Date(now - Math.max(daysKeepMs, 86400000L)))});
                wdb.delete("phone_calls", "ts<?", new String[]{String.valueOf(now - Math.max(callsKeepMs, 86400000L))});
                wdb.delete("phone_snapshots", "bucket_ts<?", new String[]{String.valueOf(now - Math.max(daysKeepMs, 86400000L))});
                wdb.execSQL("DELETE FROM phone_events WHERE id NOT IN"
                        + " (SELECT id FROM phone_events ORDER BY id DESC LIMIT " + Math.max(1, keepEvents) + ")");

                wdb.delete("phone_notifications", "ts<?",
                        new String[]{String.valueOf(now - Math.max(notifKeepMs, 86400000L))});
                wdb.execSQL("DELETE FROM phone_notifications WHERE id NOT IN"
                        + " (SELECT id FROM phone_notifications ORDER BY id DESC LIMIT " + Math.max(1, notifMaxRows) + ")");
                wdb.setTransactionSuccessful();
            } finally {
                wdb.endTransaction();
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "归档/清理手机感知失败: " + e.getMessage());
        }
    }

    public int countPhoneAwarenessRows() {
        int n = 0;
        Cursor c = null;
        try {
            String[] tabs = {"phone_events", "phone_buckets", "phone_days", "phone_snapshots",
                    "phone_calls", "phone_notifications"};
            for (String t : tabs) {
                c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + t, null);
                if (c.moveToFirst()) n += c.getInt(0);
                c.close();
                c = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return n;
    }

    public void clearPhoneAwareness() {
        try {
            SQLiteDatabase wdb = getWritableDatabase();
            wdb.beginTransaction();
            try {
                for (String t : new String[]{"phone_events", "phone_buckets", "phone_days",
                        "phone_snapshots", "phone_calls", "phone_notifications"}) {
                    wdb.execSQL("DELETE FROM " + t);
                }
                wdb.setTransactionSuccessful();
            } finally {
                wdb.endTransaction();
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "清空手机感知失败: " + e.getMessage());
        }
    }

    public static final class PhoneNotification {
        public long ts;
        public String app;
        public String title;
        public String text;
        public String category;
        public boolean ongoing;
    }

    public void insertPhoneNotification(long ts, String app, String title, String text,
                                        String category, boolean ongoing) {
        try {
            ContentValues v = new ContentValues();
            v.put("ts", ts);
            v.put("app", app);
            v.put("title", title);
            v.put("text", text);
            v.put("category", category);
            v.put("ongoing", ongoing ? 1 : 0);
            getWritableDatabase().insert("phone_notifications", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "写 phone_notifications 失败: " + e.getMessage());
        }
    }

    public List<PhoneNotification> listPhoneNotifications(long fromTs, int limit) {
        List<PhoneNotification> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, app, title, text, category, ongoing FROM phone_notifications"
                            + " WHERE ts>=? ORDER BY ts DESC LIMIT " + Math.max(1, limit),
                    new String[]{String.valueOf(fromTs)});
            while (c.moveToNext()) {
                PhoneNotification n = new PhoneNotification();
                n.ts = c.getLong(0);
                n.app = c.getString(1);
                n.title = SecretBox.open(dbContext, c.getString(2));
                n.text = SecretBox.open(dbContext, c.getString(3));
                n.category = c.getString(4);
                n.ongoing = c.getInt(5) == 1;
                out.add(n);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读 phone_notifications 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void prunePhoneNotifications(int keep) {
        try {
            getWritableDatabase().execSQL("DELETE FROM phone_notifications WHERE id NOT IN"
                    + " (SELECT id FROM phone_notifications ORDER BY id DESC LIMIT " + Math.max(1, keep) + ")");
        } catch (Exception ignored) { }
    }

    public int countGmMemberSpeeches(long roomId, long memberId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_messages WHERE room_id=? AND member_id=? AND (is_thought IS NULL OR is_thought=0)",
                new String[]{String.valueOf(roomId), String.valueOf(memberId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int countGmSpeeches(long roomId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_messages WHERE room_id=? AND (kind IS NULL OR kind='chat')",
                new String[]{String.valueOf(roomId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public long lastGmRoundBoundaryId(long roomId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(MAX(id),0) FROM gm_messages WHERE room_id=? AND kind='system' AND ("
                        + "content LIKE '%轮结束%' OR content LIKE '%直接进入第%')",
                new String[]{String.valueOf(roomId)});
        long id = c.moveToFirst() ? c.getLong(0) : 0L;
        c.close();
        return id;
    }

    public java.util.Set<Long> gmRoundMemberIds(long roomId, long afterMsgId, boolean isThought) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT member_id FROM gm_messages WHERE room_id=? AND id>? AND member_id>0 AND is_thought=?",
                new String[]{String.valueOf(roomId), String.valueOf(afterMsgId), isThought ? "1" : "0"});
        while (c.moveToNext()) out.add(c.getLong(0));
        c.close();
        return out;
    }

    public int countGmUserMessages(long roomId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_messages WHERE room_id=? AND speaker=?",
                new String[]{String.valueOf(roomId), "我"});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int countGmMemberMessages(long roomId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM gm_messages WHERE room_id=? AND member_id>0",
                new String[]{String.valueOf(roomId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public void clearGmMessages(long roomId) {
        getWritableDatabase().delete("gm_messages", "room_id=?", new String[]{String.valueOf(roomId)});
    }

    private void createGmInquiryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_inquiries ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "room_id INTEGER NOT NULL,"
                + "title TEXT,"
                + "items TEXT,"
                + "status TEXT DEFAULT 'pending',"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_inquiries_room ON gm_inquiries(room_id, id)");
    }

    public static class GmInquiryItem {
        public String asker;
        public String question;
        public String answer;

        public List<String> options = new ArrayList<>();

        public boolean multi = false;

        public String clarifyLog = "";
        public GmInquiryItem() { }
        public GmInquiryItem(String asker, String question) {
            this.asker = asker;
            this.question = question;
        }
        public GmInquiryItem(String asker, String question, List<String> options, boolean multi) {
            this.asker = asker;
            this.question = question;
            if (options != null) this.options = options;
            this.multi = multi;
        }
        public boolean hasOptions() { return this.options != null && !this.options.isEmpty(); }
    }

    public static class GmInquiry {
        public long id;
        public long roomId;
        public String title;
        public List<GmInquiryItem> items = new ArrayList<>();
        public String status = "pending";
        public long createdAt;
    }

    public long insertGmInquiry(long roomId, String title, List<GmInquiryItem> items) {
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("title", title);
        v.put("items", inquiryItemsToJson(items));
        v.put("status", "pending");
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_inquiries", null, v);
    }

    public GmInquiry latestGmInquiry(long roomId) {
        Cursor c = getReadableDatabase().query("gm_inquiries", null, "room_id=?",
                new String[]{String.valueOf(roomId)}, null, null, "id DESC", "1");
        GmInquiry out = null;
        if (c.moveToNext()) out = cursorToInquiry(c);
        c.close();
        return out;
    }

    public GmInquiry getGmInquiry(long id) {
        Cursor c = getReadableDatabase().query("gm_inquiries", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null, "1");
        GmInquiry out = null;
        if (c.moveToNext()) out = cursorToInquiry(c);
        c.close();
        return out;
    }

    public List<GmInquiry> listGmInquiries(long roomId) {
        List<GmInquiry> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_inquiries", null, "room_id=?",
                    new String[]{String.valueOf(roomId)}, null, null, "id ASC");
            while (c.moveToNext()) out.add(cursorToInquiry(c));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmInquiries 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void updateGmInquiryAnswers(long inquiryId, List<GmInquiryItem> items, String status) {
        ContentValues v = new ContentValues();
        v.put("items", inquiryItemsToJson(items));
        if (status != null) v.put("status", status);
        getWritableDatabase().update("gm_inquiries", v, "id=?", new String[]{String.valueOf(inquiryId)});
    }

    private GmInquiry cursorToInquiry(Cursor c) {
        GmInquiry q = new GmInquiry();
        q.id = c.getLong(c.getColumnIndexOrThrow("id"));
        q.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
        q.title = c.getString(c.getColumnIndexOrThrow("title"));
        q.status = c.getString(c.getColumnIndexOrThrow("status"));
        q.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        q.items = inquiryItemsFromJson(c.getString(c.getColumnIndexOrThrow("items")));
        return q;
    }

    private String inquiryItemsToJson(List<GmInquiryItem> items) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (items != null) {
            for (GmInquiryItem it : items) {
                try {
                    org.json.JSONArray opts = new org.json.JSONArray();
                    if (it.options != null) {
                        for (String o : it.options) if (o != null && !o.trim().isEmpty()) opts.put(o.trim());
                    }
                    arr.put(new org.json.JSONObject()
                            .put("asker", it.asker == null ? "" : it.asker)
                            .put("question", it.question == null ? "" : it.question)
                            .put("answer", it.answer == null ? "" : it.answer)
                            .put("options", opts)
                            .put("clarify", it.clarifyLog == null ? "" : it.clarifyLog)
                            .put("multi", it.multi));
                } catch (Exception ignored) { }
            }
        }
        return arr.toString();
    }

    private List<GmInquiryItem> inquiryItemsFromJson(String json) {
        List<GmInquiryItem> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                GmInquiryItem it = new GmInquiryItem();
                it.asker = o.optString("asker", "");
                it.question = o.optString("question", "");
                it.answer = o.optString("answer", "");
                it.multi = o.optBoolean("multi", false);
                it.clarifyLog = o.optString("clarify", "");
                org.json.JSONArray opts = o.optJSONArray("options");
                if (opts != null) {
                    for (int k = 0; k < opts.length(); k++) {
                        String s = opts.optString(k, "");
                        if (!s.trim().isEmpty()) it.options.add(s.trim());
                    }
                }
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void ensureGmPendingQuestionsTable() {
        try {
            getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS gm_pending_questions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "room_id INTEGER NOT NULL,"
                    + "member_id INTEGER NOT NULL,"
                    + "asker TEXT,"
                    + "question TEXT,"
                    + "options TEXT,"
                    + "multi INTEGER DEFAULT 0,"
                    + "status TEXT DEFAULT 'pending',"
                    + "created_at INTEGER NOT NULL)");
            getWritableDatabase().execSQL("CREATE INDEX IF NOT EXISTS idx_gm_pq_room ON gm_pending_questions(room_id, status)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "ensureGmPendingQuestionsTable 失败: " + e.getMessage());
        }
    }

    private void ensureGmFeedbackTable() {
        try {
            getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS gm_feedback ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "room_id INTEGER NOT NULL,"
                    + "member_id INTEGER NOT NULL,"
                    + "author TEXT,"
                    + "avatar TEXT,"
                    + "category TEXT DEFAULT 'issue',"
                    + "title TEXT,"
                    + "detail TEXT,"
                    + "context TEXT,"
                    + "status TEXT DEFAULT 'unread',"
                    + "created_at INTEGER NOT NULL)");
            getWritableDatabase().execSQL("CREATE INDEX IF NOT EXISTS idx_gm_fb_room ON gm_feedback(room_id, status)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "ensureGmFeedbackTable 失败: " + e.getMessage());
        }
    }

    public static class GmAdminMessage {
        public long id;
        public long roomId;
        public String role;
        public String content;
        public String reasoning;

        public String toolLog;
        public long createdAt;
    }

    public static class GmAdminProposal {
        public long id;
        public long roomId;
        public String kind;
        public String payload;
        public String summary;
        public String status;
        public long createdAt;
    }

    private void ensureAdminTables() {
        try {

            try { getWritableDatabase().execSQL("ALTER TABLE gm_members ADD COLUMN sort_order INTEGER DEFAULT 0"); } catch (Exception ignored) { }
            getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS gm_admin_messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, room_id INTEGER NOT NULL,"
                    + "role TEXT, content TEXT, reasoning TEXT,"

                    + "read_by_user INTEGER DEFAULT 0, created_at INTEGER NOT NULL)");
            try { getWritableDatabase().execSQL("ALTER TABLE gm_admin_messages ADD COLUMN read_by_user INTEGER DEFAULT 0"); } catch (Exception ignored) { }

            try { getWritableDatabase().execSQL("ALTER TABLE gm_admin_messages ADD COLUMN tool_log TEXT"); } catch (Exception ignored) { }
            getWritableDatabase().execSQL("CREATE INDEX IF NOT EXISTS idx_gm_admin_room ON gm_admin_messages(room_id, id)");
            getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS gm_admin_proposals ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, room_id INTEGER NOT NULL,"
                    + "kind TEXT, payload TEXT, summary TEXT,"
                    + "status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL)");
            getWritableDatabase().execSQL("CREATE INDEX IF NOT EXISTS idx_gm_ap_room ON gm_admin_proposals(room_id, status)");
            getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS gm_admin_config ("
                    + "room_id INTEGER PRIMARY KEY, base_url TEXT, api_key TEXT, model TEXT,"
                    + "context_limit INTEGER DEFAULT 0, think_level INTEGER DEFAULT -1,"

                    + "repeat_min_chars INTEGER DEFAULT 3000,"
                    + "repeat_len INTEGER DEFAULT 500,"
                    + "repeat_window INTEGER DEFAULT 2000,"
                    + "repeat_max INTEGER DEFAULT 3,"
                    + "tool_max_iter INTEGER DEFAULT 37,"
                    + "repeat_retry INTEGER DEFAULT 3)");

            for (String col : new String[]{
                    "repeat_min_chars INTEGER DEFAULT 3000",
                    "repeat_len INTEGER DEFAULT 500",
                    "repeat_window INTEGER DEFAULT 2000",
                    "repeat_max INTEGER DEFAULT 3",
                    "tool_max_iter INTEGER DEFAULT 37",
                    "repeat_retry INTEGER DEFAULT 3"}) {
                try { getWritableDatabase().execSQL("ALTER TABLE gm_admin_config ADD COLUMN " + col); }
                catch (Exception ignored) { }
            }

            getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS gm_anomalies ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, room_id INTEGER NOT NULL,"
                    + "round INTEGER DEFAULT 0, member_id INTEGER DEFAULT 0, member_name TEXT,"
                    + "kind TEXT, detail TEXT, sample TEXT, created_at INTEGER NOT NULL)");
            getWritableDatabase().execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_gm_anomaly_room ON gm_anomalies(room_id, id)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "ensureAdminTables 失败: " + e.getMessage());
        }
    }

    public long insertGmAdminMessage(long roomId, String role, String content, String reasoning) {
        return insertGmAdminMessage(roomId, role, content, reasoning, false, null);
    }

    public long insertGmAdminMessage(long roomId, String role, String content, String reasoning, boolean unread) {
        return insertGmAdminMessage(roomId, role, content, reasoning, unread, null);
    }

    public long insertGmAdminMessage(long roomId, String role, String content, String reasoning,
                                     boolean unread, String toolLog) {
        ensureAdminTables();
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("role", role == null ? "user" : role);
        v.put("content", content == null ? "" : content);
        v.put("reasoning", reasoning == null ? "" : reasoning);
        v.put("tool_log", toolLog == null ? "" : toolLog);
        v.put("read_by_user", unread ? 0 : 1);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_admin_messages", null, v);
    }

    public static class GmAdminConfig {
        public long roomId;
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
        public int contextLimit = 0;
        public int thinkLevel = -1;

        public int repeatMinChars = 3000;

        public int repeatLen = 500;

        public int repeatWindow = 2000;

        public int repeatMax = 3;

        public int toolMaxIter = 37;

        public int repeatRetry = 3;
    }

    public GmAdminConfig getGmAdminConfig(long roomId) {
        ensureAdminTables();
        GmAdminConfig cfg = new GmAdminConfig();
        cfg.roomId = roomId;
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_admin_config", null, "room_id=?",
                    new String[]{String.valueOf(roomId)}, null, null, null, "1");
            if (c.moveToNext()) {
                int bi = c.getColumnIndex("base_url");
                int ki = c.getColumnIndex("api_key");
                int mi = c.getColumnIndex("model");
                int ci = c.getColumnIndex("context_limit");
                int ti = c.getColumnIndex("think_level");
                cfg.baseUrl = bi >= 0 && c.getString(bi) != null ? c.getString(bi) : "";
                cfg.apiKey = ki >= 0 && c.getString(ki) != null ? c.getString(ki) : "";
                cfg.model = mi >= 0 && c.getString(mi) != null ? c.getString(mi) : "";
                cfg.contextLimit = ci >= 0 ? c.getInt(ci) : 0;
                cfg.thinkLevel = ti >= 0 ? c.getInt(ti) : -1;

                int rm = c.getColumnIndex("repeat_min_chars");
                if (rm >= 0) cfg.repeatMinChars = c.getInt(rm);
                int rl = c.getColumnIndex("repeat_len");
                if (rl >= 0) cfg.repeatLen = c.getInt(rl);
                int rw = c.getColumnIndex("repeat_window");
                if (rw >= 0) cfg.repeatWindow = c.getInt(rw);
                int rx = c.getColumnIndex("repeat_max");
                if (rx >= 0) cfg.repeatMax = c.getInt(rx);
                int tm = c.getColumnIndex("tool_max_iter");
                if (tm >= 0) cfg.toolMaxIter = c.getInt(tm);
                int rr = c.getColumnIndex("repeat_retry");
                if (rr >= 0) cfg.repeatRetry = c.getInt(rr);

                if (normalizeRepeat(cfg)) {
                    try { getWritableDatabase().update("gm_admin_config", cfgValues(cfg),
                            "room_id=?", new String[]{String.valueOf(roomId)}); }
                    catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "getGmAdminConfig 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return cfg;
    }

    public static boolean normalizeRepeat(GmAdminConfig cfg) {
        if (cfg == null) return false;
        int minC = Math.max(500, Math.min(200000, cfg.repeatMinChars));
        int rl = Math.max(100, Math.min(20000, cfg.repeatLen));

        int rw = Math.max(rl * 3, Math.min(200000, cfg.repeatWindow));
        int rx = Math.max(2, Math.min(100, cfg.repeatMax));
        int tm = Math.max(1, Math.min(100, cfg.toolMaxIter));
        int rr = Math.max(0, Math.min(10, cfg.repeatRetry));
        boolean changed = (minC != cfg.repeatMinChars) || (rl != cfg.repeatLen)
                || (rw != cfg.repeatWindow) || (rx != cfg.repeatMax)
                || (tm != cfg.toolMaxIter) || (rr != cfg.repeatRetry);
        cfg.repeatMinChars = minC;
        cfg.repeatLen = rl;
        cfg.repeatWindow = rw;
        cfg.repeatMax = rx;
        cfg.toolMaxIter = tm;
        cfg.repeatRetry = rr;
        return changed;
    }

    private static ContentValues cfgValues(GmAdminConfig cfg) {
        ContentValues v = new ContentValues();
        v.put("base_url", cfg.baseUrl == null ? "" : cfg.baseUrl);
        v.put("api_key", cfg.apiKey == null ? "" : cfg.apiKey);
        v.put("model", cfg.model == null ? "" : cfg.model);
        v.put("context_limit", cfg.contextLimit);
        v.put("think_level", cfg.thinkLevel);
        v.put("repeat_min_chars", cfg.repeatMinChars);
        v.put("repeat_len", cfg.repeatLen);
        v.put("repeat_window", cfg.repeatWindow);
        v.put("repeat_max", cfg.repeatMax);
        v.put("tool_max_iter", cfg.toolMaxIter);
        v.put("repeat_retry", cfg.repeatRetry);
        return v;
    }

    public void setGmAdminConfig(GmAdminConfig cfg) {
        if (cfg == null || cfg.roomId <= 0L) return;
        ensureAdminTables();
        normalizeRepeat(cfg);
        ContentValues v = cfgValues(cfg);
        v.put("room_id", cfg.roomId);
        try {
            getWritableDatabase().insertWithOnConflict("gm_admin_config", null, v,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "setGmAdminConfig 失败: " + e.getMessage());
        }
    }

    public static class GmAnomaly {
        public long id;
        public long roomId;
        public int round;
        public long memberId;
        public String memberName;
        public String kind;
        public String detail;
        public String sample;
        public long createdAt;
    }

    public void insertGmAnomaly(long roomId, int round, long memberId, String memberName,
                                String kind, String detail, String sample) {
        try {
            ensureAdminTables();
            ContentValues v = new ContentValues();
            v.put("room_id", roomId);
            v.put("round", round);
            v.put("member_id", memberId);
            v.put("member_name", memberName == null ? "" : memberName);
            v.put("kind", kind == null ? "" : kind);
            v.put("detail", detail == null ? "" : detail);

            String s = sample == null ? "" : sample;
            if (s.length() > 500) s = s.substring(0, 500);
            v.put("sample", s);
            v.put("created_at", System.currentTimeMillis());
            getWritableDatabase().insert("gm_anomalies", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "insertGmAnomaly 失败: " + e.getMessage());
        }
    }

    public List<GmAnomaly> listGmAnomalies(long roomId, int limit) {
        List<GmAnomaly> out = new ArrayList<>();
        Cursor c = null;
        try {
            ensureAdminTables();
            c = limit > 0
                    ? getReadableDatabase().query("gm_anomalies", null, "room_id=?",
                            new String[]{String.valueOf(roomId)}, null, null, "id DESC", String.valueOf(limit))
                    : getReadableDatabase().query("gm_anomalies", null, "room_id=?",
                            new String[]{String.valueOf(roomId)}, null, null, "id DESC");
            while (c.moveToNext()) {
                GmAnomaly a = new GmAnomaly();
                a.id = c.getLong(c.getColumnIndexOrThrow("id"));
                a.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
                a.round = c.getInt(c.getColumnIndexOrThrow("round"));
                a.memberId = c.getLong(c.getColumnIndexOrThrow("member_id"));
                a.memberName = c.getString(c.getColumnIndexOrThrow("member_name"));
                a.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                a.detail = c.getString(c.getColumnIndexOrThrow("detail"));
                a.sample = c.getString(c.getColumnIndexOrThrow("sample"));
                a.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                out.add(a);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmAnomalies 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public int countGmAnomalies(long roomId) {
        try {
            ensureAdminTables();
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM gm_anomalies WHERE room_id=?",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) { int n = c.getInt(0); c.close(); return n; }
            c.close();
        } catch (Exception ignored) { }
        return 0;
    }

    public int countUnreadGmAdminMessages(long roomId) {
        ensureAdminTables();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM gm_admin_messages WHERE room_id=? AND role='assistant' AND read_by_user=0",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public java.util.HashMap<Long, Integer> mapUnreadGmAdminMessages() {
        ensureAdminTables();
        java.util.HashMap<Long, Integer> out = new java.util.HashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT room_id, COUNT(*) FROM gm_admin_messages "
                            + "WHERE role='assistant' AND read_by_user=0 GROUP BY room_id", null);
            while (c.moveToNext()) out.put(c.getLong(0), c.getInt(1));
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void markGmAdminMessagesRead(long roomId) {
        ensureAdminTables();
        try {
            ContentValues v = new ContentValues();
            v.put("read_by_user", 1);
            getWritableDatabase().update("gm_admin_messages", v,
                    "room_id=? AND role='assistant' AND read_by_user=0", new String[]{String.valueOf(roomId)});
        } catch (Exception ignored) { }
    }

    public List<GmAdminMessage> listGmAdminMessages(long roomId, int limit) {
        ensureAdminTables();
        List<GmAdminMessage> out = new ArrayList<>();
        Cursor c = null;
        try {
            if (limit > 0) {
                c = getReadableDatabase().query("gm_admin_messages", null, "room_id=?",
                        new String[]{String.valueOf(roomId)}, null, null, "id DESC", String.valueOf(limit));
                while (c.moveToNext()) out.add(0, cursorToAdminMsg(c));
            } else {
                c = getReadableDatabase().query("gm_admin_messages", null, "room_id=?",
                        new String[]{String.valueOf(roomId)}, null, null, "id ASC");
                while (c.moveToNext()) out.add(cursorToAdminMsg(c));
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmAdminMessages 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    private GmAdminMessage cursorToAdminMsg(Cursor c) {
        GmAdminMessage m = new GmAdminMessage();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
        m.role = c.getString(c.getColumnIndexOrThrow("role"));
        m.content = c.getString(c.getColumnIndexOrThrow("content"));
        int ri = c.getColumnIndex("reasoning");
        m.reasoning = ri >= 0 ? c.getString(ri) : "";
        int tli = c.getColumnIndex("tool_log");
        m.toolLog = tli >= 0 && c.getString(tli) != null ? c.getString(tli) : "";
        m.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return m;
    }

    public void clearGmAdminMessages(long roomId) {
        ensureAdminTables();
        try { getWritableDatabase().delete("gm_admin_messages", "room_id=?", new String[]{String.valueOf(roomId)}); }
        catch (Exception ignored) { }
    }

    public long insertGmAdminProposal(long roomId, String kind, String payload, String summary) {
        ensureAdminTables();
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("kind", kind == null ? "order" : kind);
        v.put("payload", payload == null ? "" : payload);
        v.put("summary", summary == null ? "" : summary);
        v.put("status", "pending");
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_admin_proposals", null, v);
    }

    public List<GmAdminProposal> listPendingGmAdminProposals(long roomId) {
        ensureAdminTables();
        List<GmAdminProposal> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_admin_proposals", null,
                    "room_id=? AND status='pending'", new String[]{String.valueOf(roomId)},
                    null, null, "id ASC");
            while (c.moveToNext()) out.add(cursorToProposal(c));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listPendingGmAdminProposals 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public int countPendingGmAdminProposals(long roomId) {
        ensureAdminTables();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM gm_admin_proposals WHERE room_id=? AND status='pending'",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public void setGmAdminProposalStatus(long proposalId, String status) {
        ensureAdminTables();
        try {
            ContentValues v = new ContentValues();
            v.put("status", status);
            getWritableDatabase().update("gm_admin_proposals", v, "id=?", new String[]{String.valueOf(proposalId)});
        } catch (Exception ignored) { }
    }

    private GmAdminProposal cursorToProposal(Cursor c) {
        GmAdminProposal p = new GmAdminProposal();
        p.id = c.getLong(c.getColumnIndexOrThrow("id"));
        p.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
        p.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        p.payload = c.getString(c.getColumnIndexOrThrow("payload"));
        p.summary = c.getString(c.getColumnIndexOrThrow("summary"));
        p.status = c.getString(c.getColumnIndexOrThrow("status"));
        p.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return p;
    }

    public void setGmMemberSortOrder(long roomId, List<Long> orderedIds) {
        ensureAdminTables();

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {

            ContentValues zero = new ContentValues();
            zero.put("sort_order", 0);
            db.update("gm_members", zero, "room_id=?",
                    new String[]{String.valueOf(roomId)});
            for (int i = 0; i < orderedIds.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort_order", i + 1);
                db.update("gm_members", v, "id=? AND room_id=?",
                        new String[]{String.valueOf(orderedIds.get(i)), String.valueOf(roomId)});
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "setGmMemberSortOrder 失败（已回滚，顺序保持原样）: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    public static class GmFeedback {        public long id;
        public long roomId;
        public long memberId;
        public String author;
        public String avatar;
        public String category;
        public String title;
        public String detail;
        public String context;
        public String status;
        public long createdAt;
    }

    public long insertGmFeedback(long roomId, long memberId, String author, String avatar,
                                 String category, String title, String detail, String context) {
        ensureGmFeedbackTable();
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("member_id", memberId);
        v.put("author", author == null ? "" : author);
        v.put("avatar", avatar == null ? "" : avatar);
        v.put("category", category == null || category.trim().isEmpty() ? "issue" : category.trim());
        v.put("title", title == null ? "" : title.trim());
        v.put("detail", detail == null ? "" : detail.trim());
        v.put("context", context == null ? "" : context.trim());
        v.put("status", "unread");
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_feedback", null, v);
    }

    public List<GmFeedback> listGmFeedback(long roomId) {
        ensureGmFeedbackTable();
        List<GmFeedback> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_feedback", null, "room_id=?",
                    new String[]{String.valueOf(roomId)}, null, null,
                    "CASE WHEN status='unread' THEN 0 ELSE 1 END, id DESC");
            while (c.moveToNext()) out.add(cursorToGmFeedback(c));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmFeedback 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public int countUnreadGmFeedback(long roomId) {
        ensureGmFeedbackTable();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM gm_feedback WHERE room_id=? AND status='unread'",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public void markGmFeedbackRead(long roomId) {
        ensureGmFeedbackTable();
        try {
            ContentValues v = new ContentValues();
            v.put("status", "read");
            getWritableDatabase().update("gm_feedback", v, "room_id=? AND status='unread'",
                    new String[]{String.valueOf(roomId)});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "markGmFeedbackRead 失败: " + e.getMessage());
        }
    }

    private GmFeedback cursorToGmFeedback(Cursor c) {
        GmFeedback f = new GmFeedback();
        f.id = c.getLong(c.getColumnIndexOrThrow("id"));
        f.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
        f.memberId = c.getLong(c.getColumnIndexOrThrow("member_id"));
        f.author = c.getString(c.getColumnIndexOrThrow("author"));
        f.avatar = c.getString(c.getColumnIndexOrThrow("avatar"));
        f.category = c.getString(c.getColumnIndexOrThrow("category"));
        f.title = c.getString(c.getColumnIndexOrThrow("title"));
        f.detail = c.getString(c.getColumnIndexOrThrow("detail"));
        int ci = c.getColumnIndex("context");
        f.context = ci >= 0 ? c.getString(ci) : "";
        f.status = c.getString(c.getColumnIndexOrThrow("status"));
        f.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return f;
    }

    private static String optionsToJson(List<String> options) {        org.json.JSONArray arr = new org.json.JSONArray();
        if (options != null) for (String o : options) if (o != null && !o.trim().isEmpty()) arr.put(o.trim());
        return arr.toString();
    }

    private static List<String> optionsFromJson(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        } catch (Exception ignored) { }
        return out;
    }

    public void addGmPendingQuestion(long roomId, long memberId, String asker, String question,
                                     List<String> options, boolean multi) {
        ensureGmPendingQuestionsTable();

        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION_CHARS) {
            android.util.Log.w("VC_DB", "丢弃异常提问（长度 " + q.length() + "，上限 " + MAX_QUESTION_CHARS
                    + "）：" + (q.length() > 80 ? q.substring(0, 80) + "…" : q));
            return;
        }
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("member_id", memberId);
        v.put("asker", asker == null ? "" : asker);
        v.put("question", q);
        v.put("options", optionsToJson(options));
        v.put("multi", multi ? 1 : 0);
        v.put("status", "pending");
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("gm_pending_questions", null, v);
    }

    private static final int MAX_QUESTION_CHARS = 400;

    private List<GmInquiryItem> queryPendingQuestions(long roomId, String where, String[] args) {
        ensureGmPendingQuestionsTable();
        List<GmInquiryItem> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("gm_pending_questions",
                    new String[]{"asker", "question", "options", "multi"},
                    where, args, null, null, "id ASC");
            while (c.moveToNext()) {
                GmInquiryItem it = new GmInquiryItem();
                it.asker = c.getString(0);
                it.question = c.getString(1);
                it.options = optionsFromJson(c.getString(2));
                it.multi = c.getInt(3) == 1;
                out.add(it);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "queryPendingQuestions 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public List<GmInquiryItem> listGmPendingQuestions(long roomId) {
        return queryPendingQuestions(roomId, "room_id=? AND status='pending'", new String[]{String.valueOf(roomId)});
    }

    public List<GmInquiryItem> listGmUnansweredQuestions(long roomId) {
        return queryPendingQuestions(roomId, "room_id=? AND status<>'answered'", new String[]{String.valueOf(roomId)});
    }

    public int countGmUnansweredQuestions(long roomId) {
        ensureGmPendingQuestionsTable();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM gm_pending_questions WHERE room_id=? AND status<>'answered'",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public long sumGmRoomTokens(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COALESCE(SUM(tokens),0) FROM gm_messages"
                            + " WHERE room_id=? AND (kind IS NULL OR kind <> 'system')",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "sumGmRoomTokens 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0L;
    }
    public void markGmPendingQuestionsAsked(long roomId) {
        ensureGmPendingQuestionsTable();
        ContentValues v = new ContentValues();
        v.put("status", "asked");
        getWritableDatabase().update("gm_pending_questions", v,
                "room_id=? AND status='pending'", new String[]{String.valueOf(roomId)});
    }

    public void markGmQuestionsAnswered(long roomId) {
        ensureGmPendingQuestionsTable();
        ContentValues v = new ContentValues();
        v.put("status", "answered");
        getWritableDatabase().update("gm_pending_questions", v,
                "room_id=? AND status<>'answered'", new String[]{String.valueOf(roomId)});
    }

    public void clearGmPendingQuestions(long roomId) {
        ensureGmPendingQuestionsTable();
        try {
            getWritableDatabase().delete("gm_pending_questions", "room_id=?", new String[]{String.valueOf(roomId)});
        } catch (Exception ignored) { }
    }

    public static class GmBasicInfo {
        public long id;
        public String title;
        public String content;
        public String category;
        public int sortOrder;
        public long createdAt;
        public long updatedAt;
    }

    public List<GmBasicInfo> listGmBasicInfo() {
        List<GmBasicInfo> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, title, content, category, sort_order, created_at, updated_at"
                            + " FROM gm_basic_info ORDER BY sort_order ASC, id ASC", null);
            while (c.moveToNext()) {
                GmBasicInfo b = new GmBasicInfo();
                b.id = c.getLong(0);
                b.title = c.getString(1);
                b.content = c.getString(2);
                b.category = c.getString(3);
                b.sortOrder = c.getInt(4);
                b.createdAt = c.getLong(5);
                b.updatedAt = c.getLong(6);
                out.add(b);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmBasicInfo 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public long insertGmBasicInfo(String title, String content, String category) {
        try {
            ContentValues v = new ContentValues();
            v.put("title", title == null ? "" : title.trim());
            v.put("content", content == null ? "" : content.trim());
            v.put("category", category == null ? "" : category.trim());
            v.put("created_at", System.currentTimeMillis());
            v.put("updated_at", System.currentTimeMillis());
            return getWritableDatabase().insert("gm_basic_info", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "insertGmBasicInfo 失败: " + e.getMessage());
            return -1L;
        }
    }

    public void updateGmBasicInfo(long id, String title, String content, String category) {
        try {
            ContentValues v = new ContentValues();
            v.put("title", title == null ? "" : title.trim());
            v.put("content", content == null ? "" : content.trim());
            v.put("category", category == null ? "" : category.trim());
            v.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().update("gm_basic_info", v, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "updateGmBasicInfo 失败: " + e.getMessage());
        }
    }

    public void deleteGmBasicInfo(long id) {
        try {
            getWritableDatabase().delete("gm_basic_info", "id=?", new String[]{String.valueOf(id)});
        } catch (Exception ignored) { }
    }

    public java.util.Set<Long> getInjectedBasicInfoIds(long roomId) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT injected_basic_info FROM gm_rooms WHERE id=?",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null) {
                    for (String p : s.split(",")) {
                        try { long v = Long.parseLong(p.trim()); if (v > 0) out.add(v); }
                        catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "getInjectedBasicInfoIds 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public void setInjectedBasicInfoIds(long roomId, java.util.Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (Long id : ids) {
                if (id == null || id <= 0) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(id);
            }
        }
        try {
            ContentValues v = new ContentValues();
            v.put("injected_basic_info", sb.length() == 0 ? null : sb.toString());
            getWritableDatabase().update("gm_rooms", v, "id=?", new String[]{String.valueOf(roomId)});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "setInjectedBasicInfoIds 失败: " + e.getMessage());
        }
    }

    public List<GmBasicInfo> listInjectedBasicInfo(long roomId) {
        java.util.Set<Long> ids = getInjectedBasicInfoIds(roomId);
        List<GmBasicInfo> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        for (GmBasicInfo b : listGmBasicInfo()) {
            if (ids.contains(b.id)) out.add(b);
        }
        return out;
    }

    public static class GmQaPair {
        public long id;
        public long roomId;
        public String roomTitle;
        public String roomGoal;
        public String asker;
        public String question;
        public String answer;
        public boolean answered;
        public long createdAt;
        public long answeredAt;
    }

    public long insertGmQaArchive(long roomId, String roomTitle, String roomGoal,
                                  String asker, String question, List<String> options,
                                  String answer, long askedAt) {
        if (question == null || question.trim().isEmpty()) return -1L;

        if (answer == null || answer.trim().isEmpty()) return -1L;
        String q = question.trim();
        try {
            boolean hasAns = answer != null && !answer.trim().isEmpty();

            if (gmQaArchiveExists(roomId, q)) {
               updateGmQaArchiveAnswer(roomId, q, answer);
               return 0L;
            }
            ContentValues v = new ContentValues();
            v.put("room_id", roomId);
            v.put("room_title", roomTitle == null ? "" : roomTitle);
            v.put("room_goal", roomGoal == null ? "" : roomGoal);
            v.put("asker", asker == null ? "" : asker);
            v.put("question", q);
            v.put("options", optionsToJson(options));
            v.put("answer", hasAns ? answer.trim() : "");
            v.put("answered", hasAns ? 1 : 0);
            v.put("created_at", askedAt > 0 ? askedAt : System.currentTimeMillis());
            v.put("answered_at", hasAns ? System.currentTimeMillis() : 0L);
            return getWritableDatabase().insert("gm_qa_archive", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "insertGmQaArchive 失败: " + e.getMessage());
            return -1L;
        }
    }

    public boolean gmQaArchiveExists(long roomId, String question) {
        if (question == null || question.trim().isEmpty()) return false;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM gm_qa_archive WHERE room_id=? AND question=?",
                    new String[]{String.valueOf(roomId), question.trim()});
            if (c.moveToFirst()) return c.getInt(0) > 0;
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return false;
    }

    public void updateGmQaArchiveAnswer(long roomId, String question, String answer) {
        if (question == null || question.trim().isEmpty()) return;
        try {
            ContentValues v = new ContentValues();
            boolean hasAns = answer != null && !answer.trim().isEmpty();
            v.put("answer", hasAns ? answer.trim() : "");
            v.put("answered", hasAns ? 1 : 0);
            v.put("answered_at", hasAns ? System.currentTimeMillis() : 0L);
            getWritableDatabase().update("gm_qa_archive", v,
                    "room_id=? AND question=?",
                    new String[]{String.valueOf(roomId), question.trim()});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "updateGmQaArchiveAnswer 失败: " + e.getMessage());
        }
    }

    public List<GmQaPair> listGmQaArchive() {
        return queryGmQaArchive("SELECT id, room_id, room_title, room_goal, asker, question,"
                + " answer, answered, created_at, answered_at FROM gm_qa_archive"
                + " ORDER BY room_id DESC, id ASC", null);
    }

    public List<GmQaPair> listGmQaArchiveByRoom(long roomId) {
        return queryGmQaArchive("SELECT id, room_id, room_title, room_goal, asker, question,"
                + " answer, answered, created_at, answered_at FROM gm_qa_archive"
                + " WHERE room_id=? ORDER BY id ASC", new String[]{String.valueOf(roomId)});
    }

    private List<GmQaPair> queryGmQaArchive(String sql, String[] args) {
        List<GmQaPair> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(sql, args);
            while (c.moveToNext()) {
                GmQaPair p = new GmQaPair();
                p.id = c.getLong(0);
                p.roomId = c.getLong(1);
                p.roomTitle = c.getString(2);
                p.roomGoal = c.getString(3);
                p.asker = c.getString(4);
                p.question = c.getString(5);
                p.answer = c.getString(6);
                p.answered = c.getInt(7) == 1;
                p.createdAt = c.getLong(8);
                p.answeredAt = c.getLong(9);
                out.add(p);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "queryGmQaArchive 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public int countGmQaArchive() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM gm_qa_archive", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public int countGmQaArchiveRooms() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(DISTINCT room_id) FROM gm_qa_archive", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public static class GmQaRoomBrief {
        public long roomId;
        public String roomTitle;
        public String roomGoal;
        public int pairCount;
        public int answeredCount;
        public long lastAt;
    }

    public List<GmQaRoomBrief> searchGmQaRooms(String keyword, int limit) {
        List<GmQaRoomBrief> out = new ArrayList<>();
        Cursor c = null;
        try {
            String kw = keyword == null ? "" : keyword.trim();
            String sql = "SELECT room_id, room_title, room_goal,"
                    + " COUNT(*) AS cnt,"
                    + " SUM(CASE WHEN answered=1 THEN 1 ELSE 0 END) AS ans,"
                    + " MAX(created_at) AS lastAt"
                    + " FROM gm_qa_archive";
            String[] args = null;
            if (!kw.isEmpty()) {
                sql += " WHERE room_title LIKE ? OR room_goal LIKE ?";
                String like = "%" + kw + "%";
                args = new String[]{like, like};
            }
            sql += " GROUP BY room_id, room_title, room_goal"
                 + " ORDER BY lastAt DESC LIMIT " + Math.max(1, Math.min(limit, 200));
            c = getReadableDatabase().rawQuery(sql, args);
            while (c.moveToNext()) {
                GmQaRoomBrief b = new GmQaRoomBrief();
                b.roomId = c.getLong(0);
                b.roomTitle = c.getString(1);
                b.roomGoal = c.getString(2);
                b.pairCount = c.getInt(3);
                b.answeredCount = c.getInt(4);
                b.lastAt = c.getLong(5);
                out.add(b);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "searchGmQaRooms 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public List<GmQaPair> fetchGmQaPairs(long roomId, String keyword, int offset, int limit) {
        List<GmQaPair> out = new ArrayList<>();
        Cursor c = null;
        try {
            String sql = "SELECT id, room_id, room_title, room_goal, asker, question,"
                    + " answer, answered, created_at, answered_at FROM gm_qa_archive WHERE room_id=?";
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(roomId));
            String kw = keyword == null ? "" : keyword.trim();
            if (!kw.isEmpty()) {
                sql += " AND (question LIKE ? OR answer LIKE ?)";
                String like = "%" + kw + "%";
                args.add(like);
                args.add(like);
            }
            sql += " ORDER BY id ASC LIMIT " + Math.max(1, Math.min(limit, 100))
                 + " OFFSET " + Math.max(0, offset);
            c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            while (c.moveToNext()) {
                GmQaPair p = new GmQaPair();
                p.id = c.getLong(0);
                p.roomId = c.getLong(1);
                p.roomTitle = c.getString(2);
                p.roomGoal = c.getString(3);
                p.asker = c.getString(4);
                p.question = c.getString(5);
                p.answer = c.getString(6);
                p.answered = c.getInt(7) == 1;
                p.createdAt = c.getLong(8);
                p.answeredAt = c.getLong(9);
                out.add(p);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "fetchGmQaPairs 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public int countGmQaPairs(long roomId, String keyword) {
        Cursor c = null;
        try {
            String sql = "SELECT COUNT(*) FROM gm_qa_archive WHERE room_id=?";
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(roomId));
            String kw = keyword == null ? "" : keyword.trim();
            if (!kw.isEmpty()) {
                sql += " AND (question LIKE ? OR answer LIKE ?)";
                String like = "%" + kw + "%";
                args.add(like);
                args.add(like);
            }
            c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public String demandTextOfRoom(long roomId) {
        try {
            GmRoom r = getGmRoom(roomId);
            if (r != null && r.goalText != null && !r.goalText.trim().isEmpty()) {
                return r.goalText.trim();
            }
        } catch (Exception ignored) { }
        Cursor c = null;
        try {

            c = getReadableDatabase().rawQuery(
                    "SELECT content FROM gm_messages WHERE room_id=? AND (member_id=0 OR speaker='我')"
                            + " AND kind='chat' ORDER BY id ASC LIMIT 1",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null) {
                    s = s.trim();
                    if (s.startsWith("需求：")) s = s.substring(3).trim();
                    else if (s.startsWith("需求:")) s = s.substring(3).trim();
                    return s;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return "";
    }

    public int backfillGmQaArchiveFromInquiries() {
        int inserted = 0;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, room_id FROM gm_inquiries WHERE status='answered' ORDER BY room_id ASC, id ASC", null);
            List<long[]> rows = new ArrayList<>();
            while (c.moveToNext()) rows.add(new long[]{c.getLong(0), c.getLong(1)});
            c.close();
            c = null;

            java.util.HashMap<Long, String> demandCache = new java.util.HashMap<>();
            java.util.HashMap<Long, String> titleCache = new java.util.HashMap<>();
            for (long[] row : rows) {
                long inqId = row[0], roomId = row[1];
                GmInquiry inq = getGmInquiry(inqId);
                if (inq == null || inq.items == null) continue;
                String title = titleCache.get(roomId);
                if (title == null) {
                    GmRoom r = getGmRoom(roomId);
                    title = (r == null || r.title == null) ? "" : r.title;
                    titleCache.put(roomId, title);
                    demandCache.put(roomId, demandTextOfRoom(roomId));
                }
                String demand = demandCache.get(roomId);
                for (GmInquiryItem x : inq.items) {
                    if (x == null || x.question == null || x.question.trim().isEmpty()) continue;
                    if (x.answer == null || x.answer.trim().isEmpty()) continue;
                    String q = x.question.trim();
                    if (gmQaArchiveExists(roomId, q)) continue;
                    long id = insertGmQaArchive(roomId, title, demand, x.asker, q,
                            x.options, x.answer, inq.createdAt);
                    if (id > 0L) ++inserted;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "补档用户信息库失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        if (inserted > 0) {
            android.util.Log.i("VC_DB", "补档用户信息库：新增 " + inserted + " 组历史问答");
        }
        return inserted;
    }

    public static class GmCitation {
        public long id;
        public long roomId;
        public String memberName;
        public long demandRoomId;
        public String demandTitle;
        public String demandGoal;
        public long qaArchiveId;
        public String asker;
        public String question;
        public String answer;
        public long createdAt;
        public boolean isRead;
    }

    public void insertGmCitation(long roomId, String memberName, long demandRoomId,
                                 String demandTitle, String demandGoal, long qaArchiveId,
                                 String asker, String question, String answer) {
        if (roomId <= 0L || question == null || question.trim().isEmpty()) return;
        String q = question.trim();
        try {
            ContentValues v = new ContentValues();
            v.put("room_id", roomId);
            v.put("member_name", memberName == null ? "" : memberName);
            v.put("demand_room_id", demandRoomId);
            v.put("demand_title", demandTitle == null ? "" : demandTitle);
            v.put("demand_goal", demandGoal == null ? "" : demandGoal);
            v.put("qa_archive_id", qaArchiveId);
            v.put("asker", asker == null ? "" : asker);
            v.put("question", q);
            v.put("answer", answer == null ? "" : answer);
            v.put("created_at", System.currentTimeMillis());
            v.put("is_read", 0);
            int n = getWritableDatabase().update("gm_citations", v,
                    "room_id=? AND demand_room_id=? AND question=?",
                    new String[]{String.valueOf(roomId), String.valueOf(demandRoomId), q});
            if (n == 0) {
                getWritableDatabase().insert("gm_citations", null, v);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "insertGmCitation 失败: " + e.getMessage());
        }
    }

    public int countUnreadGmCitations(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM gm_citations WHERE room_id=? AND is_read=0",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public List<GmCitation> listGmCitations(long roomId) {
        List<GmCitation> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, room_id, member_name, demand_room_id, demand_title, demand_goal,"
                            + " qa_archive_id, asker, question, answer, created_at, is_read"
                            + " FROM gm_citations WHERE room_id=?"
                            + " ORDER BY demand_room_id DESC, id ASC",
                    new String[]{String.valueOf(roomId)});
            while (c.moveToNext()) {
                GmCitation x = new GmCitation();
                x.id = c.getLong(0);
                x.roomId = c.getLong(1);
                x.memberName = c.getString(2);
                x.demandRoomId = c.getLong(3);
                x.demandTitle = c.getString(4);
                x.demandGoal = c.getString(5);
                x.qaArchiveId = c.getLong(6);
                x.asker = c.getString(7);
                x.question = c.getString(8);
                x.answer = c.getString(9);
                x.createdAt = c.getLong(10);
                x.isRead = c.getInt(11) == 1;
                out.add(x);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmCitations 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public int countGmCitationDemands(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(DISTINCT demand_room_id) FROM gm_citations WHERE room_id=?",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0;
    }

    public void markGmCitationsRead(long roomId) {
        try {
            ContentValues v = new ContentValues();
            v.put("is_read", 1);
            getWritableDatabase().update("gm_citations", v, "room_id=?",
                    new String[]{String.valueOf(roomId)});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "markGmCitationsRead 失败: " + e.getMessage());
        }
    }

    public static class GmAgentMemory {
        public String content = "";

        public boolean dropRaw = false;
    }

    public GmAgentMemory getGmAgentMemory(long roomId, long memberId) {
        GmAgentMemory out = new GmAgentMemory();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT content, drop_raw FROM gm_agent_memory WHERE room_id=? AND member_id=?",
                    new String[]{String.valueOf(roomId), String.valueOf(memberId)});
            if (c.moveToFirst()) {
                String s = c.getString(0);
                out.content = s == null ? "" : s;
                out.dropRaw = c.getInt(1) == 1;
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "getGmAgentMemory 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public boolean setGmAgentMemory(long roomId, long memberId, String content, boolean dropRaw) {
        try {
            String c = content == null ? "" : content;
            int tok = AiClient.estimateTokensText(c);
            if (tok > AiClient.TOOLS_DETAIL_TOKEN_CAP) {
                android.util.Log.w("VC_DB", "压缩结论超资料额度被拒：" + tok + " > "
                        + AiClient.TOOLS_DETAIL_TOKEN_CAP);
                return false;
            }
            ContentValues v = new ContentValues();
            v.put("room_id", roomId);
            v.put("member_id", memberId);
            v.put("content", c);
            v.put("drop_raw", dropRaw ? 1 : 0);
            v.put("updated_at", System.currentTimeMillis());
            getWritableDatabase().insertWithOnConflict("gm_agent_memory", null, v,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
            return true;
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "setGmAgentMemory 失败: " + e.getMessage());
            return false;
        }
    }

    public static class GmPoolItem {
        public long id;
        public long roomId;
        public String owner;
        public String sourceTag;
        public String content;
        public long contentLen;
        public int tokens;
        public long createdAt;
    }

    public long insertGmPoolItem(long roomId, String owner, String sourceTag, String content) {
        if (content == null || content.trim().isEmpty()) return -1L;
        String c = content.trim();
        try {

            Cursor chk = null;
            try {
                chk = getReadableDatabase().rawQuery(
                        "SELECT id FROM gm_material_pool WHERE room_id=? AND content=? LIMIT 1",
                        new String[]{String.valueOf(roomId), c});
                if (chk.moveToFirst()) return chk.getLong(0);
            } finally {
                if (chk != null) try { chk.close(); } catch (Exception ignored) { }
            }
            ContentValues v = new ContentValues();
            v.put("room_id", roomId);
            v.put("owner", owner == null ? "" : owner);
            v.put("source_tag", sourceTag == null ? "" : sourceTag);
            v.put("content", c);
            v.put("content_len", c.length());
            v.put("tokens", AiClient.estimateTokensText(c));
            v.put("created_at", System.currentTimeMillis());
            long newId = getWritableDatabase().insert("gm_material_pool", null, v);

            try {
                getWritableDatabase().execSQL(
                        "DELETE FROM gm_material_pool WHERE room_id=? AND id NOT IN"
                                + " (SELECT id FROM gm_material_pool WHERE room_id=?"
                                + "  ORDER BY id DESC LIMIT " + MATERIAL_POOL_MAX_ITEMS + ")",
                        new Object[]{roomId, roomId});
            } catch (Exception ignored) { }
            return newId;
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "insertGmPoolItem 失败: " + e.getMessage());
            return -1L;
        }
    }

    public long[] poolStats(long roomId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*), COALESCE(SUM(tokens),0) FROM gm_material_pool WHERE room_id=?",
                    new String[]{String.valueOf(roomId)});
            if (c.moveToFirst()) return new long[]{c.getLong(0), c.getLong(1)};
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return new long[]{0L, 0L};
    }

    public List<GmPoolItem> searchGmPool(long roomId, String keyword, int limit) {
        List<GmPoolItem> out = new ArrayList<>();
        Cursor c = null;
        try {
            String kw = keyword == null ? "" : keyword.trim();
            String sql = "SELECT id, room_id, owner, source_tag,"
                    + " substr(content,1,240) AS head, content_len, tokens, created_at"
                    + " FROM gm_material_pool WHERE room_id=?";
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(roomId));
            if (!kw.isEmpty()) {
                sql += " AND content LIKE ?";
                args.add("%" + kw + "%");
            }
            sql += " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 200));
            c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            while (c.moveToNext()) {
                GmPoolItem it = new GmPoolItem();
                it.id = c.getLong(0);
                it.roomId = c.getLong(1);
                it.owner = c.getString(2);
                it.sourceTag = c.getString(3);
                it.content = c.getString(4);
                it.contentLen = c.getLong(5);
                it.tokens = c.getInt(6);
                it.createdAt = c.getLong(7);
                out.add(it);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "searchGmPool 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public String fetchGmPoolContent(long roomId, long id, int offsetChars, int maxChars) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT content FROM gm_material_pool WHERE id=? AND room_id=?",
                    new String[]{String.valueOf(id), String.valueOf(roomId)});
            if (!c.moveToFirst()) return null;
            String all = c.getString(0);
            if (all == null) return "";
            int off = Math.max(0, offsetChars);
            if (off >= all.length()) return "";
            int len = Math.max(1, Math.min(maxChars <= 0 ? 6000 : maxChars, all.length() - off));
            return all.substring(off, off + len);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "fetchGmPoolContent 失败: " + e.getMessage());
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    public long gmPoolContentLen(long roomId, long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT content_len FROM gm_material_pool WHERE id=? AND room_id=?",
                    new String[]{String.valueOf(id), String.valueOf(roomId)});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    private void createScheduledTasksTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_tasks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT,"
                + "prompt TEXT NOT NULL,"
                + "schedule_type INTEGER DEFAULT 1,"
                + "time_millis INTEGER DEFAULT 0,"
                + "day_of_week INTEGER DEFAULT 1,"
                + "conversation_start INTEGER DEFAULT 0,"
                + "enabled INTEGER DEFAULT 1,"
                + "last_run_at INTEGER DEFAULT 0,"
                + "run_count INTEGER DEFAULT 0,"
                + "fail_count INTEGER DEFAULT 0,"
                + "only_if_important INTEGER DEFAULT 0,"
                + "notify INTEGER DEFAULT 1,"

                + "model TEXT,"
                + "base_url TEXT,"
                + "api_key TEXT,"
                + "think_level INTEGER DEFAULT -1,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sched_enabled ON scheduled_tasks(enabled, id)");
    }

    public static class ScheduledTask {
        public long id;
        public String title;
        public String prompt;
        public int scheduleType;
        public long timeMillis;
        public int dayOfWeek;
        public long conversationStart;
        public boolean enabled;
        public long lastRunAt;
        public int runCount;
        public int failCount;
        public boolean onlyIfImportant;
        public boolean notify;

        public String model = "";
        public String baseUrl = "";
        public String apiKey = "";
        public int thinkLevel = -1;
        public long createdAt;
    }

    public long insertScheduledTask(String title, String prompt, int scheduleType, long timeMillis,
                                    int dayOfWeek, long conversationStart, boolean onlyIfImportant, boolean notify) {
        ensureScheduledTasksTable();
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("prompt", prompt);
        v.put("schedule_type", scheduleType);
        v.put("time_millis", timeMillis);
        v.put("day_of_week", dayOfWeek);
        v.put("conversation_start", conversationStart);
        v.put("enabled", 1);
        v.put("last_run_at", 0);
        v.put("run_count", 0);
        v.put("fail_count", 0);
        v.put("only_if_important", onlyIfImportant ? 1 : 0);
        v.put("notify", notify ? 1 : 0);

        v.put("model", "");
        v.put("base_url", "");
        v.put("api_key", "");
        v.put("think_level", -1);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("scheduled_tasks", null, v);
    }

    public List<ScheduledTask> listScheduledTasks(boolean onlyEnabled) {
        ensureScheduledTasksTable();
        List<ScheduledTask> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("scheduled_tasks", null,
                onlyEnabled ? "enabled=1" : null, null, null, null, "id ASC");
        while (c.moveToNext()) out.add(cursorToScheduledTask(c));
        c.close();
        return out;
    }

    public ScheduledTask getScheduledTask(long id) {
        ensureScheduledTasksTable();
        Cursor c = getReadableDatabase().query("scheduled_tasks", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        ScheduledTask t = c.moveToFirst() ? cursorToScheduledTask(c) : null;
        c.close();
        return t;
    }

    public void updateScheduledTask(ScheduledTask t) {
        ensureScheduledTasksTable();
        ContentValues v = new ContentValues();
        v.put("title", t.title);
        v.put("prompt", t.prompt);
        v.put("schedule_type", t.scheduleType);
        v.put("time_millis", t.timeMillis);
        v.put("day_of_week", t.dayOfWeek);
        v.put("only_if_important", t.onlyIfImportant ? 1 : 0);
        v.put("notify", t.notify ? 1 : 0);

        v.put("model", t.model == null ? "" : t.model);
        v.put("base_url", t.baseUrl == null ? "" : t.baseUrl);
        v.put("api_key", t.apiKey == null ? "" : t.apiKey);
        v.put("think_level", t.thinkLevel);
        getWritableDatabase().update("scheduled_tasks", v, "id=?", new String[]{String.valueOf(t.id)});
    }

    public void setScheduledTaskEnabled(long id, boolean enabled) {
        ensureScheduledTasksTable();
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        getWritableDatabase().update("scheduled_tasks", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteScheduledTask(long id) {
        ensureScheduledTasksTable();
        getWritableDatabase().delete("scheduled_tasks", "id=?", new String[]{String.valueOf(id)});
    }

    public void markScheduledTaskRun(long id, boolean success) {
        ensureScheduledTasksTable();
        try {

            if (success) {
                getWritableDatabase().execSQL(
                        "UPDATE scheduled_tasks SET last_run_at=?, run_count=run_count+1, fail_count=0 WHERE id=?",
                        new Object[]{System.currentTimeMillis(), id});
            } else {
                getWritableDatabase().execSQL(
                        "UPDATE scheduled_tasks SET last_run_at=?, run_count=run_count+1, fail_count=fail_count+1 WHERE id=?",
                        new Object[]{System.currentTimeMillis(), id});
            }
        } catch (Exception e) {
            android.util.Log.w("VC_TASK", "markScheduledTaskRun 失败: " + e.getMessage());
        }
    }

    private void ensureScheduledTasksTable() {
        try { createScheduledTasksTable(getWritableDatabase()); } catch (Exception ignored) { }
    }

    private ScheduledTask cursorToScheduledTask(Cursor c) {
        ScheduledTask t = new ScheduledTask();
        t.id = c.getLong(c.getColumnIndexOrThrow("id"));
        t.title = c.getString(c.getColumnIndex("title"));
        t.prompt = c.getString(c.getColumnIndex("prompt"));
        t.scheduleType = c.getInt(c.getColumnIndex("schedule_type"));
        t.timeMillis = c.getLong(c.getColumnIndex("time_millis"));
        t.dayOfWeek = c.getInt(c.getColumnIndex("day_of_week"));
        t.conversationStart = c.getLong(c.getColumnIndex("conversation_start"));
        t.enabled = c.getInt(c.getColumnIndex("enabled")) == 1;
        t.lastRunAt = c.getLong(c.getColumnIndex("last_run_at"));
        t.runCount = c.getInt(c.getColumnIndex("run_count"));
        t.failCount = c.getInt(c.getColumnIndex("fail_count"));
        t.onlyIfImportant = c.getInt(c.getColumnIndex("only_if_important")) == 1;
        t.notify = c.getInt(c.getColumnIndex("notify")) == 1;

        int mi = c.getColumnIndex("model");
        t.model = mi >= 0 ? c.getString(mi) : null;
        int bi = c.getColumnIndex("base_url");
        t.baseUrl = bi >= 0 ? c.getString(bi) : null;
        int ki = c.getColumnIndex("api_key");
        t.apiKey = ki >= 0 ? c.getString(ki) : null;
        int ti = c.getColumnIndex("think_level");
        t.thinkLevel = ti >= 0 ? c.getInt(ti) : -1;
        if (t.model == null) t.model = "";
        if (t.baseUrl == null) t.baseUrl = "";
        if (t.apiKey == null) t.apiKey = "";
        t.createdAt = c.getLong(c.getColumnIndex("created_at"));
        return t;
    }

    private void ensureGmDocsTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_docs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "room_id INTEGER NOT NULL,"
                    + "title TEXT,"
                    + "content TEXT NOT NULL,"
                    + "model_name TEXT,"
                    + "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_docs_room ON gm_docs(room_id, id)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "ensureGmDocsTable 失败: " + e.getMessage());
        }
    }

    private void ensureGmDocsTable() { ensureGmDocsTable(getWritableDatabase()); }

    public static class GmDoc {
        public long id;
        public long roomId;
        public String title;

        public String content;
        public String modelName;
        public long createdAt;

        public long contentLength;
    }

    public long insertGmDoc(long roomId, String title, String content, String modelName) {
        ensureGmDocsTable();
        ContentValues v = new ContentValues();
        v.put("room_id", roomId);
        v.put("title", title);
        v.put("content", content);
        v.put("model_name", modelName);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_docs", null, v);
    }

    public java.util.List<GmDocGroup> listAllGmDocsGrouped() {
        ensureGmDocsTable();
        java.util.List<GmDocGroup> out = new java.util.ArrayList<>();
        java.util.Map<Long, GmDocGroup> byRoom = new java.util.LinkedHashMap<>();
        Cursor c = null;
        try {

            c = getReadableDatabase().rawQuery(
                    "SELECT d.id, d.room_id, d.title, d.model_name, d.created_at, LENGTH(d.content),"
                            + " COALESCE(r.title,'（已删除的群）')"
                            + " FROM gm_docs d LEFT JOIN gm_rooms r ON r.id = d.room_id"
                            + " ORDER BY d.room_id DESC, d.id DESC", null);
            while (c.moveToNext()) {
                long roomId = c.getLong(1);
                GmDocGroup g = byRoom.get(roomId);
                if (g == null) {
                    g = new GmDocGroup();
                    g.roomId = roomId;
                    g.roomTitle = c.getString(6);
                    byRoom.put(roomId, g);
                    out.add(g);
                }
                GmDoc d = new GmDoc();
                d.id = c.getLong(0);
                d.roomId = roomId;
                d.title = c.getString(2);
                d.modelName = c.getString(3);
                d.createdAt = c.getLong(4);
                d.contentLength = c.getLong(5);
                d.content = null;
                g.docs.add(d);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listAllGmDocsGrouped 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public static class GmDocGroup {
        public long roomId;
        public String roomTitle;
        public java.util.List<GmDoc> docs = new java.util.ArrayList<>();
    }

    public java.util.List<GmDoc> listGmDocs(long roomId) {        ensureGmDocsTable();
        java.util.List<GmDoc> out = new java.util.ArrayList<>();

        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id, room_id, title, model_name, created_at, LENGTH(content)"
                            + " FROM gm_docs WHERE room_id=? ORDER BY id DESC",
                    new String[]{String.valueOf(roomId)});
            while (c.moveToNext()) {
                GmDoc d = new GmDoc();
                d.id = c.getLong(0);
                d.roomId = c.getLong(1);
                d.title = c.getString(2);
                d.modelName = c.getString(3);
                d.createdAt = c.getLong(4);
                d.contentLength = c.getLong(5);
                d.content = null;
                out.add(d);
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "listGmDocs 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public GmDoc getGmDoc(long id) {
        ensureGmDocsTable();
        Cursor c = null;
        try {

            c = getReadableDatabase().rawQuery(
                    "SELECT id, room_id, title, model_name, created_at, LENGTH(content), substr(content,1,150000)"
                            + " FROM gm_docs WHERE id=?", new String[]{String.valueOf(id)});
            if (!c.moveToFirst()) return null;
            GmDoc d = new GmDoc();
            d.id = c.getLong(0);
            d.roomId = c.getLong(1);
            d.title = c.getString(2);
            d.modelName = c.getString(3);
            d.createdAt = c.getLong(4);
            d.contentLength = c.getLong(5);
            d.content = c.getString(6);
            return d;
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "getGmDoc 失败: " + e.getMessage());
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    public String getGmDocContent(long id) {
        ensureGmDocsTable();

        final int CHUNK = 150000;
        StringBuilder sb = new StringBuilder();
        long total = 0L;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT LENGTH(content) FROM gm_docs WHERE id=?", new String[]{String.valueOf(id)});
            if (c.moveToFirst()) total = c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        if (total <= 0L) return "";
        for (long off = 1; off <= total; off += CHUNK) {
            Cursor cc = null;
            try {
                cc = getReadableDatabase().rawQuery(
                        "SELECT substr(content," + off + "," + CHUNK + ") FROM gm_docs WHERE id=?",
                        new String[]{String.valueOf(id)});
                if (cc.moveToFirst()) {
                    String part = cc.getString(0);
                    if (part != null) sb.append(part);
                }
            } catch (Exception e) {
                android.util.Log.w("VC_DB", "getGmDocContent 分块读取失败 off=" + off + ": " + e.getMessage());
                break;
            } finally {
                if (cc != null) try { cc.close(); } catch (Exception ignored) { }
            }
        }
        return sb.toString();
    }

    public String getGmDocPreview(long id, int maxChars) {
        ensureGmDocsTable();
        int n = maxChars <= 0 ? 160 : Math.min(maxChars, 200000);
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT substr(content,1," + n + ") FROM gm_docs WHERE id=?",
                    new String[]{String.valueOf(id)});
            if (c.moveToFirst()) {
                String s = c.getString(0);
                return s == null ? "" : s;
            }
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "getGmDocPreview 失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return "";
    }

    public void deleteGmDoc(long id) {
        getWritableDatabase().delete("gm_docs", "id=?", new String[]{String.valueOf(id)});
    }

    private GmDoc cursorToGmDoc(Cursor c) {
        GmDoc d = new GmDoc();
        d.id = c.getLong(c.getColumnIndexOrThrow("id"));
        d.roomId = c.getLong(c.getColumnIndexOrThrow("room_id"));
        d.title = c.getString(c.getColumnIndexOrThrow("title"));
        d.content = c.getString(c.getColumnIndexOrThrow("content"));
        int mi = c.getColumnIndex("model_name");
        d.modelName = mi >= 0 ? c.getString(mi) : "";
        d.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return d;
    }

public static class GmProvider {
        public long id;
        public String name;
        public String baseUrl;
        public String apiKey;
        public String models;
        public long createdAt;
        public List<String> modelList() {
            List<String> out = new ArrayList<>();
            if (models == null) return out;
            for (String s : models.split("\n")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
    }

    public long insertGmProvider(String name, String baseUrl, String apiKey, String models) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("base_url", baseUrl);
        v.put("api_key", apiKey);
        v.put("models", models);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_providers", null, v);
    }

    public void updateGmProvider(GmProvider p) {
        ContentValues v = new ContentValues();
        v.put("name", p.name);
        v.put("base_url", p.baseUrl);
        v.put("api_key", p.apiKey);
        v.put("models", p.models);
        getWritableDatabase().update("gm_providers", v, "id=?", new String[]{String.valueOf(p.id)});
    }

    public void deleteGmProvider(long id) {
        getWritableDatabase().delete("gm_providers", "id=?", new String[]{String.valueOf(id)});
    }

    public List<GmProvider> listGmProviders() {
        List<GmProvider> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("gm_providers", null, null, null, null, null, "id ASC");
        while (c.moveToNext()) {
            GmProvider p = new GmProvider();
            p.id = c.getLong(c.getColumnIndexOrThrow("id"));
            p.name = c.getString(c.getColumnIndexOrThrow("name"));
            p.baseUrl = c.getString(c.getColumnIndexOrThrow("base_url"));
            p.apiKey = c.getString(c.getColumnIndexOrThrow("api_key"));
            p.models = c.getString(c.getColumnIndexOrThrow("models"));
            p.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
            out.add(p);
        }
        c.close();
        return out;
    }

    private void ensureCustomTemplateTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_custom_templates ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT NOT NULL,"
                    + "avatar TEXT,"
                    + "members_json TEXT NOT NULL,"
                    + "room_prompt TEXT,"
                    + "created_at INTEGER NOT NULL)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "ensureCustomTemplateTable 失败: " + e.getMessage());
        }
    }
    private void ensureCustomTemplateTable() { ensureCustomTemplateTable(getWritableDatabase()); }

    public static class GmCustomTemplate {
        public long id;
        public String title;
        public String avatar;
        public String membersJson;
        public String roomPrompt;
        public long createdAt;
    }

    public long insertCustomTemplate(String title, String avatar, String membersJson, String roomPrompt) {
        ensureCustomTemplateTable();
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("avatar", avatar);
        v.put("members_json", membersJson);
        v.put("room_prompt", roomPrompt);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("gm_custom_templates", null, v);
    }

    public List<GmCustomTemplate> listCustomTemplates() {
        ensureCustomTemplateTable();
        List<GmCustomTemplate> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("gm_custom_templates", null, null, null, null, null, "id DESC");
        while (c.moveToNext()) {
            GmCustomTemplate t = new GmCustomTemplate();
            t.id = c.getLong(c.getColumnIndexOrThrow("id"));
            t.title = c.getString(c.getColumnIndexOrThrow("title"));
            t.avatar = c.getString(c.getColumnIndexOrThrow("avatar"));
            t.membersJson = c.getString(c.getColumnIndexOrThrow("members_json"));
            int pi = c.getColumnIndex("room_prompt");
            t.roomPrompt = pi >= 0 ? c.getString(pi) : "";
            t.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
            out.add(t);
        }
        c.close();
        return out;
    }

    public void deleteCustomTemplate(long id) {
        getWritableDatabase().delete("gm_custom_templates", "id=?", new String[]{String.valueOf(id)});
    }

    // ============================================================
    // 感知树（v59，2026-10-13 用户需求）
    // 一棵任意深度的标题树（知识图谱/思维导图骨架）：节点带标题+内容。
    // 删除不落库就是删：整棵子树快照进 perception_archive（防删错可找回）；
    // 归档行只有用户在界面上手动删，助手侧没有这个分支。
    // ============================================================

    public static class PerceptionNode {
        public long id;
        public long parentId;
        public String title;
        public String content;
        public long createdAt;
        public long updatedAt;
    }

    public static class PerceptionArchiveItem {
        public long id;
        public long nodeId;
        public long parentId;
        public String path;
        public String title;
        public String content;
        public String subtreeJson;
        public long deletedAt;
    }

    private void createPerceptionTables(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS perception_nodes ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "parent_id INTEGER DEFAULT 0,"
                    + "title TEXT NOT NULL,"
                    + "content TEXT DEFAULT '',"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_perception_parent ON perception_nodes(parent_id)");

            db.execSQL("CREATE TABLE IF NOT EXISTS perception_archive ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "node_id INTEGER DEFAULT 0,"
                    + "parent_id INTEGER DEFAULT 0,"
                    + "path TEXT,"
                    + "title TEXT,"
                    + "content TEXT,"
                    + "subtree_json TEXT,"
                    + "deleted_at INTEGER NOT NULL)");
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "建感知树表失败: " + e.getMessage());
        }
    }

    public long insertPerceptionNode(long parentId, String title, String content) {
        ContentValues v = new ContentValues();
        v.put("parent_id", parentId);
        v.put("title", title == null ? "" : title);
        v.put("content", content == null ? "" : content);
        long now = System.currentTimeMillis();
        v.put("created_at", now);
        v.put("updated_at", now);
        try {
            return getWritableDatabase().insert("perception_nodes", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "写感知树节点失败: " + e.getMessage());
            return -1;
        }
    }

    public List<PerceptionNode> listPerceptionNodes() {
        List<PerceptionNode> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("perception_nodes", null, null, null, null, null, "id ASC");
            while (c.moveToNext()) out.add(cursorToPerceptionNode(c));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读感知树失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public PerceptionNode getPerceptionNode(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query("perception_nodes", null, "id=?",
                    new String[]{String.valueOf(id)}, null, null, null, "1");
            return c.moveToFirst() ? cursorToPerceptionNode(c) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    public void updatePerceptionNode(long id, String title, String content) {
        ContentValues v = new ContentValues();
        if (title != null) v.put("title", title);
        if (content != null) v.put("content", content);
        v.put("updated_at", System.currentTimeMillis());
        try {
            getWritableDatabase().update("perception_nodes", v, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "改感知树失败: " + e.getMessage());
        }
    }

    public void deletePerceptionNode(long id) {
        try {
            getWritableDatabase().delete("perception_nodes", "id=?", new String[]{String.valueOf(id)});
        } catch (Exception ignored) { }
    }

    public long insertPerceptionArchive(long nodeId, long parentId, String path, String title,
                                        String content, String subtreeJson) {
        ContentValues v = new ContentValues();
        v.put("node_id", nodeId);
        v.put("parent_id", parentId);
        v.put("path", path);
        v.put("title", title);
        v.put("content", content);
        v.put("subtree_json", subtreeJson);
        v.put("deleted_at", System.currentTimeMillis());
        try {
            return getWritableDatabase().insert("perception_archive", null, v);
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "写感知树归档失败: " + e.getMessage());
            return -1;
        }
    }

    public List<PerceptionArchiveItem> listPerceptionArchive(int limit) {
        return listPerceptionArchive(limit, 0);
    }

    /** offset 用于"载入更早"：归档无上限，界面一次渲染不完，要能往后翻。 */
    public List<PerceptionArchiveItem> listPerceptionArchive(int limit, int offset) {
        List<PerceptionArchiveItem> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("perception_archive", null, null, null, null, null,
                    "deleted_at DESC", Math.max(0, offset) + "," + Math.max(1, limit));
            while (c.moveToNext()) out.add(cursorToPerceptionArchive(c));
        } catch (Exception e) {
            android.util.Log.w("VC_DB", "读感知树归档失败: " + e.getMessage());
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    public PerceptionArchiveItem getPerceptionArchive(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query("perception_archive", null, "id=?",
                    new String[]{String.valueOf(id)}, null, null, null, "1");
            return c.moveToFirst() ? cursorToPerceptionArchive(c) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    public int countPerceptionArchive() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM perception_archive", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) { }
        }
    }

    /** 只有用户界面上的手动删除（二次确认后）会调它；助手侧没有这条路径。 */
    public void deletePerceptionArchiveRow(long id) {
        try {
            getWritableDatabase().delete("perception_archive", "id=?", new String[]{String.valueOf(id)});
        } catch (Exception ignored) { }
    }

    private PerceptionNode cursorToPerceptionNode(Cursor c) {
        PerceptionNode n = new PerceptionNode();
        n.id = c.getLong(c.getColumnIndexOrThrow("id"));
        n.parentId = c.getLong(c.getColumnIndexOrThrow("parent_id"));
        n.title = c.getString(c.getColumnIndexOrThrow("title"));
        n.content = c.getString(c.getColumnIndexOrThrow("content"));
        n.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        n.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return n;
    }

    private PerceptionArchiveItem cursorToPerceptionArchive(Cursor c) {
        PerceptionArchiveItem a = new PerceptionArchiveItem();
        a.id = c.getLong(c.getColumnIndexOrThrow("id"));
        a.nodeId = c.getLong(c.getColumnIndexOrThrow("node_id"));
        a.parentId = c.getLong(c.getColumnIndexOrThrow("parent_id"));
        a.path = c.getString(c.getColumnIndexOrThrow("path"));
        a.title = c.getString(c.getColumnIndexOrThrow("title"));
        a.content = c.getString(c.getColumnIndexOrThrow("content"));
        a.subtreeJson = c.getString(c.getColumnIndexOrThrow("subtree_json"));
        a.deletedAt = c.getLong(c.getColumnIndexOrThrow("deleted_at"));
        return a;
    }
}
