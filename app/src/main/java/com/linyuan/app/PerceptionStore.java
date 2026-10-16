package com.linyuan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「感知树」的数据操作、工具实现与上下文骨架（2026-10-13 用户需求）。
 *
 * <p>一张任意深度的标题树（手机知识图谱）：每个节点 = 标题 + 内容。规则来自用户：
 * <ul>
 *   <li>删除不真的丢：整棵子树快照进 perception_archive，界面里可以找回（恢复）。</li>
 *   <li>归档无上限、无截断，只有用户能在界面上手动彻底删除；助手侧没有这条路径。</li>
 *   <li>上下文里只注入标题骨架（主标题/子标题），细节靠 perception 工具按 id 查。</li>
 *   <li>记录内容一律英文（省 token），专有名词保留原文。</li>
 * </ul>
 */
public final class PerceptionStore {

    private PerceptionStore() { }

    /**
     * 骨架注入的节点上限（用户 2026-10-13：800 太少，提到 5000）。
     * 它是"数据随时可查"，注入只是标题骨架，占用小。
     * 超了仍带一行可见说明（绝不静默截断），可用 tree parent=<id> 逐支查。
     */
    private static final int SKELETON_MAX_NODES = 5000;

    // ------------------------------------------------------------
    // 建档标准：《感知档案通用框架 v1.0》
    // ------------------------------------------------------------

    /**
     * 框架节点的标题（树根上的一个普通节点）。
     * 用户 2026-10-13 定案的投放方式：**全文不常驻上下文**，只在三个时机露面：
     * ① 模型主动 read 这个节点；② add 创建根级节点（新域）时随工具返回附上全文；
     * ③ 每轮骨架块里只有一行 ~30 token 的路标指向它。
     */
    public static final String FRAMEWORK_TITLE = "_framework";

