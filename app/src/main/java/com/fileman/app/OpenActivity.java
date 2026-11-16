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

    private static final int REQ_WHERE = 83;

    private volatile boolean gone = false;
    private java.util.ArrayList<Uri> pending = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent in = getIntent();
        if (in == null) {
            finish();
            return;
        }
        android.net.Uri ref = null;
        try {
            ref = getReferrer();
        } catch (Throwable ignored) {
        }
        PickLog.add(this, "open " + in.getAction() + " from " + ref + " type=" + in.getType()
                + " data=" + in.getData() + " clip=" + (in.getClipData() == null ? 0 : in.getClipData().getItemCount())
                + " flags=0x" + Integer.toHexString(in.getFlags()), null);
        if ("android.intent.action.VIEW_DOWNLOADS".equals(in.getAction())) {
            openDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            return;
        }
        if (Semo.ACTION_SHOW_IN_FILES.equals(in.getAction())) {
            // another Semo app (Gallery / Music) asks to see where a file lives: the path is in the data Uri only
            Uri d = in.getData();
            String q = d != null && Semo.SCHEME_FILES.equals(d.getScheme()) ? d.getQueryParameter("p") : null;
            File f = q == null || q.isEmpty() ? null : new File(q);
            openDir(f == null ? null : (f.isDirectory() ? f : f.getParentFile()));
            return;
        }
        if ("android.provider.action.BROWSE".equals(in.getAction())) {
            openDir(Environment.getExternalStorageDirectory());
            return;
        }
        if (isSend(in)) {
            final java.util.ArrayList<Uri> shared = sharedUris(in);
            if (shared.isEmpty()) {
                finish();
                return;
            }
            pending = shared;
            if (SaveTarget.ASK.equals(SaveTarget.mode(this))) {
                if (savedInstanceState == null) askWhere(shared);
            } else {
                new Thread(() -> saveShared(shared, null)).start();
            }
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
        PickLog.add(this, "open resolved " + link + " -> " + real, null);
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
                PickLog.add(this, "open copy FAILED " + link, e);
                fail(R.string.open_link_failed);
                return;
            }
        }
        final File target = file;
        runOnUiThread(() -> {
            if (gone) return;
            String x = Cats.extOf(target.getName());
            int kind = Cats.viewKind(x);
            if (Opener.isArchiveExt(x) || (kind == Cats.V_NONE && Opener.looksLikeZip(target))) {
                Opener.openArchive(this, target);   // zip / jar / cbz (also files with a missing or wrong extension)
            } else if (kind == Cats.V_LEGACY || kind == Cats.V_NONE) {
                Opener.external(this, target, true);
            } else {
                Opener.open(this, target, false);
            }
            finish();
        });
    }

    private static boolean isSend(Intent in) {
        String a = in.getAction();
        return Intent.ACTION_SEND.equals(a) || Intent.ACTION_SEND_MULTIPLE.equals(a);
    }

    /** Shared streams come from the ClipData only (the system copies EXTRA_STREAM into it); no extras are read. */
    private static java.util.ArrayList<Uri> sharedUris(Intent in) {
        java.util.ArrayList<Uri> out = new java.util.ArrayList<>();
        ClipData cd = in.getClipData();
        if (cd != null) {
            for (int k = 0; k < cd.getItemCount(); k++) {
                Uri u = cd.getItemAt(k).getUri();
                if (u != null && !out.contains(u)) out.add(u);
            }
        }
        return out;
    }

    private void askWhere(java.util.ArrayList<Uri> uris) {
        String name = uris.size() == 1 ? SaveCopy.nameOf(this, uris.get(0)) : getString(R.string.dl_many_files, uris.size());
        Intent i = new Intent(this, FileManagerActivity.class);
        i.putExtra("save_name", name);
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dl != null && dl.isDirectory()) i.putExtra("path", dl.getAbsolutePath());
        try {
            startActivityForResult(i, REQ_WHERE);
        } catch (Throwable t) {
            new Thread(() -> saveShared(pending, null)).start();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WHERE) return;
        String dir = resultCode == RESULT_OK && data != null ? data.getStringExtra("picked_dir") : null;
        if (dir == null) {
            finish();
            return;
        }
        final File target = new File(dir);
        final java.util.ArrayList<Uri> uris = pending;
        new Thread(() -> saveShared(uris, target)).start();
    }

    private void saveShared(java.util.ArrayList<Uri> uris, File dir) {
        int ok = 0;
        for (Uri u : uris) {
            String saved = dir != null ? SaveCopy.uriToDirectory(this, u, dir) : SaveCopy.uriToConfigured(this, u);
            PickLog.add(this, "share saved " + u + " -> " + saved, null);
            if (saved != null) ok++;
        }
        final boolean good = ok > 0;
        final File show = dir != null ? dir : (SaveTarget.FOLDER.equals(SaveTarget.mode(this)) && SaveTarget.tree(this) != null
                ? null : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        runOnUiThread(() -> {
            if (gone) return;
            Toast.makeText(this, good ? R.string.zip_saved_copy : R.string.zip_save_failed, Toast.LENGTH_LONG).show();
            if (good && show != null) {
                openDir(show);
            } else {
                finish();
            }
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
        PickLog.add(this, "open fail " + getString(msg), null);
        runOnUiThread(() -> {
            if (gone) return;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
