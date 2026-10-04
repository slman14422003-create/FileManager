package com.fileman.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

/** Plays video (VideoView with the standard controls) and audio (own player card). */
public class MediaActivity extends AppCompatActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File file;
    private boolean video;
    private View loading;

    private VideoView videoView;
    private MediaPlayer player;
    private boolean prepared = false;
    private SeekBar seek;
    private ImageButton playBtn;
    private TextView timeNow, timeTotal;
    private boolean dragging = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared && !dragging) {
                try {
                    seek.setProgress(player.getCurrentPosition());
                    timeNow.setText(time(player.getCurrentPosition()));
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
        ((TextView) findViewById(R.id.subtitle)).setText(Fmt.size(file.length()));
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        ImageButton more = findViewById(R.id.btnA1);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> Opener.moreMenu(this, file));

        FrameLayout holder = findViewById(R.id.holder);
        if (video) buildVideo(holder);
        else buildAudio(holder);
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

    // ------------------------------------------------------------------ video

    private void buildVideo(FrameLayout holder) {
        videoView = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        holder.addView(videoView, lp);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(holder);
        videoView.setMediaController(mc);
        loading.setVisibility(View.VISIBLE);
        videoView.setOnPreparedListener(mp -> {
            loading.setVisibility(View.INVISIBLE);
            videoView.start();
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            failed();
            return true;
        });
        videoView.setVideoPath(file.getAbsolutePath());
    }

    // ------------------------------------------------------------------ audio

    private void buildAudio(FrameLayout holder) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 28), Ui.dp(this, 40), Ui.dp(this, 28), Ui.dp(this, 28));

        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.ic_music);
        art.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.info)));
        int p = Ui.dp(this, 48);
        art.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 48));
        g.setColor(Ui.color(this, R.color.surface));
        g.setStroke(Ui.dp(this, 1), Ui.color(this, R.color.stroke_soft));
        art.setBackground(g);
        box.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 220)));

        TextView name = new TextView(this);
        name.setText(file.getName());
        name.setTextColor(Ui.color(this, R.color.text_primary));
        name.setTextSize(18);
        name.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 20));
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

        playBtn = new ImageButton(this);
        playBtn.setImageResource(R.drawable.ic_play);
        playBtn.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
        playBtn.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable pb = new GradientDrawable();
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(Ui.color(this, R.color.accent));
        playBtn.setBackground(pb);
        playBtn.setEnabled(false);
        Ui.press(this, playBtn);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68));
        pl.topMargin = Ui.dp(this, 24);
        box.addView(playBtn, pl);
        playBtn.setOnClickListener(v -> togglePlay());

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
                if (player != null && prepared) player.seekTo(s.getProgress());
            }
        });
        holder.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        loading.setVisibility(View.VISIBLE);
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                prepared = true;
                loading.setVisibility(View.INVISIBLE);
                seek.setMax(mp.getDuration());
                timeTotal.setText(time(mp.getDuration()));
                playBtn.setEnabled(true);
                mp.start();
                playBtn.setImageResource(R.drawable.ic_pause);
                ui.post(tick);
            });
            player.setOnCompletionListener(mp -> {
                playBtn.setImageResource(R.drawable.ic_play);
                seek.setProgress(0);
                timeNow.setText(time(0));
            });
            player.setOnErrorListener((mp, what, extra) -> {
                failed();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            failed();
        }
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                playBtn.setImageResource(R.drawable.ic_play);
            } else {
                player.start();
                playBtn.setImageResource(R.drawable.ic_pause);
            }
        } catch (Exception ignored) {
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
            if (video && videoView != null && videoView.isPlaying()) videoView.pause();
            if (player != null && prepared && player.isPlaying()) {
                player.pause();
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
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }
}