    /**
     * 《感知档案通用框架 v1.0》全文英译（用户要求：原样质量保留、不缩减精简；
     * 中译英为省 token）。它是数据不是代码：种进树里、随备份走、用户可在界面上改——改了即新标准。
     * 末尾附「本 App 的套用口径」一节，与正文分开标注，用户可单独删改。
     */
    public static final String FRAMEWORK_TEXT = """
            # Perception Archive Universal Framework v1.0

            Universal = it never enumerates specific apps. One set of abstract rules covers every kind of data on the phone: things never seen before are covered automatically, and the standard itself never needs to change.
            The whole framework is only five rules: 4 data forms, 4-level grammar, one node format, the three filing questions, extension rules.

            ## 1. All data comes in exactly 4 forms

            | Form | What it is | Test | Examples |
            |---|---|---|---|
            | Object | a thing (noun) | "who / what" | person, group, app, device, official account, file |
            | State | a value that changes | "how much / on or off" | balance, battery, a switch, a count |
            | Event | something that happened (has a time) | "what happened" | lending money, a transfer, a meeting, a notification received |
            | Task | something in the future | "when should what be done" | repayment date, reminder, promise |

            Any piece of text belongs to one of them; if one note mixes forms, split it into several entries and file each on its own.

            ## 2. The tree has only 4 levels of grammar

            L1 Domain   - which world the data belongs to (apps.<app> / persons / events / system / device / media / tasks / inbox)
            L2 Category - grouping inside a domain, decided by the data itself (contacts / groups / wallet / battery / ...)
            L3 Instance - the specific one (zhangsan / jiating / balance)
            L4 Aspect   - one facet of an instance (members / history), optional

            ## 3. Every node uses one format

            ### <path> - <English short name>
            - type: object | state | event | task
            - summary: one line (English, stating the current situation)
            - <free attributes>: add whatever is needed, key: value
            - updated: <date> | source: manual|screenshot|agent | confidence: high|medium|low

            Free attributes have NO fixed list - for a person write what that person has (relation / traits / open_items), for a group write what that group has (members / agreements). The framework locks the skeleton, not the fields.

            ## 4. The three filing questions (universal)

            1. Is it something that happened? -> events.<yyyy-mm>.<what>-<who>
            2. Is it an object? -> hang it under its owning domain (a person -> persons; something inside an app -> the matching category under apps.<app>)
            3. Is it a state? -> a state node under the object it belongs to (current value + confirmed date; old values are appended as history lines, never deleted)
            None of the three -> inbox (the unclassified area; filed into place later)

            ## 5. Extension rules (where "universal" lands)

            - New app -> open a new subtree under apps with the same mold (account / contacts / groups / wallet - create what exists, skip what doesn't)
            - New kind of data -> run the three filing questions; if it fits none of the four forms -> inbox
            - Person across apps -> the master record exists exactly once under persons; every app-side channel node carries person_ref pointing back to it
            - A state changes -> keep only the current value; old values move into history lines
            - Events are never edited -> if one was recorded wrong, write a new correction event; leave the old one untouched
            - The standard is never modified because of new data - data finds its own place

            ## Appendix: 4 worked examples (showing the grammar covers everything)

            Example 1 | Object: WeChat contact "Zhang San, college classmate"
            ### persons.zhangsan - Zhang San
            - type: object
            - summary: College friend; no open items
            - name_cn: Zhang San
            - relation: college friend
            - updated: 2026-10-13 | source: screenshot | confidence: high

            #### apps.wechat.contacts.zhangsan
            - type: object
            - person_ref: persons.zhangsan

            Example 2 | State: wallet screenshot "balance CNY 1,234.56"
            #### apps.wechat.wallet.balance
            - type: state
            - summary: WeChat wallet CNY 1234.56
            - current: CNY 1234.56
            - confirmed: 2026-10-13

            Example 3 | Event + Task: "I lent Zhang San 500, he repays next month"
            ### events.2026-10.lend-zhangsan-500
            - type: event
            - summary: Lent Zhang San CNY 500; repay due 10-30
            - ts: 2026-10-13 14:20 | target: persons.zhangsan | evidence: chat

            ### tasks.2026-10.repay-zhangsan
            - type: task
            - summary: Repayment due from Zhang San
            - trigger: 2026-10-27 | target: persons.zhangsan | status: open

            Example 4 | Something never discussed before (proof of universality): today's step count in Mi Fitness
            -> it is a state -> a state node under the matching domain; the new subtree springs up automatically, no need to ask the standard:
            #### apps.mihealth.steps.daily
            - type: state
            - summary: 8,432 steps today
            - current: 8432
            - confirmed: 2026-10-13

            (The earlier "Perception Archive Structure Standard v1.0" and the blank template are demoted to example sets - field references for common apps, not the framework itself; consult them or not. This file is the framework.)

            ## How this app applies the framework (adoption notes - a separate section, edit or delete freely)

            - A node's TITLE is only the short English name (the last path segment, e.g. Zhang San / balance); the full path is given by the node's position in the tree. Never write whole dot-paths into titles - the skeleton must stay short.
            - The node format above (type / summary / free attributes / updated ...) is written into the node's CONTENT as plain text lines.
            - The source / confidence lines are only needed on facts that will drive action (money, health, promises, numbers read from screenshots); ordinary nodes may skip them.
            - inbox is not a junk drawer: drain it on every maintenance pass (file each item into place, then remove the inbox note).
            - person_ref / history lines / correction events are plain text conventions inside content - the tree itself is only titles + content.
            - This _framework node is the single copy of the standard and is fixed (read-only for everyone, tools included); it changes only if a new app version ships a new text. Do not try to edit, rename or delete it, and do not add children under it.
            """;

