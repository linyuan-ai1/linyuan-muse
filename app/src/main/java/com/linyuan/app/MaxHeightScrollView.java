package com.linyuan.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

public class MaxHeightScrollView extends ScrollView {

    private int maxHeightPx = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeightPx(int px) {
        this.maxHeightPx = px <= 0 ? Integer.MAX_VALUE : px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (maxHeightPx != Integer.MAX_VALUE && getMeasuredHeight() > maxHeightPx) {
            setMeasuredDimension(getMeasuredWidth(), maxHeightPx);
        }
    }
}
