package com.linyuan.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 「上下文用量」面板的卡片底：渐变 + 网格 + 描边 + 左上/右下角标。
 *
 * 配色**跟随主题**（用户 2026-10-15 定）：由 {@link TokenUsagePanel.Pal} 传入 ——
 * 深色主题走深色那套，浅色主题走浅色那套。不直接读 {@code R.color.*}，
 * 因为卡片内的自绘元素（分段条、条形）也用同一组，避免"一半跟随主题、一半写死"。
 */
class TechCardDrawable extends Drawable {

    private final float corner, density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TokenUsagePanel.Pal pal;
    private final boolean dark;

    TechCardDrawable(float density, TokenUsagePanel.Pal pal, boolean dark) {
        this.density = density;
        this.pal = pal;
        this.dark = dark;
        this.corner = 20 * density;
    }

    @Override
    public void draw(Canvas cv) {
        RectF r = new RectF(getBounds());
        Path round = new Path();
        round.addRoundRect(r, corner, corner, Path.Direction.CW);

        int sc = cv.saveLayer(null, null);

        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(pal.cardBg);
        cv.drawPath(round, p);

        // 左上角一团冷光。alpha=0 的同色渐变做收尾，避免出现透明→黑的脏边。
        p.setShader(new RadialGradient(r.left + r.width() * 0.12f, r.top - r.height() * 0.08f,
                r.width() * 1.25f, pal.glow, pal.glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawPath(round, p);

        // 顶部横向冷带（深色下这就是原设计那层底色的来源）
        p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.22f,
                pal.wash, pal.wash & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawPath(round, p);
        p.setShader(null);

        cv.clipPath(round);

        // 网格：只在上半部，往下渐隐（用 DST_OUT 擦，避免逐条算 alpha）
        int cell = Math.max(1, Math.round(26 * density));
        int fadeH = Math.max(1, Math.round(r.height() * 0.42f));
        int layer = cv.saveLayer(null, null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, density * 0.5f));
        p.setColor(pal.grid);
        for (float x = r.left + cell; x < r.right; x += cell) cv.drawLine(x, r.top, x, r.bottom, p);
        for (float y = r.top + cell; y < r.bottom; y += cell) cv.drawLine(r.left, y, r.right, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        p.setShader(new LinearGradient(0, r.top, 0, r.top + fadeH,
                new int[]{0x00000000, 0xFF000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(r.left, r.top, r.right, r.top + fadeH, p);
        p.setShader(null);
        p.setXfermode(null);
        cv.restoreToCount(layer);

        // 扫描线：只深色版有（白色上加扫描线会变成脏纹）
        if (dark) {
            p.setColor(pal.scan);
            float scan = Math.max(3f, 3 * density);
            for (float y = r.top; y < r.bottom; y += scan) {
                cv.drawRect(r.left, y, r.right, Math.min(y + Math.max(1f, density), r.bottom), p);
            }
        }

        cv.restoreToCount(sc);

        // 描边 + 左上/右下角标（画在裁剪之外，保证线宽完整）
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, density));
        p.setColor(pal.border);
        cv.drawPath(round, p);

        p.setStrokeWidth(1.5f * density);
        p.setColor(pal.bracket);
        float m = 8 * density, L = 14 * density, rad = 5 * density;
        Path br = new Path();
        br.moveTo(r.left + m, r.top + m + L);
        br.lineTo(r.left + m, r.top + m + rad);
        br.quadTo(r.left + m, r.top + m, r.left + m + rad, r.top + m);
        br.lineTo(r.left + m + L, r.top + m);
        br.moveTo(r.right - m - L, r.bottom - m);
        br.lineTo(r.right - m - rad, r.bottom - m);
        br.quadTo(r.right - m, r.bottom - m, r.right - m, r.bottom - m - rad);
        br.lineTo(r.right - m, r.bottom - m - L);
        cv.drawPath(br, p);
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(ColorFilter cf) { }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
