package com.fileman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;

/**
 * Entry point for links from other apps. Registered for every file / content link (any MIME type), for
 * folders and for the system "downloads" screen, so the app can stand in for the system Files app.
 * Has no screen of its own: it works out which file the link points to and starts the right viewer.
 */
public class OpenActivity extends Activity {
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private volatile boolean gone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent in = getIntent();
        if (in == null) {
            finish();
            return;
        }
        if ("android.intent.action.VIEW_DOWNLOADS".equals(in.getAction())) {
            openDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            return;
        }
        Uri data = in.getData();
        if (data == null) {
            ClipData cd = in.getClipData();
            if (cd != null && cd.getItemCount() > 0) data = cd.getItemAt(0).getUri();
        }
        if (data == null) {
            finish();
            return;
        }
        // the "make this app the default" helper only sends a probe link: nothing to open
        if (Perms.isProbe(this, data)) {
            Toast.makeText(this, R.string.def_probe_ok, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final Uri link = data;
        new Thread(() -> route(in, link)).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gone = true;
    }

    private void route(final Intent in, final Uri link) {
        UriFiles.cleanIncoming(this);
        final String type = in.getType();
        File real = UriFiles.resolve(this, link);
        if (real != null && real.isDirectory()) {
            final File dir = real;
            runOnUiThread(() -> openDir(dir));
            return;
        }
        if (real == null || !real.isFile() || !real.canRead()) real = null;

        String name = real != null ? real.getName() : Cats.displayName(getContentResolver(), link);
        String ext = name == null ? "" : Cats.extOf(name);
        boolean apk = APK_MIME.equals(type) || Cats.typeOfExt(ext) == Cats.T_APK;
        if (apk) {
            runOnUiThread(() -> {
                if (gone) return;
                Intent f = new Intent(in);
                f.setComponent(new android.content.ComponentName(this, InstallActivity.class));
                startActivity(f);
                finish();
            });
            return;
        }

        File file = real;
        if (file != null && !Safe.mayExpose(this, file)) {
            fail(R.string.fm_private_blocked);
            return;
        }
        if (file == null) {
            try {
                file = UriFiles.copyToCache(this, link);
            } catch (Exception e) {
                fail(R.string.open_link_failed);
                return;
            }
        }
        final File target = file;
        runOnUiThread(() -> {
            if (gone) return;
            int kind = Cats.viewKind(Cats.extOf(target.getName()));
            if (kind == Cats.V_LEGACY || kind == Cats.V_NONE) {
                Opener.external(this, target, true);
            } else {
                Opener.open(this, target);
            }
            finish();
        });
    }

    private void openDir(File dir) {
        if (dir == null || !dir.isDirectory() || !Safe.mayExpose(this, dir)) {
            fail(R.string.open_link_failed);
            return;
        }
        Intent i = new Intent(this, FileManagerActivity.class);
        i.putExtra("path", dir.getAbsolutePath());
        startActivity(i);
        finish();
    }

    private void fail(final int msg) {
        runOnUiThread(() -> {
            if (gone) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
