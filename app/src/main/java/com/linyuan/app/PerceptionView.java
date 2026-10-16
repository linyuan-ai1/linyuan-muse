package com.linyuan.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「感知树」弹窗（2026-10-13 用户需求）。
 *
 * <p>一张知识图谱式的层级档案树：主标题 → 子标题 → 子子目录，每个节点带一段内容。
 * 右上角「新建」建顶层节点；每个节点行右侧有 详情/加子级/改/删。删除走 {@link PerceptionStore}
 * ——整棵子树进归档（防删错可找回）；归档页里只有用户能彻底删除（二次确认）。
 */
public class PerceptionView {

    private final MainActivity act;
    private final AppDatabase db;

    /** 重建整棵树的入口（show() 里装好；所有增删改之后都走它，界面与数据不会脱节）。 */
    private Runnable reloadTree;

    public PerceptionView(MainActivity act, AppDatabase db) {
        this.act = act;
        this.db = db;
    }

    // ------------------------------------------------------------
    // 主弹窗
    // ------------------------------------------------------------

    public void show() {
        // 播种「_framework」建档标准节点（固定节点，界面同样只读）
        PerceptionStore.ensureFrameworkNode(db);
        final Dialog dlg = new Dialog(act);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(22));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        box.setBackground(bg);
        box.setPadding(act.dp(18), act.dp(16), act.dp(18), act.dp(12));

        // 标题行：左标题 + 右「新建」（用户指定的位置：右上角）
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(act);
        h.setText("🗺 感知树");
        h.setTextColor(act.col(R.color.text));
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        head.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button newBtn = smallBtn("＋ 新建");
        newBtn.setTextColor(act.col(R.color.onAccent));
        newBtn.setBackgroundResource(R.drawable.btn_primary);
        head.addView(newBtn);
        box.addView(head);

        // 画布（思维导图）：根在左、分支往右、同支同色、可拖可缩放（用户 2026-10-13 晚：
        // "就像一棵树……各个分支都能找到它的源头是哪一些的""可以放大缩小的，像一个画布一样"）
        FrameLayout mapWrap = new FrameLayout(act);
        PerceptionMapView map = new PerceptionMapView(act);
        map.setColors(act.col(R.color.text2), act.col(R.color.surface), act.col(R.color.accent));
        map.setListener(n -> showNodeDetail(n.id));
        mapWrap.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView empty = new TextView(act);
        empty.setText("还没有记录。");
        empty.setTextColor(act.col(R.color.muted));
        empty.setTextSize(13);
        empty.setLineSpacing(act.dp(3), 1f);
        empty.setVisibility(View.GONE);
        mapWrap.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        LinearLayout.LayoutParams mwlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mwlp.topMargin = act.dp(10);
        box.addView(mapWrap, mwlp);

