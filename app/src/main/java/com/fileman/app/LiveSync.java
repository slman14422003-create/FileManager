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

    private final Runnable fire = new Runnable() {
        @Override
        public void run() {
            pending = false;
            if (!active) return;
            int total = 0;
            for (Set<String> v : changed.values()) total += v.size();
            boolean all = full || delta == null || changed.isEmpty() || total > MAX_DELTA;
            Map<String, Set<String>> copy = new HashMap<>(changed);
            changed.clear();
            full = false;
            if (all) {
                onChange.run();
                return;
            }
            for (Map.Entry<String, Set<String>> e : copy.entrySet()) delta.onDelta(e.getKey(), e.getValue());
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
                        schedule();
                    }

                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        full = true;
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
        pending = false;
        changed.clear();
        full = false;
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
        h.postDelayed(fire, Math.min(QUIET_MS, Math.max(0, firstAt + MAX_WAIT_MS - now)));
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
                boolean self = name == null || (event & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0
                        || (event & 0x4000) != 0;   // Q_OVERFLOW
                if (self) {
                    full = true;
                } else {
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
