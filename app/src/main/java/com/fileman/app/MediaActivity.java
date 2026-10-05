package com.fileman.app;

import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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

import java.io.File;
import java.util.Locale;

/**
 * Plays video and audio with one shared look: a rounded stage (the picture, or cover art for audio),
 * the title, a seek bar, and a control row with skip back / play / skip forward, speed and repeat.
 */
public class MediaActivity extends AppCompatActivity {
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};
    private static final int SKIP_MS = 10_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private File file;
    private boolean video;
    private View loading;
    private TextView subtitle;

    private VideoView videoView;
    private MediaPlayer player;      // audio player, or the VideoView's own player once prepared
    private boolean prepared = false;
    private int duration = 0;
    private boolean dragging = false;
    private boolean looping = false;
    private int speedIdx = 0;

    private SeekBar seek;
    private ImageButton playBtn, loopBtn;
    private TextView timeNow, timeTotal, speedChip;
    private FrameLayout stage;

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
            ui.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        video = Cats.typeOfExt(Cats.extOf(file.getName())) == Cats.T_VID;
        ((TextView) findViewById(R.id.title)).setText(file.getName());
        subtitle = findViewById(R.id.subtitle);
        setSubtitle(0);
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ImageButton more = findViewById(R.id.btnA1);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> Opener.moreMenu(this, file));

        buildUi((FrameLayout) findViewById(R.id.holder));
        start();
    }

    private void setSubtitle(int durMs) {
        String t = Cats.extOf(file.getName()).toUpperCase(Locale.ROOT) + " · " + Fmt.size(file.length());
        if (durMs > 0) t += " · " + time(durMs);
        subtitle.setText(t);
    }

    private void failed() {
        if (isFinishing() || isDestroyed()) return;
        loading.setVisibility(View.INVISIBLE);
        new Dlg(this).setTitle(R.string.v_media_failed_title).setMessage(R.string.v_media_failed)
                .setPositiveButton(R.string.fm_open_with, (d, w) -> {
                    Opener.external(this, file, true);
                    finish();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .show();
    }

    // ------------------------------------------------------------------ one UI for audio and video

    private GradientDrawable oval(int colorRes) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.color(this, colorRes));
        return g;
    }

    private ImageButton round(int icon, int sizeDp, int bgRes, int tintRes, int descRes) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(this, tintRes)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(oval(bgRes));
        b.setContentDescription(getString(descRes));
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

    private void buildUi(FrameLayout holder) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 24));
        sv.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // stage
        stage = new FrameLayout(this);
        int sw = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 40);
        if (video) {
            stage.setBackgroundColor(0xFF000000);
            rounded(stage, 22);
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(Ui.dp(this, 22));
            border.setColor(0xFF000000);
            border.setStroke(Ui.dp(this, 1), Ui.color(this, R.color.stroke_soft));
            stage.setBackground(border);
            videoView = new VideoView(this);
            stage.addView(videoView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            videoView.setOnClickListener(v -> togglePlay());
            box.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sw * 9 / 16));
        } else {
            int side = Math.min(sw, Ui.dp(this, 260));
            ImageView art = new ImageView(this);
            art.setImageResource(R.drawable.ic_music);
            art.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.info)));
            int pad = side / 4;
            art.setPadding(pad, pad, pad, pad);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 36));
            g.setColor(Ui.color(this, R.color.surface));
            g.setStroke(Ui.dp(this, 1), Ui.color(this, R.color.stroke_soft));
            art.setBackground(g);
            stage.addView(art, new FrameLayout.LayoutParams(side, side));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(side, side);
            sp.topMargin = Ui.dp(this, 20);
            box.addView(stage, sp);
        }

        TextView name = new TextView(this);
        name.setText(file.getName());
        name.setTextColor(Ui.color(this, R.color.text_primary));
        name.setTextSize(18);
        name.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 14));
        box.addView(name);

        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        seek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        box.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout times = new LinearLayout(this);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        timeNow = new TextView(this);
        timeTotal = new TextView(this);
        for (TextView t : new TextView[]{timeNow, timeTotal}) {
            t.setTextColor(Ui.color(this, R.color.text_secondary));
            t.setTextSize(13);
        }
        timeNow.setText(time(0));
        timeTotal.setText(time(0));
        times.addView(timeNow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(timeTotal);
        box.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // controls: speed · back · play · forward · repeat
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        speedChip = Ui.chip(this, "1x", false);
        speedChip.setOnClickListener(v -> cycleSpeed());
        row.addView(speedChip, new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 40)));
        speedChip.setGravity(Gravity.CENTER);
        speedChip.setPadding(0, 0, 0, 0);

        ImageButton back = round(R.drawable.ic_rewind, 52, R.color.surface, R.color.text_primary, R.string.v_back10);
        playBtn = round(R.drawable.ic_play, 72, R.color.accent, R.color.on_accent, R.string.v_play);
        ImageButton fwd = round(R.drawable.ic_forward, 52, R.color.surface, R.color.text_primary, R.string.v_fwd10);
        loopBtn = round(R.drawable.ic_refresh, 40, R.color.surface, R.color.text_secondary, R.string.v_repeat);
        int gap = Ui.dp(this, 14);
        for (View v : new View[]{back, playBtn, fwd}) {
            ((LinearLayout.LayoutParams) v.getLayoutParams()).setMargins(gap, 0, gap, 0);
        }
        row.addView(back);
        row.addView(playBtn);
        row.addView(fwd);
        row.addView(loopBtn);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Ui.dp(this, 22);
        box.addView(row, rl);

        playBtn.setEnabled(false);
        playBtn.setOnClickListener(v -> togglePlay());
        back.setOnClickListener(v -> skip(-SKIP_MS));
        fwd.setOnClickListener(v -> skip(SKIP_MS));
        loopBtn.setOnClickListener(v -> {
            looping = !looping;
            applyLoop();
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) timeNow.setText(time(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (prepared) seekTo(s.getProgress());
            }
        });
        holder.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------ transport

    private void start() {
        loading.setVisibility(View.VISIBLE);
        if (video) {
            videoView.setOnPreparedListener(mp -> {
                player = mp;
                onReady(videoView.getDuration());
                fitVideo(mp.getVideoWidth(), mp.getVideoHeight());
                videoView.start();
                playBtn.setImageResource(R.drawable.ic_pause);
            });
            videoView.setOnCompletionListener(mp -> onEnded());
            videoView.setOnErrorListener((mp, what, extra) -> {
                failed();
                return true;
            });
            videoView.setVideoPath(file.getAbsolutePath());
        } else {
            try {
                player = new MediaPlayer();
                player.setDataSource(file.getAbsolutePath());
                player.setOnPreparedListener(mp -> {
                    onReady(mp.getDuration());
                    mp.start();
                    playBtn.setImageResource(R.drawable.ic_pause);
                });
                player.setOnCompletionListener(mp -> onEnded());
                player.setOnErrorListener((mp, what, extra) -> {
                    failed();
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                failed();
            }
        }
    }

    private void onReady(int dur) {
        prepared = true;
        duration = Math.max(0, dur);
        loading.setVisibility(View.INVISIBLE);
        seek.setMax(duration);
        timeTotal.setText(time(duration));
        setSubtitle(duration);
        playBtn.setEnabled(true);
        applyLoop();
        ui.post(tick);
    }

    /** Sizes the video stage to the picture (landscape and portrait alike), capped to 62% of the screen. */
    private void fitVideo(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        int w = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 40);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        int h = Math.min(maxH, Math.round(w * (float) vh / vw));
        ViewGroup.LayoutParams lp = stage.getLayoutParams();
        lp.height = Math.max(Ui.dp(this, 120), h);
        stage.setLayoutParams(lp);
    }

    private void onEnded() {
        if (looping) return;   // the player restarts itself
        playBtn.setImageResource(R.drawable.ic_play);
        seek.setProgress(0);
        timeNow.setText(time(0));
    }

    private boolean playing() {
        try {
            return video ? videoView.isPlaying() : player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private int position() {
        return video ? videoView.getCurrentPosition() : player.getCurrentPosition();
    }

    private void seekTo(int ms) {
        int t = Math.max(0, Math.min(duration, ms));
        if (video) videoView.seekTo(t);
        else player.seekTo(t);
        seek.setProgress(t);
        timeNow.setText(time(t));
    }

    private void skip(int delta) {
        if (!prepared) return;
        try {
            seekTo(position() + delta);
        } catch (Exception ignored) {
        }
    }

    private void togglePlay() {
        if (!prepared) return;
        try {
            if (playing()) {
                if (video) videoView.pause();
                else player.pause();
                playBtn.setImageResource(R.drawable.ic_play);
            } else {
                if (video) videoView.start();
                else player.start();
                playBtn.setImageResource(R.drawable.ic_pause);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyLoop() {
        try {
            if (player != null) player.setLooping(looping);
        } catch (Exception ignored) {
        }
        loopBtn.setImageTintList(ColorStateList.valueOf(Ui.color(this, looping ? R.color.accent_text : R.color.text_secondary)));
        GradientDrawable g = oval(looping ? R.color.accent_soft : R.color.surface);
        loopBtn.setBackground(g);
    }

    private void cycleSpeed() {
        if (!prepared || player == null) return;
        int next = (speedIdx + 1) % SPEEDS.length;
        boolean wasPlaying = playing();
        try {
            player.setPlaybackParams(new PlaybackParams().setSpeed(SPEEDS[next]));
            if (!wasPlaying) {   // some versions start playing when the speed is set
                if (video) videoView.pause();
                else player.pause();
            }
            speedIdx = next;
            float sp = SPEEDS[next];
            speedChip.setText((sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "x");
            Ui.setChip(this, speedChip, next != 0);
        } catch (Exception e) {
            Toast.makeText(this, R.string.v_speed_na, Toast.LENGTH_SHORT).show();
        }
    }

    private static String time(int ms) {
        int s = Math.max(0, ms) / 1000;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        s = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (playing()) {
                if (video) videoView.pause();
                else player.pause();
                playBtn.setImageResource(R.drawable.ic_play);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (videoView != null) videoView.stopPlayback();
        else if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
        }
        player = null;
    }
}
