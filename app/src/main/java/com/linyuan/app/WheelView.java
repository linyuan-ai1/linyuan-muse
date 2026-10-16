package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 自绘滚轮选择器（选小时 / 分钟用）。
 *
 * <p>为什么不用系统 {@link android.widget.NumberPicker}：它把上下两行画在画布上，
 * 颜色字号存在私有字段（mSelectorWheelPaint）里，只能靠反射改。我反射改过了，
 * 但在 ColorOS 上实测是「只看得见中间一行，一滑动整片空白」——用户 2026-10-13
 * 反馈的现象。与其继续猜系统私有实现，不如自己画：上面一行、中间选中行、下面
 * 一行全部由本类绘制，颜色字号写在自己的 Paint 里，不依赖任何私有字段。
 *
 * <p>做成**循环**滚轮（跟系统闹钟一致）：在 00 时继续下滑会露出 23 时，
 * 任何时刻三行都填满，不会出现"上/下是空白"。
 */
public class WheelView extends View {

    private String[] labels = new String[0];
    /** 落定的选中项。 */
    private int index = 0;
    /** 连续位置（可为任意实数）：中心正在显示哪个虚拟序号（可越界，显示时按模映射）。 */
    private double pos = 0;

    private Runnable onChanged;

    private final Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 单行高度（px）。 */
    private float itemH;
    private float downY = 0;
    private double startPos = 0;
    private boolean dragging = false;
    private float lastY = 0;
    private long lastT = 0;
    private float velocity = 0;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable animStep;

    public WheelView(Context c) {
        super(c);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        itemH = 44 * d;
        normalPaint.setTextSize(16 * d);
        normalPaint.setTextAlign(Paint.Align.CENTER);
        selPaint.setTextSize(19 * d);
        selPaint.setTextAlign(Paint.Align.CENTER);
        selPaint.setFakeBoldText(true);
        linePaint.setStrokeWidth(Math.max(1f, d));
        applyColors(Color.parseColor("#202632"), Color.parseColor("#1A6BFF"),
                Color.parseColor("#EEF3FF"), Color.parseColor("#DCE1E8"),
                Color.parseColor("#FFFFFF"));
    }

    /** 主题色注入（用 Activity 主题解析出来的颜色，避免写死和暗色模式问题）。 */
    public void applyColors(int text, int accent, int selBg, int line, int bg) {
        normalPaint.setColor(text);
        selPaint.setColor(accent);
        selBgPaint.setColor(selBg);
        linePaint.setColor(line);
        setBackgroundColor(bg);
        invalidate();
    }

    /** 设置内容与初始选中项。changed 可为 null。 */
    public void setUp(String[] labels, int initIndex, Runnable changed) {
        this.labels = labels == null ? new String[0] : labels;
        this.onChanged = changed;
        int n = this.labels.length;
        this.index = n == 0 ? 0 : Math.max(0, Math.min(n - 1, initIndex));
        this.pos = this.index;
        invalidate();
    }

    public int getIndex() {
        return index;
    }

    private int count() {
        return labels.length;
    }

    /** 虚拟序号 → 实际下标（循环）。 */
    private int wrap(int k) {
        int n = count();
        if (n <= 0) return 0;
        int r = k % n;
        return r < 0 ? r + n : r;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize((int) (getResources().getDisplayMetrics().density * 110), wSpec);
        int h = (int) (itemH * 3f) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (count() == 0) return;
        float cy = getHeight() / 2f;
        float w = getWidth();
        float radius = 10 * getResources().getDisplayMetrics().density;

        // 选中行底 + 上下分隔线
        cv.drawRoundRect(0, cy - itemH / 2f, w, cy + itemH / 2f, radius, radius, selBgPaint);
        float lw = linePaint.getStrokeWidth();
        cv.drawRect(0, cy - itemH / 2f, w, cy - itemH / 2f + lw, linePaint);
        cv.drawRect(0, cy + itemH / 2f - lw, w, cy + itemH / 2f, linePaint);

        // 画中心 ±2 范围内的行：y(k) = cy + (k - pos) * itemH；k 与 pos 都是虚拟序号，
        // 显示文字走 wrap 循环，所以最上/最下永远不会空。
        float viewH = getHeight();
        int base = (int) Math.floor(pos);
        for (int k = base - 2; k <= base + 2; k++) {
            float y = cy + (float) ((k - pos) * itemH);
            if (y < -itemH * 0.8f || y > viewH + itemH * 0.8f) continue;
            float dist = (float) Math.abs(k - pos);
            float alpha = Math.max(0.25f, 1f - dist * 0.45f);
            Paint p = (dist < 0.5f) ? selPaint : normalPaint;
            int baseAlpha = p.getAlpha();
            p.setAlpha((int) (255 * alpha));
            cv.drawText(labels[wrap(k)], w / 2f, y - (p.descent() + p.ascent()) / 2f, p);
            p.setAlpha(baseAlpha);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                downY = e.getY();
                startPos = pos;
                lastY = e.getY();
                lastT = System.currentTimeMillis();
                velocity = 0;
                h.removeCallbacks(animStep);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                // 手指往下拖 = 露出更小的值（跟系统闹钟一致）
                double want = startPos - (e.getY() - downY) / itemH;
                double lo = Math.floor(startPos) - 1.3, hi = Math.ceil(startPos) + 1.3;
                if (want < lo) want = lo;
                if (want > hi) want = hi;
                pos = want;
                long now = System.currentTimeMillis();
                long dt = now - lastT;
                if (dt > 0) velocity = (e.getY() - lastY) / (float) dt * 1000f;   // px/s
                lastY = e.getY();
                lastT = now;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return true;
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                float d = getResources().getDisplayMetrics().density;
                int target = (int) Math.round(pos);
                if (velocity < -500 * d) target += 1;        // 快速上甩 = 值变大
                else if (velocity > 500 * d) target -= 1;    // 快速下甩 = 值变小
                animateTo(target);
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /** 平滑收尾：pos 动画到 target，落定后归一化并回调。 */
    private void animateTo(final int target) {
        final double from = pos;
        final long t0 = System.currentTimeMillis();
        final long dur = 170;
        final DecelerateInterpolator di = new DecelerateInterpolator();
        h.removeCallbacks(animStep);
        animStep = new Runnable() {
            @Override
            public void run() {
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                pos = from + (target - from) * di.getInterpolation(p);
                if (p >= 1f) {
                    int ni = wrap(target);
                    pos = ni;                    // 归一化到 [0, n)：画面等价，无跳动
                    if (index != ni) {
                        index = ni;
                        if (onChanged != null) onChanged.run();
                    }
                    invalidate();
                    return;
                }
                invalidate();
                h.postDelayed(this, 16);
            }
        };
        h.post(animStep);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        h.removeCallbacks(animStep);
    }
}
