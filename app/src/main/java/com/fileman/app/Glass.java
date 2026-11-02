package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/**
 * Liquid-glass look for the whole app: a soft tinted backdrop, translucent surfaces, real background blur
 * on floating bars (BlurView) and on dialog windows (platform blur on Android 12+).
 */
public final class Glass {
    private Glass() {
    }

    private static final String TARGET_TAG = "fm_glass_target";

    public static boolean windowBlurSupported(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            android.view.WindowManager wm = (android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Frosted panel: tinted translucent fill, bright top sheen and a thin light edge. */
    public static Drawable fill(Context c, float radiusPx) {
        boolean night = Appearance.isNight(c);
        int base = Ui.color(c, R.color.surface);
        GradientDrawable body = new GradientDrawable();
        body.setCornerRadius(radiusPx);
        body.setColor(base);

        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(Color.WHITE, night ? 0x2E : 0x73), withAlpha(Color.WHITE, 0x00)});
        sheen.setCornerRadius(radiusPx);

        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(radiusPx);
        edge.setColor(Color.TRANSPARENT);
        edge.setStroke(Math.max(1, Ui.dp(c, 1) / 2 + 1), withAlpha(Color.WHITE, night ? 0x40 : 0xB0));

        return new LayerDrawable(new Drawable[]{body, sheen, edge});
    }

    /** Lighter variant for small floating controls (header buttons, chips). */
    public static Drawable chip(Context c, float radiusPx) {
        boolean night = Appearance.isNight(c);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radiusPx);
        g.setColor(withAlpha(Color.WHITE, night ? 0x1F : 0x66));
        g.setStroke(Math.max(1, Ui.dp(c, 1) / 2 + 1), withAlpha(Color.WHITE, night ? 0x33 : 0x99));
        return g;
    }

    /** Soft coloured backdrop that gives the glass something to refract. */
    public static Drawable backdrop(Context c) {
        boolean night = Appearance.isNight(c);
        int base = Ui.color(c, R.color.bg_group);
        int accent = Ui.color(c, R.color.accent);
        int a = withAlpha(accent, night ? 0x55 : 0x40);
        int b = withAlpha(Ui.color(c, R.color.brand_end), night ? 0x44 : 0x30);

        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float big = Math.max(dm.widthPixels, dm.heightPixels);

        ColorDrawable floor = new ColorDrawable(withAlpha(base, 0xFF));

        GradientDrawable orbA = new GradientDrawable();
        orbA.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        orbA.setGradientRadius(big * 0.75f);
        orbA.setGradientCenter(0.15f, 0.08f);
        orbA.setColors(new int[]{a, withAlpha(accent, 0)});

        GradientDrawable orbB = new GradientDrawable();
        orbB.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        orbB.setGradientRadius(big * 0.8f);
        orbB.setGradientCenter(0.9f, 0.85f);
        orbB.setColors(new int[]{b, withAlpha(Ui.color(c, R.color.brand_end), 0)});

        return new LayerDrawable(new Drawable[]{floor, orbA, orbB});
    }

    /** Puts the glass backdrop behind a screen. */
    public static void backdrop(Activity a) {
        try {
            a.getWindow().setBackgroundDrawable(backdrop(a));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Wraps the screen's root view in a BlurTarget (once) so floating bars can blur what scrolls beneath them.
     * Returns the target, or null when the screen has no usable root.
     */
    public static BlurTarget target(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return null;
        View first = content.getChildAt(0);
        if (first instanceof BlurTarget) return (BlurTarget) first;
        ViewGroup.LayoutParams lp = first.getLayoutParams();
        content.removeViewAt(0);
        BlurTarget t = new BlurTarget(a);
        t.setTag(TARGET_TAG);
        View back = new View(a);
        back.setBackground(backdrop(a));
        t.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        t.addView(first, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams outer = lp instanceof FrameLayout.LayoutParams
                ? new FrameLayout.LayoutParams((FrameLayout.LayoutParams) lp)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        content.addView(t, 0, outer);
        return t;
    }

    /** A blurred glass container wired to the screen's target. Add children to it directly. */
    public static BlurView bar(Context c, BlurTarget target, float radiusPx, float blurRadius) {
        BlurView v = new BlurView(c);
        setup(v, target, radiusPx, blurRadius);
        return v;
    }

    /** Turns an existing BlurView into a rounded glass panel wired to the screen's target. */
    public static void setup(BlurView v, BlurTarget target, final float radiusPx, float blurRadius) {
        v.setBackground(fill(v.getContext(), radiusPx));
        v.setClipToOutline(true);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setupWith(target).setBlurRadius(blurRadius);
    }

    /** Applies glass to a dialog or sheet window: blurred scene behind it on Android 12+, translucent panel everywhere. */
    public static void window(Window w) {
        if (w == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && windowBlurSupported(w.getContext())) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.setBlurBehindRadius(Ui.dp(w.getContext(), 26));
                w.setAttributes(lp);
                w.setDimAmount(0.18f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Re-tints a view as a glass panel with the given corner radius in dp. */
    public static void panel(View v, int radiusDp) {
        v.setBackground(fill(v.getContext(), Ui.dp(v.getContext(), radiusDp)));
    }
}
