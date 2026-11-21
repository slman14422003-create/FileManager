package com.fileman.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

public final class AirView extends View {
    public static final int IDLE = 0;
    public static final int SEND = 1;
    public static final int RECEIVE = 2;
    public static final int DONE = 3;

    private static final int RINGS = 3;
    private static final int PACKETS = 6;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint packet = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path checkPath = new Path();
    private final Path partial = new Path();

    private int mode = IDLE;
    private int ringColor;
    private int discColor;
    private int glyphColor = 0xFFFFFFFF;
    private int doneColor;
    private float discFactor = 0.30f;
    private Drawable icon;
    private boolean started;
    private float phase;
    private float progress = -1f;
    private float shownProgress;
    private float doneT;
    private ValueAnimator loop;
    private ValueAnimator progAnim;
    private ValueAnimator doneAnim;

    public AirView(Context c, AttributeSet a) {
        super(c, a);
        int accent = Ui.color(c, R.color.accent);
        ringColor = accent;
        discColor = accent;
        doneColor = Ui.color(c, R.color.ok);
        check.setStyle(Paint.Style.STROKE);
        check.setStrokeCap(Paint.Cap.ROUND);
        check.setStrokeJoin(Paint.Join.ROUND);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
    }

    public AirView(Context c) {
        this(c, null);
    }

    public AirView icon(int res) {
        icon = res == 0 ? null : androidx.core.content.ContextCompat.getDrawable(getContext(), res);
        if (icon != null) icon = icon.mutate();
        invalidate();
        return this;
    }

    public AirView palette(int ringArgb, int discArgb, int glyphArgb) {
        ringColor = ringArgb;
        discColor = discArgb;
        glyphColor = glyphArgb;
        invalidate();
        return this;
    }

    public AirView disc(float f) {
        discFactor = f;
        invalidate();
        return this;
    }

    public int mode() {
        return mode;
    }

    public void setMode(int m) {
        if (m == mode) return;
        int old = mode;
        mode = m;
        if (m == DONE) {
            if (doneAnim != null) doneAnim.cancel();
            doneT = Fx.motion(getContext()) ? 0f : 1f;
            if (doneT < 1f) {
                doneAnim = ValueAnimator.ofFloat(0f, 1f);
                doneAnim.setDuration(620L);
                doneAnim.setInterpolator(new OvershootInterpolator(1.1f));
                doneAnim.addUpdateListener(a -> {
                    doneT = Math.min(1f, (float) a.getAnimatedValue());
                    invalidate();
                });
                doneAnim.start();
            }
            setProgress(1f);
        } else if (old == DONE) {
            doneT = 0f;
        }
        invalidate();
    }

