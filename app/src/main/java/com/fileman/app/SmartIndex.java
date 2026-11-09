package com.fileman.app;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

final class SmartIndex {
    private SmartIndex() {
    }

    static final class Entry {
        final String path;
        final long mod;
        final long size;
        final String name;
        final String text;

        Entry(String path, long mod, long size, String name, String text) {
            this.path = path;
            this.mod = mod;
            this.size = size;
            this.name = name;
            this.text = text;
        }
    }

    static final class Hit {
        final File f;
        final int matched;
        final double score;
        final long mod;

        Hit(File f, int matched, double score) {
            this(f, matched, score, 0L);
        }

        Hit(File f, int matched, double score, long mod) {
            this.f = f;
            this.matched = matched;
            this.score = score;
            this.mod = mod;
        }
    }

    private static final String FILE = "smart_index.tsv";
    private static final int MAX_FILES = 60000;
    private static final int MAX_TEXT_FILES = 4000;
    private static final long MAX_TEXT_BYTES = 25L * 1024 * 1024;
    private static final int BUDGET = 400000;

    private static final Set<String> STOP = new HashSet<>(Arrays.asList("\u0641\u064a", "\u0645\u0646", "\u0639\u0644\u0649", "\u0639\u0646", "\u0627\u0644\u0649", "\u0645\u0639", "\u0647\u0630\u0627",
            "\u0647\u0630\u0647", "\u0645\u0644\u0641", "\u0645\u0644\u0641\u0627\u062a", "the", "of", "for", "and", "my", "to", "in", "on", "a", "file", "files"));

    private static final String[][] SYN = {
            {"\u0641\u0627\u062a\u0648\u0631\u0629", "\u0641\u0648\u0627\u062a\u064a\u0631", "invoice", "bill", "receipt", "\u0625\u064a\u0635\u0627\u0644", "\u0627\u064a\u0635\u0627\u0644", "\u0648\u0635\u0644"},
            {"\u0643\u0647\u0631\u0628\u0627\u0621", "\u0643\u0647\u0631\u0628\u0627", "electricity", "electric", "power"},
            {"\u0645\u0627\u0621", "\u0645\u064a\u0627\u0647", "water"},
            {"\u0639\u0642\u062f", "\u0639\u0642\u0648\u062f", "contract", "agreement"},
            {"\u0633\u064a\u0631\u0629", "cv", "resume", "\u0630\u0627\u062a\u064a\u0629"},
            {"\u0634\u0647\u0627\u062f\u0629", "\u0634\u0647\u0627\u062f\u0627\u062a", "certificate", "diploma"},
            {"\u0647\u0648\u064a\u0629", "\u062c\u0648\u0627\u0632", "passport", "identity", "id"},
            {"\u062a\u0642\u0631\u064a\u0631", "\u062a\u0642\u0627\u0631\u064a\u0631", "report"},
            {"\u0635\u0648\u0631\u0629", "\u0635\u0648\u0631", "image", "photo", "picture"},
            {"\u0631\u0627\u062a\u0628", "\u0631\u0648\u0627\u062a\u0628", "salary", "payslip"},
            {"\u0628\u0646\u0643", "\u0645\u0635\u0631\u0641", "bank", "statement", "\u0643\u0634\u0641"},
            {"\u062a\u0623\u0645\u064a\u0646", "\u062a\u0627\u0645\u064a\u0646", "insurance"},
            {"\u0625\u064a\u062c\u0627\u0631", "\u0627\u064a\u062c\u0627\u0631", "rent", "lease"},
            {"\u062a\u0630\u0643\u0631\u0629", "\u062a\u0630\u0627\u0643\u0631", "ticket", "boarding"},
            {"\u0648\u0635\u0641\u0629", "\u0648\u0635\u0641\u0627\u062a", "recipe"},
            {"\u0637\u0628\u064a", "\u0635\u062d\u064a", "medical", "health", "\u062a\u062d\u0644\u064a\u0644"},
            {"\u0633\u064a\u0627\u0631\u0629", "\u0645\u0631\u0643\u0628\u0629", "car", "vehicle", "\u0631\u062e\u0635\u0629"},
            {"\u0643\u062a\u0627\u0628", "\u0643\u062a\u0628", "book", "ebook", "\u0645\u0646\u0647\u062c"},
            {"\u0636\u0631\u064a\u0628\u0629", "\u0636\u0631\u0627\u0626\u0628", "tax", "vat"},
            {"\u0645\u064a\u0632\u0627\u0646\u064a\u0629", "budget", "\u062d\u0633\u0627\u0628\u0627\u062a", "accounts"},
            {"\u0645\u062d\u0627\u0636\u0631\u0629", "\u0645\u062d\u0627\u0636\u0631\u0627\u062a", "lecture", "notes", "\u0645\u0644\u062e\u0635"},
            {"\u0645\u0634\u0631\u0648\u0639", "\u0645\u0634\u0627\u0631\u064a\u0639", "project", "proposal", "\u0639\u0631\u0636"},
            {"\u0627\u0634\u062a\u0631\u0627\u0643", "subscription", "\u062a\u062c\u062f\u064a\u062f", "renewal"},
            {"\u062c\u062f\u0648\u0644", "schedule", "timetable", "\u0645\u0648\u0627\u0639\u064a\u062f"}
    };

