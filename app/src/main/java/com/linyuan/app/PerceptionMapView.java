package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「感知树」的思维导图画布（2026-10-13 用户："完全就是往上下滑动的各种卡片……看不到分支属于谁……
 * 能不能像一棵树/思维导图那样，能放大缩小像画布一样"）。
 *
 * <p>画法：左边一个根（📱），一级目录各占一条彩色主枝，同一条分支的后代共用这个颜色；
 * 父到子用曲线相连（像树枝），节点是挂在枝上的小圆角牌子，点它看内容，
 * 牌子右侧的小圆点收起/展开这一支。捏合缩放、拖动平移、双击或「适应」复位。
 *
 * <p>只画视口内的东西（大树也不卡）；折叠状态在 view 内自己维护，不改数据。
 */
public class PerceptionMapView extends View {

    public interface Listener {
        void onNodeTap(AppDatabase.PerceptionNode n);
    }

    /** 分支配色（同支同色；_framework 固定节点用灰色单独画）。 */
    private static final int[] PALETTE = {
            0xFF4A90D9, 0xFFE67E22, 0xFF27AE60, 0xFF9B59B6,
            0xFFE74C3C, 0xFF16A085, 0xFFD4831A, 0xFF8E44AD,
            0xFF2E7D6B, 0xFFC2456B
    };
    private static final int FIXED_COLOR = 0xFF9AA0A6;

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // 数据
    private List<AppDatabase.PerceptionNode> nodes = new ArrayList<>();
    private final Map<Long, List<AppDatabase.PerceptionNode>> byParent = new HashMap<>();
    private final Set<Long> collapsed = new HashSet<>();
    private final Map<Long, RectF> rects = new HashMap<>();
    private final Map<Long, RectF> toggles = new HashMap<>();
    private final Map<Long, String> labels = new HashMap<>();
    private final Map<Long, Integer> colors = new HashMap<>();
    private final Set<Long> withContent = new HashSet<>();
    private final RectF bounds = new RectF();
    private float rootCx, rootCy, rootR;
    private int lastSize = -1;

    // 视口
    private float scale = 0f, tx = 0f, ty = 0f;
    private boolean userMoved = false;
    private float downX, downY, lastX, lastY;
    private boolean multi = false;

    // 颜色（由 PerceptionView 注入，跟随主题）
    private int textColor = 0xFF222222;
    private int surfaceColor = 0xFFF5F6F8;
    private int accentColor = 0xFF4A90D9;

    private Listener listener;
    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;

