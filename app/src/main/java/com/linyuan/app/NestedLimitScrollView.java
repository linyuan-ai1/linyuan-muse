package com.linyuan.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

public class NestedLimitScrollView extends ScrollView {

    private int maxHeightPx = Integer.MAX_VALUE;
    private float lastY;

    private boolean userTouching = false;

    private Runnable releaseRestoreRunnable;

    public NestedLimitScrollView(Context context) {
        super(context);
        init();
    }

    public NestedLimitScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NestedLimitScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setVerticalScrollBarEnabled(true);
        setFillViewport(false);
    }

    public void setMaxHeightPx(int px) {
        this.maxHeightPx = px <= 0 ? Integer.MAX_VALUE : px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int limit = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, limit);
    }

    public boolean isUserTouching() {
        return userTouching;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                userTouching = true;
                lastY = ev.getY();

                if (releaseRestoreRunnable != null) {
                    removeCallbacks(releaseRestoreRunnable);
                    releaseRestoreRunnable = null;
                }

                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getY() - lastY;
                lastY = ev.getY();
                boolean canScrollUp = canScrollVertically(-1);
                boolean canScrollDown = canScrollVertically(1);

                boolean atEdge = (dy > 0 && !canScrollUp) || (dy < 0 && !canScrollDown);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(!atEdge);
                break;
            }
            case MotionEvent.ACTION_UP:

                releaseRestoreRunnable = () -> {
                    userTouching = false;
                    releaseRestoreRunnable = null;
                };
                postDelayed(releaseRestoreRunnable, 800);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                if (releaseRestoreRunnable != null) {
                    removeCallbacks(releaseRestoreRunnable);
                    releaseRestoreRunnable = null;
                }
                userTouching = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }
}
