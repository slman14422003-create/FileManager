package com.fileman.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.widget.NestedScrollView;
import androidx.drawerlayout.widget.DrawerLayout;

import java.util.ArrayList;
import java.util.List;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/**
 * Live glass for the whole app. A screen's column is wrapped in a {@link BlurTarget}; the header, the bottom
 * bars and the side panel are {@link BlurView}s that blur what scrolls behind them. Dialogs and sheets are
 * separate windows, so they use the system window blur (Android 12+) and a translucent surface.
 */
final class Blur {
    private static final String PREFS = "blur";
    private static final String K_ON = "on";
    private static final String SPEC = "glass:";

    static final int ALPHA_BAR = 0xB4;
    static final int ALPHA_CAPSULE = 0xC8;
    static final int ALPHA_PANEL = 0xDC;

    private Blur() {
    }

    private static final class State {
        BlurTarget target;
        BlurView top;
        LinearLayout bottom;
        final List<View> scrollers = new ArrayList<>();
        int tint;
        int appliedTop = -1;
        int appliedBottom = -1;
    }

    // ------------------------------------------------------------------ switch

    static boolean enabled(Context c) {
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.contains(K_ON)) return sp.getBoolean(K_ON, true);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;   // the pre-12 path is software blur: opt-in only
    }

    static void setEnabled(Context c, boolean on) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply();
    }

    private static boolean weakDevice(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.isLowRamDevice() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ screens

    private static State state(Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (content == null) return null;
        Object o = content.getTag(R.id.tag_blur);
        return o instanceof State ? (State) o : null;
    }

    /** The vertical column that holds a screen's header, content and bottom bars (looks inside a drawer too). */
    static LinearLayout column(Activity a) {
        State s = state(a);
        if (s != null && s.target != null && s.target.getChildCount() > 0
                && s.target.getChildAt(0) instanceof LinearLayout) {
            return (LinearLayout) s.target.getChildAt(0);
        }
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return null;
        View root = content.getChildAt(0);
        if (root instanceof LinearLayout) return (LinearLayout) root;
        if (root instanceof DrawerLayout) {
            DrawerLayout d = (DrawerLayout) root;
            if (d.getChildCount() > 0 && d.getChildAt(0) instanceof LinearLayout) {
                return (LinearLayout) d.getChildAt(0);
            }
        }
        return null;
    }

    /** Where floating bottom bars are added: the glass overlay when the screen has one, else the column itself. */
    static LinearLayout bottomHost(Activity a) {
        State s = state(a);
        if (s != null && s.bottom != null) return s.bottom;
        return column(a);
    }

    /** Called once per screen, after its content exists. Never throws; a screen that cannot be glassed stays as it was. */
    static void install(Activity a, boolean glassScreen) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        State st = state(a);
        if (st == null) {
            st = new State();
            content.setTag(R.id.tag_blur, st);
            if (glassScreen && enabled(a)) {
                try {
                    build(a, st);
                } catch (Throwable t) {
                    st.target = null;
                    st.top = null;
                    st.bottom = null;
                }
            }
        }
        scan(a, content, st);
    }

    private static void build(final Activity a, final State st) {
        final LinearLayout col = column(a);
        if (col == null || col.getOrientation() != LinearLayout.VERTICAL) return;
        if (!(col.getParent() instanceof ViewGroup)) return;
        int mainIdx = -1;
        for (int i = 0; i < col.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = col.getChildAt(i).getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) lp).weight > 0f) {
                mainIdx = i;
                break;
            }
        }
        if (mainIdx < 0) return;
        final View main = col.getChildAt(mainIdx);
        List<View> tops = new ArrayList<>();
        List<View> bottoms = new ArrayList<>();
        for (int i = 0; i < col.getChildCount(); i++) {
            View v = col.getChildAt(i);
            if (i < mainIdx) tops.add(v);
            else if (i > mainIdx) bottoms.add(v);
        }
        int bg = col.getBackground() instanceof ColorDrawable
                ? ((ColorDrawable) col.getBackground()).getColor() : Ui.color(a, R.color.bg);
        st.tint = bg & 0x00FFFFFF;

        ViewGroup parent = (ViewGroup) col.getParent();
        int idx = parent.indexOfChild(col);
        ViewGroup.LayoutParams colLp = col.getLayoutParams();
        parent.removeViewAt(idx);
        for (View v : tops) col.removeView(v);
        for (View v : bottoms) col.removeView(v);

        FrameLayout host = new FrameLayout(a);
        BlurTarget target = new BlurTarget(a);
        target.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(target, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        BlurView topGlass = null;
        if (!tops.isEmpty()) {
            LinearLayout topCol = new LinearLayout(a);
            topCol.setOrientation(LinearLayout.VERTICAL);
            for (View v : tops) topCol.addView(v);
            View line = new View(a);
            line.setBackgroundColor(Ui.color(a, R.color.stroke_soft));
            topCol.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Ui.dp(a, 1) / 2)));
            topGlass = new BlurView(a);
            topGlass.setTag(R.id.tag_glass_spec, SPEC + "0:" + ALPHA_BAR);
            topGlass.addView(topCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            host.addView(topGlass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        }

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        for (View v : bottoms) bottom.addView(v);
        host.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        parent.addView(host, idx, colLp);

        st.target = target;
        st.top = topGlass;
        st.bottom = bottom;
        collect(main, st.scrollers, 0);
        if (st.scrollers.isEmpty()) st.scrollers.add(main);
        for (View v : st.scrollers) {
            v.setTag(R.id.tag_pad, new int[]{v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    v.getPaddingBottom()});
            if (v instanceof ViewGroup) ((ViewGroup) v).setClipToPadding(false);
        }
        View.OnLayoutChangeListener l = (v, x1, y1, x2, y2, x3, y3, x4, y4) -> v.post(() -> applyPadding(st));
        if (topGlass != null) topGlass.addOnLayoutChangeListener(l);
        bottom.addOnLayoutChangeListener(l);
    }

    private static void collect(View v, List<View> out, int depth) {
        if (v instanceof ScrollView || v instanceof AbsListView || v instanceof NestedScrollView) {
            out.add(v);
            return;
        }
        if (depth > 4 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out, depth + 1);
    }

    /** Lets the content scroll under the glass but start (and end) clear of it. */
    private static void applyPadding(State st) {
        int top = st.top != null ? st.top.getHeight() : 0;
        int bottom = st.bottom != null ? st.bottom.getHeight() : 0;
        if (top == st.appliedTop && bottom == st.appliedBottom) return;
        st.appliedTop = top;
        st.appliedBottom = bottom;
        for (View v : st.scrollers) {
            Object o = v.getTag(R.id.tag_pad);
            if (!(o instanceof int[])) continue;
            int[] b = (int[]) o;
            v.setPadding(b[0], b[1] + top, b[2], b[3] + bottom);
        }
    }

    // ------------------------------------------------------------------ glass views

    /** Registers a glass view built in code; sets it up now when its screen is already installed. */
    static void glass(Activity a, BlurView v, int radiusDp, int alpha, boolean surface) {
        v.setTag(R.id.tag_glass_spec, SPEC + radiusDp + ":" + alpha + (surface ? ":s" : ""));
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content != null && content.getTag(R.id.tag_blur) != null) setupOne(a, v, state(a));
    }

    private static void scan(Activity a, View v, State st) {
        if (v instanceof BlurView) {
            setupOne(a, (BlurView) v, st);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scan(a, g.getChildAt(i), st);
        }
    }

    private static void setupOne(Activity a, BlurView v, State st) {
        if (v.getTag(R.id.tag_glass_done) != null) return;
        Object spec = v.getTag(R.id.tag_glass_spec);
        if (spec == null) spec = v.getTag();
        if (!(spec instanceof String) || !((String) spec).startsWith(SPEC)) return;
        String[] p = ((String) spec).split(":");
        int radius = 0;
        int alpha = ALPHA_BAR;
        boolean surface = false;
        try {
            radius = Integer.parseInt(p[1]);
            alpha = Integer.parseInt(p[2]);
            surface = p.length > 3 && "s".equals(p[3]);
        } catch (Exception ignored) {
        }
        v.setTag(R.id.tag_glass_done, Boolean.TRUE);
        int r = Ui.dp(a, radius);
        if (r > 0) {
            v.setOutlineProvider(new Round(r));
            v.setClipToOutline(true);
        }
        if (st != null && st.target != null) {
            int base = surface ? Ui.color(a, R.color.surface) : st.tint;
            int overlay = (base & 0x00FFFFFF) | (alpha << 24);
            boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            // BlurView 3: radius 10 at scale 3 looks like radius 30 at scale 1 but blurs a 9x smaller snapshot
            v.setupWith(st.target, modern ? 3f : 6f, false)
                    .setBlurRadius(modern ? 10f : 5f)
                    .setOverlayColor(overlay);
            if (r > 0) v.setBackground(edge(a, r));
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.color(a, R.color.surface));
            g.setCornerRadius(r);
            if (r > 0) g.setStroke(Math.max(1, Ui.dp(a, 1) / 2), Ui.color(a, R.color.stroke_soft));
            v.setBackground(g);
        }
    }

    private static GradientDrawable edge(Context c, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0);
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, Ui.dp(c, 1) / 2), Ui.color(c, R.color.stroke));
        return g;
    }

    private static final class Round extends ViewOutlineProvider {
        private final float radius;

        Round(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            float r = Math.min(radius, Math.min(view.getWidth(), view.getHeight()) / 2f);
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
        }
    }

    // ------------------------------------------------------------------ dialogs and sheets (own windows)

    /** True when the system can blur what is behind a window (Android 12+, not switched off by power saving). */
    static boolean windowBlur(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !enabled(c)) return false;
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Blurs the screen behind a dialog or sheet window. */
    @SuppressWarnings("deprecation")
    static void dialogWindow(Window w, Context c, boolean strong) {
        if (w == null || !windowBlur(c)) return;
        try {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.setBlurBehindRadius(Ui.dp(c, strong ? 26 : 18));
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    /** Dim amount that suits the window: lighter when the backdrop is blurred. */
    static float dim(Context c, float normal) {
        return windowBlur(c) ? normal * 0.55f : normal;
    }

    /** A surface colour that lets the blurred backdrop show through when the window blur is available. */
    static int surface(Context c, int color, int alpha) {
        return windowBlur(c) ? (color & 0x00FFFFFF) | (alpha << 24) : color;
    }
}
