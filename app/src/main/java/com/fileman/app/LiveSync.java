package com.fileman.app;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

final class LiveSync {
    private static final long QUIET_MS = 350;
    private static final long MAX_WAIT_MS = 1600;
    /** Media database changes arrive in long bursts: wait for them to settle, and never rescan more often than this. */
    private static final long MEDIA_QUIET_MS = 1200;
    private static final long MEDIA_MAX_WAIT_MS = 6000;
    private static final long MIN_FULL_GAP_MS = 4000;
    /** A file that was created but not yet closed is still being written: hold it back this long at most. */
    private static final long OPEN_HOLD_MS = 45000;
    private static final long SWEEP_MS = 5000;
    private static final int IN_ISDIR = 0x40000000;
    private static final String[] PARTIAL = {".crdownload", ".part", ".partial", ".download", ".downloading",
            ".opdownload", ".tmp", ".temp", ".cache"};
    private static final int MASK = FileObserver.CREATE | FileObserver.DELETE | FileObserver.DELETE_SELF
            | FileObserver.MOVED_FROM | FileObserver.MOVED_TO | FileObserver.MOVE_SELF
            | FileObserver.CLOSE_WRITE | FileObserver.ATTRIB;

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** Receives only the names that changed in one watched folder, so a screen can patch its list in place. */
    interface Delta {
        void onDelta(String dir, Set<String> names);
    }

    private static final int MAX_DELTA = 40;
    private final Runnable onChange;
    private Delta delta;
    private final Map<String, Set<String>> changed = new HashMap<>();
    private boolean full;
    private final List<FileObserver> observers = new ArrayList<>();
    private ContentObserver media;
    private boolean pending;
    private boolean active;
    private long firstAt;
    private long lastFullAt;
    private boolean mediaEvent;
    private final Map<String, Long> opened = new HashMap<>();
    private final Map<String, String[]> held = new HashMap<>();

    private final Runnable fire = new Runnable() {
        @Override
        public void run() {
            pending = false;
            if (!active) return;
            if (!full) holdUnfinished();
            int total = 0;
            for (Set<String> v : changed.values()) total += v.size();
            boolean all = full || delta == null || changed.isEmpty() || total > MAX_DELTA;
            if (changed.isEmpty() && !full) return;
            Map<String, Set<String>> copy = new HashMap<>(changed);
            changed.clear();
            full = false;
            if (all) {
                lastFullAt = SystemClock.uptimeMillis();
                mediaEvent = false;
                onChange.run();
                return;
            }
            for (Map.Entry<String, Set<String>> e : copy.entrySet()) delta.onDelta(e.getKey(), e.getValue());
        }
    };

