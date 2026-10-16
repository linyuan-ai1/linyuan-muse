package com.linyuan.app;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Scroller;

public class SwipePages extends ViewGroup {

    private final Scroller scroller;
    private int page = 0;
    private float downX, downY;
    private boolean dragging;
    private int touchSlop;
    private Runnable onPageChanged;

    public SwipePages(Context context) {
        super(context);
        scroller = new Scroller(context);
        touchSlop = (int) (context.getResources().getDisplayMetrics().density * 12);
    }

    public void setOnPageChanged(Runnable r) {
        this.onPageChanged = r;
    }

    public int pageCount() {
        return getChildCount();
    }

    public int currentPage() {
        return page;
    }

    public void goTo(int which, boolean animate) {
        if (getChildCount() == 0) return;
        int target = Math.max(0, Math.min(getChildCount() - 1, which));
        if (target == page && getScrollX() == target * getWidth()) return;
        page = target;
        if (animate && getWidth() > 0) {
            scroller.startScroll(getScrollX(), 0, target * getWidth() - getScrollX(), 0, 260);
            invalidate();
        } else {
            scrollTo(target * getWidth(), 0);
        }
        if (onPageChanged != null) onPageChanged.run();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - downX);
                float dy = Math.abs(ev.getY() - downY);
                if (dx > touchSlop && dx > dy * 1.5f) {
                    dragging = true;
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                float dy = Math.abs(ev.getY() - downY);
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > dy * 1.5f) dragging = true;
                if (dragging) {
                    int want = page * getWidth() - (int) dx;
                    int max = (getChildCount() - 1) * getWidth();
                    if (want < 0) want = want / 3;
                    if (want > max) want = max + (want - max) / 3;
                    scrollTo(want, 0);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    int w = Math.max(1, getWidth());
                    int nearest = Math.round(getScrollX() / (float) w);
                    int dx2 = (int) (ev.getX() - downX);
                    if (Math.abs(dx2) > w / 5) {
                        nearest = dx2 < 0 ? page + 1 : page - 1;
                    }
                    dragging = false;
                    goTo(nearest, true);
                    return true;
                }
                break;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int maxH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    hMode == MeasureSpec.UNSPECIFIED
                            ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                            : MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            maxH = Math.max(maxH, c.getMeasuredHeight());
        }
        if (hMode != MeasureSpec.EXACTLY) {
            setMeasuredDimension(w, maxH);
        } else {
            setMeasuredDimension(w, h);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(i * w, 0, i * w + w, c.getMeasuredHeight());
        }
        scrollTo(page * w, 0);
    }
}
