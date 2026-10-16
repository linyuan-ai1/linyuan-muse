package com.linyuan.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

public class ZoomableImageView extends android.widget.ImageView {

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private float scale = 1f;
    private static final float MIN = 0.5f;
    private static final float MAX = 5f;

    private ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    private boolean dragging;
    private long lastTapAt;
    private final PointF lastTapPoint = new PointF();

    public ZoomableImageView(Context c) { super(c); init(c); }
    public ZoomableImageView(Context c, AttributeSet a) { super(c, a); init(c); }
    public ZoomableImageView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        setScaleType(android.widget.ImageView.ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float factor = d.getScaleFactor();
                float target = scale * factor;
                if (target < MIN) factor = MIN / scale;
                if (target > MAX) factor = MAX / scale;
                scale *= factor;
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = ev.getX();
                lastY = ev.getY();
                dragging = false;

                long now = System.currentTimeMillis();
                if (now - lastTapAt < 500
                        && Math.abs(ev.getX() - lastTapPoint.x) < 60
                        && Math.abs(ev.getY() - lastTapPoint.y) < 60) {
                    resetZoom();
                    lastTapAt = 0;
                    return true;
                }
                lastTapAt = now;
                lastTapPoint.set(ev.getX(), ev.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    float dx = ev.getX() - lastX;
                    float dy = ev.getY() - lastY;
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) dragging = true;
                    if (dragging) {
                        matrix.postTranslate(dx, dy);
                        setImageMatrix(matrix);
                    }
                }
                lastX = ev.getX();
                lastY = ev.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    public void resetZoom() {
        scale = 1f;
        matrix.reset();
        setImageMatrix(matrix);
    }
}
