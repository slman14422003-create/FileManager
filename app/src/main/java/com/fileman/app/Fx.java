package com.fileman.app;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Motion policy, haptics and spring helpers shared by every animated screen. */
final class Fx {
    private Fx() {
    }

    private static long checkedAt;
    private static boolean on = true;

    /** False when animations are switched off in system settings or battery saver is on. */
    static boolean motion(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - checkedAt < 1500 && checkedAt != 0) return on;
        checkedAt = now;
        boolean ok = true;
        try {
            Context app = c.getApplicationContext();
            float s = Settings.Global.getFloat(app.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (s == 0f) ok = false;
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) ok = false;
        } catch (Throwable ignored) {
        }
        on = ok;
        return ok;
    }

    static long dur(Context c, long ms) {
        return motion(c) ? ms : 0L;
    }

    // ------------------------------------------------------------------ haptics

    static void light(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        } catch (Throwable ignored) {
        }
    }

    static void tick(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } catch (Throwable ignored) {
        }
    }

    static void done(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.CONTEXT_CLICK);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ springs

    /** Springs a view property to a value; a hardware layer exists only while it moves. */
    static SpringAnimation spring(final View v, DynamicAnimation.ViewProperty prop, float to, float velocity,
                                  float stiffness, float damping) {
        if (v == null) return null;
        cancel(v);
        if (!motion(v.getContext())) {
            prop.setValue(v, to);
            return null;
        }
        final SpringAnimation a = new SpringAnimation(v, prop);
        SpringForce f = new SpringForce(to);
        f.setStiffness(stiffness);
        f.setDampingRatio(damping);
        a.setSpring(f);
        a.setStartVelocity(velocity);
        a.setMinimumVisibleChange(prop == DynamicAnimation.ALPHA || prop == DynamicAnimation.SCALE_X
                || prop == DynamicAnimation.SCALE_Y ? DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
                : DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);
        final int prevLayer = v.getLayerType();
        final boolean layer = prevLayer == View.LAYER_TYPE_NONE;
        if (layer) v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        final int key = prop == DynamicAnimation.TRANSLATION_X ? R.id.fx_spring_x : R.id.fx_spring_y;
        v.setTag(key, a);
        a.addEndListener((animation, canceled, value, vel) -> {
            if (layer) v.setLayerType(View.LAYER_TYPE_NONE, null);
            if (v.getTag(key) == animation) v.setTag(key, null);
        });
        a.start();
        return a;
    }

    static SpringAnimation settle(View v, DynamicAnimation.ViewProperty prop, float to, float velocity) {
        return spring(v, prop, to, velocity, SpringForce.STIFFNESS_MEDIUM, 0.82f);
    }

    /** Stops springs started by {@link #spring} on this view. */
    static void cancel(View v) {
        if (v == null) return;
        Object x = v.getTag(R.id.fx_spring_x);
        if (x instanceof SpringAnimation && ((SpringAnimation) x).isRunning()) ((SpringAnimation) x).cancel();
        Object y = v.getTag(R.id.fx_spring_y);
        if (y instanceof SpringAnimation && ((SpringAnimation) y).isRunning()) ((SpringAnimation) y).cancel();
    }

    // ------------------------------------------------------------------ simple transitions

    /** Fades a view in; the layer lives only for the animation. */
    static void fadeIn(View v, long ms) {
        if (v == null) return;
        v.animate().cancel();
        if (!motion(v.getContext())) {
            v.setAlpha(1f);
            return;
        }
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ms).setInterpolator(Motion.EASE).withLayer().start();
    }

    /** A new list item: fades in from an 8dp offset. */
    static void appear(View v) {
        if (v == null) return;
        v.animate().cancel();
        cancel(v);
        if (!motion(v.getContext())) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(v.getContext(), 8));
        v.animate().alpha(1f).translationY(0f).setDuration(Motion.DUR_SHORT).setInterpolator(Motion.OUT)
                .withLayer().start();
    }

    /** Puts a recycled row back to rest. */
    static void rest(View v) {
        v.animate().cancel();
        cancel(v);
        v.setAlpha(1f);
        v.setTranslationY(0f);
    }
}
