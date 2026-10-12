package com.fileman.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;

final class ThemePreview extends View {
    static final class Colors {
        int bg, surface, stroke, text, hint, accent, soft, onAccent, b1, b2, b3;

        static Colors of(Context c) {
            Colors k = new Colors();
            k.bg = Ui.color(c, R.color.bg);
            k.surface = Ui.color(c, R.color.surface);
            k.stroke = Ui.color(c, R.color.stroke);
            k.text = Ui.color(c, R.color.text_secondary);
            k.hint = Ui.color(c, R.color.text_hint);
            k.accent = Ui.color(c, R.color.accent);
            k.soft = Ui.color(c, R.color.accent_soft);
            k.onAccent = Ui.color(c, R.color.on_accent);
            k.b1 = Ui.color(c, R.color.brand_start);
            k.b2 = Ui.color(c, R.color.brand_mid);
            k.b3 = Ui.color(c, R.color.brand_end);
            return k;
        }
    }

    private final Colors first, second;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;

    ThemePreview(Context c, Colors first, Colors second) {
        super(c);
        this.first = first;
        this.second = second;
        this.dp = c.getResources().getDisplayMetrics().density;
        final float radius = 16 * dp;
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (second != null) {
            canvas.save();
            canvas.clipRect(0, 0, w / 2f, h);
            screen(canvas, first, w, h);
            canvas.restore();
            canvas.save();
            canvas.clipRect(w / 2f, 0, w, h);
            screen(canvas, second, w, h);
            canvas.restore();
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33808080);
            canvas.drawRect(w / 2f - 0.5f * dp, 0, w / 2f + 0.5f * dp, h, p);
        } else {
            screen(canvas, first, w, h);
        }
    }

    private void round(Canvas c, float l, float t, float rr, float b, float rad, int fill, int stroke) {
        r.set(l, t, rr, b);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawRoundRect(r, rad, rad, p);
        if (stroke != 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp);
            p.setColor(stroke);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private void screen(Canvas c, Colors k, float w, float h) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(k.bg);
        c.drawRect(0, 0, w, h, p);

        round(c, w * 0.10f, h * 0.075f, w * 0.46f, h * 0.075f + 6 * dp, 3 * dp, k.text, 0);
        round(c, w * 0.10f, h * 0.075f + 10 * dp, w * 0.30f, h * 0.075f + 14 * dp, 2 * dp, k.hint, 0);
        round(c, w * 0.78f, h * 0.06f, w * 0.90f, h * 0.06f + w * 0.12f, w * 0.06f, k.soft, 0);

        float hl = w * 0.10f, ht = h * 0.24f, hr = w * 0.90f, hb = h * 0.47f;
        r.set(hl, ht, hr, hb);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(hl, ht, hr, hb, new int[]{k.b1, k.b2, k.b3}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 10 * dp, 10 * dp, p);
        p.setShader(null);
        round(c, hl + 8 * dp, ht + 9 * dp, hl + 8 * dp + w * 0.28f, ht + 13 * dp, 2 * dp, alpha(k.onAccent, 0xCC), 0);
        round(c, hl + 8 * dp, ht + 19 * dp, hl + 8 * dp + w * 0.44f, ht + 22 * dp, 1.5f * dp, alpha(k.onAccent, 0x80), 0);

        float rowH = h * 0.12f;
        for (int i = 0; i < 2; i++) {
            float top = h * 0.51f + i * (rowH + 5 * dp);
            round(c, w * 0.10f, top, w * 0.90f, top + rowH, 8 * dp, k.surface, k.stroke);
            float cy = top + rowH / 2f;
            float rad = Math.min(rowH * 0.28f, 9 * dp);
            round(c, w * 0.10f + 8 * dp, cy - rad, w * 0.10f + 8 * dp + rad * 2, cy + rad, rad * 0.6f, k.soft, 0);
            round(c, w * 0.10f + 8 * dp + rad * 2 + 7 * dp, cy - 3 * dp, w * 0.62f, cy, 2 * dp, k.text, 0);
            round(c, w * 0.10f + 8 * dp + rad * 2 + 7 * dp, cy + 3 * dp, w * 0.52f, cy + 5.5f * dp, 1.5f * dp, k.hint, 0);
        }

        float by = h * 0.86f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(k.surface);
        c.drawRect(0, by, w, h, p);
        p.setColor(k.stroke);
        c.drawRect(0, by, w, by + dp, p);
        float cy = (by + h) / 2f;
        round(c, w * 0.14f, cy - 5 * dp, w * 0.14f + 22 * dp, cy + 5 * dp, 5 * dp, k.accent, 0);
        p.setColor(k.hint);
        c.drawCircle(w * 0.52f, cy, 3 * dp, p);
        c.drawCircle(w * 0.82f, cy, 3 * dp, p);
    }
}