    public void setProgress(float p) {
        float t = Math.max(0f, Math.min(1f, p));
        if (progress < 0f) {
            progress = t;
            shownProgress = t;
            invalidate();
            return;
        }
        progress = t;
        if (progAnim != null) progAnim.cancel();
        if (!Fx.motion(getContext())) {
            shownProgress = t;
            invalidate();
            return;
        }
        progAnim = ValueAnimator.ofFloat(shownProgress, t);
        progAnim.setDuration(420L);
        progAnim.setInterpolator(Motion.OUT);
        progAnim.addUpdateListener(a -> {
            shownProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        progAnim.start();
    }

    public void clearProgress() {
        progress = -1f;
        shownProgress = 0f;
        invalidate();
    }

    public void popIn() {
        if (!Fx.motion(getContext())) return;
        setScaleX(0.55f);
        setScaleY(0.55f);
        setAlpha(0f);
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(460L)
                .setInterpolator(new OvershootInterpolator(1.5f)).start();
    }

    public void start() {
        started = true;
        if (loop != null || !Fx.motion(getContext()) || !isAttachedToWindow()) {
            invalidate();
            return;
        }
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(2400L);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        loop.start();
    }

    public void stop() {
        started = false;
        halt();
        phase = 0f;
        invalidate();
    }

    private void halt() {
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (started) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        halt();
        if (progAnim != null) progAnim.cancel();
        if (doneAnim != null) doneAnim.cancel();
    }

    private static int alpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy);
        if (maxR <= 0) return;
        float dr = maxR * discFactor;
        boolean moving = mode == SEND || mode == RECEIVE;
        boolean animated = started && loop != null;

        if (mode != DONE) {
            ring.setStrokeWidth(Ui.dpf(getContext(), 1.6f));
            for (int i = 0; i < RINGS; i++) {
                float t = animated ? (phase + i / (float) RINGS) % 1f : i / (float) RINGS;
                float s = mode == RECEIVE ? 1f - t : t;
                float r = dr * 1.15f + (maxR - dr * 1.15f) * s;
                ring.setColor(alpha(ringColor, (int) (150 * (1f - s))));
                cv.drawCircle(cx, cy, r, ring);
            }
        }

        if (moving && animated) {
            float unit = Ui.dpf(getContext(), 6f);
            for (int i = 0; i < PACKETS; i++) {
                float p = (phase * 1.0f + i / (float) PACKETS) % 1f;
                float e = mode == SEND ? p : 1f - p;
                float ang = (float) Math.toRadians(-160f + (i * 140f / (PACKETS - 1)) + Math.sin(p * Math.PI) * 10f);
                float r = dr * 1.2f + (maxR * 0.96f - dr * 1.2f) * e;
                float px = cx + (float) Math.cos(ang) * r;
                float py = cy + (float) Math.sin(ang) * r;
                float fade = (float) Math.sin(p * Math.PI);
                packet.setColor(alpha(ringColor, (int) (230 * fade)));
                float sz = unit * (0.55f + 0.55f * fade);
                cv.save();
                cv.rotate((float) Math.toDegrees(ang) + 90f, px, py);
                box.set(px - sz * 0.8f, py - sz, px + sz * 0.8f, py + sz);
                cv.drawRoundRect(box, sz * 0.35f, sz * 0.35f, packet);
                cv.restore();
            }
        }

        float breathe = 1f;
        if (animated) breathe = 1f + (mode == IDLE ? 0.02f : 0.04f) * (float) Math.sin(phase * Math.PI * (mode == IDLE ? 2 : 4));
        float r0 = dr * breathe;

        if (progress >= 0f && (moving || mode == DONE)) {
            float ar = r0 + Ui.dpf(getContext(), 9f);
            arc.setStrokeWidth(Ui.dpf(getContext(), 4f));
            arc.setColor(alpha(mode == DONE ? doneColor : ringColor, 56));
            cv.drawCircle(cx, cy, ar, arc);
            arc.setColor(mode == DONE ? doneColor : ringColor);
            box.set(cx - ar, cy - ar, cx + ar, cy + ar);
            cv.drawArc(box, -90f, 360f * shownProgress, false, arc);
        }

        disc.setStyle(Paint.Style.FILL);
        disc.setColor(mode == DONE ? doneColor : discColor);
        cv.drawCircle(cx, cy, r0, disc);

        if (mode == DONE) {
            float k = Math.max(0f, Math.min(1f, doneT));
            check.setColor(glyphColor);
            check.setStrokeWidth(Ui.dpf(getContext(), 4.5f));
            checkPath.reset();
            checkPath.moveTo(cx - r0 * 0.36f, cy + r0 * 0.02f);
            checkPath.lineTo(cx - r0 * 0.10f, cy + r0 * 0.28f);
            checkPath.lineTo(cx + r0 * 0.38f, cy - r0 * 0.26f);
            PathMeasure pm = new PathMeasure(checkPath, false);
            partial.reset();
            pm.getSegment(0f, pm.getLength() * k, partial, true);
            cv.drawPath(partial, check);
        } else if (icon != null) {
            int h = (int) (r0 * 0.95f);
            icon.setBounds((int) (cx - h / 2f), (int) (cy - h / 2f), (int) (cx + h / 2f), (int) (cy + h / 2f));
            icon.setTint(glyphColor);
            icon.draw(cv);
        }
    }
}
