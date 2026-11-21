package com.fileman.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.util.Random;

final class TapShare {
    static final int OFF = 0;
    static final int AUTO = 1;
    static final int SERVE = 2;
    static final int READ = 3;

    static final byte[] AID = {(byte) 0xF0, 0x46, 0x4D, 0x53, 0x48, 0x41, 0x52, 0x45};
    static final byte[] OK = {(byte) 0x90, 0x00};
    static final byte[] NOT_FOUND = {0x6A, (byte) 0x82};
    static final byte[] UNKNOWN = {0x6D, 0x00};
    private static final int CHUNK = 240;
    private static final String PREF = "tap_share";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String link;
    private static volatile boolean emulating;
    private static volatile Runnable touch;

    private TapShare() {
    }

    interface Listener {
        void onPeer();

        void onLink(String code);
    }

    static boolean supported(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            return NfcAdapter.getDefaultAdapter(c) != null && pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean nfcOn(Context c) {
        try {
            NfcAdapter a = NfcAdapter.getDefaultAdapter(c);
            return a != null && a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean enabled(Context c) {
        return Store.intPref(c, PREF, 0) == 1;
    }

    static void setEnabled(Context c, boolean on) {
        Store.setIntPref(c, PREF, on ? 1 : 0);
    }

    static void setLink(String l) {
        link = l;
    }

    static String link() {
        return link;
    }

    static boolean emulating() {
        return emulating;
    }

    static void touched() {
        Runnable t = touch;
        if (t != null) MAIN.post(t);
    }

    static byte[] chunk(int off) {
        String l = link;
        byte[] all;
        try {
            all = l == null ? new byte[0] : l.getBytes("UTF-8");
        } catch (Exception e) {
            all = new byte[0];
        }
        int n = Math.max(0, Math.min(CHUNK, all.length - off));
        byte[] out = new byte[n + 2];
        if (n > 0) System.arraycopy(all, off, out, 0, n);
        out[n] = OK[0];
        out[n + 1] = OK[1];
        return out;
    }

    static void openHub(final Activity a) {
        ripple(a);
        Fx.done(a.getWindow().getDecorView());
        MAIN.postDelayed(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            a.startActivity(new Intent(a, ShareActivity.class).putExtra("tap", true));
            if (!Store.reduceMotion(a)) a.overridePendingTransition(R.anim.share_zoom_in, R.anim.share_hold);
        }, Fx.motion(a) ? 320L : 0L);
    }

    static void openReceive(final Activity a, final String code) {
        ripple(a);
        Fx.done(a.getWindow().getDecorView());
        MAIN.postDelayed(() -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            a.startActivity(new Intent(a, ShareReceiveActivity.class).putExtra("code", code));
            if (!Store.reduceMotion(a)) a.overridePendingTransition(R.anim.share_zoom_in, R.anim.share_hold);
        }, Fx.motion(a) ? 320L : 0L);
    }

    static void ripple(Activity a) {
        if (!Fx.motion(a)) return;
        try {
            final ViewGroup host = a.findViewById(android.R.id.content);
            if (host == null) return;
            final RippleView rv = new RippleView(a);
            host.addView(rv, new FrameLayout.LayoutParams(-1, -1));
            rv.run(() -> host.removeView(rv));
        } catch (Throwable ignored) {
        }
    }

    private static final class RippleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private float t;

        RippleView(Context c) {
            super(c);
            color = Ui.color(c, R.color.accent);
            setClickable(false);
        }

        void run(final Runnable end) {
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(780L);
            va.setInterpolator(new DecelerateInterpolator(1.4f));
            va.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    end.run();
                }
            });
            va.start();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float max = (float) Math.hypot(cx, cy);
            p.setStyle(Paint.Style.FILL);
            p.setColor((color & 0xFFFFFF) | ((int) (46 * (1f - t)) << 24));
            cv.drawCircle(cx, cy, max * t, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(getContext(), 3f));
            for (int i = 0; i < 2; i++) {
                float k = Math.max(0f, t - i * 0.18f);
                p.setColor((color & 0xFFFFFF) | ((int) (200 * (1f - k)) << 24));
                cv.drawCircle(cx, cy, max * k, p);
            }
        }
    }

    static final class Session implements NfcAdapter.ReaderCallback {
        private static final int FLAGS = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

        private final Activity act;
        private final Listener listener;
        private final Handler h = new Handler(Looper.getMainLooper());
        private final Random rnd = new Random();
        private NfcAdapter nfc;
        private int mode = OFF;
        private boolean running;
        private boolean readerOn;
        private volatile boolean busy;
        private long lastFire;

        Session(Activity a, Listener l) {
            act = a;
            listener = l;
        }

        void start(int m) {
            stop();
            if (m == OFF || !enabled(act) || !supported(act)) return;
            try {
                nfc = NfcAdapter.getDefaultAdapter(act);
            } catch (Throwable t) {
                nfc = null;
            }
            if (nfc == null || !nfc.isEnabled()) return;
            mode = m;
            running = true;
            if (m == READ) {
                emulating = false;
                touch = null;
                enableReader();
            } else {
                emulating = true;
                touch = () -> {
                    if (running && mode == AUTO) firePeer();
                    else if (running) Fx.light(act.getWindow().getDecorView());
                };
                if (m == AUTO) cycle();
            }
        }

        void stop() {
            running = false;
            h.removeCallbacksAndMessages(null);
            disableReader();
            emulating = false;
            touch = null;
        }

        private void enableReader() {
            if (nfc == null) return;
            try {
                nfc.enableReaderMode(act, this, FLAGS, null);
                readerOn = true;
            } catch (Throwable ignored) {
                readerOn = false;
            }
        }

        private void disableReader() {
            if (nfc != null && readerOn) {
                try {
                    nfc.disableReaderMode(act);
                } catch (Throwable ignored) {
                }
            }
            readerOn = false;
        }

        private void cycle() {
            if (!running || mode != AUTO) return;
            if (busy) {
                h.postDelayed(this::cycle, 300);
                return;
            }
            if (readerOn) {
                disableReader();
                h.postDelayed(this::cycle, 260 + rnd.nextInt(320));
            } else {
                enableReader();
                h.postDelayed(this::cycle, 300 + rnd.nextInt(360));
            }
        }

        private boolean gate() {
            long now = System.currentTimeMillis();
            if (now - lastFire < 2500) return false;
            lastFire = now;
            return true;
        }

        private void firePeer() {
            if (!running || !gate()) return;
            listener.onPeer();
        }

        private void fireLink(final String code) {
            h.post(() -> {
                if (!running || !gate()) return;
                listener.onLink(code);
            });
        }

        private static boolean ok(byte[] r) {
            return r != null && r.length >= 2 && r[r.length - 2] == (byte) 0x90 && r[r.length - 1] == 0x00;
        }

        @Override
        public void onTagDiscovered(Tag tag) {
            IsoDep d = IsoDep.get(tag);
            if (d == null) return;
            busy = true;
            try {
                d.connect();
                d.setTimeout(2500);
                byte[] sel = new byte[6 + AID.length];
                sel[0] = 0x00;
                sel[1] = (byte) 0xA4;
                sel[2] = 0x04;
                sel[3] = 0x00;
                sel[4] = (byte) AID.length;
                System.arraycopy(AID, 0, sel, 5, AID.length);
                sel[sel.length - 1] = 0x00;
                byte[] r = d.transceive(sel);
                if (!ok(r) || r.length < 3) return;
                if (r[0] == 1) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    int off = 0;
                    for (int k = 0; k < 40; k++) {
                        byte[] c = d.transceive(new byte[]{(byte) 0x80, (byte) 0xB0, (byte) (off >> 8), (byte) off, (byte) CHUNK});
                        if (!ok(c)) break;
                        int n = c.length - 2;
                        if (n <= 0) break;
                        bo.write(c, 0, n);
                        off += n;
                        if (n < CHUNK) break;
                    }
                    String code = new String(bo.toByteArray(), "UTF-8");
                    if (ShareLink.parse(code) != null) {
                        fireLink(code);
                        return;
                    }
                }
                h.post(this::firePeer);
            } catch (Throwable ignored) {
            } finally {
                try {
                    d.close();
                } catch (Throwable ignored) {
                }
                busy = false;
            }
        }
    }
}
