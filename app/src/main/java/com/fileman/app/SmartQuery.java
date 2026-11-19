package com.fileman.app;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Understands what a search sentence means besides its keywords: the kind of file ("photos", "pdf"),
 * a time window ("yesterday", "last week"), a size ("large") and "latest". The words that carried such a
 * meaning are removed, and what is left is searched as keywords.
 */
final class SmartQuery {
    final Set<Integer> types = new HashSet<>();
    final Set<String> exts = new HashSet<>();
    long since = 0L;
    long until = Long.MAX_VALUE;
    long minSize = 0L;
    boolean recent;
    String rest = "";

    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Set<String> set(String... w) {
        return new HashSet<>(Arrays.asList(w));
    }

    private static final Set<String> IMG = set("\u0635\u0648\u0631", "\u0635\u0648\u0631\u0647", "\u0635\u0648\u0631\u0629", "image", "images", "photo", "photos", "picture", "pictures", "pic", "pics", "\u0644\u0642\u0637\u0647", "\u0644\u0642\u0637\u0627\u062a");
    private static final Set<String> VID = set("\u0641\u064a\u062f\u064a\u0648", "\u0641\u064a\u062f\u064a\u0648\u0647\u0627\u062a", "\u0641\u062f\u064a\u0648", "\u0645\u0642\u0637\u0639", "\u0645\u0642\u0627\u0637\u0639", "video", "videos", "movie", "movies", "\u0641\u0644\u0645", "\u0627\u0641\u0644\u0627\u0645");
    private static final Set<String> AUD = set("\u0635\u0648\u062a", "\u0635\u0648\u062a\u064a\u0627\u062a", "\u0627\u063a\u0646\u064a\u0647", "\u0627\u063a\u0627\u0646\u064a", "\u0645\u0648\u0633\u064a\u0642\u064a", "\u0645\u0648\u0633\u064a\u0642\u0649", "audio", "music", "song", "songs", "mp3", "\u062a\u0633\u062c\u064a\u0644", "\u062a\u0633\u062c\u064a\u0644\u0627\u062a");
    private static final Set<String> APP = set("\u062a\u0637\u0628\u064a\u0642", "\u062a\u0637\u0628\u064a\u0642\u0627\u062a", "\u0628\u0631\u0646\u0627\u0645\u062c", "\u0628\u0631\u0627\u0645\u062c", "app", "apps", "apk");
    private static final Set<String> ARC = set("\u0627\u0631\u0634\u064a\u0641", "\u0645\u0636\u063a\u0648\u0637", "\u0645\u0636\u063a\u0648\u0637\u0647", "\u0645\u0636\u063a\u0648\u0637\u0627\u062a", "archive", "archives", "zip", "rar");
    private static final Set<String> PDF = set("pdf", "\u0628\u064a \u062f\u064a \u0627\u0641");
    private static final Set<String> WORD = set("\u0648\u0648\u0631\u062f", "word", "docx");
    private static final Set<String> XLS = set("\u0627\u0643\u0633\u0644", "excel", "xlsx", "\u062c\u062f\u0648\u0644", "\u062c\u062f\u0627\u0648\u0644", "spreadsheet");
    private static final Set<String> PPT = set("\u0628\u0648\u0631\u0628\u0648\u064a\u0646\u062a", "\u0628\u0627\u0648\u0631\u0628\u0648\u064a\u0646\u062a", "powerpoint", "ppt", "pptx", "presentation");
    private static final Set<String> TXT = set("\u0646\u0635", "\u0646\u0635\u064a", "txt", "\u0646\u0635\u0648\u0635");
    private static final Set<String> DOC = set("\u0645\u0633\u062a\u0646\u062f", "\u0645\u0633\u062a\u0646\u062f\u0627\u062a", "\u0648\u062b\u064a\u0642\u0647", "\u0648\u062b\u0627\u0626\u0642", "document", "documents", "docs");

