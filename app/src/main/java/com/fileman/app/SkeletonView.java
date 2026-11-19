package com.fileman.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Placeholder rows shown while a big folder loads. Draws only; the pulse changes paint alpha, never size. */
final class SkeletonView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int rowH;
    private final float pad;
    private final float icon;
    private float phase;
    private ValueAnimator anim;
    private final int baseAlpha = 70;

    SkeletonView(Context c, int rows) {
        super(c);
        rowH = Ui.dp(c, 64);
        pad = Ui.dp(c, 16);
        icon = Ui.dp(c, 40);
        paint.setColor(Ui.color(c, R.color.stroke));
        setMinimumHeight(rows * rowH);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), getMinimumHeight());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!Fx.motion(getContext())) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1100);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.addUpdateListener(a -> {
            phase = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        anim = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        paint.setAlpha(baseAlpha + (int) (phase * 60));
        int rows = getHeight() / rowH;
        float rad = Ui.dp(getContext(), 8);
        for (int i = 0; i < rows; i++) {
            float top = i * rowH + (rowH - icon) / 2f;
            float ix = rtl ? w - pad - icon : pad;
            r.set(ix, top, ix + icon, top + icon);
            c.drawRoundRect(r, rad, rad, paint);
            float tx = rtl ? pad : pad * 2 + icon;
            float tw = w - pad * 3 - icon;
            float l1 = tw * (0.45f + 0.1f * (i % 3));
            float l2 = tw * 0.28f;
            float x0 = rtl ? w - pad * 2 - icon - l1 : tx;
            r.set(x0, top + 4, x0 + l1, top + 16);
            c.drawRoundRect(r, 6, 6, paint);
            float x1 = rtl ? w - pad * 2 - icon - l2 : tx;
            r.set(x1, top + 24, x1 + l2, top + 34);
            c.drawRoundRect(r, 6, 6, paint);
        }
    }
}
