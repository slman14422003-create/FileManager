package com.fileman.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

/** File types, categories (the quick-access tiles on the home screen) and a bounded storage scan. */
public final class Cats {
    private Cats() {
    }

    public static final int T_DIR = 0, T_IMG = 1, T_VID = 2, T_AUD = 3, T_ARC = 4, T_APK = 5,
            T_TXT = 6, T_PDF = 7, T_OTHER = 8, T_DOC = 9, T_CONTACT = 10;

    /** Category keys used in the "cat" intent extra of FileManagerActivity. */
    public static final String IMG = "img", VID = "vid", AUD = "aud", DOC = "doc", APK = "apk",
            ARC = "arc", RECENT = "recent", LARGE = "large", FAV = "fav";

    // ------------------------------------------------------------------ names and types

    public static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean in(String ext, String... list) {
        return Arrays.asList(list).contains(ext);
    }

    public static boolean isTextExt(String x) {
        return in(x, "txt", "md", "json", "xml", "yml", "yaml", "java", "kt", "kts", "gradle",
                "properties", "js", "ts", "html", "htm", "css", "py", "sh", "c", "cpp", "h", "hpp", "cs",
                "go", "rs", "php", "rb", "sql", "csv", "log", "ini", "cfg", "conf", "toml", "gitignore",
                "pro", "bat", "svg", "tsx", "jsx", "dart", "swift", "lua", "env", "gitattributes")
                || in(x, "cmd", "ps1", "psm1", "bash", "zsh", "fish", "tf", "hcl", "scala", "groovy", "r", "m", "mm",
                "pl", "pm", "vb", "vbs", "fs", "ex", "exs", "erl", "hs", "clj", "cljs", "elm", "nim", "zig", "v", "sv",
                "vhd", "vhdl", "asm", "s", "cmake", "mk", "dockerfile", "lock", "scss", "sass", "less", "vue", "svelte",
                "astro", "graphql", "gql", "proto", "sol", "smali", "aidl", "kt", "xaml", "xsd", "xsl", "xslt", "plist",
                "strings", "json5", "jsonc", "jsonl", "ndjson", "ipynb", "tex", "bib", "rst", "adoc", "org", "nfo",
                "diff", "patch", "ics", "vcs", "eml", "srt", "vtt", "ass", "ssa", "lrc", "m3u", "m3u8", "pls", "cue",
                "gpx", "kml", "tcx", "reg", "inf", "editorconfig", "bashrc", "zshrc", "profile", "npmrc", "dockerignore",
                "prettierrc", "eslintrc", "babelrc", "csproj", "sln", "vcxproj", "pom", "manifest", "rules", "tpl",
                "twig", "jsp", "asp", "aspx", "cshtml", "erb", "ejs", "hbs", "mustache", "j2", "jinja", "wsdl");
    }

