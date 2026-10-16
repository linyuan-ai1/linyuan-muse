package com.linyuan.app;

import android.content.Context;

public final class AssistantMemory {

    private AssistantMemory() { }

    public static final String PREFIX = "asst:";

    public static final String STORE_ORDER = PREFIX + "order";

    public static final String STORE_EPISODE = PREFIX + "episode";

    public static final String STORE_JUDGE = PREFIX + "judge";

    public static final String STORE_SELF = PREFIX + "self";

    public static final String STORE_PROFILE = PREFIX + "profile";

    public static final String STORE_NOTES = PREFIX + "notes";

    private static final String MIGRATED_KEY = "asst_partition_migrated_v1";

    public static boolean isAssistantStore(String store) {
        return store != null && store.startsWith(PREFIX);
    }

    public static boolean isUserStore(String store) {
        return store == null || !store.startsWith(PREFIX);
    }

    public static String label(String store) {
        if (STORE_ORDER.equals(store)) return "助手·序";
        if (STORE_EPISODE.equals(store)) return "助手·事";
        if (STORE_JUDGE.equals(store)) return "助手·判";
        if (STORE_SELF.equals(store)) return "助手·自述";
        if (STORE_PROFILE.equals(store)) return "助手·画像";
        if (STORE_NOTES.equals(store)) return "助手·笔记";
        return "助手记忆";
    }

    public static String tag(String store) {
        if (STORE_ORDER.equals(store)) return "🗂 序";
        if (STORE_EPISODE.equals(store)) return "📋 事";
        if (STORE_JUDGE.equals(store)) return "⚖️ 判";
        if (STORE_SELF.equals(store)) return "🪞 自述";
        if (STORE_PROFILE.equals(store)) return "👤 画像";
        if (STORE_NOTES.equals(store)) return "📒 笔记";
        return "";
    }

    public static void migrateLegacy(Context c, AppDatabase db) {
        try {
            android.content.SharedPreferences sp =
                    c.getSharedPreferences(SubAgentStore.PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean(MIGRATED_KEY, false)) return;
            int moved = 0;
            moved += move(db, STORE_PROFILE, STORE_ORDER, "偏好");
            moved += move(db, STORE_NOTES, STORE_ORDER, "经验");
            sp.edit().putBoolean(MIGRATED_KEY, true).apply();
            if (moved > 0) {
                android.util.Log.i("VC_MEM", "记忆分区迁移：画像/笔记 → 序，共 " + moved + " 条");
            }
        } catch (Throwable t) {
            android.util.Log.w("VC_MEM", "记忆分区迁移失败: " + t.getMessage());
        }
    }

    private static int move(AppDatabase db, String from, String to, String category) {
        try {
            java.util.List<AppDatabase.MemoryItem> old = db.listMemoriesByStore(from);
            int n = 0;
            for (AppDatabase.MemoryItem it : old) {
                db.reassignMemoryStore(it.id, to, category);
                n++;
            }
            return n;
        } catch (Throwable t) {
            android.util.Log.w("VC_MEM", "迁移 " + from + " → " + to + " 失败: " + t.getMessage());
            return 0;
        }
    }
}