    private static boolean partialName(String n) {
        String l = n.toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith(".com.google.chrome.") || l.startsWith(".com.android.chrome.")) return true;
        for (String x : PARTIAL) if (l.endsWith(x)) return true;
        return false;
    }

    /** Takes out of the pending batch every file that is still being written, so it is never read half done. */
    private void holdUnfinished() {
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (Map.Entry<String, Set<String>> e : changed.entrySet()) {
            java.util.Iterator<String> it = e.getValue().iterator();
            while (it.hasNext()) {
                String n = it.next();
                String key = e.getKey() + "/" + n;
                Long t = opened.get(key);
                boolean writing = t != null && now - t < OPEN_HOLD_MS;
                if (writing || partialName(n)) {
                    it.remove();
                    if (writing) {
                        held.put(key, new String[]{e.getKey(), n});
                        any = true;
                    }
                }
            }
        }
        java.util.Iterator<Map.Entry<String, Set<String>>> di = changed.entrySet().iterator();
        while (di.hasNext()) if (di.next().getValue().isEmpty()) di.remove();
        if (any) h.postDelayed(sweep, SWEEP_MS);
    }

    private final Runnable sweep = new Runnable() {
        @Override
        public void run() {
            if (!active || held.isEmpty()) return;
            long now = SystemClock.uptimeMillis();
            boolean release = false;
            java.util.Iterator<Map.Entry<String, String[]>> it = held.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String[]> e = it.next();
                Long t = opened.get(e.getKey());
                if (t == null || now - t >= OPEN_HOLD_MS) {
                    opened.remove(e.getKey());
                    String[] dn = e.getValue();
                    Set<String> set = changed.get(dn[0]);
                    if (set == null) {
                        set = new LinkedHashSet<>();
                        changed.put(dn[0], set);
                    }
                    set.add(dn[1]);
                    it.remove();
                    release = true;
                }
            }
            if (release) schedule();
            if (!held.isEmpty()) h.postDelayed(this, SWEEP_MS);
        }
    };

    LiveSync(Context c, Runnable onChange) {
        this.app = c.getApplicationContext();
        this.onChange = onChange;
    }

    void setDelta(Delta d) {
        delta = d;
    }

    void watch(List<File> dirs, boolean withMedia) {
        stop();
        active = true;
        Set<String> seen = new HashSet<>();
        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            String p = d.getAbsolutePath();
            if (!seen.add(p)) continue;
            try {
                FileObserver o = new Obs(p);
                o.startWatching();
                observers.add(o);
            } catch (Throwable ignored) {
            }
        }
        if (withMedia) {
            try {
                media = new ContentObserver(h) {
                    @Override
                    public void onChange(boolean selfChange) {
                        full = true;
                        mediaEvent = true;
                        schedule();
                    }

                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        full = true;
                        mediaEvent = true;
                        schedule();
                    }
                };
                Uri[] uris = {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Files.getContentUri("external")
                };
                for (Uri u : uris) app.getContentResolver().registerContentObserver(u, true, media);
            } catch (Throwable ignored) {
            }
        }
    }

    void stop() {
        active = false;
        h.removeCallbacks(fire);
        h.removeCallbacks(sweep);
        pending = false;
        opened.clear();
        held.clear();
        changed.clear();
        full = false;
        mediaEvent = false;
        for (FileObserver o : observers) {
            try {
                o.stopWatching();
            } catch (Throwable ignored) {
            }
        }
        observers.clear();
        if (media != null) {
            try {
                app.getContentResolver().unregisterContentObserver(media);
            } catch (Throwable ignored) {
            }
            media = null;
        }
    }

    private void schedule() {
        if (!active) return;
        long now = SystemClock.uptimeMillis();
        if (!pending) {
            pending = true;
            firstAt = now;
        }
        h.removeCallbacks(fire);
        long quiet = mediaEvent ? MEDIA_QUIET_MS : QUIET_MS;
        long maxWait = mediaEvent ? MEDIA_MAX_WAIT_MS : MAX_WAIT_MS;
        long delay = Math.min(quiet, Math.max(0, firstAt + maxWait - now));
        if (full || mediaEvent) delay = Math.max(delay, lastFullAt + MIN_FULL_GAP_MS - now);
        h.postDelayed(fire, delay);
    }

    @SuppressWarnings("deprecation")
    private final class Obs extends FileObserver {
        private final String dir;

        Obs(String path) {
            super(path, MASK);
            dir = path;
        }

        @Override
        public void onEvent(final int event, final String name) {
            h.post(() -> {
                if (!active) return;
                if (name != null && (name.startsWith(".pending-") || name.startsWith(".trashed-"))) return;   // system temp names
                boolean self = name == null || (event & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0
                        || (event & 0x4000) != 0;   // Q_OVERFLOW
                if (self) {
                    full = true;
                } else {
                    String key = dir + "/" + name;
                    if ((event & FileObserver.CREATE) != 0 && (event & IN_ISDIR) == 0) {
                        opened.put(key, SystemClock.uptimeMillis());
                    } else if ((event & (FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.DELETE
                            | FileObserver.MOVED_FROM)) != 0) {
                        opened.remove(key);
                        if (held.remove(key) != null) h.removeCallbacks(sweep);
                        if (!held.isEmpty()) h.postDelayed(sweep, SWEEP_MS);
                    }
                    Set<String> set = changed.get(dir);
                    if (set == null) {
                        set = new LinkedHashSet<>();
                        changed.put(dir, set);
                    }
                    set.add(name);
                }
                schedule();
            });
        }
    }
}
