package com.fileman.app;

import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.HashMap;

/** Slides surviving rows into place after items were added or removed (translation only). */
final class ListFlip {
    interface Ids {
        String idOf(View child);
    }

    private final ListView lv;
    private final Ids ids;
    private final HashMap<String, Integer> tops = new HashMap<>();
    private boolean captured;

    ListFlip(ListView lv, Ids ids) {
        this.lv = lv;
        this.ids = ids;
    }

    /** Call before the data changes. */
    void capture() {
        tops.clear();
        captured = false;
        if (!Fx.motion(lv.getContext())) return;
        for (int i = 0; i < lv.getChildCount(); i++) {
            View c = lv.getChildAt(i);
            String id = ids.idOf(c);
            if (id != null) tops.put(id, c.getTop());
        }
        captured = !tops.isEmpty();
    }

    /** Call right after the adapter was notified. */
    void play() {
        if (!captured) return;
        captured = false;
        final ViewTreeObserver.OnPreDrawListener l = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                lv.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < lv.getChildCount(); i++) {
                    View c = lv.getChildAt(i);
                    String id = ids.idOf(c);
                    if (id == null) continue;
                    Integer old = tops.get(id);
                    if (old == null) continue;
                    int d = old - c.getTop();
                    if (d == 0 || Math.abs(d) > lv.getHeight()) continue;
                    c.setTranslationY(d);
                    Fx.spring(c, DynamicAnimation.TRANSLATION_Y, 0f, 0f, SpringForce.STIFFNESS_LOW, 0.85f);
                }
                tops.clear();
                return true;
            }
        };
        lv.getViewTreeObserver().addOnPreDrawListener(l);
        lv.invalidate();
    }
}
