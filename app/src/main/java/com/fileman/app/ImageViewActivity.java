package com.fileman.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Image viewer: pinch / double-tap zoom, and swipe to move between the images of the same folder. */
public class ImageViewActivity extends AppCompatActivity {
    private static final int MAX_SIDE = 2560;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<File> images = new ArrayList<>();
    private int index = 0;
    private volatile int gen = 0;

    private ZoomImageView zoom;
    private View loading;
    private TextView titleView, subtitleView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        final File start = path == null ? null : new File(path);
        if (start == null || !start.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
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
        more.setOnClickListener(v -> Opener.moreMenu(this, images.isEmpty() ? start : images.get(index)));

        zoom = new ZoomImageView(this);
        zoom.setSwipeListener(dir -> go(index + dir));
        ((FrameLayout) findViewById(R.id.holder)).addView(zoom,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the other images of the same folder, in name order
        images.add(start);
        io.execute(() -> {
            final List<File> list = new ArrayList<>();
            File[] kids = start.getParentFile() == null ? null : start.getParentFile().listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.isFile() && !k.getName().startsWith(".")
                            && Cats.typeOfExt(Cats.extOf(k.getName())) == Cats.T_IMG) list.add(k);
                }
            }
            Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || list.isEmpty()) return;
                int i = list.indexOf(start);
                if (i < 0) return;
                images.clear();
                images.addAll(list);
                index = i;
                updateTitle();
            });
        });
        show();
    }

    private void go(int i) {
        if (i < 0 || i >= images.size() || i == index) return;
        index = i;
        show();
    }

    private void updateTitle() {
        File f = images.get(index);
        titleView.setText(f.getName());
        String pos = images.size() > 1 ? (index + 1) + " / " + images.size() + " · " : "";
        subtitleView.setText(pos + Fmt.size(f.length()));
    }

    private void show() {
        updateTitle();
        final int my = ++gen;
        final File f = images.get(index);
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            final Bitmap b = decode(f);
            ui.post(() -> {
                if (my != gen || isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.INVISIBLE);
                if (b == null) {
                    Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
                    return;
                }
                zoom.show(b);
            });
        });
    }

    private static Bitmap decode(File f) {
        try {
            String path = f.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int s = 1;
            int m = Math.max(o.outWidth, o.outHeight);
            while (m / s > MAX_SIDE) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(path, o2);
            if (b == null) return null;
            int rot = 0;
            try {
                int ori = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) {
            }
            if (rot != 0) {
                Matrix mx = new Matrix();
                mx.postRotate(rot);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }
}
