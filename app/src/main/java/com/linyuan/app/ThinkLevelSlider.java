package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 思考档位滑杆（用户 2026-10-14 定案的"灵液轨道"设计，先在浏览器里定稿再搬到这里）。
 *
 * 视觉：
 *  - 凹槽轨道（细边 + 内阴影感），左边填充成蓝色液体，右端一个白色圆环把手；
 *  - 轨道 20dp 粗、把手 34dp，跟原型一致；
 *  - 最高档（Ultra）时：液体表面有流光扫过，轨道里同时有 5~8 颗流星从右往左飘
 *    （亮头朝左，向右拖尾）。每颗流星的长度/速度/发射时机各自随机，飞完重新随机。
 *
 * 交互：整条滑杆可拖动/点按，**档位一变就实时回调**（跟手，不用等松手）。
 *
 * 为什么不用系统 SeekBar：系统控件的样式在 ColorOS 上被主题改得很难看（项目里
 * 之前也踩过 NumberPicker 的坑），而且流星这类动效只能自绘。
 */
public class ThinkLevelSlider extends View {

    public interface OnLevelChanged {
        void onLevelChanged(int level, boolean fromUser);
    }

    private static final int MAX_LEVEL = 5;

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMeteor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSweep = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF trackRect = new RectF();
    private final RectF fillRect = new RectF();
    private final RectF sweepRect = new RectF();

    private final float density;
    private final float trackH;
    private final float thumbR;

    private int level = MAX_LEVEL;
    private OnLevelChanged listener;

    private int colorTrack;
    private int colorAccent;
    private int colorAccentBright;
    private int colorSurface;
    private int colorFaint;

    private boolean running;

    /** 一颗流星：长度、时长、起始延迟、当前开始时刻。 */
    private static class Meteor {
        float widthPx;
        float travelPx;
        long durationMs;
        long startAt;
        float x;          // 当前左端位置（相对轨道左缘）
        boolean active;
        final float yBias; // 上下轻微错开，避免排成一条线

        Meteor(float yBias) { this.yBias = yBias; }
    }

    private final List<Meteor> meteors = new ArrayList<>();
    private final Random rnd = new Random();
    private long lastFrame;
    private float sweepPhase;

    public ThinkLevelSlider(Context c) { this(c, null); }

    public ThinkLevelSlider(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        trackH = 20 * density;
        thumbR = 17 * density;

        // 默认浅色（浅色主题的取值）。深色主题由调用方 setColors() 覆盖
        // —— 用户 2026-10-15 定：档位卡要跟随主题，不再是永远一套白底。
        colorTrack = 0xFFFBF9F5;
        colorAccent = 0xFF2F6BE6;
        colorAccentBright = 0xFF4C8DFF;
        colorSurface = 0xFFFFFFFF;
        colorFaint = 0xFFB4BAC6;

        for (int i = 0; i < 8; i++) {
            meteors.add(new Meteor((rnd.nextFloat() - 0.5f) * 0.42f));
        }
        setClickable(true);
        setFocusable(true);
    }

    /** 由调用方按当前主题注入一套颜色（深色主题下轨道/把手/描边都要换）。 */
    void setColors(int track, int accent, int accentBright, int surface, int faint) {
        this.colorTrack = track;
        this.colorAccent = accent;
        this.colorAccentBright = accentBright;
        this.colorSurface = surface;
        this.colorFaint = faint;
        invalidate();
    }

