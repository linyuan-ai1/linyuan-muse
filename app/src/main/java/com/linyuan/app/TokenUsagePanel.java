package com.linyuan.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「上下文用量」面板（用户 2026-10-15 定稿的深色数据面板方案，配色跟随主题）。
 *
 * 配色**跟随 App 主题**：{@link #build} 收一个 dark 标志，深色主题走 {@link Pal#DARK}、
 * 浅色走 {@link Pal#LIGHT}。两套值都取自 {@code res/values/colors.xml} 与
 * {@code values-night/colors.xml} 的同名字段，所以面板跟 App 其余部分一致。
 *
 * 为什么不用 {@code col(R.color.x)}：卡片内的分段条、条形、网格都是自绘，需要一个**整体**
 * 配色的对象；逐个字段去读资源反而会出现"一半跟随主题、一半写死"的割裂。
 *
 * 注意：面板**完全铺满**它所在的对话框，所以 {@link StyledDialog} 那张硬编码白卡
 * 不会露出来 —— 深色主题下这里就是深色。
 */
final class TokenUsagePanel {

    private TokenUsagePanel() { }

    /** 分项角色：定色不写死色值，由 Pal 按主题给（见 {@link Pal#colorOf}）。 */
    static final int SYS = 0;
    static final int TOOLS = 1;
    static final int HIST = 2;
    static final int USER = 3;

    /** 一套主题配色。 */
    static final class Pal {
        final boolean dark;
        final int cardBg, glow, wash, grid, scan, border, bracket;
        final int fg, name, dim, track, divider;
        final int iconBg, iconStroke, pillBg, pillStroke;
        final int heroTop, heroBottom, heroStroke;
        final int blockBg, blockStroke, dashed;
        final int tagFg, tagBg, tagStroke;
        final int accent, accentBright;
        final int[] series = new int[4];
        /** 子项往卡片底色里退多少（越大越弱）。 */
        final float subT;

        private Pal(boolean dark, int cardBg, int glow, int wash, int grid, int scan,
                    int border, int bracket, int fg, int name, int dim, int track, int divider,
                    int iconBg, int iconStroke, int pillBg, int pillStroke,
                    int heroTop, int heroBottom, int heroStroke,
                    int blockBg, int blockStroke, int dashed,
                    int tagFg, int tagBg, int tagStroke,
                    int accent, int accentBright, float subT,
                    int cSys, int cTools, int cHist, int cUser) {
            this.dark = dark;
            this.cardBg = cardBg; this.glow = glow; this.wash = wash; this.grid = grid;
            this.scan = scan; this.border = border; this.bracket = bracket;
            this.fg = fg; this.name = name; this.dim = dim; this.track = track;
            this.divider = divider;
            this.iconBg = iconBg; this.iconStroke = iconStroke;
            this.pillBg = pillBg; this.pillStroke = pillStroke;
            this.heroTop = heroTop; this.heroBottom = heroBottom; this.heroStroke = heroStroke;
            this.blockBg = blockBg; this.blockStroke = blockStroke; this.dashed = dashed;
            this.tagFg = tagFg; this.tagBg = tagBg; this.tagStroke = tagStroke;
            this.accent = accent; this.accentBright = accentBright; this.subT = subT;
            series[SYS] = cSys; series[TOOLS] = cTools; series[HIST] = cHist; series[USER] = cUser;
        }

        int colorOf(int role) {
            return role >= 0 && role < series.length ? series[role] : series[SYS];
        }

        /** 子项弱化：朝卡片底色退（浅色下变浅、深色下变暗，语义一致）。 */
        int subColorOf(int role) {
            return blend(colorOf(role), cardBg, subT);
        }

        int heroBarEnd() {
            return dark ? 0xFF7DD3FC : 0xFF6AA4FF;
        }
    }

    /** 浅色：对齐 values/colors.xml。 */
    static final Pal LIGHT = new Pal(false,
            0xFFFFFFFF, 0x0F4C8DFF, 0x0F2F6BE6, 0x42DFE7F6, 0x00000000,
            0xFFE7E1D6, 0xFFA8C4F0,
            0xFF202632, 0xFF3A4152, 0xFF6B7280, 0xFFEDF0F5, 0xFFECE7DE,
            0xFFE6EEFF, 0x402F6BE6, 0x142F6BE6, 0x332F6BE6,
            0xFFF3F7FE, 0xFFFAFCFF, 0xFFE3EAF7,
            0xFFF3F7FE, 0xFFE3EAF7, 0xFFD8DFEA,
            0xFF12805C, 0xFFE7F6EF, 0x4012805C,
            0xFF2F6BE6, 0xFF4C8DFF, 0.62f,
            0xFF2F6BE6, 0xFF0E9AA7, 0xFF7C6BD6, 0xFFD98A0B);

    /** 深色：对齐 values-night/colors.xml（深色下那层发光/网格是设计里本来就有的）。 */
    static final Pal DARK = new Pal(true,
            0xFF141A28, 0x384C8DFF, 0x1A4C8DFF, 0x3D7AA2FF, 0x08FFFFFF,
            0xFF273349, 0x8C7AA2FF,
            0xFFEAF0FB, 0xFFC6D2E6, 0xFF9AACC6, 0x1F7AA2FF, 0x1A7AA2FF,
            0x2E4C8DFF, 0x4D7AA2FF, 0x294C8DFF, 0x577AA2FF,
            0x1A4C8DFF, 0x084C8DFF, 0x267AA2FF,
            0x1A4C8DFF, 0x267AA2FF, 0x577AA2FF,
            0xFF9FE8C8, 0x1F34D399, 0x4734D399,
            0xFF4C8DFF, 0xFF6AA4FF, 0.55f,
            0xFF4C8DFF, 0xFF22D3EE, 0xFFA78BFA, 0xFFFBBF24);

    /** 一行明细。role 决定颜色（见 {@link Pal#colorOf}）；sub=true 是子项（缩进 + 弱化）。 */
    static final class Item {
        final String name;
        final int tokens;
        final int role;
        final boolean sub;
        /** note=true 是"说明行"（如：本次知识库只做了关键词检索）：只显示一行小字，
         *  不带数值/占比/条形 —— 否则会渲染成 0 tok / 0% 的假数据行。 */
        final boolean note;

        Item(String name, int tokens, int role, boolean sub) {
            this(name, tokens, role, sub, false);
        }

        Item(String name, int tokens, int role, boolean sub, boolean note) {
            this.name = name;
            this.tokens = tokens;
            this.role = role;
            this.sub = sub;
            this.note = note;
        }
    }

    static final class Data {
        /** true = 实测还没算完（骨架态）：只画卡面 + 计算中，不画任何数字 —— 免得出现假的 0 tok。 */
        boolean loading;
        int total;
        int limit;
        int remaining;
        /** 模型自报的 prompt 占用；<=0 表示这次会话还没发过请求。 */
        int reported;
        /** 分段条 + 图例（只放主项）。 */
        final List<Item> stack = new ArrayList<>();
        /** 明细行（含子项）。 */
        final List<Item> rows = new ArrayList<>();
    }

    /** maxHeightPx<=0：撑满对话框给定的高度（对话框用 showFixedHeight 定了尺寸）。
     *  面板内容放得下就整屏显示、放不下才内部滚动 —— 不要把窗口做小。 */
    static View build(Activity act, Data d, int maxHeightPx, boolean dark) {
        final float den = act.getResources().getDisplayMetrics().density;
        final Pal pal = dark ? DARK : LIGHT;
        final List<TechBarView> bars = new ArrayList<>();

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new TechCardDrawable(den, pal, dark));
        card.setPadding(dp(den, 18), dp(den, 18), dp(den, 18), dp(den, 14));

        card.addView(buildHeader(act, den, d, pal));
        if (d.loading) {
            card.addView(buildSkeleton(act, den, pal));
        } else {
            card.addView(buildHero(act, den, d, pal, bars));
            card.addView(buildStack(act, den, d, pal));
            card.addView(buildLegend(act, den, d, pal));
            card.addView(buildRows(act, den, d, pal, bars));
            card.addView(buildFoot(act, den, d, pal));
        }

        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(card, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                maxHeightPx > 0 ? Math.min(maxHeightPx, dp(den, 620))
                        : ViewGroup.LayoutParams.MATCH_PARENT));

        animateBars(bars);
        return sv;
    }

    /** 骨架：与真面板同一张卡，先给几条灰条占位，实测回来整块换掉（用户 2026-10-16 要求"先弹卡片再刷新"）。 */
    private static View buildSkeleton(Activity act, float den, Pal pal) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 4; i++) {
            View bar = new View(act);
            bar.setBackground(round(pal.track, dp(den, 10)));
            bar.setAlpha(i == 0 ? 0.9f : 0.55f);
            LinearLayout.LayoutParams blp = lp(-1, dp(den, i == 0 ? 46 : 34));
            blp.topMargin = dp(den, i == 0 ? 0 : 11);
            box.addView(bar, blp);
        }
        return box;
    }

    // ---------------------------------------------------------------- 头部

    private static View buildHeader(Activity act, float den, Data d, Pal pal) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = lp(-1, -2);
        rlp.bottomMargin = dp(den, 13);
        row.setLayoutParams(rlp);

        LinearLayout titleWrap = new LinearLayout(act);
        titleWrap.setOrientation(LinearLayout.HORIZONTAL);
        titleWrap.setGravity(Gravity.CENTER_VERTICAL);

        TextView ico = new TextView(act);
        ico.setText("📊");
        ico.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ico.setGravity(Gravity.CENTER);
        GradientDrawable ibg = round(pal.iconBg, dp(den, 7));
        ibg.setStroke(Math.max(1, dp(den, 1)), pal.iconStroke);
        ico.setBackground(ibg);
        titleWrap.addView(ico, lp(dp(den, 22), dp(den, 22)));

        TextView title = new TextView(act);
        title.setText("上下文用量");
        title.setTextColor(pal.fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams tlp = lp(-2, -2);
        tlp.setMarginStart(dp(den, 7));
        titleWrap.addView(title, tlp);
        row.addView(titleWrap, lp(-2, -2));

        TextView pill = new TextView(act);
        pill.setText(d.loading ? "计算中" : oneDecimal(d.total * 100f / Math.max(1, d.limit)) + "%");
        pill.setTextColor(pal.dark ? 0xFFD8E6FF : pal.accent);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        GradientDrawable pbg = round(pal.pillBg, dp(den, 999));
        pbg.setStroke(Math.max(1, dp(den, 1)), pal.pillStroke);
        pill.setBackground(pbg);
        pill.setPadding(dp(den, 9), dp(den, 3), dp(den, 9), dp(den, 3));
        // 弹片把胶囊推到右边；胶囊本身保持内容宽（直接给 weight 会被拉伸成一整条）
        View spacer = new View(act);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(pill, lp(-2, -2));
        return row;
    }

    // ---------------------------------------------------------------- 合计

    private static View buildHero(Activity act, float den, Data d, Pal pal, List<TechBarView> bars) {
        LinearLayout hero = new LinearLayout(act);
        hero.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{pal.heroTop, pal.heroBottom});
        bg.setCornerRadius(dp(den, 14));
        bg.setStroke(Math.max(1, dp(den, 1)), pal.heroStroke);
        hero.setBackground(bg);
        hero.setPadding(dp(den, 12), dp(den, 12), dp(den, 12), dp(den, 12));
        LinearLayout.LayoutParams hlp = lp(-1, -2);
        hlp.bottomMargin = dp(den, 14);
        hero.setLayoutParams(hlp);

        // 标签靠左、大数字靠右，同一行
        LinearLayout topRow = new LinearLayout(act);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.BOTTOM);

        TextView label = new TextView(act);
        label.setText("一次请求合计");
        label.setTextColor(pal.dim);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1f);
        llp.gravity = Gravity.BOTTOM;
        llp.bottomMargin = dp(den, 3);
        topRow.addView(label, llp);

        TextView value = new TextView(act);
        value.setText(figure("", MainActivity.fmt(d.total), " tok", 23f, 12f, pal.fg, pal.dim));
        topRow.addView(value, lp(-2, -2));
        hero.addView(topRow, lp(-1, -2));

        TechBarView bar = new TechBarView(act);
        bar.configure(pal.colorOf(SYS), pal.heroBarEnd(), pal.track, dp(den, 999));
        bar.target = clamp01(d.total / (float) Math.max(1, d.limit));
        bars.add(bar);
        LinearLayout.LayoutParams blp = lp(-1, dp(den, 8));
        blp.topMargin = dp(den, 10);
        blp.bottomMargin = dp(den, 8);
        hero.addView(bar, blp);

        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        TextView cap = new TextView(act);
        cap.setText(figure("上限 ", MainActivity.fmt(d.limit), "", 11f, 11f,
                pal.dark ? 0xFFCFE0FF : pal.fg, pal.dim));
        LinearLayout.LayoutParams clp = lp(-2, -2);
        clp.weight = 1f;
        foot.addView(cap, clp);

        TextView remain = new TextView(act);
        remain.setText(figure("剩余 ", MainActivity.fmt(d.remaining), " tok", 11f, 11f,
                pal.dark ? 0xFFCFE0FF : pal.fg, pal.dim));
        foot.addView(remain, lp(-2, -2));
        hero.addView(foot, lp(-1, -2));
        return hero;
    }

    // ---------------------------------------------------------------- 分段条 + 图例

    private static View buildStack(Activity act, float den, Data d, Pal pal) {
        LinearLayout stack = new LinearLayout(act);
        stack.setOrientation(LinearLayout.HORIZONTAL);
        float sum = 0f;
        for (Item it : d.stack) sum += it.tokens;
        for (int i = 0; i < d.stack.size(); i++) {
            Item it = d.stack.get(i);
            View seg = new View(act);
            seg.setBackground(round(pal.colorOf(it.role), dp(den, 3)));
            // 下限放到能看见的最小比例（~0.6% 宽度），避免小项在分段条里消失
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -1,
                    sum > 0 ? Math.max(0.006f, it.tokens / sum) : 1f);
            if (i > 0) slp.setMarginStart(dp(den, 2));
            stack.addView(seg, slp);
        }
        LinearLayout.LayoutParams stlp = lp(-1, dp(den, 12));
        stlp.topMargin = dp(den, 14);
        stlp.bottomMargin = dp(den, 5);
        stack.setLayoutParams(stlp);
        return stack;
    }

    private static View buildLegend(Activity act, float den, Data d, Pal pal) {
        LinearLayout legend = new LinearLayout(act);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < d.stack.size(); i++) {
            Item it = d.stack.get(i);
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(act);
            dot.setBackground(round(pal.colorOf(it.role), dp(den, 2)));
            cell.addView(dot, lp(dp(den, 7), dp(den, 7)));

            TextView tv = new TextView(act);
            tv.setText(it.name);
            tv.setTextColor(pal.dim);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = lp(-2, -2);
            tlp.setMarginStart(dp(den, 5));
            cell.addView(tv, tlp);

            legend.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams llp = lp(-1, -2);
        llp.bottomMargin = dp(den, 11);
        legend.setLayoutParams(llp);
        return legend;
    }

    // ---------------------------------------------------------------- 明细行

    private static View buildRows(Activity act, float den, Data d, Pal pal, List<TechBarView> bars) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int maxTokens = 0;
        for (Item it : d.rows) maxTokens = Math.max(maxTokens, it.tokens);

        for (int i = 0; i < d.rows.size(); i++) {
            Item it = d.rows.get(i);
            if (it.note) {
                TextView note = new TextView(act);
                note.setText(it.name);
                note.setTextColor(pal.dim);
                note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                note.setPadding(0, dp(den, 6), 0, dp(den, 2));
                box.addView(note, lp(-1, -2));
                continue;
            }
            int color = it.sub ? pal.subColorOf(it.role) : pal.colorOf(it.role);
            if (i > 0) {
                View line = new View(act);
                line.setBackground(new DashedRuleDrawable(pal.dashed, den));
                box.addView(line, lp(-1, Math.max(2, Math.round(den))));
            }

            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, dp(den, 5), 0, dp(den, 6));

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(act);
            dot.setBackground(round(color, dp(den, 2)));
            row.addView(dot, lp(dp(den, 8), dp(den, 8)));

            TextView name = new TextView(act);
            name.setText(it.name);
            name.setTextColor(it.sub ? pal.dim : pal.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, it.sub ? 12 : 13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
            nlp.setMarginStart(dp(den, 9));
            row.addView(name, nlp);

            TextView val = new TextView(act);
            val.setText(MainActivity.fmt(it.tokens));
            val.setTextColor(it.sub ? pal.dim : pal.fg);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, it.sub ? 12 : 13);
            row.addView(val, lp(-2, -2));

            TextView pct = new TextView(act);
            pct.setText(percentText(it.tokens, d.total));
            pct.setTextColor(pal.dim);
            pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, it.sub ? 11 : 12);
            pct.setGravity(Gravity.END);
            row.addView(pct, lp(dp(den, 48), -2));

            wrap.addView(row, lp(-1, -2));

            TechBarView bar = new TechBarView(act);
            bar.configure(color, it.sub ? color : lighten(color, pal), pal.track, dp(den, 999));
            bar.target = maxTokens > 0 ? clamp01(it.tokens / (float) maxTokens) : 0f;
            bars.add(bar);
            LinearLayout.LayoutParams blp = lp(-1, dp(den, it.sub ? 3 : 4));
            blp.topMargin = dp(den, 7);
            blp.setMarginStart(dp(den, it.sub ? 26 : 17));
            wrap.addView(bar, blp);

            box.addView(wrap, lp(-1, -2));
        }
        return box;
    }

    // ---------------------------------------------------------------- 页脚

    private static View buildFoot(Activity act, float den, Data d, Pal pal) {
        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams flp = lp(-1, -2);
        flp.topMargin = dp(den, 12);
        foot.setLayoutParams(flp);

        View dashed = new View(act);
        dashed.setBackground(new DashedRuleDrawable(pal.dashed, den));
        foot.addView(dashed, lp(-1, Math.max(2, Math.round(den))));

        LinearLayout line = new LinearLayout(act);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(den, 10), 0, 0);

        TextView label = new TextView(act);
        label.setText("上次请求模型实报");
        label.setTextColor(pal.dim);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        line.addView(label, lp(-2, -2));

        TextView val = new TextView(act);
        if (d.reported > 0) {
            val.setText(figure(" ", MainActivity.fmt(d.reported), " tok", 11f, 11f,
                    pal.dark ? 0xFFBCD2F5 : pal.accent, pal.dim));
        } else {
            val.setText(" 暂无（还没发过请求）");
            val.setTextColor(pal.dim);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        }
        line.addView(val, lp(-2, -2));
        foot.addView(line, lp(-1, -2));

        if (d.reported > 0) {
            // 有实报值才谈得上"校准"；没有真值时这个标签自相矛盾，整块不画
            View sp = new View(act);
            LinearLayout.LayoutParams slp = lp(0, 1);
            slp.weight = 1f;
            line.addView(sp, slp);

            TextView tag = new TextView(act);
            tag.setText("校准估算");
            tag.setTextColor(pal.tagFg);
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            GradientDrawable tbg = round(pal.tagBg, dp(den, 6));
            tbg.setStroke(Math.max(1, dp(den, 1)), pal.tagStroke);
            tag.setBackground(tbg);
            tag.setPadding(dp(den, 7), dp(den, 2), dp(den, 7), dp(den, 2));
            line.addView(tag, lp(-2, -2));
        }
        return foot;
    }

    // ---------------------------------------------------------------- 小工具

    private static void animateBars(List<TechBarView> bars) {
        if (bars.isEmpty()) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            for (TechBarView b : bars) { b.fraction = b.target; b.invalidate(); }
            return;
        }
        for (int i = 0; i < bars.size(); i++) {
            final TechBarView b = bars.get(i);
            b.fraction = 0f;
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(460);
            va.setStartDelay(Math.min(180, i * 25L));
            va.addUpdateListener(a -> {
                float p = (float) a.getAnimatedValue();
                float e = 1f - (1f - p) * (1f - p) * (1f - p);
                b.fraction = b.target * e;
                b.invalidate();
            });
            va.start();
        }
    }

    /** 把"前缀 + 数字 + 单位"拼成一段，数字部分单独放大/提亮。 */
    private static CharSequence figure(String prefix, String num, String suffix,
                                       float numSp, float unitSp, int numColor, int unitColor) {
        SpannableString s = new SpannableString(prefix + num + suffix);
        int a = prefix.length(), b = a + num.length();
        s.setSpan(new AbsoluteSizeSpan(Math.round(numSp), true), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(numColor), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!suffix.isEmpty()) {
            s.setSpan(new AbsoluteSizeSpan(Math.round(unitSp), true), b, s.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(unitColor), b, s.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!prefix.isEmpty()) {
            s.setSpan(new AbsoluteSizeSpan(Math.round(unitSp), true), 0, a,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(unitColor), 0, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    /** 占比文案：0 就是 0%；非零但很小则显示 <0.1%（写 0% 会让人以为没算上）。 */
    private static String percentText(int tokens, int total) {
        if (total <= 0) return "—";
        if (tokens <= 0) return "0%";
        float pct = tokens * 100f / total;
        if (pct < 0.1f) return "<0.1%";
        return oneDecimal(pct) + "%";
    }

    private static String oneDecimal(float v) {
        int x10 = Math.round(v * 10f);
        return x10 % 10 == 0 ? String.valueOf(x10 / 10) : (x10 / 10) + "." + (x10 % 10);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 往白里提亮（条形末端渐变用）。 */
    private static int lighten(int color, Pal pal) {
        return blend(color, 0xFFFFFFFF, pal.dark ? 0.35f : 0.28f);
    }

    /** 与给定底色按 t 混合（t=0 保留原色，t=1 变底色）。 */
    private static int blend(int fg, int bg, float t) {
        int r = clamp255(Math.round(Color.red(fg) * (1 - t) + Color.red(bg) * t));
        int g = clamp255(Math.round(Color.green(fg) * (1 - t) + Color.green(bg) * t));
        int b = clamp255(Math.round(Color.blue(fg) * (1 - t) + Color.blue(bg) * t));
        return Color.rgb(r, g, b);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        return bg;
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static int dp(float density, int v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- 条形

    static class TechBarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int startColor, endColor, trackColor;
        private float radius;
        float fraction = 0f;
        float target = 0f;

        TechBarView(Context c) {
            super(c);
        }

        void configure(int startColor, int endColor, int trackColor, float radius) {
            this.startColor = startColor;
            this.endColor = endColor;
            this.trackColor = trackColor;
            this.radius = radius;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(trackColor);
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, radius, radius, p);

            float fw = Math.max(0f, Math.min(1f, fraction)) * w;
            // 有量就必须看得见：极小占比（如本条消息 11 tok）也要留一小段，
            // 否则空槽看起来像没渲染（用户会当成 bug）。
            if (fw > 0f) fw = Math.max(fw, getResources().getDisplayMetrics().density * 4f);
            if (fw > 0.5f) {
                p.setShader(new LinearGradient(0, 0, w, 0, startColor, endColor, Shader.TileMode.CLAMP));
                r.set(0, 0, Math.min(Math.max(fw, h), w), h);
                cv.drawRoundRect(r, radius, radius, p);
                p.setShader(null);
            }
        }
    }

    /** 细分隔线（虚线）。用自绘而不是 GradientDrawable 的 LINE 形状：
     *  后者的虚线在 1~2px 高的 View 里会被压成实线。 */
    static class DashedRuleDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DashedRuleDrawable(int color, float density) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, density));
            p.setPathEffect(new DashPathEffect(
                    new float[]{3 * density, 3 * density}, 0f));
        }

        @Override
        public void draw(Canvas cv) {
            float y = getBounds().height() / 2f;
            cv.drawLine(getBounds().left, y, getBounds().right, y, p);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }

        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
