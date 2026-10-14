package com.fileman.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Google Drive REST calls (v3). All of them block: call from a worker thread only. */
final class DriveApi {
    private DriveApi() {
    }

    static final String API = "https://www.googleapis.com/drive/v3/";
    static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/";
    static final String FOLDER = "application/vnd.google-apps.folder";
    private static final String FIELDS = "id,name,mimeType,size,modifiedTime,thumbnailLink,trashed";
    private static final int CHUNK = 5 * 256 * 1024;

    static final class DriveError extends IOException {
        final int code;

        DriveError(int code, String body) {
            super(message(body, code));
            this.code = code;
        }
    }

    static final class DFile {
        String id = "";
        String name = "";
        String mime = "";
        String thumb;
        long size;
        long modified;
        boolean trashed;

        boolean isFolder() {
            return FOLDER.equals(mime);
        }

        boolean isGoogleDoc() {
            return mime.startsWith("application/vnd.google-apps.") && !isFolder();
        }
    }

    static final class Page {
        final List<DFile> files = new ArrayList<>();
        String next;
    }

    static final class About {
        String name = "";
        String email = "";
        long limit;     // 0 = unlimited
        long usage;
        long inDrive;
        long inTrash;
    }

    private static String message(String body, int code) {
        try {
            String m = new JSONObject(body).getJSONObject("error").optString("message", "");
            if (!m.isEmpty()) return m.length() > 160 ? m.substring(0, 160) : m;
        } catch (Exception ignored) {
        }
        return "http " + code;
    }