    private int col(int res) {
        try { return getResources().getColor(res); } catch (Throwable t) { return 0xFF2F6BE6; }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    public void setOnLevelChanged(OnLevelChanged l) { this.listener = l; }

    public int getLevel() { return level; }

    public void setLevel(int lv) {
        int v = Math.max(0, Math.min(MAX_LEVEL, lv));
        if (v == level) { invalidate(); return; }
        level = v;
        onLevelSettled();
        invalidate();
    }

    private void onLevelSettled() {
        boolean ultra = (level == MAX_LEVEL);
        for (Meteor m : meteors) m.active = false;
        if (ultra) seedMeteors(SystemClock.uptimeMillis());
        updateRunning();
    }

    /** Ultra 档：一次给 5~8 颗流星分配各自的长度/速度/起飞时刻。 */
    private void seedMeteors(long now) {
        int count = 5 + rnd.nextInt(4);
        float trackW = Math.max(1f, trackRect.width());
        for (int i = 0; i < meteors.size(); i++) {
            Meteor m = meteors.get(i);
            m.active = i < count;
            if (!m.active) continue;
            m.widthPx = (22 + rnd.nextFloat() * 20) * density;
            m.travelPx = trackW + m.widthPx + 20 * density;
            m.durationMs = 1500 + rnd.nextInt(900);
            m.startAt = now + (long) (rnd.nextFloat() * 900);
            m.x = trackW + 10 * density;
        }
    }

    private void updateRunning() {
        boolean want = (level == MAX_LEVEL) && isShown();
        if (want == running) return;
        running = want;
        if (running) {
            lastFrame = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float pad = thumbR + 6 * density;
        float cy = h / 2f;
        trackRect.set(pad, cy - trackH / 2f, w - pad, cy + trackH / 2f);
        if (level == MAX_LEVEL) seedMeteors(SystemClock.uptimeMillis());
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateRunning();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    private float centerXForLevel(int lv) {
        float pad = thumbR + 6 * density;
        float span = (getWidth() - 2 * pad);
        if (span <= 0) return pad;
        return pad + span * (lv / (float) MAX_LEVEL);
    }

    @Override
    protected void onDraw(Canvas cv) {
        boolean ultra = (level == MAX_LEVEL);
        float cx = centerXForLevel(level);
        long now = SystemClock.uptimeMillis();

        // ---- 轨道凹槽 ----
        pTrack.setColor(colorTrack);
        cv.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, pTrack);

        // ---- 液体填充 ----
        if (level > 0) {
            fillRect.set(trackRect.left, trackRect.top, Math.max(trackRect.left + trackH, cx), trackRect.bottom);
            pFill.setShader(new LinearGradient(fillRect.left, 0, fillRect.right, 0,
                    colorAccent, colorAccentBright, Shader.TileMode.CLAMP));
            cv.drawRoundRect(fillRect, trackH / 2f, trackH / 2f, pFill);
            pFill.setShader(null);

            if (ultra) {
                // 流光扫过
                float period = 1900f;
                sweepPhase = ((now % (long) period) / period);
                float sw = Math.max(60 * density, fillRect.width() * 0.34f);
                float sx = fillRect.left + (fillRect.width() + sw * 2) * sweepPhase - sw;
                sweepRect.set(sx, fillRect.top, sx + sw, fillRect.bottom);
                pSweep.setShader(new LinearGradient(sweepRect.left, 0, sweepRect.right, 0,
                        new int[]{0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                cv.save();
                cv.clipRect(fillRect);
                cv.drawRect(sweepRect, pSweep);
                cv.restore();
                pSweep.setShader(null);
            }
        }

        // ---- 流星（只在 Ultra 档，从右往左）----
        if (ultra && running) {
            cv.save();
            cv.clipRect(trackRect);
            for (Meteor m : meteors) {
                if (!m.active) continue;
                if (now < m.startAt) continue;
                float t = (now - m.startAt) / (float) m.durationMs;
                if (t >= 1f) {
                    // 飞完重新随机，保持"永远有流星飘"
                    m.widthPx = (22 + rnd.nextFloat() * 20) * density;
                    m.travelPx = trackRect.width() + m.widthPx + 20 * density;
                    m.durationMs = 1500 + rnd.nextInt(900);
                    m.startAt = now + (long) (rnd.nextFloat() * 700);
                    m.x = trackRect.right + 10 * density;
                    continue;
                }
                m.x = trackRect.right + 10 * density - m.travelPx * t;

                float alpha = t < 0.08f ? (t / 0.08f) : (t > 0.9f ? (1f - t) / 0.10f : 1f);
                alpha = Math.max(0f, Math.min(1f, alpha)) * 0.95f;
                float my = trackRect.centerY() + m.yBias * trackRect.height() * 0.5f;
                int head = withAlpha(0xFFFFFF, (int) (alpha * 255));
                int tail = withAlpha(colorAccentBright, 0);
                // 流星往左飞：亮头必须在运动前方（左端 m.x），向右逐渐淡出成尾。
                pMeteor.setShader(new LinearGradient(m.x, 0, m.x + m.widthPx, 0,
                        head, tail, Shader.TileMode.CLAMP));
                pMeteor.setAlpha(255);
                cv.drawRoundRect(new RectF(m.x, my - 1.5f * density, m.x + m.widthPx, my + 1.5f * density),
                        2 * density, 2 * density, pMeteor);
                pMeteor.setShader(null);
            }
            cv.restore();
        }

        // ---- 把手（无外发光：Ultra 档的脉动光环用户 2026-10-14 明确不要）----
        // 把手 = 白底圆 + 彩色描边环（用户 2026-10-15 定：上一轮改成方块是改错了对象）。
        int ringColor = level == 0 ? colorFaint : (ultra ? colorAccentBright : colorAccent);
        pThumb.setStyle(Paint.Style.FILL);
        pThumb.setColor(colorSurface);
        cv.drawCircle(cx, trackRect.centerY(), thumbR - 3.5f * density, pThumb);

        pThumb.setStyle(Paint.Style.STROKE);
        pThumb.setStrokeWidth(4 * density);
        pThumb.setColor(ringColor);
        cv.drawCircle(cx, trackRect.centerY(), thumbR - 2 * density, pThumb);
        pThumb.setStyle(Paint.Style.FILL);

        if (running) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(ev.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(ev.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                // 档位已经在 updateFromTouch 里实时回调过了，这里不再补发。
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private void updateFromTouch(float x) {
        float pad = thumbR + 6 * density;
        float span = Math.max(1f, getWidth() - 2 * pad);
        float rel = (x - pad) / span;
        int lv = Math.round(rel * MAX_LEVEL);
        lv = Math.max(0, Math.min(MAX_LEVEL, lv));
        if (lv != level) {
            level = lv;
            onLevelSettled();
            // 实时跟手：档位一变就回调，档位字/高亮当场跟着动（用户 2026-10-15 定）。
            // 只在真的跨档时触发，同一档内拖动不会重复回调。
            if (listener != null) listener.onLevelChanged(level, true);
        }
        invalidate();
    }
}