    /**
     * 确保树里有 _framework 节点（数据播种，不用迁移，新旧安装都生效）。
     * v2（用户 2026-10-13 晚："固定住……被AI改动了怎么办呢？或者被我不小心删了怎么办呢"）：
     * 这个节点**结构上锁死，全体只读**——工具层拒绝任何 update/delete/加子级（见 handleTool 各处守卫），
     * 界面里也没有修改/删除入口（详情弹窗只有「关闭」）。既然谁都不能"故意动它"了，
     * 那"它不在树里"只可能是意外（旧版本删过、库被清、异常）——一律补种回来，这就是恢复。
     * 已存在则绝不覆盖内容（连 App 新版本也不覆盖）——标准要换代必须走显式迁移。
     * @return 节点 id；失败返回 -1。
     */
    public static long ensureFrameworkNode(AppDatabase db) {
        try {
            for (AppDatabase.PerceptionNode n : db.listPerceptionNodes()) {
                if (isFrameworkRow(n)) {
                    // 一次性文本修订：早期版本种的是"用户可改即新标准"口径（用户当晚改成"固定住"）。
                    // 只有旧版特征句还在才换——真被人手动改过的内容绝不覆盖。
                    String c = n.content == null ? "" : n.content;
                    if (c.contains("whatever it says after an edit IS the standard")) {
                        db.updatePerceptionNode(n.id, FRAMEWORK_TITLE, FRAMEWORK_TEXT);
                    }
                    return n.id;
                }
            }
            return db.insertPerceptionNode(0, FRAMEWORK_TITLE, FRAMEWORK_TEXT);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 该行是不是固定的标准节点（根级 + 标题 _framework）。 */
    public static boolean isFrameworkRow(AppDatabase.PerceptionNode n) {
        return n != null && n.parentId == 0
                && FRAMEWORK_TITLE.equals(n.title == null ? "" : n.title.trim());
    }

    /** 某个 id 是不是固定的标准节点（工具守卫与界面共用的判定）。 */
    public static boolean isFrameworkNode(AppDatabase db, long id) {
        if (id <= 0) return false;
        try {
            return isFrameworkRow(db.getPerceptionNode(id));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------
    // 读：骨架 / 单点
    // ------------------------------------------------------------

    /** 标题骨架文本（每行 "#id 标题"，按层级缩进两个空格）。空树返回 ""。 */
    public static String treeLines(AppDatabase db) {
        return treeLines(db, 0L, false);
    }

    /**
     * 标题骨架文本。
     * @param fromId 0 = 整棵树；>0 = 只看这一支（含它自己）
     * @param noCap  true = 不截断（子树的定向查看用；整树注入仍受限）
     */
    public static String treeLines(AppDatabase db, long fromId, boolean noCap) {
        List<AppDatabase.PerceptionNode> all = db.listPerceptionNodes();
        if (all.isEmpty()) return "";
        Map<Long, List<AppDatabase.PerceptionNode>> byParent = byParent(all);
        Map<Long, AppDatabase.PerceptionNode> ids = byId(all);
        long root = fromId;
        int baseDepth = 1;
        int total = all.size();
        if (fromId != 0) {
            AppDatabase.PerceptionNode r = ids.get(fromId);
            if (r == null) return "";
            // 只数这一支
            List<Long> sub = new ArrayList<>();
            collectIds(byParent, fromId, sub);
            total = sub.size();
            baseDepth = 1;
        }
        StringBuilder sb = new StringBuilder();
        int[] shown = {0};
        if (fromId != 0) {
            AppDatabase.PerceptionNode r = ids.get(fromId);
            sb.append("#").append(r.id).append(" ").append(r.title == null ? "" : r.title).append("\n");
            shown[0]++;
            appendLevel(sb, byParent, r.id, baseDepth + 1, shown, noCap ? Integer.MAX_VALUE : SKELETON_MAX_NODES);
        } else {
            appendLevel(sb, byParent, 0L, baseDepth, shown, noCap ? Integer.MAX_VALUE : SKELETON_MAX_NODES);
        }
        int hidden = total - shown[0];
        if (hidden > 0) {
            sb.append("… (+").append(hidden)
                    .append(" more nodes not shown here - the map is too large to list in full;")
                    .append(" call perception action=tree with parent=<id> to walk one branch at a time, or read single nodes by id)\n");
        }
        return sb.toString();
    }

    private static void appendLevel(StringBuilder sb,
                                    Map<Long, List<AppDatabase.PerceptionNode>> byParent,
                                    long parentId, int depth, int[] shown, int cap) {
        List<AppDatabase.PerceptionNode> kids = byParent.get(parentId);
        if (kids == null) return;
        for (AppDatabase.PerceptionNode n : kids) {
            if (shown[0] >= cap) return;
            for (int i = 1; i < depth; i++) sb.append("  ");
            sb.append("#").append(n.id).append(" ").append(n.title == null ? "" : n.title).append("\n");
            shown[0]++;
            appendLevel(sb, byParent, n.id, depth + 1, shown, cap);
        }
    }

    /** 注入助手提示词的块（含用法说明 + 一行指向 _framework 的路标）。空树返回 ""。 */
    public static String skeletonBlock(AppDatabase db) {
        String lines;
        long fid;
        try {
            fid = ensureFrameworkNode(db);
            lines = treeLines(db);
        } catch (Throwable t) {
            return "";
        }
        if (lines == null || lines.isEmpty()) return "";
        // 常驻的只有这一行路标（~30 token）；框架全文只在 read _framework / 根级 add 的返回里出现
        String pointer = fid > 0
                ? ("Filing standard: node #" + fid + " (" + FRAMEWORK_TITLE + ") is the universal filing framework"
                        + " (4 data forms / 4 tree levels / one node format / three filing questions)"
                        + " - read it before your first add/update in a conversation, then file everything by it."
                        + " It is fixed: read-only for you, never edit or delete it.\n")
                : "";
        return "\n【Perception records】(your phone knowledge map about this user - titles only, refreshed every round)\n"
                + lines
                + pointer
                + "Details live behind each id: perception(action=read, id=N)."
                + " Keep the map growing and accurate with the perception tool - add what you learn, update what changed,"
                + " delete what no longer holds (a delete moves the subtree to the archive, from where it can be restored at any time;"
                + " correcting the map never loses anything)."
                + " Write every title and content in English; proper nouns (app names, places, people, document titles) stay as they are.\n";
    }

    // ------------------------------------------------------------
    // 树工具
    // ------------------------------------------------------------

    private static Map<Long, List<AppDatabase.PerceptionNode>> byParent(List<AppDatabase.PerceptionNode> all) {
        Map<Long, List<AppDatabase.PerceptionNode>> byParent = new HashMap<>();
        for (AppDatabase.PerceptionNode n : all) {
            List<AppDatabase.PerceptionNode> l = byParent.get(n.parentId);
            if (l == null) {
                l = new ArrayList<>();
                byParent.put(n.parentId, l);
            }
            l.add(n);
        }
        return byParent;
    }

    private static Map<Long, AppDatabase.PerceptionNode> byId(List<AppDatabase.PerceptionNode> all) {
        Map<Long, AppDatabase.PerceptionNode> m = new HashMap<>();
        for (AppDatabase.PerceptionNode n : all) m.put(n.id, n);
        return m;
    }

    /** 标题路径，如 "微信 / 联系人 / 张三"。 */
    public static String pathOf(Map<Long, AppDatabase.PerceptionNode> byId, long id) {
        StringBuilder sb = new StringBuilder();
        long cur = id;
        int guard = 0;
        while (cur != 0 && byId.containsKey(cur) && guard++ < 100) {
            AppDatabase.PerceptionNode n = byId.get(cur);
            if (sb.length() > 0) sb.insert(0, " / ");
            sb.insert(0, n.title == null ? "" : n.title);
            cur = n.parentId;
        }
        return sb.toString();
    }

    public static String pathOfId(AppDatabase db, long id) {
        return pathOf(byId(db.listPerceptionNodes()), id);
    }

    /** 子树节点数（含自己）。 */
    public static int countSubtree(AppDatabase db, long id) {
        List<AppDatabase.PerceptionNode> all = db.listPerceptionNodes();
        Map<Long, List<AppDatabase.PerceptionNode>> byParent = byParent(all);
        if (byId(all).get(id) == null) return 0;
        List<Long> ids = new ArrayList<>();
        collectIds(byParent, id, ids);
        return ids.size();
    }

    private static String fmtTime(long ms) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(ms));
        } catch (Exception e) {
            return String.valueOf(ms);
        }
    }

    // ------------------------------------------------------------
    // 删除 → 归档 / 恢复
    // ------------------------------------------------------------

    /**
     * 删除一棵子树：先快照进归档，再删节点。
     * @return {archiveRowId, 删除的节点数}；节点不存在返回 null；归档写入失败返回 {-1, 0}。
     */
    public static long[] deleteSubtree(AppDatabase db, long id) {
        List<AppDatabase.PerceptionNode> all = db.listPerceptionNodes();
        Map<Long, AppDatabase.PerceptionNode> ids = byId(all);
        AppDatabase.PerceptionNode root = ids.get(id);
        if (root == null) return null;
        // 数据层保险：固定标准节点在任何调用路径下都删不掉（-2 = 被拒绝）
        if (isFrameworkRow(root)) return new long[]{-2, 0};
        Map<Long, List<AppDatabase.PerceptionNode>> byParent = byParent(all);

        JSONObject snap;
        int[] cnt = {0};
        try {
            snap = nodeJson(root, byParent, cnt);
        } catch (Exception e) {
            return new long[]{-1, 0};
        }
        JSONArray roots = new JSONArray();
        roots.put(snap);

        String path = pathOf(ids, id);
        long archiveId = db.insertPerceptionArchive(id, root.parentId, path, root.title, root.content,
                roots.toString());
        if (archiveId < 0) return new long[]{-1, 0};

        // 收集子树全部 id 后删除（DFS，先收集再删，避免边遍历边删）
        List<Long> toDelete = new ArrayList<>();
        collectIds(byParent, id, toDelete);
        for (Long nid : toDelete) db.deletePerceptionNode(nid);
        return new long[]{archiveId, toDelete.size()};
    }

    private static JSONObject nodeJson(AppDatabase.PerceptionNode n,
                                       Map<Long, List<AppDatabase.PerceptionNode>> byParent,
                                       int[] cnt) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", n.title == null ? "" : n.title);
        o.put("content", n.content == null ? "" : n.content);
        JSONArray ch = new JSONArray();
        List<AppDatabase.PerceptionNode> kids = byParent.get(n.id);
        if (kids != null) {
            for (AppDatabase.PerceptionNode k : kids) ch.put(nodeJson(k, byParent, cnt));
        }
        o.put("children", ch);
        cnt[0]++;
        return o;
    }

