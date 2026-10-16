package com.linyuan.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

public class MaxWidthLinearLayout extends LinearLayout {

    private int maxWidthPx = Integer.MAX_VALUE;

    public MaxWidthLinearLayout(Context context) {
        super(context);
    }

    public MaxWidthLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxWidthLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxWidthPx(int px) {
        this.maxWidthPx = px <= 0 ? Integer.MAX_VALUE : px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST
                && MeasureSpec.getSize(widthMeasureSpec) > maxWidthPx) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST);
        }

        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST
                && hasMixedWidthChildren()) {
            int natural = 0;
            final int hSpec = heightMeasureSpec;
            final int unspecifiedW = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                ViewGroup.LayoutParams lp = ch.getLayoutParams();
                measureChildWithMargins(ch, unspecifiedW, 0, hSpec, 0);
                int w = ch.getMeasuredWidth();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    w += mlp.leftMargin + mlp.rightMargin;
                }
                if (w > natural) natural = w;
            }
            natural += getPaddingLeft() + getPaddingRight();
            int cap = MeasureSpec.getSize(widthMeasureSpec);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(Math.min(natural, cap),
                    MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private boolean hasMixedWidthChildren() {
        boolean hasMatch = false;
        boolean hasWrap = false;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            ViewGroup.LayoutParams lp = ch.getLayoutParams();
            if (lp == null) continue;
            if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                hasMatch = true;
            } else {
                hasWrap = true;
            }
            if (hasMatch && hasWrap) return true;
        }
        return false;
    }
}
