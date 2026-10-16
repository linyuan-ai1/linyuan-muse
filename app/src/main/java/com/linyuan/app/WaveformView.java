package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class WaveformView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] heights = new float[21];
    private boolean active = false;

    public WaveformView(Context context) {
        super(context);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setActive(boolean active) {
        this.active = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float barWidth = 6f;
        float gap = 8f;
        float total = barWidth * heights.length + gap * (heights.length - 1);
        float startX = (getWidth() - total) / 2f;
        float centerY = getHeight() / 2f;
        paint.setColor(active ? Color.parseColor("#22C55E") : Color.parseColor("#334155"));

        for (int i = 0; i < heights.length; i++) {
            float h;
            if (active) {
                h = 12 + random.nextFloat() * Math.max(12f, getHeight() - 26f);
            } else {
                h = 10 + (i % 4) * 4f;
            }
            heights[i] = h;
            float x = startX + i * (barWidth + gap);
            canvas.drawRoundRect(x, centerY - h / 2f, x + barWidth, centerY + h / 2f, 3f, 3f, paint);
        }

        if (active) {
            postInvalidateDelayed(70);
        }
    }
}