    public PerceptionMapView(Context ctx) {
        super(ctx);
        d = getResources().getDisplayMetrics().density;
        title.setTypeface(Typeface.DEFAULT_BOLD);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1.4f, 1.8f * d));
        line.setStrokeCap(Paint.Cap.ROUND);

        sgd = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector det) {
                float ns = clamp(scale * det.getScaleFactor(), 0.22f, 3.2f);
                if (scale <= 0f) scale = 1f;
                float fx = det.getFocusX(), fy = det.getFocusY();
                tx = fx - (fx - tx) * (ns / scale);
                ty = fy - (fy - ty) * (ns / scale);
                scale = ns;
                userMoved = true;
                clampPan();
                invalidate();
                return true;
            }
        });
        gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (multi || sgd.isInProgress()) return true;
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 16 * d) return true;
                handleTap(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fit();
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setColors(int text, int surface, int accent) {
        this.textColor = text;
        this.surfaceColor = surface;
        this.accentColor = accent;
    }

    // ------------------------------------------------------------
    // 数据与布局
    // ------------------------------------------------------------

    /** 换数据。节点数变了（新增/删除）或用户还没动过视口 → 自动适应屏幕；只改内容 → 保持当前视角。 */
    public void setData(List<AppDatabase.PerceptionNode> all) {
        nodes = all == null ? new ArrayList<>() : all;
        boolean sizeChanged = nodes.size() != lastSize;
        lastSize = nodes.size();
        relayout();
        if (scale <= 0f || !userMoved || sizeChanged) fit();
        invalidate();
    }

    /**
     * 布局。异常绝不外抛（2026-10-14 实机事故后加）：这个画布是「感知树」入口的全部内容，
     * 崩在这里 = 用户彻底进不去这个页面——且重开一次又走同一条路，会一直进不去。
     * 异常时降级为空画布（数据一条不动），原因写 logcat。所有调用点（setData / 点折叠圆点）都经这里。
     */
    private void relayout() {
        try {
            relayoutInner();
        } catch (Throwable t) {
            android.util.Log.w("VC_PERCEPTION", "画布布局失败（已降级为空视图，数据未动）", t);
            rects.clear();
            toggles.clear();
            labels.clear();
            colors.clear();
            bounds.setEmpty();
            rootR = 0;
        }
    }

    private void relayoutInner() {
        byParent.clear();
        rects.clear();
        toggles.clear();
        labels.clear();
        colors.clear();
        withContent.clear();
        bounds.setEmpty();

        Map<Long, AppDatabase.PerceptionNode> byId = new HashMap<>();
        for (AppDatabase.PerceptionNode n : nodes) {
            byId.put(n.id, n);
            List<AppDatabase.PerceptionNode> l = byParent.get(n.parentId);
            if (l == null) { l = new ArrayList<>(); byParent.put(n.parentId, l); }
            l.add(n);
            if (n.content != null && !n.content.trim().isEmpty()) withContent.add(n.id);
        }
        List<AppDatabase.PerceptionNode> roots = byParent.get(0L);
        if (roots == null || roots.isEmpty()) {
            rootR = 0;
            return;
        }

        // 先量好每个节点的宽度和"深度"，再算出每一层统一的 x 起点 ——
        // 同一层对齐（用户 2026-10-13 晚："不够结构……看不出来哪一层是哪一层"）
        Map<Long, Float> width = new HashMap<>();
        Map<Long, Integer> depth = new HashMap<>();
        int maxDepth = 0;
        float colGap = 34 * d;
        for (AppDatabase.PerceptionNode n : nodes) {
            List<AppDatabase.PerceptionNode> kids = byParent.get(n.id);
            boolean hasKids = kids != null && !kids.isEmpty();
            width.put(n.id, measureWidth(n, hasKids));
            int dpt = depthOf(n, byId);
            depth.put(n.id, dpt);
            if (dpt > maxDepth) maxDepth = dpt;
        }
        // 列坐标：摆进 colX[c] 的节点 place 深度为 c（place 深度 = depthOf+1；
        // 根 depthOf=0 落在 colX[1]）。最深一层 depthOf=maxDepth 落在 colX[maxDepth+1]，
        // 所以数组必须 maxDepth+2 格 —— 2026-10-14 修：树里只剩根时 maxDepth=0，
        // 旧代码 new float[1] 后写 colX[1] 越界崩溃（删到只剩根就崩、之后入口永远点不开）。
        float[] colX = new float[maxDepth + 2];
        colX[1] = 0f;
        for (int lev = 1; lev <= maxDepth; lev++) {
            // 给这一列往后让位的宽度 = 摆在本列（depthOf=lev-1）里最宽的那个节点；
            // 旧代码用的是"下一层"的宽度，窄标题跟在宽标题后面时会撞位。
            float maxW = 0;
            for (Map.Entry<Long, Integer> en : depth.entrySet()) {
                if (en.getValue() == lev - 1) {
                    Float w = width.get(en.getKey());
                    if (w != null) maxW = Math.max(maxW, w);
                }
            }
            colX[lev + 1] = colX[lev] + maxW + colGap;
        }

        float[] cursor = {0f};
        int i = 0;
        for (AppDatabase.PerceptionNode r : roots) {
            boolean fixed = PerceptionStore.isFrameworkRow(r);
            int color = fixed ? FIXED_COLOR : PALETTE[i % PALETTE.length];
            place(r, 1, color, cursor, width, colX, fixed);
            i++;
        }

        // 根圆：所有一级目录左侧
        float minLeft = Float.MAX_VALUE, top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (AppDatabase.PerceptionNode r : roots) {
            RectF rr = rects.get(r.id);
            if (rr == null) continue;
            minLeft = Math.min(minLeft, rr.left);
            top = Math.min(top, rr.centerY());
            bottom = Math.max(bottom, rr.centerY());
        }
        if (minLeft == Float.MAX_VALUE) return;
        rootR = 15 * d;
        rootCx = minLeft - 30 * d - rootR;
        rootCy = (top + bottom) / 2f;
        bounds.union(rootCx - rootR, rootCy - rootR, rootCx + rootR, rootCy + rootR);
    }

    /** 节点的渲染宽度（标题量完 + 给折叠圆点留位置）。 */
    private float measureWidth(AppDatabase.PerceptionNode n, boolean hasKids) {
        String label = n.title == null ? "" : n.title.trim();
        if (PerceptionStore.isFrameworkRow(n)) label = "🔒 " + label;
        title.setTextSize(12 * d);
        CharSequence cs = TextUtils.ellipsize(label, title, 128 * d, TextUtils.TruncateAt.END);
        float tw = title.measureText(cs.toString());
        return Math.max(46 * d, tw + 9 * d * 2 + (hasKids ? 12 * d : 0));
    }

    /** 第几层（根目录=1）。 */
    private int depthOf(AppDatabase.PerceptionNode n, Map<Long, AppDatabase.PerceptionNode> byId) {
        int d = 0;
        long cur = n.parentId;
        int guard = 0;
        while (cur != 0 && guard++ < 64) {
            AppDatabase.PerceptionNode p = byId.get(cur);
            if (p == null) break;
            d++;
            cur = p.parentId;
        }
        return d;
    }

    /**
     * 摆放一个节点（含其可见子树）。
     * @return 该节点的中心 y
     */
    private float place(AppDatabase.PerceptionNode n, int depth, int color,
                        float[] cursor, Map<Long, Float> width, float[] colX, boolean fixed) {
        String label = n.title == null ? "" : n.title.trim();
        if (fixed) label = "🔒 " + label;
        List<AppDatabase.PerceptionNode> kids = byParent.get(n.id);
        boolean hasKids = kids != null && !kids.isEmpty();

        float x = colX[Math.min(depth, colX.length - 1)];
        float w = width.get(n.id) == null ? 46 * d : width.get(n.id);
        float h = 26 * d;

        float cy;
        List<AppDatabase.PerceptionNode> vis = collapsed.contains(n.id) ? null : kids;
        if (vis == null || vis.isEmpty()) {
            cy = cursor[0] + h / 2f;
            cursor[0] += h + 11 * d;
        } else {
            float first = 0, last = 0;
            for (int i = 0; i < vis.size(); i++) {
                float ky = place(vis.get(i), depth + 1, color, cursor, width, colX, false);
                if (i == 0) first = ky;
                last = ky;
            }
            cy = (first + last) / 2f;
        }

        RectF rect = new RectF(x, cy - h / 2f, x + w, cy + h / 2f);
        rects.put(n.id, rect);
        title.setTextSize(12 * d);
        CharSequence cs = TextUtils.ellipsize(label, title, 128 * d, TextUtils.TruncateAt.END);
        labels.put(n.id, cs.toString());
        colors.put(n.id, color);
        bounds.union(rect);
        if (hasKids) {
            float r = 8 * d;
            RectF t = new RectF(rect.right + 8 * d, cy - r, rect.right + 8 * d + r * 2, cy + r);
            toggles.put(n.id, t);
            bounds.union(t);
        }
        return cy;
    }

    // ------------------------------------------------------------
    // 视口
    // ------------------------------------------------------------

    public void fit() {
        if (bounds.isEmpty() || getWidth() == 0 || getHeight() == 0) return;
        float pad = 18 * d;
        float cw = Math.max(1f, bounds.width());
        float ch = Math.max(1f, bounds.height());
        float s = Math.min((getWidth() - 2 * pad) / cw, (getHeight() - 2 * pad) / ch);
        s = clamp(Math.min(s, 1.15f), 0.22f, 3.2f);
        scale = s;
        tx = (getWidth() - cw * scale) / 2f - bounds.left * scale;
        ty = (getHeight() - ch * scale) / 2f - bounds.top * scale;
        if (ch * scale > getHeight() - 2 * pad) ty = pad - bounds.top * scale;
        userMoved = false;
        clampPan();
        invalidate();
    }

    /** 以视口中心为锚点缩放（弹窗下方 −/＋ 按钮用）。 */
    public void zoomBy(float factor) {
        if (scale <= 0f) scale = 1f;
        float fx = getWidth() / 2f, fy = getHeight() / 2f;
        float ns = clamp(scale * factor, 0.22f, 3.2f);
        tx = fx - (fx - tx) * (ns / scale);
        ty = fy - (fy - ty) * (ns / scale);
        scale = ns;
        userMoved = true;
        clampPan();
        invalidate();
    }

    private void clampPan() {
        if (bounds.isEmpty() || getWidth() == 0) return;
        float vis = 44 * d;
        float loX = vis - bounds.right * scale;
        float hiX = getWidth() - vis - bounds.left * scale;
        if (loX > hiX) tx = (getWidth() - (bounds.left + bounds.right) * scale) / 2f;
        else tx = clamp(tx, loX, hiX);
        float loY = vis - bounds.bottom * scale;
        float hiY = getHeight() - vis - bounds.top * scale;
        if (loY > hiY) ty = (getHeight() - (bounds.top + bounds.bottom) * scale) / 2f;
        else ty = clamp(ty, loY, hiY);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (scale <= 0f) fit();
        else clampPan();
    }

    // ------------------------------------------------------------
    // 触摸
    // ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                multi = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() == 1 && !sgd.isInProgress()) {
                    float x = e.getX(), y = e.getY();
                    tx += x - lastX;
                    ty += y - lastY;
                    lastX = x;
                    lastY = y;
                    if (Math.hypot(x - downX, y - downY) > 8 * d) userMoved = true;
                    clampPan();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                multi = false;
                break;
            default:
                break;
        }
        sgd.onTouchEvent(e);
        gd.onTouchEvent(e);
        return true;
    }

    private void handleTap(float sx, float sy) {
        // 先看收起/展开的小圆点
        for (Map.Entry<Long, RectF> en : toggles.entrySet()) {
            RectF t = en.getValue();
            float cx = tx + t.centerX() * scale;
            float cy = ty + t.centerY() * scale;
            float r = Math.max(15 * d, t.width() / 2f * scale + 8 * d);
            if (Math.hypot(sx - cx, sy - cy) <= r) {
                Long id = en.getKey();
                if (collapsed.contains(id)) collapsed.remove(id);
                else collapsed.add(id);
                relayout();
                clampPan();
                invalidate();
                return;
            }
        }
        float wx = (sx - tx) / (scale <= 0f ? 1f : scale);
        float wy = (sy - ty) / (scale <= 0f ? 1f : scale);
        float slop = 8 * d;
        for (AppDatabase.PerceptionNode n : nodes) {
            RectF r = rects.get(n.id);
            if (r == null) continue;
            if (wx >= r.left - slop && wx <= r.right + slop
                    && wy >= r.top - slop && wy <= r.bottom + slop) {
                if (listener != null) listener.onNodeTap(n);
                return;
            }
        }
    }

    // ------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (scale <= 0f || rects.isEmpty()) return;

        RectF view = new RectF(-tx / scale - 30 * d, -ty / scale - 30 * d,
                (getWidth() - tx) / scale + 30 * d, (getHeight() - ty) / scale + 30 * d);

        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(scale, scale);

        // 1) 枝条：根 → 一级
        if (rootR > 0) {
            List<AppDatabase.PerceptionNode> roots = byParent.get(0L);
            if (roots != null) {
                for (AppDatabase.PerceptionNode r : roots) {
                    RectF rr = rects.get(r.id);
                    if (rr == null) continue;
                    Integer c = colors.get(r.id);
                    line.setColor(withAlpha(c == null ? accentColor : c, 150));
                    canvas.drawLine(rootCx + rootR, rootCy, rr.left, rr.centerY(), line);
                }
            }
        }
        // 2) 枝条：父 → 子（曲线）
        for (AppDatabase.PerceptionNode n : nodes) {
            RectF cr = rects.get(n.id);
            if (cr == null) continue;
            RectF pr = rects.get(n.parentId);
            if (pr == null) continue;
            RectF linkBox = new RectF(Math.min(pr.right, cr.left), Math.min(pr.centerY(), cr.centerY()),
                    Math.max(pr.right, cr.left), Math.max(pr.centerY(), cr.centerY()));
            if (!RectF.intersects(view, linkBox)) continue;
            Integer c = colors.get(n.id);
            line.setColor(withAlpha(c == null ? accentColor : c, 165));
            path.reset();
            path.moveTo(pr.right, pr.centerY());
            float mx = (pr.right + cr.left) / 2f;
            path.cubicTo(mx, pr.centerY(), mx, cr.centerY(), cr.left, cr.centerY());
            canvas.drawPath(path, line);
        }
        // 3) 根圆
        if (rootR > 0) {
            card.setStyle(Paint.Style.FILL);
            card.setColor(accentColor);
            canvas.drawCircle(rootCx, rootCy, rootR, card);
            title.setTextSize(14 * d);
            title.setColor(0xFFFFFFFF);
            Paint.FontMetrics fm = title.getFontMetrics();
            float base = rootCy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText("📱", rootCx - title.measureText("📱") / 2f, base, title);
        }

        // 4) 节点牌
        boolean drawText = scale >= 0.32f;
        for (AppDatabase.PerceptionNode n : nodes) {
            RectF r = rects.get(n.id);
            if (r == null || !RectF.intersects(view, r)) continue;
            Integer c = colors.get(n.id);
            int color = c == null ? accentColor : c;

            float radius = 7 * d;
            card.setStyle(Paint.Style.FILL);
            card.setColor(blend(surfaceColor, color, 0.13f));
            canvas.drawRoundRect(r, radius, radius, card);
            card.setStyle(Paint.Style.STROKE);
            card.setStrokeWidth(Math.max(1f, 1.1f * d));
            card.setColor(withAlpha(color, 190));
            canvas.drawRoundRect(r, radius, radius, card);

            // 有内容的小点
            if (withContent.contains(n.id)) {
                dot.setColor(withAlpha(color, 220));
                float dx = r.right - (toggles.containsKey(n.id) ? 6 * d : 7 * d) - 2.4f * d;
                canvas.drawCircle(dx, r.centerY(), 2.4f * d, dot);
            }

            if (drawText) {
                title.setTextSize(12 * d);
                title.setColor(textColor);
                Paint.FontMetrics fm = title.getFontMetrics();
                float base = r.centerY() - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(labels.get(n.id) == null ? "" : labels.get(n.id), r.left + 9 * d, base, title);
            }

            // 收起/展开圆点
            RectF t = toggles.get(n.id);
            if (t != null) {
                boolean isC = collapsed.contains(n.id);
                card.setStyle(Paint.Style.FILL);
                card.setColor(blend(surfaceColor, color, 0.30f));
                canvas.drawCircle(t.centerX(), t.centerY(), t.width() / 2f, card);
                card.setStyle(Paint.Style.STROKE);
                card.setStrokeWidth(Math.max(1f, 1.3f * d));
                card.setColor(withAlpha(color, 230));
                canvas.drawCircle(t.centerX(), t.centerY(), t.width() / 2f, card);
                line.setColor(withAlpha(color, 240));
                line.setStrokeWidth(Math.max(1.4f, 1.8f * d));
                float g = 3.4f * d;
                canvas.drawLine(t.centerX() - g, t.centerY(), t.centerX() + g, t.centerY(), line);
                if (isC) canvas.drawLine(t.centerX(), t.centerY() - g, t.centerX(), t.centerY() + g, line);
            }
        }

        canvas.restore();
    }

    // ------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** color 按 t 混进 base（t=0 全 base，t=1 全 color）。 */
    private static int blend(int base, int color, float t) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
        int r = (int) (br + (cr - br) * t);
        int g = (int) (bg + (cg - bg) * t);
        int b = (int) (bb + (cb - bb) * t);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