    private static void collectIds(Map<Long, List<AppDatabase.PerceptionNode>> byParent, long id, List<Long> out) {
        out.add(id);
        List<AppDatabase.PerceptionNode> kids = byParent.get(id);
        if (kids == null) return;
        for (AppDatabase.PerceptionNode k : kids) collectIds(byParent, k.id, out);
    }

    /**
     * 把一条归档恢复到树里。
     *
     * <p>语义（用户 2026-10-13 第二次澄清）：**恢复 = 移回档案树**——内容回到树里，
     * 归档里这一条相应移出（归档变少是正常的、不是丢失：数据只是从归档回到了档案树）。
     * 归档"不能删掉"指的是没有自动清理、没有截断、没有上限；能减少归档的只有两种：
     * 用户手动彻底删除、或把内容恢复回树里。助手侧没有彻底删除这条路径。
     *
     * @return 描述恢复结果的文本；归档行不存在/快照坏了返回 null。
     */
    public static String restoreFromArchive(AppDatabase db, long archiveId) {
        AppDatabase.PerceptionArchiveItem a = db.getPerceptionArchive(archiveId);
        if (a == null) return null;
        // 最深处保险：固定标准节点的根级旧拷贝永不恢复（树里永远有一份，种回就是重名根）。
        if (a.parentId == 0 && FRAMEWORK_TITLE.equals(a.title == null ? "" : a.title.trim())) return null;
        long anchor = a.parentId;
        boolean moved = false;
        if (anchor != 0 && db.getPerceptionNode(anchor) == null) {
            anchor = 0;
            moved = true;
        }
        JSONArray roots;
        try {
            roots = new JSONArray(a.subtreeJson == null ? "[]" : a.subtreeJson);
        } catch (Exception e) {
            return null;
        }
        int restored = 0;
        for (int i = 0; i < roots.length(); i++) {
            JSONObject o = roots.optJSONObject(i);
            if (o == null) continue;
            restored += insertSnapshot(db, anchor, o);
        }
        if (restored == 0) return null;
        // 移回：归档行相应移出（数据没丢，只是回到了档案树里）。
        db.deletePerceptionArchiveRow(archiveId);
        return (moved ? "restored to the top level (its old parent no longer exists)" : "restored under its old parent")
                + ", " + restored + " node(s) back in the map";
    }

