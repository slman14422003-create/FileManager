package com.fileman.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uploads the folders the person picked into "FileManager Backups" on Drive, keeping the folder structure.
 * Only new or changed files are sent (a local index remembers size, modified time and Drive id).
 */
final class DriveBackup {
    private DriveBackup() {
    }

    static final String ROOT_NAME = "FileManager Backups";
    private static final int MAX_FILES = 50000;

    interface Listener {
        void progress(String name, int fileNo, int fileCount, long bytesDone, long bytesTotal);
    }

    static final class Result {
        int uploaded;
        int skipped;
        int failed;
    }

    private static String sig(File f) {
        return f.length() + ":" + f.lastModified();
    }

    private static void walk(Context c, File dir, List<File> out) {
        ArrayDeque<File> todo = new ArrayDeque<>();
        todo.push(dir);
        while (!todo.isEmpty() && out.size() < MAX_FILES) {
            File d = todo.pop();
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (out.size() >= MAX_FILES) break;
                if (Cats.isLink(k) || !Safe.mayExpose(c, k)) continue;
                if (k.isDirectory()) todo.push(k);
                else if (k.isFile() && k.canRead()) out.add(k);
            }
        }
    }

    static Result run(Context c, List<String> folders, Listener l, AtomicBoolean cancel) throws Exception {
        Result res = new Result();
        Map<String, String> idx = DriveStore.index(c);
        String root = DriveApi.ensureFolder(c, "root", ROOT_NAME);
        Map<String, String> dirIds = new HashMap<>();

        // what has to be sent
        List<File> bases = new ArrayList<>();
        List<File> todo = new ArrayList<>();
        List<File> baseOf = new ArrayList<>();
        long totalBytes = 0;
        for (String path : folders) {
            File base = new File(path);
            if (!base.isDirectory() || !Safe.mayExpose(c, base)) continue;
            List<File> files = new ArrayList<>();
            walk(c, base, files);
            for (File f : files) {
                String old = idx.get(f.getAbsolutePath());
                if (old != null && old.startsWith(sig(f) + "|")) {
                    res.skipped++;
                } else {
                    todo.add(f);
                    baseOf.add(base);
                    totalBytes += f.length();
                }
            }
            bases.add(base);
        }

        long doneBytes = 0;
        int sinceSave = 0;
        for (int i = 0; i < todo.size(); i++) {
            if (cancel.get()) break;
            final File f = todo.get(i);
            File base = baseOf.get(i);
            final int no = i + 1;
            final int count = todo.size();
            final long before = doneBytes;
            final long all = totalBytes;
            try {
                String parent = dirFor(c, root, base, f.getParentFile(), dirIds);
                String key = f.getAbsolutePath();
                String old = idx.get(key);
                String existing = old != null && old.contains("|") ? old.substring(old.indexOf('|') + 1) : null;
                String id;
                String signature = sig(f);
                try {
                    id = DriveApi.upload(c, f, parent, existing, (d, t) -> {
                        if (l != null) l.progress(f.getName(), no, count, before + d, all);
                    }, cancel);
                } catch (DriveApi.DriveError e) {
                    if (existing == null || e.code != 404) throw e;
                    id = DriveApi.upload(c, f, parent, null, null, cancel);   // it was deleted on Drive: send it again
                }
                idx.put(key, signature + "|" + id);
                res.uploaded++;
                if (++sinceSave >= 25) {
                    DriveStore.saveIndex(c, idx);
                    sinceSave = 0;
                }
            } catch (Exception e) {
                if (cancel.get() || (e.getMessage() != null && e.getMessage().contains("cancelled"))) break;
                if (e instanceof DriveApi.DriveError && ((DriveApi.DriveError) e).code == 403) {
                    DriveStore.saveIndex(c, idx);
                    throw e;   // permission or quota problem: stop instead of failing every file
                }
                res.failed++;
            }
            doneBytes += f.length();
        }
        DriveStore.saveIndex(c, idx);
        return res;
    }

    /** Drive folder for a local folder inside a backed-up base, created level by level and remembered. */
    private static String dirFor(Context c, String root, File base, File dir, Map<String, String> cache) throws Exception {
        String top = DriveApi.ensureFolderCached(c, root, base.getName(), cache, "/" + base.getAbsolutePath());
        String rel = dir.getAbsolutePath().substring(base.getAbsolutePath().length());
        String parent = top;
        StringBuilder key = new StringBuilder("/" + base.getAbsolutePath());
        for (String part : rel.split("/")) {
            if (part.isEmpty()) continue;
            key.append('/').append(part);
            parent = DriveApi.ensureFolderCached(c, parent, part, cache, key.toString());
        }
        return parent;
    }
}
