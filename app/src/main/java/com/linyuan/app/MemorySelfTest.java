package com.linyuan.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public class MemorySelfTest extends android.content.BroadcastReceiver {

    @Override
    public void onReceive(Context context, android.content.Intent intent) {
        final Context ctx = context.getApplicationContext();

        boolean debuggable;
        try {
            debuggable = (ctx.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            debuggable = false;
        }
        if (!debuggable) {
            android.util.Log.w("VC_SELFTEST", "自检只在 debug 构建可用，已忽略");
            return;
        }
        new Thread(() -> {
            try {
                AppSettings s = AutoReplyService.loadSettingsHeadless(ctx);
                AppDatabase db = new AppDatabase(ctx);
                StringBuilder sb = new StringBuilder();
                AutoReplyService.injectLongTermMemoryForTest(sb, db, s);
                String out = sb.toString();

                android.util.Log.i("VC_SELFTEST", "===== 长期记忆注入自检 =====");
                android.util.Log.i("VC_SELFTEST", "memoryEnabled=" + s.memoryEnabled
                        + " entityEnabled=" + s.entityEnabled);

                String[] titles = {"【长期记忆】", "【你自己的笔记】",
                        "【实体记忆（你认识的人 / 地点 / 项目等）】", "【知识库片段"};
                for (int i = 0; i < titles.length; i++) {
                    String title = titles[i];
                    int start = out.indexOf(title);
                    if (start < 0) {
                        android.util.Log.i("VC_SELFTEST", "✗ 没注入 " + title);
                        continue;
                    }

                    int end = out.length();
                    for (String t2 : titles) {
                        int p = out.indexOf(t2, start + title.length());
                        if (p > 0 && p < end) end = p;
                    }
                    String section = out.substring(start, end);
                    int lines = 0;
                    for (String ln : section.split("\n")) {
                        if (ln.startsWith("- ")) lines++;
                    }
                    android.util.Log.i("VC_SELFTEST", "✔ " + title + " → **" + lines + " 条**，"
                            + section.length() + " 字符");
                }
                android.util.Log.i("VC_SELFTEST", "总长度=" + out.length() + " 字符");

                android.util.Log.i("VC_SELFTEST", "---- search_memory 实测 ----");

                try {
                    java.util.List<AppDatabase.MemoryItem> mems =
                            db.listAllMemoriesWithEmbeddings(5000);
                    int withVec = 0;
                    for (AppDatabase.MemoryItem m : mems) if (m.embedding != null) withVec++;
                    android.util.Log.i("VC_SELFTEST", "记忆向量覆盖率： " + withVec + "/" + mems.size());
                } catch (Throwable t) {
                    android.util.Log.w("VC_SELFTEST", "统计覆盖率失败: " + t);
                }

                String custom = intent.getStringExtra("q");
                String[] queries = (custom != null && !custom.trim().isEmpty())
                        ? new String[]{custom.trim()}
                        : new String[]{"用户", "手机"};
                for (String q : queries) {
                    try {
                        long t0 = System.currentTimeMillis();
                        String res = AutoReplyService.searchMemoryForTest(ctx, q, 6);
                        long ms = System.currentTimeMillis() - t0;
                        android.util.Log.i("VC_SELFTEST", "查「" + q + "」→ " + ms + "ms："
                                + (res.length() > 700 ? res.substring(0, 700) + "…" : res));
                    } catch (Throwable t) {
                        android.util.Log.w("VC_SELFTEST", "查「" + q + "」失败: " + t);
                    }
                }
                db.close();

                android.util.Log.i("VC_SELFTEST", "---- 前 1500 字 ----\n"
                        + (out.length() > 1500 ? out.substring(0, 1500) : out));
                db.close();
            } catch (Throwable t) {
                android.util.Log.w("VC_SELFTEST", "自检失败: " + t);
            }
        }, "memselftest").start();
    }
}
