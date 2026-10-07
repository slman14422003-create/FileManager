package com.fileman.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Video and audio player.
 * <ul>
 *   <li>Video: full-screen stage, auto-hiding controls, double-tap to skip 10 s, swipe left side for
 *   brightness, right side for volume, swipe sideways to scrub, fit / fill / stretch, rotate, lock.</li>
 *   <li>Audio: cover art (embedded picture when there is one), title / artist / album, shuffle, repeat,
 *   previous / next inside the folder, playlist.</li>
 *   <li>Both: speed, sleep timer, resume where you stopped, pause on audio-focus loss.</li>
 * </ul>
 */
public class MediaActivity extends BaseActivity {
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f};
    private static final int SKIP_MS = 10_000;
    private static final int HIDE_MS = 3500;
    private static final int[] SLEEP_MIN = {15, 30, 45, 60, 90};
    private static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final int FIT = 0, FILL = 1, STRETCH = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();

    // playlist
    private final List<File> list = new ArrayList<>();
    private int index = 0;
    private File file;
    private boolean video;
    private boolean shuffle = false;
    private int repeat = REPEAT_OFF;
    private volatile int gen = 0;

    // player
    private VideoView videoView;
    private MediaPlayer player;
    private boolean prepared = false;
    private int duration = 0;
    private boolean dragging = false;
    private int speedIdx = 0;
    private int aspect = FIT;
    private int vidW = 0, vidH = 0;
    private boolean landscapeForced = false;
    private boolean locked = false;
    private long sleepAt = 0;
    private boolean resumeAfterFocus = false;
    private AudioManager audio;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    // shared widgets
    private View loading;
    private SeekBar seek;
    private ImageButton playBtn, prevBtn, nextBtn, shuffleBtn, repeatBtn;
    private TextView timeNow, timeTotal, speedChip, sleepChip, titleView, subtitleView, trackChip;

    // audio widgets
    private ImageView art;
    private TextView songTitle, songArtist;

    // video widgets
    private FrameLayout stage, root;
    private View topBar, bottomBar, lockBtn;
    private TextView indicator, aspectChip;
    private boolean controlsShown = true;
    private float startBright = -1f;
    private int startVol = 0;
    private int gestureMode = 0;       // 0 none, 1 brightness, 2 volume, 3 seek
    private int seekStart = 0, seekTarget = 0;

    private final Runnable hideControls = () -> {
        if (video && playing() && !dragging) showControls(false);
    };
    private final Runnable hideIndicator = () -> {
        if (indicator != null) indicator.animate().alpha(0f).setDuration(200).start();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (prepared && !dragging) {
                try {
                    int p = position();
                    seek.setProgress(p);
                    timeNow.setText(time(p));
                } catch (Exception ignored) {
                }
            }
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                sleepAt = 0;
                pausePlayback();
                updateSleepChip();
                Toast.makeText(MediaActivity.this, R.string.mp_sleep_done, Toast.LENGTH_LONG).show();
            }
            ui.postDelayed(this, 400);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra("path");
        File start = path == null ? null : new File(path);
        if (start == null || !start.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        file = start;
        video = Cats.typeOfExt(Cats.extOf(file.getName())) == Cats.T_VID;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        list.add(file);

        if (video) {
            buildVideoUi();
            goImmersive();
        } else {
            setContentView(R.layout.activity_viewer);
            titleView = findViewById(R.id.title);
            subtitleView = findViewById(R.id.subtitle);
            loading = findViewById(R.id.loading);
            if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
            findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
            findViewById(R.id.btnRefresh).setVisibility(View.GONE);
            ImageButton more = findViewById(R.id.btnA1);
            more.setImageResource(R.drawable.ic_more);
            more.setContentDescription(getString(R.string.more));
            more.setVisibility(View.VISIBLE);
            more.setOnClickListener(v -> Opener.moreMenu(this, file));
            buildAudioUi((FrameLayout) findViewById(R.id.holder));
        }

        focusListener = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                resumeAfterFocus = playing() && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                pausePlayback();
            } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) {
                resumeAfterFocus = false;
                startPlayback();
            }
        };

        // the other files of the same type in this folder (the playlist)
        final File first = file;
        io.execute(() -> {
            final List<File> found = new ArrayList<>();
            File[] kids = first.getParentFile() == null ? null : first.getParentFile().listFiles();
            int want = video ? Cats.T_VID : Cats.T_AUD;
            if (kids != null) {
                for (File k : kids) {
                    if (k.isFile() && !k.getName().startsWith(".")
                            && Cats.typeOfExt(Cats.extOf(k.getName())) == want) found.add(k);
                }
            }
            Collections.sort(found, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || found.isEmpty()) return;
                int i = found.indexOf(first);
                if (i < 0) return;
                list.clear();
                list.addAll(found);
                index = i;
                updateTrackUi();
            });
        });

        loadTrack(file, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveResume();
        pausePlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        abandonFocus();
        releasePlayer();
        if (video) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        }
    }

    // ------------------------------------------------------------------ shared UI helpers

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private ImageButton round(int icon, int sizeDp, int bgColor, int tintColor, int descRes) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tintColor));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(oval(bgColor));
        if (descRes != 0) b.setContentDescription(getString(descRes));
        Ui.press(this, b);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return b;
    }

    private void rounded(View v, int radiusDp) {
        final float r = Ui.dp(this, radiusDp);
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
    }

    private TextView pill(CharSequence text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 100));
        g.setColor(0x66000000);
        g.setStroke(Ui.dp(this, 1), 0x33FFFFFF);
        t.setBackground(g);
        Ui.press(this, t);
        return t;
    }

    private void styleSeek(SeekBar s) {
        s.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        s.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        s.setSecondaryProgressTintList(ColorStateList.valueOf(0x33FFFFFF));
        s.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) timeNow.setText(time(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
                ui.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (prepared) seekTo(sb.getProgress());
                scheduleHide();
            }
        });
    }

    // ------------------------------------------------------------------ audio UI

    private void buildAudioUi(FrameLayout holder) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 24));
        sv.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // cover
        int sw = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 44);
        int side = Math.min(sw, Ui.dp(this, 300));
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rounded(art, 30);
        setDefaultArt();
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(side, side);
        ap.topMargin = Ui.dp(this, 14);
        box.addView(art, ap);

        songTitle = new TextView(this);
        songTitle.setTextColor(Ui.color(this, R.color.text_primary));
        songTitle.setTextSize(21);
        songTitle.setTypeface(Typeface.create("serif", Typeface.BOLD));
        songTitle.setGravity(Gravity.CENTER);
        songTitle.setMaxLines(2);
        songTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        songTitle.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 4));
        box.addView(songTitle);

        songArtist = new TextView(this);
        songArtist.setTextColor(Ui.color(this, R.color.text_secondary));
        songArtist.setTextSize(14);
        songArtist.setGravity(Gravity.CENTER);
        songArtist.setSingleLine(true);
        songArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(songArtist);

        seek = new SeekBar(this);
        styleSeek(seek);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 18);
        box.addView(seek, sp);

        LinearLayout times = new LinearLayout(this);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        timeNow = new TextView(this);
        timeTotal = new TextView(this);
        for (TextView t : new TextView[]{timeNow, timeTotal}) {
            t.setTextColor(Ui.color(this, R.color.text_secondary));
            t.setTextSize(12.5f);
        }
        timeNow.setText(time(0));
        timeTotal.setText(time(0));
        times.addView(timeNow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(timeTotal);
        box.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // transport: shuffle · prev · play · next · repeat
        int sur = Ui.color(this, R.color.surface);
        int txt = Ui.color(this, R.color.text_primary);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shuffleBtn = round(R.drawable.ic_shuffle, 44, 0, Ui.color(this, R.color.text_secondary), R.string.mp_shuffle);
        prevBtn = round(R.drawable.ic_skip_prev, 54, sur, txt, R.string.mp_prev);
        playBtn = round(R.drawable.ic_play_fill, 78, Ui.color(this, R.color.accent), Ui.color(this, R.color.on_accent), R.string.v_play);
        nextBtn = round(R.drawable.ic_skip_next, 54, sur, txt, R.string.mp_next);
        repeatBtn = round(R.drawable.ic_repeat, 44, 0, Ui.color(this, R.color.text_secondary), R.string.mp_repeat_off);
        int gap = Ui.dp(this, 10);
        for (View v : new View[]{shuffleBtn, prevBtn, playBtn, nextBtn, repeatBtn}) {
            ((LinearLayout.LayoutParams) v.getLayoutParams()).setMargins(gap, 0, gap, 0);
            row.addView(v);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(this, 16);
        box.addView(row, rl);

        // chips: back 10 · speed · sleep · playlist · fwd 10
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        chips.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView back10 = Ui.chip(this, "−10s", false);
        TextView fwd10 = Ui.chip(this, "+10s", false);
        speedChip = Ui.chip(this, "1x", false);
        sleepChip = Ui.chip(this, getString(R.string.mp_sleep), false);
        trackChip = Ui.chip(this, getString(R.string.mp_playlist), false);
        chips.addView(back10);
        chips.addView(speedChip);
        chips.addView(sleepChip);
        chips.addView(fwd10);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.dp(this, 22);
        box.addView(chips, cl);
        LinearLayout chips2 = new LinearLayout(this);
        chips2.setGravity(Gravity.CENTER);
        chips2.addView(trackChip);
        LinearLayout.LayoutParams cl2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl2.topMargin = Ui.dp(this, 12);
        box.addView(chips2, cl2);

        wireCommon();
        back10.setOnClickListener(v -> skip(-SKIP_MS));
        fwd10.setOnClickListener(v -> skip(SKIP_MS));
        holder.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void setDefaultArt() {
        if (art == null) return;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.color(this, R.color.accent_soft), Ui.color(this, R.color.surface)});
        g.setStroke(Ui.dp(this, 1), Ui.color(this, R.color.stroke_soft));
        art.setBackground(g);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setImageResource(R.drawable.ic_music);
        art.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        int pad = Math.max(Ui.dp(this, 40), art.getWidth() / 5);
        art.setPadding(pad, pad, pad, pad);
    }

    private void loadAudioMeta(final File f, final int my) {
        io.execute(() -> {
            String title = null, artist = null, album = null;
            Bitmap cover = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                    int s = 1;
                    while (Math.max(o.outWidth, o.outHeight) / s > 1024) s *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = s;
                    cover = BitmapFactory.decodeByteArray(pic, 0, pic.length, o2);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    r.release();
                } catch (Throwable ignored) {
                }
            }
            final String ft = title, fa = artist, fb = album;
            final Bitmap fc = cover;
            ui.post(() -> {
                if (my != gen || isFinishing() || isDestroyed() || songTitle == null) return;
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                songTitle.setText(ft != null && !ft.trim().isEmpty() ? ft.trim() : name);
                String sub = fa != null && !fa.trim().isEmpty() ? fa.trim() : getString(R.string.mp_unknown_artist);
                if (fb != null && !fb.trim().isEmpty()) sub += " · " + fb.trim();
                songArtist.setText(sub);
                if (fc != null) {
                    art.setBackground(null);
                    art.setPadding(0, 0, 0, 0);
                    art.setImageTintList(null);
                    art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    art.setImageBitmap(fc);
                } else {
                    setDefaultArt();
                }
            });
        });
    }

    // ------------------------------------------------------------------ video UI

    private void buildVideoUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF000000);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        videoView = new VideoView(this);
        stage.addView(videoView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // touch layer (gestures)
        View touch = new View(this);
        root.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                gestureMode = 0;
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (locked) {
                    showLockOnly();
                } else {
                    showControls(!controlsShown);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked || !prepared) return true;
                float x = e.getX() / Math.max(1, root.getWidth());
                if (x < 0.35f) {
                    skip(-SKIP_MS);
                    say("⏪ −10s");
                } else if (x > 0.65f) {
                    skip(SKIP_MS);
                    say("+10s ⏩");
                } else {
                    togglePlay();
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (locked || !prepared || e1 == null) return false;
                float totalX = e2.getX() - e1.getX();
                float totalY = e2.getY() - e1.getY();
                if (gestureMode == 0) {
                    if (Math.abs(totalX) < Ui.dp(MediaActivity.this, 14)
                            && Math.abs(totalY) < Ui.dp(MediaActivity.this, 14)) return true;
                    if (Math.abs(totalX) > Math.abs(totalY)) {
                        gestureMode = 3;
                        seekStart = position();
                    } else {
                        gestureMode = e1.getX() < root.getWidth() / 2f ? 1 : 2;
                        if (gestureMode == 1) {
                            float cur = getWindow().getAttributes().screenBrightness;
                            startBright = cur < 0 ? 0.5f : cur;
                        } else {
                            startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                        }
                    }
                }
                int h = Math.max(1, root.getHeight());
                if (gestureMode == 1) {
                    float v = Math.max(0.02f, Math.min(1f, startBright - totalY / h * 1.3f));
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v;
                    getWindow().setAttributes(lp);
                    say("☀ " + Math.round(v * 100) + "%");
                } else if (gestureMode == 2) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int v = Math.max(0, Math.min(max, Math.round(startVol - totalY / h * 1.3f * max)));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    say("🔊 " + Math.round(v * 100f / Math.max(1, max)) + "%");
                } else if (gestureMode == 3) {
                    int w = Math.max(1, root.getWidth());
                    seekTarget = Math.max(0, Math.min(duration, seekStart + Math.round(totalX / w * 120_000f)));
                    int diff = (seekTarget - seekStart) / 1000;
                    say(time(seekTarget) + "  (" + (diff >= 0 ? "+" : "") + diff + "s)");
                }
                return true;
            }
        });
        touch.setOnTouchListener((v, ev) -> {
            boolean r = gd.onTouchEvent(ev);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (gestureMode == 3 && prepared) seekTo(seekTarget);
                gestureMode = 0;
            }
            return r;
        });

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 22));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000}));
        ImageButton back = round(R.drawable.ic_back, 44, 0, 0xFFFFFFFF, R.string.back);
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        top.addView(back);
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(0xCCFFFFFF);
        subtitleView.setTextSize(12);
        subtitleView.setSingleLine(true);
        tl.addView(titleView);
        tl.addView(subtitleView);
        top.addView(tl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton listBtn = round(R.drawable.ic_list_play, 44, 0, 0xFFFFFFFF, R.string.mp_playlist);
        listBtn.setOnClickListener(v -> showPlaylist());
        top.addView(listBtn);
        ImageButton more = round(R.drawable.ic_more, 44, 0, 0xFFFFFFFF, R.string.more);
        more.setOnClickListener(v -> Opener.moreMenu(this, file));
        top.addView(more);
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // bottom bar: seek + times, then buttons
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(this, 14), Ui.dp(this, 26), Ui.dp(this, 14), Ui.dp(this, 10));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xDD000000}));
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        timeNow = new TextView(this);
        timeTotal = new TextView(this);
        for (TextView t : new TextView[]{timeNow, timeTotal}) {
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(12.5f);
            t.setText(time(0));
        }
        seek = new SeekBar(this);
        styleSeek(seek);
        sr.addView(timeNow);
        sr.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sr.addView(timeTotal);
        bottom.addView(sr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout br = new LinearLayout(this);
        br.setGravity(Gravity.CENTER_VERTICAL);
        br.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        lockBtn = round(R.drawable.ic_lock_fill, 42, 0, 0xFFFFFFFF, R.string.mp_lock);
        lockBtn.setOnClickListener(v -> setLocked(true));
        prevBtn = round(R.drawable.ic_skip_prev, 46, 0, 0xFFFFFFFF, R.string.mp_prev);
        ImageButton back10 = round(R.drawable.ic_rewind, 46, 0, 0xFFFFFFFF, R.string.v_back10);
        playBtn = round(R.drawable.ic_play_fill, 62, 0x33FFFFFF, 0xFFFFFFFF, R.string.v_play);
        ImageButton fwd10 = round(R.drawable.ic_forward, 46, 0, 0xFFFFFFFF, R.string.v_fwd10);
        nextBtn = round(R.drawable.ic_skip_next, 46, 0, 0xFFFFFFFF, R.string.mp_next);
        ImageButton rot = round(R.drawable.ic_rotate, 42, 0, 0xFFFFFFFF, R.string.mp_rotate);
        rot.setOnClickListener(v -> toggleRotation());
        View spacer1 = new View(this), spacer2 = new View(this);
        br.addView(lockBtn);
        br.addView(spacer1, new LinearLayout.LayoutParams(0, 1, 1f));
        br.addView(prevBtn);
        br.addView(back10);
        br.addView(playBtn);
        br.addView(fwd10);
        br.addView(nextBtn);
        br.addView(spacer2, new LinearLayout.LayoutParams(0, 1, 1f));
        br.addView(rot);
        bottom.addView(br);

        LinearLayout cr = new LinearLayout(this);
        cr.setGravity(Gravity.CENTER);
        cr.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        speedChip = pill("1x");
        aspectChip = pill(getString(R.string.mp_aspect_fit));
        sleepChip = pill(getString(R.string.mp_sleep));
        repeatBtn = round(R.drawable.ic_repeat, 38, 0, 0xFFFFFFFF, R.string.mp_repeat_off);
        repeatBtn.setAlpha(0.6f);
        for (View v : new View[]{speedChip, aspectChip, sleepChip}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
            cr.addView(v, p);
        }
        cr.addView(repeatBtn);
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        crp.topMargin = Ui.dp(this, 4);
        bottom.addView(cr, crp);
        bottomBar = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // centre feedback + loading
        indicator = pill("");
        indicator.setTextSize(18);
        indicator.setAlpha(0f);
        indicator.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        root.addView(indicator, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(ColorStateList.valueOf(0xFFFFFFFF));
        pb.setVisibility(View.INVISIBLE);
        loading = pb;
        root.addView(pb, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));

        // unlock button (visible only while locked)
        lockOverlay = round(R.drawable.ic_lock_fill, 52, 0x88000000, 0xFFFFFFFF, R.string.mp_unlock);
        lockOverlay.setVisibility(View.GONE);
        lockOverlay.setOnLongClickListener(v -> {
            setLocked(false);
            return true;
        });
        lockOverlay.setOnClickListener(v -> Toast.makeText(this, R.string.mp_unlock, Toast.LENGTH_SHORT).show());
        FrameLayout.LayoutParams lop = new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52),
                Gravity.CENTER_VERTICAL | Gravity.START);
        lop.setMarginStart(Ui.dp(this, 22));
        root.addView(lockOverlay, lop);

        wireCommon();
        back10.setOnClickListener(v -> skip(-SKIP_MS));
        fwd10.setOnClickListener(v -> skip(SKIP_MS));
        aspectChip.setOnClickListener(v -> cycleAspect());
    }

    private ImageButton lockOverlay;

    /** Wires the controls that audio and video share. */
    private void wireCommon() {
        playBtn.setEnabled(false);
        playBtn.setOnClickListener(v -> togglePlay());
        prevBtn.setOnClickListener(v -> previous());
        nextBtn.setOnClickListener(v -> next(true));
        speedChip.setOnClickListener(v -> pickSpeed());
        sleepChip.setOnClickListener(v -> pickSleep());
        repeatBtn.setOnClickListener(v -> {
            repeat = (repeat + 1) % 3;
            applyRepeat();
        });
        if (shuffleBtn != null) {
            shuffleBtn.setOnClickListener(v -> {
                shuffle = !shuffle;
                applyShuffle();
            });
        }
        if (trackChip != null) trackChip.setOnClickListener(v -> showPlaylist());
        applyRepeat();
        applyShuffle();
    }

    // ------------------------------------------------------------------ immersive / controls / lock

    private void goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wl = getWindow().getAttributes();
            wl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wl);
        }
    }

    private void showControls(boolean show) {
        if (!video || locked) return;
        controlsShown = show;
        for (View v : new View[]{topBar, bottomBar}) {
            v.animate().cancel();
            if (show) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(180).start();
            } else {
                final View fv = v;
                v.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                    if (!controlsShown) fv.setVisibility(View.INVISIBLE);
                }).start();
            }
        }
        if (show) scheduleHide();
        else ui.removeCallbacks(hideControls);
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideControls);
        if (video) ui.postDelayed(hideControls, HIDE_MS);
    }

    private void setLocked(boolean on) {
        locked = on;
        if (on) {
            showControls(false);
            lockOverlay.setVisibility(View.VISIBLE);
            lockOverlay.setAlpha(1f);
            ui.postDelayed(() -> {
                if (locked) lockOverlay.animate().alpha(0f).setDuration(250).start();
            }, 2200);
        } else {
            lockOverlay.setVisibility(View.GONE);
            showControls(true);
        }
    }

    private void showLockOnly() {
        lockOverlay.animate().cancel();
        lockOverlay.setAlpha(1f);
        ui.postDelayed(() -> {
            if (locked) lockOverlay.animate().alpha(0f).setDuration(250).start();
        }, 2200);
    }

    private void say(CharSequence text) {
        if (indicator == null) return;
        indicator.setText(text);
        indicator.animate().cancel();
        indicator.setAlpha(1f);
        ui.removeCallbacks(hideIndicator);
        ui.postDelayed(hideIndicator, 900);
    }

    private void toggleRotation() {
        landscapeForced = !landscapeForced;
        setRequestedOrientation(landscapeForced ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        scheduleHide();
    }

    private void cycleAspect() {
        aspect = (aspect + 1) % 3;
        aspectChip.setText(aspect == FIT ? R.string.mp_aspect_fit
                : aspect == FILL ? R.string.mp_aspect_fill : R.string.mp_aspect_stretch);
        applyAspect();
        scheduleHide();
    }

    /** Sizes the video inside the stage according to fit / fill / stretch. */
    private void applyAspect() {
        if (!video || videoView == null || vidW <= 0 || vidH <= 0) return;
        int sw = stage.getWidth(), sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) {
            stage.post(this::applyAspect);
            return;
        }
        float vr = (float) vidW / vidH, sr = (float) sw / sh;
        int w, h;
        if (aspect == STRETCH) {
            w = sw;
            h = sh;
        } else if ((aspect == FIT) == (vr > sr)) {
            w = sw;
            h = Math.round(sw / vr);
        } else {
            h = sh;
            w = Math.round(sh * vr);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        videoView.setLayoutParams(lp);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (video && stage != null) stage.post(this::applyAspect);
    }

    // ------------------------------------------------------------------ tracks

    private void loadTrack(File f, boolean first) {
        saveResume();
        releasePlayer();
        file = f;
        final int my = ++gen;
        prepared = false;
        duration = 0;
        vidW = vidH = 0;
        loading.setVisibility(View.VISIBLE);
        playBtn.setEnabled(false);
        playBtn.setImageResource(R.drawable.ic_play_fill);
        seek.setProgress(0);
        seek.setMax(0);
        timeNow.setText(time(0));
        timeTotal.setText(time(0));
        titleView.setText(f.getName());
        if (!video) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            songTitle.setText(dot > 0 ? n.substring(0, dot) : n);
            songArtist.setText("");
            loadAudioMeta(f, my);
        }
        setSubtitle(0);
        updateTrackUi();
        requestFocus();

        if (video) {
            videoView.setOnPreparedListener(mp -> {
                if (my != gen) return;
                player = mp;
                vidW = mp.getVideoWidth();
                vidH = mp.getVideoHeight();
                applyAspect();
                onReady(videoView.getDuration());
                mp.setLooping(repeat == REPEAT_ONE);
                startPlayback();
            });
            videoView.setOnCompletionListener(mp -> {
                if (my == gen) onEnded();
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                if (my == gen) failed();
                return true;
            });
            videoView.setVideoPath(f.getAbsolutePath());
        } else {
            try {
                player = new MediaPlayer();
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                player.setDataSource(f.getAbsolutePath());
                player.setOnPreparedListener(mp -> {
                    if (my != gen) return;
                    onReady(mp.getDuration());
                    mp.setLooping(repeat == REPEAT_ONE);
                    startPlayback();
                });
                player.setOnCompletionListener(mp -> {
                    if (my == gen) onEnded();
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    if (my == gen) failed();
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                failed();
            }
        }
    }

    private void updateTrackUi() {
        if (prevBtn == null) return;
        boolean many = list.size() > 1;
        prevBtn.setAlpha(many ? 1f : 0.35f);
        nextBtn.setAlpha(many ? 1f : 0.35f);
        if (trackChip != null) {
            trackChip.setText(many ? getString(R.string.mp_track_of, index + 1, list.size())
                    : getString(R.string.mp_playlist));
        }
        setSubtitle(duration);
    }

    private void setSubtitle(int durMs) {
        if (subtitleView == null || file == null) return;
        String t = Cats.extOf(file.getName()).toUpperCase(Locale.ROOT) + " · " + Fmt.size(file.length());
        if (durMs > 0) t += " · " + time(durMs);
        if (list.size() > 1) t = (index + 1) + "/" + list.size() + " · " + t;
        subtitleView.setText(t);
    }

    private void failed() {
        if (isFinishing() || isDestroyed()) return;
        loading.setVisibility(View.INVISIBLE);
        new Dlg(this).setTitle(R.string.v_media_failed_title).setMessage(R.string.v_media_failed)
                .setPositiveButton(R.string.fm_open_with, (d, w) -> {
                    Opener.external(this, file, true);
                    finish();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    if (list.size() > 1) next(false);
                    else finish();
                })
                .show();
    }

    private void onReady(int dur) {
        prepared = true;
        duration = Math.max(0, dur);
        loading.setVisibility(View.INVISIBLE);
        seek.setMax(duration);
        timeTotal.setText(time(duration));
        setSubtitle(duration);
        playBtn.setEnabled(true);
        // resume where the user stopped last time
        long saved = Store.resume(this, file.getAbsolutePath());
        if (saved > 5000 && saved < duration - 5000) {
            seekTo((int) saved);
            Toast.makeText(this, getString(R.string.mp_resume, time((int) saved)), Toast.LENGTH_SHORT).show();
        }
        applySpeed();
        ui.removeCallbacks(tick);
        ui.post(tick);
        if (video) {
            showControls(true);
        }
    }

    private void onEnded() {
        Store.setResume(this, file.getAbsolutePath(), 0);
        if (repeat == REPEAT_ONE) {
            seekTo(0);
            startPlayback();
            return;
        }
        boolean last = index >= list.size() - 1;
        if (!last || repeat == REPEAT_ALL || (shuffle && list.size() > 1)) {
            next(false);
            return;
        }
        playBtn.setImageResource(R.drawable.ic_play_fill);
        seek.setProgress(0);
        timeNow.setText(time(0));
        if (video) showControls(true);
    }

    private void next(boolean user) {
        if (list.size() <= 1) {
            if (user) seekTo(0);
            return;
        }
        int n;
        if (shuffle) {
            do {
                n = rnd.nextInt(list.size());
            } while (n == index);
        } else {
            n = index + 1;
            if (n >= list.size()) n = 0;
        }
        index = n;
        loadTrack(list.get(index), false);
    }

    private void previous() {
        // like every player: a few seconds in, "previous" restarts the track first
        if (prepared && position() > 3000 || list.size() <= 1) {
            seekTo(0);
            return;
        }
        index = index - 1 < 0 ? list.size() - 1 : index - 1;
        loadTrack(list.get(index), false);
    }

    private void showPlaylist() {
        if (list.size() <= 1) {
            Toast.makeText(this, file.getName(), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = (i == index ? "▶  " : "     ") + list.get(i).getName();
        new Dlg(this).setTitle(getString(R.string.mp_playlist) + " (" + names.length + ")")
                .setItems(names, (d, which) -> {
                    index = which;
                    loadTrack(list.get(index), false);
                })
                .show();
    }

    // ------------------------------------------------------------------ transport

    private boolean playing() {
        try {
            return video ? videoView != null && videoView.isPlaying() : player != null && prepared && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private int position() {
        try {
            return video ? videoView.getCurrentPosition() : player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private void seekTo(int ms) {
        int t = Math.max(0, Math.min(duration, ms));
        try {
            if (video) videoView.seekTo(t);
            else player.seekTo(t);
        } catch (Exception ignored) {
        }
        seek.setProgress(t);
        timeNow.setText(time(t));
    }

    private void skip(int delta) {
        if (!prepared) return;
        seekTo(position() + delta);
    }

    private void startPlayback() {
        if (!prepared) return;
        try {
            requestFocus();
            if (video) videoView.start();
            else player.start();
            playBtn.setImageResource(R.drawable.ic_pause_fill);
            scheduleHide();
        } catch (Exception ignored) {
        }
    }

    private void pausePlayback() {
        try {
            if (prepared && playing()) {
                if (video) videoView.pause();
                else player.pause();
            }
        } catch (Exception ignored) {
        }
        if (playBtn != null) playBtn.setImageResource(R.drawable.ic_play_fill);
    }

    private void togglePlay() {
        if (!prepared) return;
        if (playing()) {
            pausePlayback();
            if (video) showControls(true);
        } else {
            startPlayback();
        }
    }

    private void requestFocus() {
        if (audio != null && focusListener != null) {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonFocus() {
        if (audio != null && focusListener != null) audio.abandonAudioFocus(focusListener);
    }

    private void releasePlayer() {
        ui.removeCallbacks(tick);
        prepared = false;
        if (video) {
            if (videoView != null) videoView.stopPlayback();
        } else if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
        }
        player = null;
    }

    private void saveResume() {
        if (file == null || !prepared || duration <= 0) return;
        int p = position();
        Store.setResume(this, file.getAbsolutePath(), p > 5000 && p < duration - 5000 ? p : 0);
    }

    // ------------------------------------------------------------------ repeat / shuffle / speed / sleep

    private void applyRepeat() {
        if (repeatBtn == null) return;
        repeatBtn.setImageResource(repeat == REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeatBtn.setContentDescription(getString(repeat == REPEAT_OFF ? R.string.mp_repeat_off
                : repeat == REPEAT_ALL ? R.string.mp_repeat_all : R.string.mp_repeat_one));
        int on = Ui.color(this, R.color.accent_text);
        int off = video ? 0xFFFFFFFF : Ui.color(this, R.color.text_secondary);
        repeatBtn.setImageTintList(ColorStateList.valueOf(repeat == REPEAT_OFF ? off : on));
        repeatBtn.setAlpha(repeat == REPEAT_OFF && video ? 0.6f : 1f);
        try {
            if (player != null) player.setLooping(repeat == REPEAT_ONE);
        } catch (Exception ignored) {
        }
        if (prepared) Toast.makeText(this, repeatBtn.getContentDescription(), Toast.LENGTH_SHORT).show();
    }

    private void applyShuffle() {
        if (shuffleBtn == null) return;
        shuffleBtn.setImageTintList(ColorStateList.valueOf(shuffle ? Ui.color(this, R.color.accent_text)
                : Ui.color(this, R.color.text_secondary)));
    }

    private void pickSpeed() {
        String[] names = new String[SPEEDS.length];
        for (int i = 0; i < names.length; i++) names[i] = speedLabel(SPEEDS[i]) + (i == speedIdx ? "  ✓" : "");
        new Dlg(this).setTitle(R.string.mp_speed).setItems(names, (d, which) -> {
            speedIdx = which;
            applySpeed();
        }).show();
    }

    private static String speedLabel(float sp) {
        return (sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "x";
    }

    private void applySpeed() {
        if (!prepared || player == null) return;
        boolean was = playing();
        try {
            player.setPlaybackParams(new PlaybackParams().setSpeed(SPEEDS[speedIdx]));
            if (!was) {   // some versions start playing when the speed is set
                if (video) videoView.pause();
                else player.pause();
            }
        } catch (Exception e) {
            if (speedIdx != 0) Toast.makeText(this, R.string.v_speed_na, Toast.LENGTH_SHORT).show();
            speedIdx = 0;
        }
        speedChip.setText(speedLabel(SPEEDS[speedIdx]));
        if (!video) Ui.setChip(this, speedChip, speedIdx != 0);
    }

    private void pickSleep() {
        String[] names = new String[SLEEP_MIN.length + 1];
        for (int i = 0; i < SLEEP_MIN.length; i++) names[i] = getString(R.string.mp_sleep_min, SLEEP_MIN[i]);
        names[SLEEP_MIN.length] = getString(R.string.mp_sleep_off);
        new Dlg(this).setTitle(R.string.mp_sleep).setItems(names, (d, which) -> {
            if (which == SLEEP_MIN.length) {
                sleepAt = 0;
            } else {
                sleepAt = System.currentTimeMillis() + SLEEP_MIN[which] * 60_000L;
                Toast.makeText(this, getString(R.string.mp_sleep_set, SLEEP_MIN[which]), Toast.LENGTH_SHORT).show();
            }
            updateSleepChip();
        }).show();
    }

    private void updateSleepChip() {
        sleepChip.setText(sleepAt > 0 ? "⏱ " + Math.max(1, (sleepAt - System.currentTimeMillis()) / 60_000L + 1) + "m"
                : getString(R.string.mp_sleep));
        if (!video) Ui.setChip(this, sleepChip, sleepAt > 0);
    }

    private static String time(int ms) {
        int s = Math.max(0, ms) / 1000;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        s = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }
}
