package com.linyuan.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class TapRecorderOverlay extends FrameLayout {

    public interface Listener {
        void onTapRecorded(int index, double xPercent, double yPercent);

        void onTapMoved(int index, double xPercent, double yPercent);

        void onTapDeleted(int index);

        void onClearRequested();
        void onFinish();
    }

    private static final long ARM_DELETE_MS = 650;
    private static final int MOVE_TOLERANCE_DP = 8;

    private final List<float[]> dots = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final DotLayer dotLayer;

    private int dragIndex = -1;
    private float downX, downY;
    private boolean dragMoved = false;
    private boolean deleteArmed = false;
    private final Runnable armDelete = new Runnable() {
        @Override
        public void run() {
            if (dragIndex < 0 || dragMoved) return;
            deleteArmed = true;
            dotLayer.invalidate();
        }
    };

    public TapRecorderOverlay(Context context, String appLabel, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(0x11000000);
        setClickable(true);
        setFocusable(true);

        dotLayer = new DotLayer(context);
        dotLayer.setClickable(false);
        dotLayer.setFocusable(false);
        addView(dotLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dotPaint.setColor(0x99FF5252);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        ringPaint.setColor(0xFFFFEB3B);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(13));
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        pillPaint.setColor(0xF0B71C1C);

        TextView hint = new TextView(context);
        hint.setText("● 正在录制点击步骤（在任意界面都有效）"
                + "\n点一下 = 记一个点；按住点位拖动 = 挪到别处；按住点位别动、松手 = 删掉那一个。"
                + "\n底部：撤销上一步 / 清空重录 / 完成录制");
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(12);
        hint.setBackgroundColor(0xE6161616);
        hint.setPadding(dp(14), dp(10), dp(14), dp(10));
        LayoutParams hp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP;
        hp.topMargin = dp(6);
        hp.setMargins(dp(10), dp(6), dp(10), 0);
        addView(hint, hp);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER);
        android.widget.Button undo = mkBtn(context, "撤销", 0xFF616161);
        undo.setOnClickListener(v -> {
            if (!dots.isEmpty()) deleteDot(dots.size() - 1);
        });
        android.widget.Button clear = mkBtn(context, "清空重录", 0xFFC62828);

        clear.setOnClickListener(v -> {
            if (listener != null) listener.onClearRequested();
        });
        android.widget.Button done = mkBtn(context, "完成录制", 0xFF2E7D32);
        done.setOnClickListener(v -> {
            if (listener != null) listener.onFinish();
        });
        bar.addView(undo);
        bar.addView(clear);
        bar.addView(done);
        LayoutParams bp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM;

        bp.bottomMargin = dp(90);
        addView(bar, bp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.widget.Button mkBtn(Context c, String text, int color) {
        android.widget.Button b = new android.widget.Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(color);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = ev.getX();
                downY = ev.getY();
                dragMoved = false;
                deleteArmed = false;
                dragIndex = findDotNear(downX, downY);
                if (dragIndex >= 0) {

                    removeCallbacks(armDelete);
                    postDelayed(armDelete, ARM_DELETE_MS);
                    dotLayer.invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragIndex >= 0 && dragIndex < dots.size()) {
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    float tol = dp(MOVE_TOLERANCE_DP);
                    if (!dragMoved && (dx > tol || dy > tol)) {
                        dragMoved = true;

                        removeCallbacks(armDelete);
                        deleteArmed = false;
                    }
                    if (dragMoved) {
                        dots.set(dragIndex, new float[]{ev.getX(), ev.getY()});
                        dotLayer.invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(armDelete);
                if (dragIndex >= 0) {
                    int idx = dragIndex;
                    boolean armed = deleteArmed;
                    boolean moved = dragMoved;
                    dragIndex = -1;
                    deleteArmed = false;
                    if (moved) {
                        notifyMoved(idx);
                        dotLayer.invalidate();
                        return true;
                    }
                    if (armed) {
                        deleteDot(idx);
                        return true;
                    }

                }
                recordTap(ev.getX(), ev.getY());
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(armDelete);
                if (dragIndex >= 0 && dragMoved && dragIndex < dots.size()) notifyMoved(dragIndex);
                dragIndex = -1;
                deleteArmed = false;
                dotLayer.invalidate();
                return true;
            }
        }
        return true;
    }

    public void recordTap(float x, float y) {
        double[] pc = toScreenPercent(x, y);
        if (pc == null) return;

        dots.add(new float[]{x, y});
        labels.add(String.valueOf(dots.size()));
        dotLayer.invalidate();
        if (listener != null) listener.onTapRecorded(dots.size() - 1, pc[0], pc[1]);
    }

    private double[] toScreenPercent(float x, float y) {
        int w = overlayW > 0 ? overlayW : getWidth();
        int h = overlayH > 0 ? overlayH : getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] loc = new int[2];
        try { getLocationOnScreen(loc); } catch (Throwable ignored) { }
        double xp = Math.max(0, Math.min(100, (loc[0] + x) * 100.0 / w));
        double yp = Math.max(0, Math.min(100, (loc[1] + y) * 100.0 / h));
        return new double[]{xp, yp};
    }

    private void notifyMoved(int index) {
        if (index < 0 || index >= dots.size()) return;
        float[] p = dots.get(index);
        double[] pc = toScreenPercent(p[0], p[1]);
        if (pc == null) return;
        if (listener != null) listener.onTapMoved(index, pc[0], pc[1]);
    }

    private int findDotNear(float x, float y) {
        float r = dp(30);
        for (int i = dots.size() - 1; i >= 0; i--) {
            float[] p = dots.get(i);
            float dx = p[0] - x, dy = p[1] - y;
            if (dx * dx + dy * dy <= r * r) return i;
        }
        return -1;
    }

    private void deleteDot(int index) {
        if (index < 0 || index >= dots.size()) return;
        dots.remove(index);
        labels.remove(index);
        for (int i = 0; i < labels.size(); i++) labels.set(i, String.valueOf(i + 1));
        dotLayer.invalidate();
        if (listener != null) listener.onTapDeleted(index);
    }

    public void clearAll() {
        dots.clear();
        labels.clear();
        dragIndex = -1;
        deleteArmed = false;
        dotLayer.invalidate();
    }

    public int count() {
        return dots.size();
    }

    private class DotLayer extends View {
        DotLayer(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            for (int i = 0; i < dots.size(); i++) {
                float[] p = dots.get(i);
                boolean dragging = (i == dragIndex && dragMoved);
                boolean armed = (i == dragIndex && deleteArmed);
                float r = dp(dragging ? 21 : 17);
                cv.drawCircle(p[0], p[1], r, dotPaint);
                if (dragging) {
                    ringPaint.setColor(0xFF00E5FF);
                    ringPaint.setStrokeWidth(dp(4));
                } else if (armed) {
                    ringPaint.setColor(0xFFFF1744);
                    ringPaint.setStrokeWidth(dp(5));
                } else {
                    ringPaint.setColor(0xFFFFEB3B);
                    ringPaint.setStrokeWidth(dp(2));
                }
                cv.drawCircle(p[0], p[1], r, ringPaint);

                cv.drawText(labels.get(i), p[0], p[1] + dp(5), textPaint);
                if (armed) {

                    String tip = "松手删除";
                    textPaint.setTextSize(dp(12));
                    float tw = textPaint.measureText(tip);
                    float ty = p[1] + r + dp(20);
                    RectF pill = new RectF(p[0] - tw / 2 - dp(8), ty - dp(15),
                            p[0] + tw / 2 + dp(8), ty + dp(7));
                    cv.drawRoundRect(pill, dp(8), dp(8), pillPaint);
                    cv.drawText(tip, p[0], ty, textPaint);
                    textPaint.setTextSize(dp(13));
                }
            }
        }
    }

    public int[] screenSize() {
        return new int[]{getWidth(), getHeight()};
    }

    public static TapRecorderOverlay attach(android.app.Activity activity, String appLabel,
                                           Listener listener) {
        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        if (root == null) return null;
        TapRecorderOverlay overlay = new TapRecorderOverlay(activity, appLabel, listener);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        try { root.bringChildToFront(overlay); } catch (Throwable ignored) { }
        return overlay;
    }

    public static TapRecorderOverlay attachOverlay(android.content.Context ctx, String appLabel,
                                                   Listener listener) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(ctx)) {
            android.util.Log.w("VC_TAPSCRIPT", "没有悬浮窗权限，无法开系统级录制层");
            return null;
        }
        android.view.WindowManager wm =
                (android.view.WindowManager) ctx.getSystemService(android.content.Context.WINDOW_SERVICE);
        if (wm == null) return null;
        TapRecorderOverlay overlay = new TapRecorderOverlay(ctx, appLabel, listener);
        int flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        try {
            wm.addView(overlay, lp);
        } catch (Throwable t) {
            android.util.Log.w("VC_TAPSCRIPT", "addView 失败: " + t);
            return null;
        }
        overlay.sysWm = wm;
        overlay.sysLp = lp;

        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.view.Display d = wm.getDefaultDisplay();
            if (d != null) d.getRealMetrics(dm);
            overlay.overlayW = dm.widthPixels;
            overlay.overlayH = dm.heightPixels;
        } catch (Throwable ignored) { }
        return overlay;
    }

    private android.view.WindowManager sysWm;
    private android.view.WindowManager.LayoutParams sysLp;

    private int overlayW = 0, overlayH = 0;

    public void detach() {
        try {
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
        } catch (Throwable ignored) { }
    }

    public void detachAny() {
        if (sysWm != null) {
            try { sysWm.removeView(this); } catch (Throwable ignored) { }
            sysWm = null;
            sysLp = null;
            return;
        }
        detach();
    }

    public boolean isSystemOverlay() {
        return sysWm != null;
    }

    static void drawRounded(Canvas cv, RectF r, Paint p) {
        cv.drawRoundRect(r, 8, 8, p);
    }
}