    private static long lng(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long time(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(s.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Escapes a value placed inside single quotes in a Drive search query. */
    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static DriveHttp.Resp call(Context c, String method, String url, String body) throws Exception {
        for (int attempt = 0; ; attempt++) {
            DriveHttp.Resp r = DriveHttp.json(method, url, DriveAuth.token(c), body);
            if (r.code == 401 && attempt == 0) {
                DriveAuth.invalidate();
                continue;
            }
            if ((r.code == 429 || r.code >= 500) && attempt < 3) {
                Thread.sleep(1000L << attempt);
                continue;
            }
            if (r.code >= 400) throw new DriveError(r.code, r.body);
            return r;
        }
    }

    private static DFile parse(JSONObject o) {
        DFile f = new DFile();
        f.id = o.optString("id");
        f.name = o.optString("name");
        f.mime = o.optString("mimeType");
        f.size = lng(o.optString("size", "0"));
        f.modified = time(o.optString("modifiedTime", ""));
        f.thumb = o.has("thumbnailLink") ? o.optString("thumbnailLink") : null;
        f.trashed = o.optBoolean("trashed", false);
        return f;
    }

    static About about(Context c) throws Exception {
        DriveHttp.Resp r = call(c, "GET", API + "about?fields=" + DriveHttp.enc("user(displayName,emailAddress),storageQuota"), null);
        JSONObject o = new JSONObject(r.body);
        About a = new About();
        JSONObject u = o.optJSONObject("user");
        if (u != null) {
            a.name = u.optString("displayName");
            a.email = u.optString("emailAddress");
        }
        JSONObject q = o.optJSONObject("storageQuota");
        if (q != null) {
            a.limit = lng(q.optString("limit", "0"));
            a.usage = lng(q.optString("usage", "0"));
            a.inDrive = lng(q.optString("usageInDrive", "0"));
            a.inTrash = lng(q.optString("usageInDriveTrash", "0"));
        }
        return a;
    }

    static Page list(Context c, String q, String orderBy, String pageToken) throws Exception {
        String url = API + "files?pageSize=100&spaces=drive&q=" + DriveHttp.enc(q)
                + "&orderBy=" + DriveHttp.enc(orderBy)
                + "&fields=" + DriveHttp.enc("nextPageToken,files(" + FIELDS + ")")
                + (pageToken == null ? "" : "&pageToken=" + DriveHttp.enc(pageToken));
        JSONObject o = new JSONObject(call(c, "GET", url, null).body);
        Page p = new Page();
        JSONArray a = o.optJSONArray("files");
        if (a != null) for (int i = 0; i < a.length(); i++) p.files.add(parse(a.getJSONObject(i)));
        p.next = o.has("nextPageToken") ? o.optString("nextPageToken") : null;
        return p;
    }

    private static String fileUrl(String id) {
        return API + "files/" + DriveHttp.enc(id);
    }

    static void setTrashed(Context c, String id, boolean trashed) throws Exception {
        call(c, "PATCH", fileUrl(id), new JSONObject().put("trashed", trashed).toString());
    }

    static void rename(Context c, String id, String name) throws Exception {
        call(c, "PATCH", fileUrl(id), new JSONObject().put("name", name).toString());
    }

    static void deleteForever(Context c, String id) throws Exception {
        call(c, "DELETE", fileUrl(id), null);
    }

    static void emptyTrash(Context c) throws Exception {
        call(c, "DELETE", API + "files/trash", null);
    }

    static String createFolder(Context c, String parent, String name) throws Exception {
        JSONObject m = new JSONObject().put("name", name).put("mimeType", FOLDER)
                .put("parents", new JSONArray().put(parent));
        return new JSONObject(call(c, "POST", API + "files?fields=id", m.toString()).body).getString("id");
    }

    /** The folder called {@code name} inside {@code parent}, created when it does not exist. */
    static String ensureFolder(Context c, String parent, String name) throws Exception {
        String q = "name='" + esc(name) + "' and mimeType='" + FOLDER + "' and '" + esc(parent)
                + "' in parents and trashed=false";
        Page p = list(c, q, "createdTime", null);
        if (!p.files.isEmpty()) return p.files.get(0).id;
        return createFolder(c, parent, name);
    }

    /** Same as {@link #ensureFolder} but remembers the answer for the duration of one backup run. */
    static String ensureFolderCached(Context c, String parent, String name, java.util.Map<String, String> cache,
                                     String key) throws Exception {
        String id = cache.get(key);
        if (id == null) {
            id = ensureFolder(c, parent, name);
            cache.put(key, id);
        }
        return id;
    }

    /** Downloads a file (Google documents are exported as PDF) into {@code out}. */
    static void download(Context c, DFile f, File out, DriveHttp.Progress p, AtomicBoolean cancel) throws Exception {
        String url = f.isGoogleDoc()
                ? fileUrl(f.id) + "/export?mimeType=" + DriveHttp.enc("application/pdf")
                : fileUrl(f.id) + "?alt=media";
        File part = new File(out.getParentFile(), out.getName() + ".part");
        try {
            try {
                DriveHttp.download(url, DriveAuth.token(c), part, p, cancel);
            } catch (IOException e) {
                if (e.getMessage() == null || !e.getMessage().contains("401")) throw e;
                DriveAuth.invalidate();
                DriveHttp.download(url, DriveAuth.token(c), part, p, cancel);
            }
            if (!part.renameTo(out)) throw new IOException("rename failed");
        } finally {
            if (part.exists()) part.delete();
        }
    }

    /**
     * Uploads a local file in pieces (resumable: a dropped connection continues where it stopped).
     * With {@code existingId} the content of that Drive file is replaced, otherwise a new file is created in
     * {@code parent}. Returns the Drive id.
     */
    static String upload(Context c, File local, String parent, String existingId, DriveHttp.Progress p,
                         AtomicBoolean cancel) throws Exception {
        final long size = local.length();
        String mime = Cats.mimeOf(local);
        if (mime == null || mime.equals("*/*")) mime = "application/octet-stream";
        String meta = existingId == null
                ? new JSONObject().put("name", local.getName()).put("parents", new JSONArray().put(parent)).toString()
                : "{}";
        String url = existingId == null
                ? UPLOAD + "files?uploadType=resumable&fields=id"
                : UPLOAD + "files/" + DriveHttp.enc(existingId) + "?uploadType=resumable&fields=id";
        String session;
        try {
            session = DriveHttp.startResumable(existingId == null ? "POST" : "PATCH", url, DriveAuth.token(c), meta, mime, size);
        } catch (DriveError e) {
            if (e.code != 401) throw e;
            DriveAuth.invalidate();
            session = DriveHttp.startResumable(existingId == null ? "POST" : "PATCH", url, DriveAuth.token(c), meta, mime, size);
        }
        byte[] buf = new byte[(int) Math.min(CHUNK, Math.max(1, size))];
        try (RandomAccessFile raf = new RandomAccessFile(local, "r")) {
            long off = 0;
            int fails = 0;
            while (true) {
                if (cancel != null && cancel.get()) throw new IOException("cancelled");
                int want = (int) Math.min(buf.length, size - off);
                raf.seek(off);
                raf.readFully(buf, 0, want);
                DriveHttp.Resp r;
                try {
                    r = DriveHttp.putChunk(session, buf, want, off, size);
                } catch (IOException e) {
                    if (++fails > 4) throw e;
                    off = DriveHttp.queryOffset(session, size);
                    continue;
                }
                if (r.code == 200 || r.code == 201) {
                    if (p != null) p.on(size, size);
                    return new JSONObject(r.body).getString("id");
                }
                if (r.code == 308) {
                    off += want;
                    fails = 0;
                    if (p != null) p.on(off, size);
                    continue;
                }
                if (r.code >= 500 && ++fails <= 4) {
                    off = DriveHttp.queryOffset(session, size);
                    continue;
                }
                throw new DriveError(r.code, r.body);
            }
        }
    }
}