    // folder names for the first 16 groups above (null: no folder), used to sort documents by topic
    private static final String[] GROUP_FOLDER = {"Invoices", "Bills", "Bills", "Contracts", "Resumes", "Certificates",
            "IDs", "Reports", null, "Payslips", "Bank", "Insurance", "Rent", "Tickets", "Recipes", "Medical"};

    static Set<String> topics() {
        Set<String> s = new HashSet<>();
        for (String g : GROUP_FOLDER) if (g != null) s.add(g);
        return s;
    }

    /** The topic a document name points to (invoice, contract, certificate...), or null. */
    static String topicFolder(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        for (String t : tokens(normalize(stem))) {
            if (t.length() < 3 && !t.equals("cv")) continue;
            Integer g = GROUP_OF.get(t);
            if (g != null && g < GROUP_FOLDER.length && GROUP_FOLDER[g] != null) return GROUP_FOLDER[g];
        }
        return null;
    }

    private static final Map<String, Integer> GROUP_OF = new HashMap<>();

    static {
        for (int g = 0; g < SYN.length; g++) {
            for (String w : SYN[g]) GROUP_OF.put(stem(normalize(w)), g);
        }
    }

    private static volatile List<Entry> cache;
    private static volatile long builtAt;

    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        String low = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            if ((c >= 0x064B && c <= 0x065F) || c == 0x0670 || c == 0x0640) continue;
            switch (c) {
                case '\u0623':
                case '\u0625':
                case '\u0622':
                case '\u0671':
                    c = '\u0627';
                    break;
                case '\u0649':
                    c = '\u064a';
                    break;
                case '\u0629':
                    c = '\u0647';
                    break;
                case '\u0624':
                    c = '\u0648';
                    break;
                case '\u0626':
                    c = '\u064a';
                    break;
                default:
                    break;
            }
            if (c >= 0x0660 && c <= 0x0669) c = (char) ('0' + (c - 0x0660));
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    static String stem(String t) {
        if (t.length() > 3 && t.startsWith("\u0627\u0644")) t = t.substring(2);
        if (t.length() > 3 && t.endsWith("\u0647")) t = t.substring(0, t.length() - 1);
        if (t.length() > 3 && t.endsWith("s") && t.charAt(0) < 128) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static List<String> tokens(String norm) {
        List<String> out = new ArrayList<>();
        for (String t : norm.split(" ")) {
            if (t.isEmpty() || STOP.contains(t)) continue;
            String s = stem(t);
            if (s.length() >= 2 && !STOP.contains(s)) out.add(s);
        }
        return out;
    }

    private static String join(Iterable<String> toks, int max) {
        StringBuilder sb = new StringBuilder(" ");
        int n = 0;
        for (String t : toks) {
            sb.append(t).append(' ');
            if (++n >= max) break;
        }
        return sb.toString();
    }

    static File indexFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    static File root() {
        return Environment.getExternalStorageDirectory();
    }

    static synchronized List<Entry> load(Context c) {
        if (cache != null) return cache;
        List<Entry> out = new ArrayList<>();
        File f = indexFile(c);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length != 5) continue;
                    out.add(new Entry(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), p[3], p[4]));
                }
                builtAt = f.lastModified();
            } catch (Exception e) {
                out.clear();
            }
        }
        cache = out;
        return out;
    }

    static int size(Context c) {
        return load(c).size();
    }

    static long builtAt(Context c) {
        load(c);
        return builtAt;
    }

    static synchronized void build(Context c, BooleanSupplier stop, IntConsumer progress) {
        Map<String, Entry> old = new HashMap<>();
        for (Entry e : load(c)) old.put(e.path, e);
        List<Entry> out = new ArrayList<>();
        int[] budget = {BUDGET};
        int[] textFiles = {0};
        long[] textBytes = {0};
        walk(root(), 0, old, out, budget, textFiles, textBytes, stop, progress);
        if (stop.getAsBoolean()) return;
        File f = indexFile(c);
        File tmp = new File(f.getParentFile(), FILE + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (Entry e : out) {
                w.write(e.path);
                w.write('\t');
                w.write(Long.toString(e.mod));
                w.write('\t');
                w.write(Long.toString(e.size));
                w.write('\t');
                w.write(e.name);
                w.write('\t');
                w.write(e.text);
                w.write('\n');
            }
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (f.exists()) f.delete();
        tmp.renameTo(f);
        cache = out;
        builtAt = System.currentTimeMillis();
    }

    private static void walk(File dir, int depth, Map<String, Entry> old, List<Entry> out, int[] budget,
                             int[] textFiles, long[] textBytes, BooleanSupplier stop, IntConsumer progress) {
        if (stop.getAsBoolean() || budget[0] <= 0 || depth > 14 || out.size() >= MAX_FILES) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (stop.getAsBoolean() || budget[0] <= 0 || out.size() >= MAX_FILES) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (depth == 0 && n.equals("Android")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!Cats.isLink(f)) walk(f, depth + 1, old, out, budget, textFiles, textBytes, stop, progress);
                continue;
            }
            String path = f.getAbsolutePath();
            if (path.indexOf('\t') >= 0 || path.indexOf('\n') >= 0) continue;
            long mod = f.lastModified();
            long len = f.length();
            Entry prev = old.get(path);
            if (prev != null && prev.mod == mod && prev.size == len) {
                out.add(prev);
            } else {
                File parent = f.getParentFile();
                String nameNorm = normalize(n + " " + (parent == null ? "" : parent.getName()));
                String name = join(new LinkedHashSet<>(tokens(nameNorm)), 40);
                String text = " ";
                String ext = Cats.extOf(n);
                if (SmartText.hasText(ext) && len > 0 && len <= 20L * 1024 * 1024 && textFiles[0] < MAX_TEXT_FILES
                        && textBytes[0] < MAX_TEXT_BYTES) {
                    textFiles[0]++;
                    textBytes[0] += len;
                    String t = SmartText.textOf(f, 6000);
                    if (!t.isEmpty()) text = join(new LinkedHashSet<>(tokens(normalize(t))), 400);
                }
                out.add(new Entry(path, mod, len, name, text));
            }
            if (progress != null && out.size() % 500 == 0) progress.accept(out.size());
        }
    }

    private static List<String> alternatives(String q) {
        List<String> alts = new ArrayList<>();
        alts.add(q);
        Integer g = GROUP_OF.get(q);
        if (g != null) {
            for (String w : SYN[g]) {
                String s = stem(normalize(w));
                if (!s.equals(q) && !alts.contains(s)) alts.add(s);
            }
        }
        return alts;
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Typing slips: one wrong, missing or extra letter (two for long words). */
    private static boolean near(String a, String b) {
        int la = a.length();
        int lb = b.length();
        int max = la >= 8 ? 2 : la >= 4 ? 1 : 0;
        if (max == 0 || Math.abs(la - lb) > max) return false;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin > max) return false;
            int[] sw = prev;
            prev = cur;
            cur = sw;
        }
        return prev[lb] <= max;
    }

    private static double recency(long now, long mod) {
        return 0.5 * Math.exp(-Math.max(0L, now - mod) / (90.0 * 86400000.0));
    }

    /**
     * Keywords with synonyms, plus what the sentence means: "photos from last week", "large videos",
     * "latest invoice pdf". Typos are forgiven when the exact pass finds little. Newer files rank higher.
     */
    static List<Hit> search(Context c, String query, int limit) {
        SmartQuery sq = SmartQuery.parse(query);
        List<String> qt = tokens(normalize(sq.rest));
        List<Hit> hits = new ArrayList<>();
        boolean onlyFilter = qt.isEmpty();
        if (onlyFilter && !sq.hasFilter()) return hits;
        long now = System.currentTimeMillis();
        List<List<String>> alts = new ArrayList<>();
        for (String q : qt) alts.add(alternatives(q));
        int need = Math.max(1, (qt.size() + 1) / 2);
        List<Entry> all = load(c);
        Set<String> seen = new HashSet<>();
        for (Entry e : all) {
            if (!sq.accept(leaf(e.path), e.mod, e.size)) continue;
            double rec = recency(now, e.mod);
            if (onlyFilter) {
                hits.add(new Hit(new File(e.path), 0, rec, e.mod));
                continue;
            }
            int matched = 0;
            double score = 0;
            for (List<String> group : alts) {
                double best = 0;
                for (int i = 0; i < group.size(); i++) {
                    String a = group.get(i);
                    double w = i == 0 ? 1.0 : 0.7;
                    double s = 0;
                    if (e.name.contains(" " + a + " ")) s = 3.0;
                    else if (a.length() >= 3 && e.name.contains(" " + a)) s = 2.4;
                    else if (e.text.length() > 1 && e.text.contains(" " + a + " ")) s = 1.2;
                    else if (a.length() >= 3 && e.text.length() > 1 && e.text.contains(" " + a)) s = 0.9;
                    if (s * w > best) best = s * w;
                }
                if (best > 0) {
                    matched++;
                    score += best;
                }
            }
            if (matched >= need) {
                hits.add(new Hit(new File(e.path), matched, score + rec, e.mod));
                seen.add(e.path);
            }
        }
        if (!onlyFilter && hits.size() < 10) {
            for (Entry e : all) {
                if (seen.contains(e.path) || !sq.accept(leaf(e.path), e.mod, e.size)) continue;
                String[] words = e.name.trim().split(" ");
                int matched = 0;
                for (String q : qt) {
                    for (String w : words) {
                        if (near(q, w)) {
                            matched++;
                            break;
                        }
                    }
                }
                if (matched >= need) {
                    hits.add(new Hit(new File(e.path), matched, 1.5 * matched + recency(now, e.mod), e.mod));
                    if (hits.size() >= limit * 2) break;
                }
            }
        }
        final boolean byDate = onlyFilter || sq.recent;
        Collections.sort(hits, (a, b) -> {
            if (byDate) return Long.compare(b.mod, a.mod);
            return a.matched != b.matched ? Integer.compare(b.matched, a.matched) : Double.compare(b.score, a.score);
        });
        if (hits.size() > limit) return new ArrayList<>(hits.subList(0, limit));
        return hits;
    }
}
