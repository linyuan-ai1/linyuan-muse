package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class RippleView extends View {

    private static final long FRAME_MS = 33;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private float targetLevel = 0f;
    private float currentLevel = 0f;
    private boolean calling = false;
    private boolean darkTheme = false;
    private long lastFrame = 0;
    private boolean animating = false;
    private boolean needsIdleRepaint = false;

    public RippleView(Context context) {
        super(context);
        init();
    }

    public RippleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setAntiAlias(true);
        edgePaint.setAntiAlias(true);
        edgePaint.setStyle(Paint.Style.STROKE);
    }

    public void setDarkTheme(boolean dark) {
        this.darkTheme = dark;
        invalidate();
    }

    public void setCalling(boolean calling) {
        this.calling = calling;
        if (calling && !animating) {
            animating = true;
            lastFrame = 0;
            postInvalidateDelayed(FRAME_MS);
        }
        invalidate();
    }

    public void setLevel(float level) {
        targetLevel = Math.max(0f, Math.min(1f, level));
        if (targetLevel > 0.02f && !animating) {
            animating = true;
            lastFrame = 0;
            postInvalidateDelayed(FRAME_MS);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (calling && !animating) {
            animating = true;
            lastFrame = 0;
            postInvalidateDelayed(FRAME_MS);
        }
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

        currentLevel += (targetLevel - currentLevel) * Math.min(1f, 0.4f * dt);

        int count = Math.max(20, Math.round(w / dp(24f)));
        float barW = dp(10f);
        float gap = (w - barW * count) / (count - 1);
        float maxH = h * 0.90f;
        float baseY = h * 0.94f;

        int fillColor = darkTheme ? Color.parseColor("#FFFFFF") : Color.parseColor("#111111");
        int edgeColor = Color.parseColor("#3B82F6");

        for (int i = 0; i < count; i++) {
            float t = count <= 1 ? 0.5f : (float) i / (count - 1);
            float env = 0.35f + 0.65f * (float) Math.sin(Math.PI * t);
            float wave = (float) Math.sin(now / 120.0 + i * 0.9) * (0.10f + currentLevel * 0.40f)
                    + (random.nextFloat() - 0.5f) * currentLevel * 0.30f;
            float hgt = Math.max(0.05f, (0.16f + currentLevel * 0.79f) * env + wave * currentLevel);
            hgt = Math.min(0.98f, hgt);

            float barH = Math.max(dp(4), hgt * maxH);
            float x = i * (barW + gap);
            float radius = barW / 2f;

            int alpha = darkTheme
                    ? (int) (120 + hgt * 135)
                    : (int) (150 + hgt * 105);
            barPaint.setColor(fillColor);
            barPaint.setAlpha(Math.min(255, alpha));
            canvas.drawRoundRect(x, baseY - barH, x + barW, baseY, radius, radius, barPaint);

            float edgeW = darkTheme ? dp(2.4f) : dp(2.6f);
            edgePaint.setColor(edgeColor);
            edgePaint.setStrokeWidth(edgeW);
            edgePaint.setAlpha((int) Math.min(255, 190 + hgt * 65));
            float edgeOut = edgeW / 2f;
            canvas.drawRoundRect(x - edgeOut, baseY - barH - edgeOut,
                    x + barW + edgeOut, baseY + edgeOut, radius + edgeOut, radius + edgeOut, edgePaint);
        }

        if (calling && (targetLevel > 0.03f || currentLevel > 0.03f)) {
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
