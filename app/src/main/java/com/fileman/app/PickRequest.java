package com.fileman.app;

import android.content.Intent;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Reads the two values another app may attach to a picker request (suggested file name, accepted MIME
 * types) and cleans them: bounded length and count, no path or control characters, only well-formed
 * MIME patterns. The picker screens never use these values raw.
 */
final class PickRequest {
    private static final int MAX_NAME = 120;
    private static final int MAX_MIMES = 32;
    private static final Pattern MIME = Pattern.compile("^[A-Za-z0-9.+-]{1,60}/([A-Za-z0-9.+-]{1,80}|\\*)$");

    private PickRequest() {
    }

    /** Suggested name for a new file; never empty and never contains a path separator. */
    static String title(Intent in) {
        String t = in == null ? null : in.getStringExtra(Intent.EXTRA_TITLE);
        if (t == null) return "file";
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < t.length() && b.length() < MAX_NAME; k++) {
            char c = t.charAt(k);
            boolean bad = c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|';
            b.append(bad ? '_' : c);
        }
        String s = b.toString().trim();
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "file" : s;
    }

    /** The well-formed MIME types the other app accepts, or null when any file will do. */
    static String[] mimes(Intent in) {
        if (in == null) return null;
        ArrayList<String> out = new ArrayList<>();
        String[] extra = in.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (extra != null) for (String m : extra) add(out, m);
        if (out.isEmpty()) add(out, in.getType());
        if (out.isEmpty()) return null;
        for (String m : out) if (m.equals("*/*")) return null;
        return out.toArray(new String[0]);
    }

    private static void add(ArrayList<String> out, String m) {
        if (m == null || out.size() >= MAX_MIMES) return;
        m = m.trim();
        if (MIME.matcher(m).matches() && !out.contains(m)) out.add(m);
    }
}