    private static final Set<String> TODAY = set("\u0627\u0644\u064a\u0648\u0645", "today");
    private static final Set<String> YESTERDAY = set("\u0627\u0645\u0633", "\u0627\u0644\u0628\u0627\u0631\u062d\u0647", "yesterday");
    private static final Set<String> WEEK = set("\u0627\u0633\u0628\u0648\u0639", "\u0627\u0644\u0627\u0633\u0628\u0648\u0639", "week");
    private static final Set<String> MONTH = set("\u0634\u0647\u0631", "\u0627\u0644\u0634\u0647\u0631", "month");
    private static final Set<String> YEAR = set("\u0633\u0646\u0647", "\u0627\u0644\u0633\u0646\u0647", "\u0639\u0627\u0645", "\u0627\u0644\u0639\u0627\u0645", "year");
    private static final Set<String> LAST = set("\u0627\u0644\u0645\u0627\u0636\u064a", "\u0627\u0644\u0645\u0627\u0636\u064a\u0647", "\u0627\u0644\u0633\u0627\u0628\u0642", "\u0627\u0644\u0633\u0627\u0628\u0642\u0647", "last", "previous", "past");
    private static final Set<String> RECENT = set("\u0627\u062e\u0631", "\u0627\u062e\u064a\u0631\u0627", "\u062c\u062f\u064a\u062f", "\u062c\u062f\u064a\u062f\u0647", "\u062d\u062f\u064a\u062b", "\u062d\u062f\u064a\u062b\u0647", "\u0627\u062d\u062f\u062b", "\u0627\u0644\u0627\u062d\u062f\u062b", "recent", "latest", "newest", "new");
    private static final Set<String> BIG = set("\u0643\u0628\u064a\u0631", "\u0643\u0628\u064a\u0631\u0647", "\u0643\u0628\u0627\u0631", "\u0636\u062e\u0645", "\u0636\u062e\u0645\u0647", "large", "big", "huge");

    private static String key(String t) {
        return t;
    }

    static SmartQuery parse(String query) {
        SmartQuery q = new SmartQuery();
        String norm = SmartIndex.normalize(query == null ? "" : query);
        if (norm.isEmpty()) return q;
        List<String> words = Arrays.asList(norm.split(" "));
        boolean[] used = new boolean[words.size()];
        boolean last = false;
        for (String w : words) if (LAST.contains(w)) last = true;
        long now = System.currentTimeMillis();
        boolean windowSet = false;
        for (int i = 0; i < words.size(); i++) {
            String w = key(words.get(i));
            if (w.isEmpty()) continue;
            boolean hit = true;
            if (IMG.contains(w)) q.types.add(Cats.T_IMG);
            else if (VID.contains(w)) q.types.add(Cats.T_VID);
            else if (AUD.contains(w)) q.types.add(Cats.T_AUD);
            else if (APP.contains(w)) q.types.add(Cats.T_APK);
            else if (ARC.contains(w)) q.types.add(Cats.T_ARC);
            else if (PDF.contains(w)) q.exts.add("pdf");
            else if (WORD.contains(w)) q.exts.addAll(Arrays.asList("doc", "docx", "odt", "rtf"));
            else if (XLS.contains(w)) q.exts.addAll(Arrays.asList("xls", "xlsx", "ods", "csv"));
            else if (PPT.contains(w)) q.exts.addAll(Arrays.asList("ppt", "pptx", "odp"));
            else if (TXT.contains(w)) q.types.add(Cats.T_TXT);
            else if (DOC.contains(w)) {
                q.types.add(Cats.T_PDF);
                q.types.add(Cats.T_DOC);
                q.types.add(Cats.T_TXT);
            } else if (TODAY.contains(w)) {
                q.since = startOfToday();
                windowSet = true;
            } else if (YESTERDAY.contains(w)) {
                q.since = startOfToday() - DAY;
                q.until = startOfToday();
                windowSet = true;
            } else if (WEEK.contains(w)) {
                q.since = now - (last ? 14 : 7) * DAY;
                if (last) q.until = now - 7 * DAY;
                windowSet = true;
            } else if (MONTH.contains(w)) {
                q.since = now - (last ? 60 : 30) * DAY;
                if (last) q.until = now - 30 * DAY;
                windowSet = true;
            } else if (YEAR.contains(w)) {
                q.since = now - (last ? 730 : 365) * DAY;
                if (last) q.until = now - 365 * DAY;
                windowSet = true;
            } else if (LAST.contains(w)) {
                // only meaningful next to a time word; consumed either way
            } else if (RECENT.contains(w)) {
                q.recent = true;
            } else if (BIG.contains(w)) {
                q.minSize = 50L * 1024 * 1024;
            } else {
                hit = false;
            }
            used[i] = hit;
        }
        if (windowSet) q.recent = true;
        StringBuilder rest = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (used[i]) continue;
            if (rest.length() > 0) rest.append(' ');
            rest.append(words.get(i));
        }
        q.rest = rest.toString();
        return q;
    }

    private static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    boolean hasFilter() {
        return !types.isEmpty() || !exts.isEmpty() || since > 0L || until != Long.MAX_VALUE || minSize > 0L || recent;
    }

    boolean accept(String name, long mod, long size) {
        if (mod < since || mod >= until) return false;
        if (size < minSize) return false;
        if (!types.isEmpty() || !exts.isEmpty()) {
            String ext = Cats.extOf(name);
            if (!exts.contains(ext) && !types.contains(Cats.typeOfExt(ext))) return false;
        }
        return true;
    }
}
