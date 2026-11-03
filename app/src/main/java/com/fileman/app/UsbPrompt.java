package com.fileman.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.StatFs;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Announces a newly attached SD card / USB drive with a dialog, once per attachment. */
final class UsbPrompt {
    private UsbPrompt() {
    }

    private static boolean showing;

    /** Marks every attached drive as seen and asks about the first one that was not seen before. */
    static void check(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed() || showing) return;
        List<Volumes.Vol> now = Volumes.removable(a);
        Set<String> seen = Store.usbSeen(a);
        Set<String> keep = new HashSet<>();
        Volumes.Vol fresh = null;
        for (Volumes.Vol v : now) {
            if (!v.mounted) continue;
            String k = v.root.getAbsolutePath();
            keep.add(k);
            if (!seen.contains(k) && fresh == null) fresh = v;
        }
        if (!keep.equals(seen)) Store.setUsbSeen(a, keep);
        if (fresh != null) show(a, fresh);
    }

    static void show(final Activity a, final Volumes.Vol v) {
        if (showing) return;
        showing = true;
        AlertDialog d = new Dlg(a).setTitle(R.string.usb_found_title)
                .setMessage(a.getString(R.string.usb_found_msg, v.label == null ? "" : v.label, sizeText(a, v.root)))
                .setPositiveButton(R.string.usb_browse, (x, w) -> open(a, v.root))
                .setNegativeButton(R.string.usb_exit, null)
                .create();
        d.setOnDismissListener(x -> showing = false);
        d.show();
    }

    static void open(Context c, File root) {
        c.startActivity(new Intent(c, UsbActivity.class).putExtra("path", root.getAbsolutePath()));
    }

    static String sizeText(Context c, File root) {
        try {
            StatFs st = new StatFs(root.getAbsolutePath());
            long total = st.getTotalBytes();
            long used = Math.max(0, total - st.getAvailableBytes());
            if (total <= 0) return "";
            return c.getString(R.string.home_used_of, Fmt.size(used), Fmt.size(total));
        } catch (Exception e) {
            return "";
        }
    }
}
