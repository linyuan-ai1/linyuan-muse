package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class BallWaveView extends View {

    private static final long FRAME_MS = 33;
    private static final int RING_COUNT = 3;
    private static final long RING_CYCLE_MS = 700;
    private static final long LOAD_CYCLE_MS = 1100;
    private static final int RIPPLE_COLOR = 0xFF3B82F6;
    private static final int LOAD_COLOR = 0xFF3B82F6;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float targetLevel = 0f;
    private float currentLevel = 0f;
    private boolean loading = false;
    private boolean animating = false;
    private boolean needsIdleRepaint = false;
    private long lastFrame = 0;

    public BallWaveView(Context context) {
        super(context);
        init();
    }

    public BallWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ringPaint.setAntiAlias(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setAntiAlias(true);
        fillPaint.setAntiAlias(true);
        fillEdgePaint.setAntiAlias(true);
        fillEdgePaint.setStyle(Paint.Style.STROKE);
        fillEdgePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setAntiAlias(true);
        strokePaint.setStyle(Paint.Style.STROKE);

        strokePaint.setColor(RIPPLE_COLOR);
        strokePaint.setAlpha(0xCC);
        strokePaint.setStrokeWidth(dp(2));
    }

    public void setLevel(float level) {
        targetLevel = Math.max(0f, Math.min(1f, level));
        startAnim();
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
        startAnim();
    }

    private void startAnim() {
        if (!animating) {
            animating = true;
            lastFrame = 0;
            postInvalidateDelayed(FRAME_MS);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 1f : Math.min(3f, (now - lastFrame) / 33f);
        lastFrame = now;

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) { postInvalidateDelayed(FRAME_MS); return; }
        float cx = w / 2f, cy = h / 2f;
        float ballR = Math.min(w, h) / 2f;

        currentLevel += (targetLevel - currentLevel) * Math.min(1f, 0.35f * dt);
        float lv = currentLevel;

        canvas.drawCircle(cx, cy, ballR - dp(1f), strokePaint);

        boolean active = false;
        if (loading) {
            active = true;

            float p = (now % LOAD_CYCLE_MS) / (float) LOAD_CYCLE_MS;
            float fillR = Math.max(dp(1f), p * ballR);

            int alpha = p > 0.92f ? (int) (40 + 195 * ((1f - p) / 0.08f)) : 235;
            fillPaint.setColor(LOAD_COLOR);
            fillPaint.setAlpha(Math.min(235, alpha));
            canvas.drawCircle(cx, cy, fillR, fillPaint);

            if (fillR < ballR - dp(2f)) {
                fillEdgePaint.setColor(Color.WHITE);
                fillEdgePaint.setAlpha(210);
                fillEdgePaint.setStrokeWidth(dp(2.5f));
                canvas.drawCircle(cx, cy, fillR, fillEdgePaint);
            }
        } else if (lv > 0.03f) {
            active = true;

            float ringMaxR = ballR * 0.93f;
            for (int i = 0; i < RING_COUNT; i++) {

                float base = (now + i * (RING_CYCLE_MS / (long) RING_COUNT)) % RING_CYCLE_MS;
                float phase = (base / RING_CYCLE_MS) * (0.8f + lv * 0.45f) % 1f;
                float r = phase * ringMaxR;
                float decay = (float) Math.pow(1f - phase, 1.3);
                int alpha = (int) Math.min(255, 215 * decay * (0.25f + lv * 0.75f));
                if (alpha < 8) continue;
                ringPaint.setColor(RIPPLE_COLOR);
                ringPaint.setAlpha(alpha);
                ringPaint.setStrokeWidth(dp(1.6f) + phase * dp(1.4f));
                canvas.drawCircle(cx, cy, Math.max(dp(1.5f), r), ringPaint);
            }

            dotPaint.setColor(Color.WHITE);
            dotPaint.setAlpha((int) (120 + lv * 135));
            canvas.drawCircle(cx, cy, Math.max(dp(3f), ballR * (0.18f + lv * 0.05f)), dotPaint);
        }

        if (active) {
            needsIdleRepaint = true;
            postInvalidateDelayed(FRAME_MS);
        } else {

            animating = false;
            if (needsIdleRepaint) {
                needsIdleRepaint = false;
                invalidate();
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