    private static int insertSnapshot(AppDatabase db, long parentId, JSONObject o) {
        if (o == null) return 0;
        long nid = db.insertPerceptionNode(parentId, o.optString("title", ""), o.optString("content", ""));
        int n = 1;
        JSONArray ch = o.optJSONArray("children");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) n += insertSnapshot(db, nid, ch.optJSONObject(i));
        }
        return n;
    }

    /** 快照里的节点数（归档列表展示用）。 */
    public static int countSnapshotNodes(String subtreeJson) {
        try {
            JSONArray arr = new JSONArray(subtreeJson == null ? "[]" : subtreeJson);
            int n = 0;
            for (int i = 0; i < arr.length(); i++) n += countObj(arr.optJSONObject(i));
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int countObj(JSONObject o) {
        if (o == null) return 0;
        int n = 1;
        JSONArray ch = o.optJSONArray("children");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) n += countObj(ch.optJSONObject(i));
        }
        return n;
    }

    // ------------------------------------------------------------
    // 工具：schema + 执行
    // ------------------------------------------------------------

    /** 主对话侧：只读查询（用户 2026-10-13："主对话也可以查询这里面的档案"）。 */
    public static JSONObject buildReadOnlyToolSchema() {
        JSONObject f = new JSONObject();
        try {
            f.put("name", "perception");
            f.put("description",
                    "Read the phone knowledge map (感知树) - a hierarchical archive of what is known about this user's phone and life:"
                            + " apps and what they hold (WeChat contacts and their character/habits/chat events, group chats, payments, moments),"
                            + " system settings, the device itself, files, people, places."
                            + " Use it to answer questions about any of this, or to check what is already recorded."
                            + " Actions: tree = the title map with ids (pass parent=<id> to walk just one branch);"
                            + " read = one node's detail (id); archive = list deleted subtrees (they can be restored by the assistant)."
                            + " **Read-only** - you cannot add, edit, delete or restore anything here; the assistant owns that, and entries are kept in English.");
            JSONObject props = new JSONObject();
            props.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("tree").put("read").put("archive")));
            props.put("id", new JSONObject().put("type", "integer")
                    .put("description", "Node id (for read)"));
            props.put("parent", new JSONObject().put("type", "integer")
                    .put("description", "For tree: show only this node's branch"));
            props.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "For archive: how many rows to list, default 20, max 100"));
            props.put("offset", new JSONObject().put("type", "integer")
                    .put("description", "For archive: skip this many of the newest rows"));
            f.put("parameters", new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("action")));
        } catch (Exception ignored) { }
        return f;
    }

    /**
     * 主对话侧的只读执行：只有 tree / read / archive 三条路，
     * 其余动作一律拒绝（只读保证在结构上，不靠调用方自律）。
     */
    public static String handleReadOnlyTool(AppDatabase db, JSONObject args) {
        String action = args == null ? "" : args.optString("action", "").trim();
        if ("tree".equals(action) || "read".equals(action) || "archive".equals(action)) {
            return handleTool(db, args);
        }
        return err("perception is read-only here; only tree / read / archive are available."
                + " Adding, editing, deleting and restoring are the assistant's job.");
    }

    public static JSONObject buildToolSchema() {
        JSONObject f = new JSONObject();
        try {
            f.put("name", "perception");
            f.put("description",
                    "Your phone knowledge map about this user - a hierarchical record of everything worth knowing about his phone,"
                            + " kept so you can recall it cheaply in later conversations (a skeleton of titles is already injected into your context each round)."
                            + " What goes in: anything - apps and what they hold (WeChat contacts with names/character/habits/chat history/events, group chats, payments, moments),"
                            + " system settings, device model and era, files and their contents, people, places, routines."
                            + " A node = a short title + the detail in content. Titles carry the skeleton; content carries the substance."
                            + " The filing standard (4 data forms, 4 tree levels, one node format, three filing questions) lives in the root node titled _framework:"
                            + " read it before your first add/update in a conversation and file everything by it; it is attached in full whenever you create a top-level node."
                            + " That node is fixed (read-only for everyone, you included): you can read it, but never edit, rename, delete it, or add children under it."
                            + " Actions: tree = the title map with ids (pass parent=<id> to walk just that branch when the map is big);"
                            + " read = one node's detail (id);"
                            + " add = new node under parent (parent 0/omitted = top level; title required); update = rewrite a node's title/content (id);"
                            + " delete = remove a node and its whole subtree (it moves to the archive; restoring brings it back, so correcting the map never loses anything);"
                            + " archive = list deleted subtrees (use offset to page back through older ones);"
                            + " restore = move an archived subtree back into the map (archive_id; it then leaves the archive)."
                            + " Only the user can permanently delete archive entries; you cannot."
                            + " **Write all titles and content in English** - for the same meaning English costs 30-50% fewer tokens, and this map is injected into your context."
                            + " Proper nouns (app names, people, places, document titles) stay exactly as they are.");
            JSONObject props = new JSONObject();
            props.put("action", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("tree").put("read").put("add")
                            .put("update").put("delete").put("archive").put("restore")));
            props.put("id", new JSONObject().put("type", "integer")
                    .put("description", "Node id (read / update / delete)"));
            props.put("parent", new JSONObject().put("type", "integer")
                    .put("description", "For add: parent node id; 0 or omitted = top level. For tree: show only this node's branch"));
            props.put("title", new JSONObject().put("type", "string")
                    .put("description", "For add/update: short label of the node"));
            props.put("content", new JSONObject().put("type", "string")
                    .put("description", "For add/update: the detail (English)"));
            props.put("archive_id", new JSONObject().put("type", "integer")
                    .put("description", "For restore: the archive row id shown by action=archive"));
            props.put("limit", new JSONObject().put("type", "integer")
                    .put("description", "For archive: how many rows to list, default 20, max 100"));
            props.put("offset", new JSONObject().put("type", "integer")
                    .put("description", "For archive: skip this many of the newest rows (page back to older ones)"));
            f.put("parameters", new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("action")));
        } catch (Exception ignored) { }
        return f;
    }

    public static String handleTool(AppDatabase db, JSONObject args) {
        String action = args == null ? "" : args.optString("action", "").trim();
        try {
            // 播种/定位框架节点：只读侧也经这里，两侧看到的树一致
            long fid = ensureFrameworkNode(db);
            if ("tree".equals(action)) {
                long from = args.optLong("parent", 0);
                String t = treeLines(db, from, from != 0);
                if (from != 0 && t.isEmpty()) return err("no node with id=" + from);
                JSONObject r = new JSONObject().put("success", true)
                        .put("tree", t.isEmpty() ? "(the map is empty - create the first node with add)" : t)
                        .put("node_count", db.listPerceptionNodes().size());
                if (fid > 0) {
                    r.put("note", "Filing standard: node #" + fid + " (" + FRAMEWORK_TITLE
                            + ") - read it before adding/editing, and file everything by it. It is fixed (read-only for tools).");
                }
                return r.toString();
            }

            if ("read".equals(action)) {
                long id = args.optLong("id", 0);
                if (id <= 0) return err("read needs id (see action=tree)");
                AppDatabase.PerceptionNode n = db.getPerceptionNode(id);
                if (n == null) return err("no node with id=" + id);
                List<AppDatabase.PerceptionNode> all = db.listPerceptionNodes();
                Map<Long, List<AppDatabase.PerceptionNode>> byParent = byParent(all);
                JSONArray ch = new JSONArray();
                List<AppDatabase.PerceptionNode> kids = byParent.get(id);
                if (kids != null) {
                    for (AppDatabase.PerceptionNode k : kids) {
                        ch.put(new JSONObject().put("id", k.id).put("title", k.title == null ? "" : k.title));
                    }
                }
                return new JSONObject().put("success", true)
                        .put("path", pathOf(byId(all), id))
                        .put("title", n.title == null ? "" : n.title)
                        .put("content", n.content == null ? "" : n.content)
                        .put("children", ch).toString();
            }

            if ("add".equals(action)) {
                String title = args.optString("title", "").trim();
                if (title.isEmpty()) return err("add needs a title");
                long parent = args.optLong("parent", 0);
                if (parent != 0 && db.getPerceptionNode(parent) == null) {
                    return err("parent node id=" + parent + " does not exist");
                }
                if (parent != 0 && isFrameworkNode(db, parent)) {
                    return err("the _framework node is fixed and takes no children - file the node under its proper L1 domain instead"
                            + " (apps.<app> / persons / events / system / device / media / tasks / inbox)");
                }
                if (parent == 0 && FRAMEWORK_TITLE.equalsIgnoreCase(title)) {
                    return err("the title " + FRAMEWORK_TITLE + " is reserved for the fixed standard node");
                }
                String content = args.optString("content", "");
                long nid = db.insertPerceptionNode(parent, title, content == null ? "" : content.trim());
                if (nid <= 0) return err("failed to create the node");
                JSONObject r = new JSONObject().put("success", true).put("id", nid)
                        .put("path", pathOfId(db, nid));
                if (parent == 0 && nid != fid) {
                    // 根级 = 建一个 L1 域，结构性时刻：框架全文随这一次返回附上。
                    // 这是用户定的投放方式——全文不常驻，调用工具进树时才看得到。
                    r.put("filing_standard_note", "You created a TOP-LEVEL node (an L1 domain)."
                            + " The universal filing framework is attached in full - check this new root against its L1 domain list"
                            + " (apps.<app> / persons / events / system / device / media / tasks / inbox).");
                    r.put("filing_standard", FRAMEWORK_TEXT);
                }
                return r.toString();
            }

            if ("update".equals(action)) {
                long id = args.optLong("id", 0);
                if (id <= 0) return err("update needs id");
                if (db.getPerceptionNode(id) == null) return err("no node with id=" + id);
                if (isFrameworkNode(db, id)) {
                    return err("the _framework node is the fixed filing standard and cannot be modified -"
                            + " write your updates into other nodes instead (its own rule: the standard never changes because of new data).");
                }
                boolean hasT = args.has("title");
                boolean hasC = args.has("content");
                if (!hasT && !hasC) return err("update needs title and/or content");
                String t = hasT ? args.optString("title", "").trim() : null;
                if (hasT && t.isEmpty()) return err("title must not be empty");
                if (hasT && FRAMEWORK_TITLE.equalsIgnoreCase(t)) {
                    return err("the title " + FRAMEWORK_TITLE + " is reserved for the fixed standard node - pick another title");
                }
                String c = hasC ? args.optString("content", "").trim() : null;
                db.updatePerceptionNode(id, t, c);
                return new JSONObject().put("success", true)
                        .put("path", pathOfId(db, id)).toString();
            }

            if ("delete".equals(action)) {
                long id = args.optLong("id", 0);
                if (id <= 0) return err("delete needs id");
                if (isFrameworkNode(db, id)) {
                    return err("the _framework node is the fixed filing standard and cannot be deleted - it is read-only for everyone."
                            + " Delete something else.");
                }
                long[] r = deleteSubtree(db, id);
                if (r == null) return err("no node with id=" + id);
                if (r[0] == -2) return err("the _framework node is fixed and cannot be deleted.");
                if (r[0] < 0) return err("failed to archive the subtree - nothing was deleted");
                return new JSONObject().put("success", true)
                        .put("archived", true).put("archive_id", r[0]).put("nodes_removed", r[1])
                        .put("note", "The subtree moved to the archive (restorable by you or the user; only the user can delete it for good).").toString();
            }

            if ("archive".equals(action)) {
                int limit = args.optInt("limit", 20);
                limit = Math.max(1, Math.min(100, limit));
                int offset = Math.max(0, args.optInt("offset", 0));
                List<AppDatabase.PerceptionArchiveItem> items = db.listPerceptionArchive(limit, offset);
                JSONArray arr = new JSONArray();
                for (AppDatabase.PerceptionArchiveItem a : items) {
                    arr.put(new JSONObject().put("archive_id", a.id)
                            .put("title", a.title == null ? "" : a.title)
                            .put("path", a.path == null ? "" : a.path)
                            .put("nodes", countSnapshotNodes(a.subtreeJson))
                            .put("deleted_at", fmtTime(a.deletedAt)));
                }
                return new JSONObject().put("success", true).put("entries", arr)
                        .put("offset", offset)
                        .put("total", db.countPerceptionArchive()).toString();
            }

            if ("restore".equals(action)) {
                long archiveId = args.optLong("archive_id", 0);
                if (archiveId <= 0) return err("restore needs archive_id (see action=archive)");
                AppDatabase.PerceptionArchiveItem fa = db.getPerceptionArchive(archiveId);
                if (fa != null && fa.parentId == 0
                        && FRAMEWORK_TITLE.equals(fa.title == null ? "" : fa.title.trim())) {
                    // 固定标准节点的旧拷贝：树里永远有一份，恢复没意义；也不静默吃掉归档行。
                    return err("this archive entry is an old copy of the fixed standard (" + FRAMEWORK_TITLE
                            + "); the standard node is always present in the map, so there is nothing to restore."
                            + " Only the user can permanently delete this copy in the archive UI.");
                }
                String res = restoreFromArchive(db, archiveId);
                if (res == null) return err("could not restore archive row " + archiveId);
                return ok(res);
            }
        } catch (Exception e) {
            return err("perception failed: " + e.getMessage());
        }
        return err("unknown action; use tree / read / add / update / delete / archive / restore");
    }

    private static String ok(String msg) {
        try {
            return new JSONObject().put("success", true).put("message", msg).toString();
        } catch (Exception e) {
            return "{\"success\":true}";
        }
    }

    private static String err(String msg) {
        try {
            return new JSONObject().put("success", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"success\":false}";
        }
    }
}