    /** Extension-less (or dotted) file names that are plain text and worth opening in the reader. */
    public static boolean isTextName(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "makefile": case "dockerfile": case "gemfile": case "rakefile": case "procfile": case "podfile":
            case "vagrantfile": case "jenkinsfile": case "brewfile": case "license": case "licence": case "readme":
            case "changelog": case "notice": case "authors": case "contributors": case "codeowners": case "copying":
            case "todo": case "gradlew": case "cmakelists": case "manifest": case "version": case "install":
                return true;
            default:
                return false;
        }
    }

    private static boolean isOfficeExt(String x) {
        return in(x, "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub",
                "pages", "numbers", "key");
    }

    /** Type of a file from its lower-case extension (directories are handled by the caller). */
    public static int typeOfExt(String x) {
        if (in(x, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "jfif", "jpe")) return T_IMG;
        if (in(x, "mp4", "mkv", "webm", "3gp", "3g2", "avi", "mov", "m4v")) return T_VID;
        if (in(x, "mp3", "wav", "ogg", "oga", "m4a", "m4b", "m4r", "aac", "flac", "opus", "amr", "mid", "midi", "mka", "weba", "3ga")) return T_AUD;
        if (in(x, "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "jar", "tbz2", "txz", "cbz", "cbr", "cb7", "war", "aar")) return T_ARC;
        if (x.equals("apk") || x.equals("apks") || x.equals("xapk") || x.equals("apkm")) return T_APK;
        if (x.equals("pdf")) return T_PDF;
        if (x.equals("vcf") || x.equals("vcard")) return T_CONTACT;
        if (isOfficeExt(x)) return T_DOC;
        if (isTextExt(x)) return T_TXT;
        return T_OTHER;
    }

    public static int iconFor(int t) {
        switch (t) {
            case T_DIR:
                return R.drawable.ic_folder;
            case T_IMG:
                return R.drawable.ic_image;
            case T_VID:
                return R.drawable.ic_video;
            case T_AUD:
                return R.drawable.ic_music;
            case T_ARC:
                return R.drawable.ic_archive;
            case T_APK:
                return R.drawable.ic_package;
            case T_TXT:
                return R.drawable.ic_code;
            case T_CONTACT:
                return R.drawable.ic_contact;
            case T_PDF:
            case T_DOC:
                return R.drawable.ic_file_text;
            default:
                return R.drawable.ic_file;
        }
    }

    public static int colorFor(int t) {
        switch (t) {
            case T_DIR:
                return R.color.accent_text;
            case T_IMG:
                return R.color.ok;
            case T_VID:
                return R.color.bad;
            case T_AUD:
                return R.color.violet;
            case T_ARC:
                return R.color.warn;
            case T_APK:
                return R.color.lime;
            case T_TXT:
                return R.color.accent_text;
            case T_PDF:
                return R.color.bad;
            case T_DOC:
                return R.color.info;
            case T_CONTACT:
                return R.color.violet;
            default:
                return R.color.text_secondary;
        }
    }

    public static String mimeOf(File f) {
        String x = extOf(f.getName());
        if (isTextExt(x) && !x.equals("svg") && !x.equals("html") && !x.equals("htm") && !x.equals("json")
                && !x.equals("xml") && !x.equals("csv")) return "text/plain";
        String m = x.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(x);
        return m == null ? "*/*" : m;
    }

    /** True when a file of this extension belongs to the category. */
    public static boolean matches(String cat, String ext) {
        int t = typeOfExt(ext);
        switch (cat) {
            case IMG:
                return t == T_IMG;
            case VID:
                return t == T_VID;
            case AUD:
                return t == T_AUD;
            case DOC:
                return t == T_PDF || t == T_DOC;
            case APK:
                return t == T_APK;
            case ARC:
                return t == T_ARC;
            default:
                return true;
        }
    }

    public static int titleOf(String cat) {
        switch (cat) {
            case IMG:
                return R.string.cat_images;
            case VID:
                return R.string.cat_videos;
            case AUD:
                return R.string.cat_audio;
            case DOC:
                return R.string.cat_docs;
            case APK:
                return R.string.cat_apps;
            case ARC:
                return R.string.cat_archives;
            case RECENT:
                return R.string.cat_recent;
            case LARGE:
                return R.string.fm_largest;
            default:
                return R.string.fm_favorites;
        }
    }


    // ------------------------------------------------------------------ which viewer opens a file

    public static final int V_NONE = 0, V_APK = 1, V_TEXT = 2, V_IMAGE = 3, V_MEDIA = 4, V_PDF = 5,
            V_DOC = 6, V_LEGACY = 7, V_CONTACT = 8;

    /** Built-in viewer for a (lower-case) extension. */
    public static int viewKind(String ext) {
        int t = typeOfExt(ext);
        if (t == T_APK) return V_APK;
        if (t == T_IMG) return V_IMAGE;
        if (t == T_VID || t == T_AUD) return V_MEDIA;
        if (t == T_PDF) return V_PDF;
        if (t == T_CONTACT) return V_CONTACT;
        if (in(ext, "docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm", "ppsx", "odt", "ods", "odp",
                "csv", "tsv", "rtf", "html", "htm", "xhtml", "svg", "doc", "dot", "dotm", "xls", "xlt", "xltm", "ppt", "pps",
                "pot", "potx", "epub", "md", "markdown", "pages", "numbers", "key")) return V_DOC;
        if (t == T_TXT) return V_TEXT;
        return V_NONE;
    }

    // ------------------------------------------------------------------ helpers

    public static boolean isLink(File f) {
        return Wipe.isLink(f);
    }

    public static String displayName(ContentResolver cr, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).getName();
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : new File(last).getName();
    }

    // ------------------------------------------------------------------ bounded scan

    /** Most recently changed files first. */
    public static final Comparator<File> NEWEST = (a, b) -> Long.compare(b.lastModified(), a.lastModified());

    /** A file with its modification time read once (comparators would otherwise hit the disk per compare). */
    public static final class Hit {
        public final File f;
        public final long mod;

        public Hit(File f) {
            this.f = f;
            this.mod = f.lastModified();
        }
    }

    public static final Comparator<Hit> HIT_OLDEST = (a, b) -> Long.compare(a.mod, b.mod);
    public static final Comparator<Hit> HIT_NEWEST = (a, b) -> Long.compare(b.mod, a.mod);

    /** Result of {@link #scan}: counts and sizes per category plus the newest files. */
    public static final class Stats {
        public final int[] count = new int[T_CONTACT + 1];   // one slot per T_* type
        public final long[] size = new long[T_CONTACT + 1];
        public final List<File> recent = new ArrayList<>();
        public boolean complete = true;
        /** Files changed during the last week ("new files" tile). */
        public int newCount = 0;
        public long newSize = 0;
        public final long newSince = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
    }

    /** Safety limit so a huge phone never makes the home screen scan forever. */
    private static final int SCAN_BUDGET = 150000;

    /**
     * Walks a volume and fills the counters shown on the home tiles. The top-level folders are walked in
     * parallel, so a big phone finishes in a fraction of the time. Call off the UI thread.
     */
    public static Stats scan(File root, boolean showHidden, int keepRecent, java.util.concurrent.atomic.AtomicBoolean stop) {
        Stats st = new Stats();
        PriorityQueue<Hit> heap = new PriorityQueue<>(keepRecent + 1, HIT_OLDEST);
        File[] top = root.listFiles();
        boolean complete = true;
        if (top != null) {
            final List<File> dirs = new ArrayList<>();
            for (File f : top) {
                String n = f.getName();
                if (!showHidden && n.startsWith(".")) continue;
                if (n.equals("Android")) continue;   // other apps' private data
                if (f.isDirectory()) {
                    if (!isLink(f)) dirs.add(f);
                } else {
                    addFile(f, n, st, heap, keepRecent);
                }
            }
            if (!dirs.isEmpty()) {
                int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
                List<java.util.concurrent.Future<Object[]>> parts = new ArrayList<>();
                final boolean hidden = showHidden;
                final int keep = keepRecent;
                for (final File d : dirs) {
                    parts.add(pool.submit(() -> {
                        Stats ps = new Stats();
                        PriorityQueue<Hit> ph = new PriorityQueue<>(keep + 1, HIT_OLDEST);
                        int[] budget = {SCAN_BUDGET / 2};
                        walk(d, hidden, ps, ph, keep, budget, stop, 1);
                        return new Object[]{ps, ph, budget[0] > 0};
                    }));
                }
                for (java.util.concurrent.Future<Object[]> fu : parts) {
                    try {
                        Object[] r = fu.get();
                        Stats ps = (Stats) r[0];
                        @SuppressWarnings("unchecked") PriorityQueue<Hit> ph = (PriorityQueue<Hit>) r[1];
                        for (int i = 0; i < st.count.length; i++) {
                            st.count[i] += ps.count[i];
                            st.size[i] += ps.size[i];
                        }
                        st.newCount += ps.newCount;
                        st.newSize += ps.newSize;
                        for (Hit h : ph) {
                            if (heap.size() < keepRecent || h.mod > heap.peek().mod) {
                                heap.add(h);
                                if (heap.size() > keepRecent) heap.poll();
                            }
                        }
                        if (!(Boolean) r[2]) complete = false;
                    } catch (Throwable e) {
                        complete = false;
                    }
                }
                pool.shutdown();
            }
        }
        st.complete = complete && !stop.get();
        List<Hit> sorted = new ArrayList<>(heap);
        Collections.sort(sorted, HIT_NEWEST);
        for (Hit h : sorted) st.recent.add(h.f);
        return st;
    }

    private static void addFile(File f, String n, Stats st, PriorityQueue<Hit> heap, int keep) {
        String ext = extOf(n);
        long len = f.length();
        int t = typeOfExt(ext);
        st.count[t]++;
        st.size[t] += len;
        if (t != T_OTHER || len > 0) {
            long mod = f.lastModified();
            if (mod >= st.newSince) {
                st.newCount++;
                st.newSize += len;
            }
            if (heap.size() < keep || mod > heap.peek().mod) {
                heap.add(new Hit(f));
                if (heap.size() > keep) heap.poll();
            }
        }
    }

    private static void walk(File dir, boolean hidden, Stats st, PriorityQueue<Hit> heap, int keep,
                             int[] budget, java.util.concurrent.atomic.AtomicBoolean stop, int depth) {
        if (stop.get() || budget[0] <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (stop.get() || budget[0] <= 0) return;
            String n = f.getName();
            if (!hidden && n.startsWith(".")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!isLink(f)) walk(f, hidden, st, heap, keep, budget, stop, depth + 1);
                continue;
            }
            addFile(f, n, st, heap, keep);
        }
    }

    /** Number of files in a category according to the scan counters. */
    public static int countOf(Stats s, String cat) {
        int n = 0;
        for (int t = 0; t < s.count.length; t++) {
            if (t == T_DIR) continue;
            if (matchesType(cat, t)) n += s.count[t];
        }
        return n;
    }

    private static boolean matchesType(String cat, int t) {
        switch (cat) {
            case IMG:
                return t == T_IMG;
            case VID:
                return t == T_VID;
            case AUD:
                return t == T_AUD;
            case DOC:
                return t == T_PDF || t == T_DOC;
            case APK:
                return t == T_APK;
            case ARC:
                return t == T_ARC;
            default:
                return false;
        }
    }

    public static long sizeOf(Stats s, String cat) {
        long n = 0;
        for (int t = 0; t < s.size.length; t++) {
            if (t == T_DIR) continue;
            if (matchesType(cat, t)) n += s.size[t];
        }
        return n;
    }
}
