package com.fileman.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Start screen drawn by the app itself (the system splash only paints the plain background). It shows the launcher
 * icon the user picked, so changing the icon colour changes the splash too. Cold start only.
 */
final class SplashOverlay {
    private static boolean done;

    private SplashOverlay() {
    }

    static void show(final Activity a) {
        if (done || a == null) return;
        done = true;
        try {
            final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            final FrameLayout root = new FrameLayout(a);
            root.setBackgroundColor(a.getColor(R.color.ic_launcher_bg));
            root.setClickable(true);
            root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            final ImageView icon = new ImageView(a);
            icon.setImageResource(IconActivity.currentIconRes(a));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            final float radius = Ui.dp(a, 27);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            icon.setClipToOutline(true);
            icon.setElevation(Ui.dp(a, 6));
            icon.setAlpha(0f);
            icon.setScaleX(0.78f);
            icon.setScaleY(0.78f);
            int size = Ui.dp(a, 120);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER);
            root.addView(icon, lp);
            decor.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.78f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.78f, 1f);
            ObjectAnimator al = ObjectAnimator.ofFloat(icon, View.ALPHA, 0f, 1f);
            sx.setInterpolator(new OvershootInterpolator(1.1f));
            sy.setInterpolator(new OvershootInterpolator(1.1f));
            al.setInterpolator(Motion.EASE);
            AnimatorSet in = new AnimatorSet();
            in.playTogether(sx, sy, al);
            in.setDuration(380);

            ObjectAnimator out = ObjectAnimator.ofFloat(root, View.ALPHA, 1f, 0f);
            out.setDuration(Motion.DUR_SHORT);
            out.setStartDelay(260);
            out.setInterpolator(Motion.EASE);
            out.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        decor.removeView(root);
                    } catch (Throwable ignored) {
                    }
                }
            });
            icon.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            AnimatorSet all = new AnimatorSet();
            all.playSequentially(in, out);
            all.start();
        } catch (Throwable ignored) {
        }
    }
}
