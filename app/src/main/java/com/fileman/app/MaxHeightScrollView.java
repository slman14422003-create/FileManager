package com.fileman.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

public class MaxHeightScrollView extends ScrollView {
    private int maxHeight = Integer.MAX_VALUE;

    public MaxHeightScrollView(Context c) {
        super(c);
    }

    public MaxHeightScrollView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setMaxHeight(int px) {
        if (px != maxHeight) {
            maxHeight = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
    }
}