        // 缩放小工具（等宽均分一行，不会互相挤）
        LinearLayout zoomRow = new LinearLayout(act);
        LinearLayout.LayoutParams zrlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zrlp.topMargin = act.dp(8);
        zoomRow.setLayoutParams(zrlp);
        Button zoomOut = smallBtn("−");
        Button zoomIn = smallBtn("＋");
        Button fitBtn = smallBtn("⟳ 适应");
        zoomRow.addView(zoomOut, flexLp(0));
        zoomRow.addView(zoomIn, flexLp(6));
        zoomRow.addView(fitBtn, flexLp(6));
        box.addView(zoomRow);
        zoomOut.setOnClickListener(v -> map.zoomBy(1f / 1.35f));
        zoomIn.setOnClickListener(v -> map.zoomBy(1.35f));
        fitBtn.setOnClickListener(v -> map.fit());

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = act.dp(8);
        btnRow.setLayoutParams(brlp);
        Button archBtn = smallBtn("📦 归档");
        btnRow.addView(archBtn);
        btnRow.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        Button close = smallBtn("关闭");
        btnRow.addView(close);
        box.addView(btnRow);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            List<AppDatabase.PerceptionNode> all = db.listPerceptionNodes();
            empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
            map.setData(all);
        };
        reloadTree = refresh[0];
        refresh[0].run();

        newBtn.setOnClickListener(v -> showNodeEditor(-1, 0, "", "", () -> refresh[0].run()));
        archBtn.setOnClickListener(v -> showArchive(() -> refresh[0].run()));
        close.setOnClickListener(v -> dlg.dismiss());

        dlg.setContentView(box);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.94F);
            lp.height = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.84f);
            dlg.getWindow().setAttributes(lp);
        }
        dlg.show();
    }

    // ------------------------------------------------------------
    // 树渲染：画布（PerceptionMapView）里画，这里只留重建入口
    // ------------------------------------------------------------

    /** 重建整棵树（增删改之后都走它）。 */
    private void requestTreeReload() {
        if (reloadTree != null) reloadTree.run();
    }

    // ------------------------------------------------------------
    // 节点编辑（新建/修改共用）
    // ------------------------------------------------------------

    private void showNodeEditor(long nodeId, long parentId, String initTitle, String initContent,
                                Runnable onDone) {
        final boolean isNew = nodeId <= 0;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(20));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        box.setBackground(bg);
        box.setPadding(act.dp(18), act.dp(16), act.dp(18), act.dp(12));

        TextView h = new TextView(act);
        if (isNew) {
            if (parentId > 0) {
                h.setText("新建子级 → " + PerceptionStore.pathOfId(db, parentId));
            } else {
                h.setText("新建主标题");
            }
        } else {
            h.setText("修改：" + initTitle);
        }
        h.setTextColor(act.col(R.color.text));
        h.setTextSize(15);
        h.setTypeface(null, Typeface.BOLD);
        box.addView(h);

        TextView lt = new TextView(act);
        lt.setText("标题");
        lt.setTextColor(act.col(R.color.muted));
        lt.setTextSize(11);
        LinearLayout.LayoutParams ltlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ltlp.topMargin = act.dp(12);
        lt.setLayoutParams(ltlp);
        box.addView(lt);

        final EditText titleEd = new EditText(act);
        titleEd.setText(initTitle == null ? "" : initTitle);
        titleEd.setTextColor(act.col(R.color.text));
        titleEd.setTextSize(14);
        titleEd.setBackgroundResource(R.drawable.bg_edit);
        titleEd.setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = act.dp(6);
        box.addView(titleEd, tlp);

        TextView lc = new TextView(act);
        lc.setText("内容");
        lc.setTextColor(act.col(R.color.muted));
        lc.setTextSize(11);
        LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lclp.topMargin = act.dp(10);
        lc.setLayoutParams(lclp);
        box.addView(lc);

        final EditText contentEd = new EditText(act);
        contentEd.setText(initContent == null ? "" : initContent);
        contentEd.setTextColor(act.col(R.color.text));
        contentEd.setTextSize(13);
        contentEd.setGravity(Gravity.TOP | Gravity.START);
        contentEd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        contentEd.setBackgroundResource(R.drawable.bg_edit);
        contentEd.setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9));
        contentEd.setMinLines(4);
        MaxHeightScrollView cs = new MaxHeightScrollView(act);
        cs.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.30f));
        cs.addView(contentEd, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = act.dp(6);
        box.addView(cs, clp);

        final Dialog dlg = new Dialog(act);
        Button cancel = smallBtn("取消");
        Button save = smallBtn("保存");
        save.setTextColor(act.col(R.color.onAccent));
        save.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = act.dp(12);
        row.setLayoutParams(rlp);
        row.addView(cancel);
        row.addView(save);
        box.addView(row);

        cancel.setOnClickListener(v -> dlg.dismiss());
        save.setOnClickListener(v -> {
            String ttl = titleEd.getText() == null ? "" : titleEd.getText().toString().trim();
            String body = contentEd.getText() == null ? "" : contentEd.getText().toString().trim();
            if (ttl.isEmpty()) { act.toast("标题不能空"); return; }
            // 固定标准节点的标题是保留名：新建/改名都不允许占用，否则会出现重名根
            if (parentId == 0 && PerceptionStore.FRAMEWORK_TITLE.equalsIgnoreCase(ttl)
                    && !PerceptionStore.isFrameworkNode(db, nodeId)) {
                act.toast("「" + PerceptionStore.FRAMEWORK_TITLE + "」是固定标准节点的保留名，换一个");
                return;
            }
            if (isNew) {
                long nid = db.insertPerceptionNode(parentId, ttl, body);
                if (nid <= 0) { act.toast("保存失败"); return; }
                act.toast("已新建");
            } else {
                db.updatePerceptionNode(nodeId, ttl, body);
                act.toast("已保存");
            }
            dlg.dismiss();
            if (onDone != null) onDone.run();
        });

        dlg.setContentView(box);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            dlg.getWindow().setAttributes(lp);
        }
        dlg.show();
    }

    // ------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------

    private void showNodeDetail(long id) {
        AppDatabase.PerceptionNode n = db.getPerceptionNode(id);
        if (n == null) { act.toast("这条已经不在了"); requestTreeReload(); return; }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(20));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        box.setBackground(bg);
        box.setPadding(act.dp(18), act.dp(16), act.dp(18), act.dp(12));

        TextView pathView = new TextView(act);
        pathView.setText("🗂 " + PerceptionStore.pathOfId(db, id));
        pathView.setTextColor(act.col(R.color.muted));
        pathView.setTextSize(11);
        box.addView(pathView);

        TextView t = new TextView(act);
        t.setText(n.title == null ? "" : n.title);
        t.setTextColor(act.col(R.color.text));
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = act.dp(6);
        t.setLayoutParams(tlp);
        box.addView(t);

        TextView c = new TextView(act);
        boolean empty = n.content == null || n.content.trim().isEmpty();
        c.setText(empty ? "（这条还没有内容）" : n.content);
        c.setTextColor(act.col(empty ? R.color.faint : R.color.text2));
        c.setTextSize(13);
        c.setLineSpacing(act.dp(3), 1f);
        MaxHeightScrollView sc = new MaxHeightScrollView(act);
        sc.setMaxHeightPx((int) (act.getResources().getDisplayMetrics().heightPixels * 0.42f));
        sc.addView(c, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = act.dp(8);
        box.addView(sc, clp);

        TextView meta = new TextView(act);
        meta.setText("节点 #" + n.id + " · 建于 " + fmt(n.createdAt)
                + (n.updatedAt > n.createdAt ? " · 改于 " + fmt(n.updatedAt) : ""));
        meta.setTextColor(act.col(R.color.faint));
        meta.setTextSize(10);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = act.dp(8);
        meta.setLayoutParams(mlp);
        box.addView(meta);

        final Dialog dlg = new Dialog(act);
        final boolean fixed = PerceptionStore.isFrameworkNode(db, id);
        if (fixed) {
            // 固定标准节点：界面同样只读——不给修改/删除/加子级（用户 2026-10-13 晚：
            // "固定住……被AI改动了怎么办呢？或者被我不小心删了怎么办呢"）。
            TextView lockInfo = new TextView(act);
            lockInfo.setText("🔒 内置的建档标准（固定节点）：不可修改、不可删除。");
            lockInfo.setTextColor(act.col(R.color.faint));
            lockInfo.setTextSize(11);
            LinearLayout.LayoutParams lilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lilp.topMargin = act.dp(12);
            lockInfo.setLayoutParams(lilp);
            box.addView(lockInfo);
        }
        // 用户 2026-10-13：四个按钮缩成一行。做法是四个都设 weight=1 等分整行宽度 ——
        // 之前"一行最多两个"的教训来自不定宽按钮挤行（最后一个被压成细条），
        // 等宽均分不存在这个问题，每个按钮始终拿 1/4 宽度。
        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = act.dp(12);
        btnRow.setLayoutParams(brlp);
        Button close = flexBtn("关闭");
        btnRow.addView(close, flexLp(0));
        if (!fixed) {
            Button del = flexBtn("删除");
            del.setTextColor(act.col(R.color.danger));
            Button addKid = flexBtn("＋子级");
            addKid.setTextColor(act.col(R.color.accent));
            Button edit = flexBtn("修改");
            edit.setTextColor(act.col(R.color.onAccent));
            edit.setBackgroundResource(R.drawable.btn_primary);
            btnRow.addView(del, flexLp(6));
            btnRow.addView(addKid, flexLp(6));
            btnRow.addView(edit, flexLp(6));
            addKid.setOnClickListener(v -> {
                dlg.dismiss();
                showNodeEditor(-1, n.id, "", "", () -> requestTreeReload());
            });
            edit.setOnClickListener(v -> {
                dlg.dismiss();
                showNodeEditor(n.id, n.parentId, n.title, n.content, () -> requestTreeReload());
            });
            del.setOnClickListener(v -> {
                String path = PerceptionStore.pathOfId(db, n.id);
                int cnt = PerceptionStore.countSubtree(db, n.id);
                act.showStyledConfirm("删除「" + (n.title == null ? "" : n.title) + "」？",
                        (cnt > 1 ? "连它下面的 " + (cnt - 1) + " 个子级一起删。" : "")
                                + "删除的内容会进归档，可以找回；归档只有你能彻底删。",
                        "删除", true, () -> {
                            long[] r = PerceptionStore.deleteSubtree(db, n.id);
                            if (r == null) { act.toast("这条已经不在了"); }
                            else if (r[0] == -2) { act.toast("这是固定标准节点，删不掉"); }
                            else if (r[0] < 0) { act.toast("归档失败，没有删除"); }
                            else { act.toast("已移入归档（" + path + "）"); }
                            dlg.dismiss();
                            requestTreeReload();
                        });
            });
        }
        box.addView(btnRow);

        close.setOnClickListener(v -> dlg.dismiss());

        dlg.setContentView(box);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.92F);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            dlg.getWindow().setAttributes(lp);
        }
        dlg.show();
    }

    // ------------------------------------------------------------
    // 归档页
    // ------------------------------------------------------------

    private void showArchive(Runnable onChanged) {
        final Dialog dlg = new Dialog(act);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.col(R.color.surface));
        bg.setCornerRadius(act.dp(22));
        bg.setStroke(act.dp(1), act.col(R.color.border));
        box.setBackground(bg);
        box.setPadding(act.dp(18), act.dp(16), act.dp(18), act.dp(12));

        TextView h = new TextView(act);
        h.setText("📦 归档");
        h.setTextColor(act.col(R.color.text));
        h.setTextSize(16);
        h.setTypeface(null, Typeface.BOLD);
        box.addView(h);

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svlp.topMargin = act.dp(10);
        box.addView(sv, svlp);

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = act.dp(8);
        btnRow.setLayoutParams(brlp);
        Button closeB = smallBtn("关闭");
        btnRow.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        btnRow.addView(closeB);
        box.addView(btnRow);

        final Runnable[] refresh = new Runnable[1];
        final int[] shownCount = {500};
        refresh[0] = () -> {
            List<AppDatabase.PerceptionArchiveItem> items = db.listPerceptionArchive(shownCount[0], 0);
            list.removeAllViews();
            int total = db.countPerceptionArchive();
            if (items.isEmpty()) {
                TextView empty = new TextView(act);
                empty.setText("归档是空的。");
                empty.setTextColor(act.col(R.color.muted));
                empty.setTextSize(13);
                list.addView(empty);
                return;
            }
            for (AppDatabase.PerceptionArchiveItem a : items) {
                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable cbg = new GradientDrawable();
                cbg.setColor(act.col(R.color.surfaceInput));
                cbg.setCornerRadius(act.dp(12));
                cbg.setStroke(act.dp(1), act.col(R.color.border));
                card.setBackground(cbg);
                card.setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(8));

                TextView t = new TextView(act);
                t.setText("🗂 " + (a.path == null || a.path.isEmpty()
                        ? (a.title == null ? "" : a.title) : a.path));
                t.setTextColor(act.col(R.color.text));
                t.setTextSize(13);
                t.setTypeface(null, Typeface.BOLD);
                card.addView(t);

                int nodes = PerceptionStore.countSnapshotNodes(a.subtreeJson);
                TextView meta = new TextView(act);
                meta.setText("删除于 " + fmt(a.deletedAt) + " · 含 " + nodes + " 个节点");
                meta.setTextColor(act.col(R.color.faint));
                meta.setTextSize(10);
                meta.setPadding(0, act.dp(4), 0, 0);
                card.addView(meta);

                if (a.content != null && !a.content.trim().isEmpty()) {
                    TextView c = new TextView(act);
                    String txt = a.content.trim();
                    c.setText(txt.length() > 160 ? txt.substring(0, 160) + "…" : txt);
                    c.setTextColor(act.col(R.color.muted));
                    c.setTextSize(12);
                    c.setLineSpacing(act.dp(2), 1f);
                    c.setPadding(0, act.dp(6), 0, 0);
                    card.addView(c);
                }

                LinearLayout row = new LinearLayout(act);
                row.setGravity(Gravity.END);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = act.dp(8);
                row.setLayoutParams(rlp);

                final int nodesFinal = nodes;
                final boolean fixedCopy = a.parentId == 0 && PerceptionStore.FRAMEWORK_TITLE.equals(
                        a.title == null ? "" : a.title.trim());
                if (fixedCopy) {
                    // 固定标准节点的旧拷贝：树里永远有一份，没有"恢复"的意义（工具侧恢复也明确拒绝），
                    // 只留用户手动彻底删除这一条路。
                    TextView fx = new TextView(act);
                    fx.setText("🔒 内置建档标准的旧拷贝（无需恢复）。");
                    fx.setTextColor(act.col(R.color.faint));
                    fx.setTextSize(10);
                    fx.setPadding(0, act.dp(6), 0, 0);
                    card.addView(fx);
                } else {
                    Button restore = smallBtn("恢复到档案树");
                    restore.setTextColor(act.col(R.color.accent));
                    restore.setOnClickListener(v -> {
                        // 恢复 = 移回档案树（内容回到树里，归档这条相应移出）——数据不丢，只是换了地方。
                        String r = PerceptionStore.restoreFromArchive(db, a.id);
                        if (r == null) { act.toast("这条归档读不出来了"); return; }
                        act.toast("已恢复到档案树：" + nodesFinal + " 个节点");
                        refresh[0].run();
                        if (onChanged != null) onChanged.run();
                    });
                    row.addView(restore);
                }

                Button purge = smallBtn("彻底删除");
                purge.setTextColor(act.col(R.color.danger));
                purge.setOnClickListener(v -> act.showStyledConfirm(
                        "彻底删除这条归档？",
                        "「" + (a.path == null ? "" : a.path) + "」会被永久删除，这一步无法恢复。",
                        "彻底删除", true, () -> {
                            db.deletePerceptionArchiveRow(a.id);
                            act.toast("已彻底删除");
                            refresh[0].run();
                            if (onChanged != null) onChanged.run();
                        }));
                row.addView(purge);

                card.addView(row);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.topMargin = act.dp(6);
                list.addView(card, clp);
            }
            // 归档无上限：超过当前渲染量时给一个"载入更早"，并明说库里还完整保留。
            if (total > items.size()) {
                TextView more = new TextView(act);
                more.setText("⟳ 载入更早的 " + Math.min(500, total - items.size()) + " 项"
                        + "（当前显示 " + items.size() + " / 共 " + total + " 项）");
                more.setTextColor(act.col(R.color.accent));
                more.setTextSize(12);
                more.setGravity(Gravity.CENTER);
                more.setPadding(0, act.dp(12), 0, act.dp(6));
                LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp2.topMargin = act.dp(6);
                more.setLayoutParams(mlp2);
                more.setOnClickListener(v -> {
                    shownCount[0] += 500;
                    refresh[0].run();
                });
                list.addView(more);
            } else if (total > 500) {
                TextView all = new TextView(act);
                all.setText("已全部列出 " + total + " 项。");
                all.setTextColor(act.col(R.color.faint));
                all.setTextSize(11);
                all.setGravity(Gravity.CENTER);
                all.setPadding(0, act.dp(10), 0, 0);
                list.addView(all);
            }
        };
        refresh[0].run();

        closeB.setOnClickListener(v -> {
            dlg.dismiss();
            if (onChanged != null) onChanged.run();
        });

        dlg.setContentView(box);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
            lp.width = act.dialogWidthPx(0.94F);
            lp.height = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.84f);
            dlg.getWindow().setAttributes(lp);
        }
        dlg.show();
    }

    // ------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------

    private Button smallBtn(String text) {
        Button b = new Button(act);
        b.setText(text);
        b.setTextColor(act.col(R.color.text2));
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(act.dp(10), act.dp(5), act.dp(10), act.dp(5));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(act.dp(6), 0, 0, 0);
        b.setLayoutParams(p);
        return b;
    }

    /** 等宽按钮（放进 detail 一行四个用；宽度由 flexLp 的 weight 决定，不受文字长度影响）。 */
    private Button flexBtn(String text) {
        Button b = new Button(act);
        b.setText(text);
        b.setTextColor(act.col(R.color.text2));
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding(act.dp(2), act.dp(5), act.dp(2), act.dp(5));
        return b;
    }

    /** flexBtn 的布局参数：weight=1 均分整行；startMarginDp 是左边距。 */
    private LinearLayout.LayoutParams flexLp(int startMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (startMarginDp > 0) p.setMarginStart(act.dp(startMarginDp));
        return p;
    }

    private static String fmt(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }
}
