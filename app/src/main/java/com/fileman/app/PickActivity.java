package com.fileman.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Replaces the system file picker (GET_CONTENT / OPEN_DOCUMENT / PICK) for apps that ask the user for a
 * file: shows the file manager in "pick" mode and hands the chosen file back through the app's
 * FileProvider with a read grant (write / persistable only when the other app asked for them).
 */
public class PickActivity extends Activity {
    private static final int REQ_PICK = 71;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            Intent i = new Intent(this, FileManagerActivity.class);
            i.putExtra("pick", true);
            i.putExtra("pick_multi", getIntent().getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
            startActivityForResult(i, REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        if (resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> many = data.getStringArrayListExtra("picked_all");
            if (many != null) paths.addAll(many);
            String one = data.getStringExtra("picked");
            if (one != null) paths.add(one);
        }
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
        try {
            String auth = getPackageName() + ".files";
            Uri first = FileProvider.getUriForFile(this, auth, files.get(0));
            ClipData clip = ClipData.newRawUri("", first);
            for (int k = 1; k < files.size(); k++) {
                clip.addItem(new ClipData.Item(FileProvider.getUriForFile(this, auth, files.get(k))));
            }
            int asked = getIntent() == null ? 0 : getIntent().getFlags();
            Intent r = new Intent();
            r.setData(first);
            r.setClipData(clip);
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if ((asked & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if ((asked & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            r.addFlags(flags);
            setResult(RESULT_OK, r);
        } catch (Exception e) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }
}
