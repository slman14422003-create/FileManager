package com.fileman.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-app temporary trash. Deleted items are renamed out of sight (instant, whatever the size) and kept until the
 * undo window closes; then they are purged on a background thread that outlives the screen.
 */
final class TrashBin {
    private TrashBin() {
    }

    private static final ExecutorService PURGE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trash-purge");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Puts the hidden items back. Returns the restored (original) files. */
    static List<File> restore(Context c, Wipe.Stash st) {
        List<File> back = new ArrayList<>();
        for (int i = 0; i < st.hidden.size(); i++) {
            File hid = st.hidden.get(i);
            File orig = st.origs.get(i);
            if (!Wipe.exists(hid)) continue;
            File target = orig;
            if (Wipe.exists(target)) target = free(orig);
            boolean ok;
            try {
                ok = hid.renameTo(target);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) back.add(target);
        }
        Wipe.settled(c, st.hidden);
        return back;
    }

    private static File free(File f) {
        File dir = f.getParentFile();
        String n = f.getName();
        String base = n;
        String ext = "";
        int i = n.lastIndexOf('.');
        if (i > 0) {
            base = n.substring(0, i);
            ext = n.substring(i);
        }
        for (int k = 1; ; k++) {
            File c = new File(dir, base + " (" + k + ")" + ext);
            if (!Wipe.exists(c)) return c;
        }
    }

    /** Frees the data of the hidden items in the background; {@code done} runs on that thread when finished. */
    static void purge(final Context c, final Wipe.Stash st, final Runnable done) {
        if (st.hidden.isEmpty()) {
            if (done != null) done.run();
            return;
        }
        final Context app = c.getApplicationContext();
        final List<File> list = new ArrayList<>(st.hidden);
        PURGE.execute(() -> {
            OpService.begin(app, app.getString(R.string.fm_deleting), null);
            try {
                Wipe.delete(list, new AtomicBoolean(), (n, rd, total, name) ->
                        OpService.update(app.getString(R.string.fm_deleting_n, n), total > 0 ? rd * 100 / total : -1));
            } finally {
                Wipe.settled(app, list);
                OpService.end(null);
            }
            if (done != null) done.run();
        });
    }
}
