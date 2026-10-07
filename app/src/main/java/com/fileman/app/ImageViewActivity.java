package com.fileman.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen image viewer: black stage, pinch / double-tap zoom, finger-following swipe between the images of
 * the folder with a slide animation, a thumbnail strip, tap to hide the bars, fling down to close, and a
 * floating action bar (share, rotate, slideshow, details).
 */
public class ImageViewActivity extends BaseActivity {
    private static final int MAX_SIDE = 2560;
    private static final int THUMB_WINDOW = 24;
    private static final int SLIDE_MS = 3500;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<File> images = new ArrayList<>();
    private final Map<String, String> dims = new ConcurrentHashMap<>();
    private LruCache<String, Bitmap> cache;
    private LruCache<String, Bitmap> thumbCache;
    private int index = 0;
    private volatile int gen = 0;

    private FrameLayout root;
    private ZoomImageView zoom;
    private View loading, topBar, bottomBox;
    private TextView titleView, subtitleView;
    private HorizontalScrollView thumbScroll;
    private LinearLayout thumbs;
    private ImageButton slideBtn;
    private int thumbStart = 0, thumbEnd = 0;
    private Bitmap current;
    private boolean barsShown = true;
    private boolean slideshow = false;
    private final Runnable slideTick = new Runnable() {
        @Override
        public void run() {
            if (!slideshow) return;
            go(index + 1 < images.size() ? index + 1 : 0, 1);
            ui.postDelayed(this, SLIDE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra("path");
        final File start = path == null ? null : new File(path);
        if (start == null || !start.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        long max = Runtime.getRuntime().maxMemory();
        cache = new LruCache<String, Bitmap>((int) Math.max(24L * 1024 * 1024, max / 6)) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
        thumbCache = new LruCache<String, Bitmap>((int) Math.max(4L * 1024 * 1024, max / 16)) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
        goImmersive();
        buildUi(start);

        // the other images of the same folder, in name order
        images.add(start);
        updateTitle();
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
                buildThumbs();
                prefetch();
            });
        });
        load(0);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        try {   // the stage is always black: keep the system icons light
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            c.setAppearanceLightStatusBars(false);
            c.setAppearanceLightNavigationBars(false);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ UI

    private void goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wl = getWindow().getAttributes();
            wl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wl);
        }
    }

    private ImageButton iconButton(int icon, int descRes) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.bg_tile_round);
        b.setContentDescription(getString(descRes));
        Ui.press(this, b);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        return b;
    }

    private void buildUi(File start) {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        zoom = new ZoomImageView(this);
        zoom.setSwipeListener(dir -> go(index + dir, dir));
        zoom.setTapListener(() -> showBars(!barsShown));
        zoom.setDismissListener(() -> getOnBackPressedDispatcher().onBackPressed());
        root.addView(zoom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(ColorStateList.valueOf(0xFFFFFFFF));
        pb.setVisibility(View.INVISIBLE);
        loading = pb;
        root.addView(pb, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));

        // top bar: back, name + position / size / dimensions, more
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 26));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000}));
        ImageButton back = iconButton(R.drawable.ic_back, R.string.back);
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        top.addView(back);
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(0xCCFFFFFF);
        subtitleView.setTextSize(12);
        subtitleView.setSingleLine(true);
        subtitleView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tl.addView(titleView);
        tl.addView(subtitleView);
        top.addView(tl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton more = iconButton(R.drawable.ic_more, R.string.more);
        more.setOnClickListener(v -> Opener.moreMenu(this, images.get(index)));
        top.addView(more);
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // bottom: thumbnail strip, then the floating action bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 12));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xDD000000}));
        thumbScroll = new HorizontalScrollView(this);
        thumbScroll.setHorizontalScrollBarEnabled(false);
        thumbScroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        thumbScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        thumbScroll.setVisibility(View.GONE);
        thumbs = new LinearLayout(this);
        thumbs.setOrientation(LinearLayout.HORIZONTAL);
        thumbs.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        thumbs.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        thumbScroll.addView(thumbs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(thumbScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = ViewerBar.build(this, true,
                new int[]{R.drawable.ic_share, R.drawable.ic_rotate, R.drawable.ic_play_fill, R.drawable.ic_info},
                new int[]{R.string.share, R.string.rd_rotate, R.string.rd_slideshow, R.string.rd_info},
                new View.OnClickListener[]{
                        v -> Opener.share(this, images.get(index)),
                        v -> rotate(),
                        v -> toggleSlideshow(),
                        v -> showInfo()});
        slideBtn = (ImageButton) actions.getChildAt(2);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 10);
        bottom.addView(actions, ap);
        bottomBox = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // keep the bars clear of the camera cut-out and the system bars when they are shown
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets in = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(in.left + Ui.dp(this, 8), in.top + Ui.dp(this, 8), in.right + Ui.dp(this, 8),
                    Ui.dp(this, 26));
            bottomBox.setPadding(in.left, Ui.dp(this, 28), in.right, in.bottom + Ui.dp(this, 12));
            return insets;
        });
    }

    private void showBars(boolean show) {
        barsShown = show;
        for (View v : new View[]{topBar, bottomBox}) {
            v.animate().cancel();
            if (show) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(180).start();
            } else {
                final View fv = v;
                v.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                    if (!barsShown) fv.setVisibility(View.INVISIBLE);
                }).start();
            }
        }
    }

    // ------------------------------------------------------------------ thumbnails

    private void buildThumbs() {
        thumbs.removeAllViews();
        if (images.size() < 2) {
            thumbScroll.setVisibility(View.GONE);
            return;
        }
        thumbScroll.setVisibility(View.VISIBLE);
        thumbStart = Math.max(0, index - THUMB_WINDOW);
        thumbEnd = Math.min(images.size(), index + THUMB_WINDOW + 1);
        for (int i = thumbStart; i < thumbEnd; i++) thumbs.addView(thumbCell(i));
        selectThumb();
    }

    private View thumbCell(final int i) {
        FrameLayout cell = new FrameLayout(this);
        int pad = Ui.dp(this, 3);
        cell.setPadding(pad, pad, pad, pad);
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0x33FFFFFF);
        final float radius = Ui.dp(this, 10);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        cell.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60));
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        cell.setLayoutParams(lp);
        cell.setOnClickListener(v -> go(i, i > index ? 1 : -1));

        final File f = images.get(i);
        final String key = f.getAbsolutePath();
        Bitmap hit = thumbCache.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
        } else {
            thumbIo.execute(() -> {
                final Bitmap b = decodeThumb(f, Ui.dp(this, 60));
                if (b == null) return;
                thumbCache.put(key, b);
                ui.post(() -> {
                    if (!isFinishing() && !isDestroyed()) iv.setImageBitmap(b);
                });
            });
        }
        return cell;
    }

    /** Highlights the current thumbnail and scrolls it to the middle. */
    private void selectThumb() {
        for (int k = 0; k < thumbs.getChildCount(); k++) {
            final View c = thumbs.getChildAt(k);
            boolean on = thumbStart + k == index;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 13));
            if (on) g.setStroke(Ui.dp(this, 2), Ui.color(this, R.color.accent));
            c.setBackground(g);
            c.setAlpha(on ? 1f : 0.72f);
            if (on) {
                thumbScroll.post(() -> thumbScroll.smoothScrollTo(
                        Math.max(0, c.getLeft() - (thumbScroll.getWidth() - c.getWidth()) / 2), 0));
            }
        }
    }

    private void updateThumbs() {
        if (images.size() < 2) return;
        boolean outside = index < thumbStart || index >= thumbEnd;
        boolean nearStart = index < thumbStart + 3 && thumbStart > 0;
        boolean nearEnd = index > thumbEnd - 4 && thumbEnd < images.size();
        if (thumbs.getChildCount() == 0 || outside || nearStart || nearEnd) buildThumbs();
        else selectThumb();
    }

    private static Bitmap decodeThumb(File f, int target) {
        try {
            String path = f.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int m = Math.min(o.outWidth, o.outHeight);
            if (m <= 0) return null;
            int s = 1;
            while (m / (s * 2) >= target) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ actions

    private void rotate() {
        if (current == null) return;
        Matrix mx = new Matrix();
        mx.postRotate(90);
        try {
            current = Bitmap.createBitmap(current, 0, 0, current.getWidth(), current.getHeight(), mx, true);
            zoom.show(current);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.v_too_large, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleSlideshow() {
        slideshow = !slideshow;
        ui.removeCallbacks(slideTick);
        slideBtn.setImageResource(slideshow ? R.drawable.ic_pause_fill : R.drawable.ic_play_fill);
        if (slideshow) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showBars(false);
            ui.postDelayed(slideTick, SLIDE_MS);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showInfo() {
        File f = images.get(index);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.rd_resolution)).append(": ").append(o.outWidth).append(" × ").append(o.outHeight).append('\n');
        sb.append(getString(R.string.rd_size)).append(": ").append(Fmt.size(f.length())).append('\n');
        sb.append(getString(R.string.rd_date)).append(": ")
                .append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(f.lastModified()))).append('\n');
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            String make = ex.getAttribute(ExifInterface.TAG_MAKE);
            String model = ex.getAttribute(ExifInterface.TAG_MODEL);
            if (make != null || model != null) {
                sb.append(getString(R.string.rd_camera)).append(": ")
                        .append(make == null ? "" : make).append(' ').append(model == null ? "" : model).append('\n');
            }
        } catch (Exception ignored) {
        }
        sb.append(getString(R.string.rd_path)).append(": ").append(f.getParent());
        new Dlg(this).setTitle(f.getName()).setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null).show();
    }

    // ------------------------------------------------------------------ navigation and loading

    private void go(int i, int dir) {
        if (i < 0 || i >= images.size() || i == index) {
            zoom.snapBack();
            return;
        }
        index = i;
        updateTitle();
        updateThumbs();
        load(dir);
    }

    private void updateTitle() {
        File f = images.get(index);
        titleView.setText(f.getName());
        String pos = images.size() > 1 ? (index + 1) + " / " + images.size() + " · " : "";
        String d = dims.get(f.getAbsolutePath());
        subtitleView.setText(pos + Fmt.size(f.length()) + (d != null ? " · " + d : ""));
    }

    /** Shows the current image; dir says which way it slides in (0 = no animation). */
    private void load(final int dir) {
        final int my = ++gen;
        final File f = images.get(index);
        final String key = f.getAbsolutePath();
        final Bitmap hit = cache.get(key);
        final float off = dir * zoom.getWidth() * 0.3f;
        if (hit != null) {
            if (dir == 0) {
                present(hit, 0);
                prefetch();
                return;
            }
            zoom.animate().translationX(-off).alpha(0f).setDuration(90).start();
            ui.postDelayed(() -> {
                if (my != gen || isFinishing() || isDestroyed()) return;
                present(hit, dir);
                prefetch();
            }, 90);
            return;
        }
        loading.setVisibility(View.VISIBLE);
        if (dir != 0) zoom.animate().translationX(-off).alpha(0f).setDuration(110).start();
        io.execute(() -> {
            if (my != gen) return;
            final Bitmap b = decode(f);
            ui.post(() -> {
                if (my != gen || isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.INVISIBLE);
                if (b == null) {
                    zoom.snapBack();
                    Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
                    return;
                }
                cache.put(key, b);
                present(b, dir);
                prefetch();
            });
        });
    }

    private void present(Bitmap b, int dir) {
        current = b;
        updateTitle();
        zoom.animate().cancel();
        zoom.show(b);
        if (dir != 0) {
            zoom.setTranslationX(dir * zoom.getWidth() * 0.3f);
            zoom.setAlpha(0f);
            zoom.animate().translationX(0f).alpha(1f).setDuration(170).start();
        } else {
            zoom.setTranslationX(0f);
            zoom.setAlpha(1f);
        }
    }

    /** Decodes the neighbours in the background so swiping feels instant. */
    private void prefetch() {
        for (int d : new int[]{1, -1}) {
            int j = index + d;
            if (j < 0 || j >= images.size()) continue;
            final File f = images.get(j);
            final String key = f.getAbsolutePath();
            if (cache.get(key) != null) continue;
            io.execute(() -> {
                if (isDestroyed() || cache.get(key) != null) return;
                Bitmap b = decode(f);
                if (b != null) cache.put(key, b);
            });
        }
    }

    private Bitmap decode(File f) {
        try {
            String path = f.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth > 0) dims.put(path, o.outWidth + " × " + o.outHeight);
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
                if (rot == 90 || rot == 270) dims.put(path, b.getWidth() + " × " + b.getHeight());
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
        thumbIo.shutdownNow();
    }
}
