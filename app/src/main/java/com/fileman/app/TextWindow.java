package com.fileman.app;

import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windowed text model: a sparse row index (one checkpoint per 64 rows) over the file on disk and a small page cache
 * (memory capped), so a file of any size can be shown and searched without ever holding it as one String. A row is a
 * line, or a slice of a very long line.
 */
final class TextWindow {
    static final int ROW_BYTES = 1500;
    static final int CHECK = 64;
    static final int PAGE = 128;   // multiple of CHECK
    private static final int MAX_CACHE_CHARS = 1_500_000;
    private static final long MEM_FILE_MAX = 8L * 1024 * 1024;

    static final class NotText extends IOException {
        NotText() {
            super("not text");
        }
    }

    static final class TooLarge extends IOException {
        TooLarge() {
            super("too large");
        }
    }

    private static final class Page {
        final String[] t;
        final int[] no;

        Page(int n) {
            t = new String[n];
            no = new int[n];
        }
    }

    interface Visitor {
        /** Return false to stop. {@code no} is the line number (the same for the slices of one long line). */
        boolean row(int index, int no, boolean cont, long start, String text);
    }

    private final File file;
    private final long length;
    private final long dataStart;
    private final String[] memText;
    private final int[] memNo;

    private final Object cpLock = new Object();
    private long[] cpOff = new long[512];
    private int[] cpInfo = new int[512];
    private int cps;
    private volatile int rows;
    private volatile int lines;
    private volatile boolean done;
    private volatile Runnable listener;
    private final Set<Integer> loading = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "textwin");
        t.setDaemon(true);
        return t;
    });

    private final LruCache<Integer, Page> pages = new LruCache<Integer, Page>(MAX_CACHE_CHARS) {
        @Override
        protected int sizeOf(Integer key, Page p) {
            int n = 256;
            for (String s : p.t) if (s != null) n += s.length() + 4;
            return n;
        }
    };

    private TextWindow(File f, long length, long dataStart, String[] memText, int[] memNo) {
        this.file = f;
        this.length = length;
        this.dataStart = dataStart;
        this.memText = memText;
        this.memNo = memNo;
    }

    // ------------------------------------------------------------------ open

    /** Opens a file for windowed reading. UTF-16 files (small ones) are decoded into memory and are read-only. */
    static TextWindow open(File f) throws IOException {
        long len = f.length();
        byte[] head = new byte[(int) Math.min(8192, len)];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(head);
        }
        boolean bomLe = head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE;
        boolean bomBe = head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF;
        if (bomLe || bomBe) {
            if (len > MEM_FILE_MAX) throw new TooLarge();
            byte[] all = new byte[(int) len];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.readFully(all);
            }
            String text = new String(all, 2, all.length - 2, bomLe ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
            return memory(f, text);
        }
        for (byte b : head) if (b == 0) throw new NotText();
        boolean bom8 = head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF;
        return new TextWindow(f, len, bom8 ? 3 : 0, null, null);
    }

    private static TextWindow memory(File f, String text) {
        java.util.ArrayList<String> t = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> n = new java.util.ArrayList<>();
        int len = text.length();
        int start = 0;
        int no = 0;
        while (start < len) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? len : nl;
            int e2 = end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
            no++;
            for (int p = start; p < e2 || p == start; p += ROW_BYTES) {
                t.add(clean(text.substring(p, Math.min(e2, p + ROW_BYTES))));
                n.add(p == start ? no : 0);
                if (e2 - p <= ROW_BYTES) break;
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        int[] nos = new int[n.size()];
        for (int i = 0; i < nos.length; i++) nos[i] = n.get(i);
        TextWindow w = new TextWindow(f, f.length(), 0, t.toArray(new String[0]), nos);
        w.rows = nos.length;
        w.lines = no;
        w.done = true;
        return w;
    }

    boolean inMemory() {
        return memText != null;
    }

    File file() {
        return file;
    }

    long length() {
        return length;
    }

    long dataStart() {
        return dataStart;
    }

    int rows() {
        return rows;
    }

    int lines() {
        return lines;
    }

    boolean indexed() {
        return done;
    }

    void setListener(Runnable r) {
        listener = r;
    }

    void close() {
        listener = null;
        io.shutdownNow();
        pages.evictAll();
    }

    static String clean(String s) {
        return s.indexOf('\t') >= 0 ? s.replace("\t", "    ") : s;
    }

    // ------------------------------------------------------------------ the scanner

    private static String decode(byte[] b, int len, boolean trimCr) {
        int n = len;
        if (trimCr && n > 0 && b[n - 1] == '\r') n--;
        return clean(new String(b, 0, n, StandardCharsets.UTF_8));
    }

    /** Walks rows from a known row start. One state machine serves the index, the pages, search and windows. */
    private void scan(long startOff, int firstIndex, int startNo, boolean startCont, boolean wantText,
                      AtomicBoolean cancel, Visitor v) throws IOException {
        byte[] buf = new byte[64 * 1024];
        byte[] row = new byte[ROW_BYTES + 8];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(startOff);
            long p = startOff;
            long rowStart = startOff;
            long lastLead = startOff;
            int no = startNo;
            boolean cont = startCont;
            int rl = 0;
            int idx = firstIndex;
            int n;
            while ((n = raf.read(buf)) > 0) {
                if (cancel != null && cancel.get()) return;
                for (int i = 0; i < n; i++, p++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        if (!v.row(idx, no, cont, rowStart, wantText ? decode(row, rl, true) : null)) return;
                        idx++;
                        no++;
                        cont = false;
                        rowStart = p + 1;
                        lastLead = p + 1;
                        rl = 0;
                        continue;
                    }
                    if ((b & 0xC0) != 0x80) lastLead = p;
                    if (rl >= ROW_BYTES) {
                        long boundary = lastLead > rowStart ? lastLead : p;
                        int keep = (int) (p - boundary);
                        if (!v.row(idx, no, cont, rowStart, wantText ? decode(row, rl - keep, false) : null)) return;
                        idx++;
                        cont = true;
                        rowStart = boundary;
                        if (keep > 0) System.arraycopy(row, rl - keep, row, 0, keep);
                        rl = keep;
                    }
                    row[rl++] = b;
                }
            }
            if (rl > 0) v.row(idx, no, cont, rowStart, wantText ? decode(row, rl, true) : null);
        }
    }

    private void addCp(long off, int no, boolean cont) {
        synchronized (cpLock) {
            if (cps == cpOff.length) {
                cpOff = java.util.Arrays.copyOf(cpOff, cps * 2);
                cpInfo = java.util.Arrays.copyOf(cpInfo, cps * 2);
            }
            cpOff[cps] = off;
            cpInfo[cps] = (no << 1) | (cont ? 1 : 0);
            cps++;
        }
    }

    /** Builds the row index in the calling (background) thread; {@code progress} fires every ~1000 rows. */
    void buildIndex(AtomicBoolean cancel, final Runnable progress) throws IOException {
        if (memText != null) return;
        scan(dataStart, 0, 1, false, false, cancel, (idx, no, cont, start, text) -> {
            if (idx % CHECK == 0) addCp(start, no, cont);
            rows = idx + 1;
            lines = no;
            if (progress != null && (idx & 1023) == 0) progress.run();
            return true;
        });
        if (cancel == null || !cancel.get()) done = true;
        if (progress != null) progress.run();
    }

    // ------------------------------------------------------------------ rows and pages

    /** Text of a row if its page is in memory; otherwise null and the page loads in the background. */
    String text(int row) {
        if (memText != null) return row >= 0 && row < memText.length ? memText[row] : null;
        Page pg = pages.get(row / PAGE);
        if (pg == null) {
            want(row / PAGE);
            return null;
        }
        int k = row % PAGE;
        return k < pg.t.length ? pg.t[k] : null;
    }

    /** Line number of a row (0 for the continuation slice of a long line); 0 while its page is not loaded. */
    int no(int row) {
        if (memNo != null) return row >= 0 && row < memNo.length ? memNo[row] : 0;
        Page pg = pages.get(row / PAGE);
        if (pg == null) return 0;
        int k = row % PAGE;
        return k < pg.no.length ? pg.no[k] : 0;
    }

    /** Queues the pages around a row (the one itself first). */
    void want(int page) {
        if (memText != null || io.isShutdown()) return;
        queue(page);
        queue(page + 1);
        queue(page - 1);
    }

    private void queue(final int page) {
        if (page < 0 || page * PAGE >= rows) return;
        if (pages.get(page) != null) return;
        synchronized (loading) {
            if (!loading.add(page)) return;
        }
        try {
            io.execute(() -> {
                try {
                    Page pg = load(page);
                    if (pg != null) pages.put(page, pg);
                } catch (Throwable ignored) {
                } finally {
                    synchronized (loading) {
                        loading.remove(page);
                    }
                }
                Runnable l = listener;
                if (l != null) l.run();
            });
        } catch (Throwable t) {
            synchronized (loading) {
                loading.remove(page);
            }
        }
    }

    private Page load(final int page) throws IOException {
        final int first = page * PAGE;
        final int total = rows;
        if (first >= total) return null;
        final Page pg = new Page(Math.min(PAGE, total - first));
        final int[] got = {0};
        long off;
        int info;
        synchronized (cpLock) {
            int cp = first / CHECK;
            if (cp >= cps) return null;
            off = cpOff[cp];
            info = cpInfo[cp];
        }
        scan(off, first, info >> 1, (info & 1) != 0, true, null, (idx, no, cont, start, text) -> {
            int k = idx - first;
            if (k < 0) return true;
            if (k >= pg.t.length) return false;
            pg.t[k] = text;
            pg.no[k] = cont ? 0 : no;
            got[0]++;
            return k + 1 < pg.t.length;
        });
        return got[0] == pg.t.length ? pg : null;
    }

    // ------------------------------------------------------------------ search, jumps, edit windows (background only)

    private long[] checkpointFor(int row) {
        synchronized (cpLock) {
            int cp = Math.min(cps - 1, Math.max(0, row / CHECK));
            if (cp < 0) return new long[]{dataStart, 1, 0, 0};
            return new long[]{cpOff[cp], cpInfo[cp] >> 1, cpInfo[cp] & 1, (long) cp * CHECK};
        }
    }

    /** Case-insensitive search from a row, wrapping once. Returns the row index or -1. */
    int find(String query, int fromRow, AtomicBoolean cancel) throws IOException {
        final String q = query.toLowerCase(Locale.ROOT);
        int a = scanFor(q, fromRow, Integer.MAX_VALUE, cancel);
        if (a >= 0) return a;
        if (fromRow > 0) return scanFor(q, 0, fromRow, cancel);
        return -1;
    }

    private int scanFor(final String q, final int lo, final int hi, AtomicBoolean cancel) throws IOException {
        if (memText != null) {
            for (int i = Math.max(0, lo); i < Math.min(hi, memText.length); i++) {
                if (memText[i].toLowerCase(Locale.ROOT).contains(q)) return i;
            }
            return -1;
        }
        if (rows == 0 || lo >= rows) return -1;
        long[] c = checkpointFor(lo);
        final int[] hit = {-1};
        scan(c[0], (int) c[3], (int) c[1], c[2] != 0, true, cancel, (idx, no, cont, start, text) -> {
            if (idx >= hi) return false;
            if (idx >= lo && text.toLowerCase(Locale.ROOT).contains(q)) {
                hit[0] = idx;
                return false;
            }
            return true;
        });
        return hit[0];
    }

    /** First row of a line number (the last row when the line is beyond the end). */
    int rowOfLine(final int line) throws IOException {
        if (memText != null) {
            for (int i = 0; i < memNo.length; i++) if (memNo[i] >= line) return i;
            return Math.max(0, memNo.length - 1);
        }
        if (rows == 0) return 0;
        int cp;
        long off;
        int info;
        synchronized (cpLock) {
            int lo = 0;
            int hi = cps - 1;
            cp = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if ((cpInfo[mid] >> 1) < line) {
                    cp = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            off = cpOff[cp];
            info = cpInfo[cp];
        }
        final int[] hit = {-1};
        scan(off, cp * CHECK, info >> 1, (info & 1) != 0, false, null, (idx, no, cont, start, text) -> {
            if (no >= line && !cont) {
                hit[0] = idx;
                return false;
            }
            return true;
        });
        return hit[0] >= 0 ? hit[0] : Math.max(0, rows - 1);
    }

    /** Whole lines around a row: {startByte, endByte, startRow, endRow}, bounded by rows and bytes. */
    long[] windowAround(final int top, final int maxRows, final long maxBytes) throws IOException {
        if (memText != null || rows == 0) return new long[]{dataStart, length, 0, rows};
        final int want = Math.max(0, top - Math.max(40, maxRows / 8));
        long[] c = checkpointFor(want);
        final long[] w = {-1, length, -1, rows};
        scan(c[0], (int) c[3], (int) c[1], c[2] != 0, false, null, (idx, no, cont, start, text) -> {
            if (w[0] < 0) {
                if (idx >= want && !cont) {
                    w[0] = start;
                    w[2] = idx;
                }
                return true;
            }
            if (!cont && (idx - w[2] >= maxRows || start - w[0] >= maxBytes)) {
                w[1] = start;
                w[3] = idx;
                return false;
            }
            return true;
        });
        if (w[0] < 0) {
            w[0] = dataStart;
            w[2] = 0;
        }
        return w;
    }

    /** Raw text of a byte range (UTF-8), for the edit window. */
    String readRaw(long from, long to) throws IOException {
        if (to <= from) return "";
        byte[] b = new byte[(int) (to - from)];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(from);
            raf.readFully(b);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    static TextWindow ofLines(File f, List<String> lines) {
        return memory(f, android.text.TextUtils.join("\n", lines));
    }
}
