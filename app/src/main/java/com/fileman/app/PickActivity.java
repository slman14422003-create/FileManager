package com.fileman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Replaces the system file picker for apps that ask the user for something: a file (GET_CONTENT /
 * OPEN_DOCUMENT / PICK), a folder (OPEN_DOCUMENT_TREE) or a place to save a new file (CREATE_DOCUMENT).
 * Shows the file manager in the matching mode and hands the result back with the grants the other app
 * asked for. Document and folder results use the ExternalDocs provider so the grant can be kept.
 */
public class PickActivity extends Activity {
    private static final int REQ_PICK = 71;

    private static final String ACTION_TREE = "android.intent.action.OPEN_DOCUMENT_TREE";
    private static final String ACTION_CREATE = "android.intent.action.CREATE_DOCUMENT";
    private static final String ACTION_OPEN = "android.intent.action.OPEN_DOCUMENT";

    private String action = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        action = in == null || in.getAction() == null ? "" : in.getAction();
        PickLog.add(this, "picker " + action + " from " + getCallingPackage() + " type=" + (in == null ? null : in.getType())
                + " title=" + PickRequest.title(in) + " flags=0x"
                + Integer.toHexString(in == null ? 0 : in.getFlags()), null);
        if (savedInstanceState == null) {
            Intent i = new Intent(this, FileManagerActivity.class);
            if (ACTION_TREE.equals(action)) {
                i.putExtra("pick_dir", true);
            } else if (ACTION_CREATE.equals(action)) {
                i.putExtra("save_name", PickRequest.title(in));
            } else {
                i.putExtra("pick", true);
                String[] mimes = PickRequest.mimes(in);
                if (mimes != null) i.putExtra("mimes", mimes);
                i.putExtra("pick_multi", in.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
            }
            try {
                startActivityForResult(i, REQ_PICK);
            } catch (Throwable t) {
                CrashLog.write(this, Thread.currentThread(), t);
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null) {
            PickLog.add(this, "picker cancelled action=" + action + " code=" + resultCode, null);
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        try {
            if (ACTION_TREE.equals(action)) {
                finishTree(data.getStringExtra("picked_dir"));
            } else if (ACTION_CREATE.equals(action)) {
                finishCreate(data.getStringExtra("picked_dir"), data.getStringExtra("save_name"));
            } else {
                finishFiles(data);
            }
        } catch (Exception e) {
            PickLog.add(this, "picker FAILED", e);
            Toast.makeText(this, R.string.open_link_failed, Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private int grantFlags(boolean forceWrite) {
        int asked = getIntent() == null ? 0 : getIntent().getFlags();
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (forceWrite || (asked & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        // the system picker always makes these grants durable; apps call takePersistableUriPermission on the result
        // and treat the SecurityException as a failed save even though the file was written
        boolean durable = ACTION_TREE.equals(action) || ACTION_CREATE.equals(action) || ACTION_OPEN.equals(action);
        if (durable || (asked & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        return flags;
    }

    private void finishTree(String dirPath) {
        File dir = dirPath == null ? null : new File(dirPath);
        if (dir == null || !dir.isDirectory() || !Safe.mayExpose(this, dir)) {
            fail();
            return;
        }
        Intent r = new Intent();
        r.setData(ExternalDocs.treeUri(dir));
        r.addFlags(grantFlags(true) | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        setResult(RESULT_OK, r);
        finish();
    }

    private void finishCreate(String dirPath, String name) {
        PickLog.add(this, "picker create dir=" + dirPath + " name=" + name + " allFiles=" + Perms.hasAllFiles(this)
                + " sdk=" + Build.VERSION.SDK_INT, null);
        String n = name == null || name.trim().isEmpty() ? "file" : name.trim().replace('/', '_');
        Uri uri = null;
        boolean durable = true;
        File dir = dirPath == null ? null : new File(dirPath);
        // direct write only with full file access: without it the old code fell back to the app's private folder
        if (Perms.hasAllFiles(this) && dir != null && dir.isDirectory() && dir.canWrite() && Safe.mayExpose(this, dir)) {
            try {
                File out = ExternalDocs.uniqueIn(dir, n, false);
                if (out.createNewFile()) uri = ExternalDocs.documentUri(out);
            } catch (Exception e) {
                PickLog.add(this, "picker direct create FAILED", e);
            }
        }
        if (uri == null && Build.VERSION.SDK_INT >= 29) {
            uri = createInDownloads(n);
            durable = false;   // MediaStore rows are not document URIs: a persistable flag would make takePersistableUriPermission throw
        }
        if (uri == null) {
            fail();
            return;
        }
        Intent r = new Intent();
        r.setData(uri);
        r.setClipData(ClipData.newRawUri("", uri));
        int flags = grantFlags(true);
        if (!durable) flags &= ~Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        r.addFlags(flags);
        PickLog.add(this, "picker result " + uri + " flags=0x" + Integer.toHexString(r.getFlags()), null);
        setResult(RESULT_OK, r);
        finish();
    }

    /** A new empty file in the public Download folder; needs no storage permission (Android 10+). */
    private Uri createInDownloads(String name) {
        try {
            String ext = name.lastIndexOf('.') >= 0 ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT) : "";
            String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        } catch (Exception e) {
            PickLog.add(this, "picker mediastore create FAILED", e);
            return null;
        }
    }

    private void finishFiles(Intent data) throws Exception {
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        java.util.ArrayList<String> many = data.getStringArrayListExtra("picked_all");
        if (many != null) paths.addAll(many);
        String one = data.getStringExtra("picked");
        if (one != null) paths.add(one);
        java.util.ArrayList<File> files = new java.util.ArrayList<>();
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile() && Safe.mayExpose(this, f)) files.add(f);
        }
        if (files.isEmpty()) {
            if (!paths.isEmpty()) Toast.makeText(this, R.string.fm_private_blocked, Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        boolean documents = ACTION_OPEN.equals(action);
        String auth = getPackageName() + ".files";
        Uri first = documents ? ExternalDocs.documentUri(files.get(0)) : FileProvider.getUriForFile(this, auth, files.get(0));
        ClipData clip = ClipData.newRawUri("", first);
        for (int k = 1; k < files.size(); k++) {
            Uri u = documents ? ExternalDocs.documentUri(files.get(k)) : FileProvider.getUriForFile(this, auth, files.get(k));
            clip.addItem(new ClipData.Item(u));
        }
        Intent r = new Intent();
        r.setData(first);
        r.setClipData(clip);
        r.addFlags(grantFlags(false));
        setResult(RESULT_OK, r);
        finish();
    }

    private void fail() {
        PickLog.add(this, "picker fail action=" + action, null);
        Toast.makeText(this, R.string.fm_private_blocked, Toast.LENGTH_SHORT).show();
        setResult(RESULT_CANCELED);
        finish();
    }
}
