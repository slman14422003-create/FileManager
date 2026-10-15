package com.fileman.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShareScanActivity extends BaseActivity {
    private PreviewView preview;
    private ScanOverlay overlay;
    private Camera camera;
    private boolean torch;
    private volatile boolean found;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final MultiFormatReader reader = new MultiFormatReader();
    private ActivityResultLauncher<String> permLauncher;
    private TextView hint;

    @Override
    protected boolean constrainContent() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        preview = new PreviewView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ScanOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 36), Ui.dp(this, 16), Ui.dp(this, 8));
        android.widget.ImageButton back = new android.widget.ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        back.setBackground(null);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView title = new TextView(this);
        title.setText(R.string.sh_scan_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 36));
        hint = new TextView(this);
        hint.setText(R.string.sh_scan_hint);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        TextView flash = Ui.chip(this, getString(R.string.sh_flash), false);
        flash.setOnClickListener(v -> {
            torch = !torch;
            if (camera != null) camera.getCameraControl().enableTorch(torch);
            Ui.setChip(this, flash, torch);
        });
        TextView manual = Ui.chip(this, getString(R.string.sh_manual), false);
        manual.setOnClickListener(v -> manualEntry());
        btns.addView(flash);
        btns.addView(manual);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = Ui.dp(this, 14);
        bottom.addView(btns, bp);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) startCamera();
            else hint.setText(R.string.sh_cam_denied);
        });
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis an = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                an.setAnalyzer(exec, this::analyze);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an);
            } catch (Exception e) {
                hint.setText(R.string.sh_cam_denied);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            if (found) return;
            ImageProxy.PlaneProxy p = image.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            int w = image.getWidth();
            int h = image.getHeight();
            int stride = p.getRowStride();
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, stride, h, 0, 0, w, h, false);
            Result r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
            String text = r.getText();
            if (ShareLink.parse(text) != null) {
                found = true;
                runOnUiThread(() -> deliver(text));
            }
        } catch (Throwable ignored) {
        } finally {
            reader.reset();
            image.close();
        }
    }

    private void deliver(String text) {
        setResult(RESULT_OK, new Intent().putExtra("code", text));
        finish();
    }

    private void manualEntry() {
        final android.widget.EditText ed = Ui.edit(this, getString(R.string.sh_manual_hint), "");
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                if (t != null && ShareLink.parse(t.toString()) != null) ed.setText(t);
            }
        } catch (Throwable ignored) {
        }
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 14));
        box.addView(ed);
        new Dlg(this).setTitle(getString(R.string.sh_manual)).setView(box)
                .setPositiveButton(R.string.sh_connect, (d, w) -> {
                    String t = ed.getText().toString().trim();
                    if (ShareLink.parse(t) != null) deliver(t);
                    else android.widget.Toast.makeText(this, R.string.sh_bad_code, android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    private static final class ScanOverlay extends View {
        private final Paint scrim = new Paint();
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private long t0 = System.currentTimeMillis();

        ScanOverlay(Context c) {
            super(c);
            scrim.setColor(0xAA000000);
            corner.setColor(Ui.color(c, R.color.accent));
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(Ui.dp(c, 5));
            corner.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(Ui.color(c, R.color.accent));
            line.setStrokeWidth(Ui.dp(c, 3));
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            int h = getHeight();
            float s = Math.min(w, h) * 0.68f;
            float l = (w - s) / 2f;
            float t = (h - s) / 2f - Ui.dp(getContext(), 30);
            box.set(l, t, l + s, t + s);
            cv.drawRect(0, 0, w, box.top, scrim);
            cv.drawRect(0, box.bottom, w, h, scrim);
            cv.drawRect(0, box.top, box.left, box.bottom, scrim);
            cv.drawRect(box.right, box.top, w, box.bottom, scrim);
            float k = s * 0.14f;
            cv.drawLine(box.left, box.top, box.left + k, box.top, corner);
            cv.drawLine(box.left, box.top, box.left, box.top + k, corner);
            cv.drawLine(box.right, box.top, box.right - k, box.top, corner);
            cv.drawLine(box.right, box.top, box.right, box.top + k, corner);
            cv.drawLine(box.left, box.bottom, box.left + k, box.bottom, corner);
            cv.drawLine(box.left, box.bottom, box.left, box.bottom - k, corner);
            cv.drawLine(box.right, box.bottom, box.right - k, box.bottom, corner);
            cv.drawLine(box.right, box.bottom, box.right, box.bottom - k, corner);
            float phase = ((System.currentTimeMillis() - t0) % 2200) / 2200f;
            float tri = phase < 0.5f ? phase * 2 : (1 - phase) * 2;
            float y = box.top + s * (0.06f + 0.88f * tri);
            cv.drawLine(box.left + k * 0.6f, y, box.right - k * 0.6f, y, line);
            postInvalidateOnAnimation();
        }
    }
}
