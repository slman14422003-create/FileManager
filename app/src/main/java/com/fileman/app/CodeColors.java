package com.fileman.app;

import android.content.Context;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Light single-line highlighting for the code view, using only the iOS system colours. */
final class CodeColors {
    private static final int MAX_LINE = 400;
    private static final Pattern COMMENT = Pattern.compile("^\\s*(//|#|--|/\\*|\\*).*");
    private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern KEYWORD = Pattern.compile("\\b(?:abstract|async|await|boolean|break|case|catch|class|const|continue"
            + "|def|default|do|else|enum|export|extends|final|finally|fn|for|from|function|if|implements|import|in"
            + "|interface|let|new|null|package|private|protected|public|return|static|struct|super|switch|this|throw"
            + "|try|true|false|var|void|while|val|fun|when|object|override|select|insert|update|delete|where)\\b");

    /** Marks the spans this class adds, so they can be replaced without touching anything else. */
    static final class Hl extends ForegroundColorSpan {
        Hl(int color) {
            super(color);
        }
    }

    private CodeColors() {
    }

    /** Colours resolved once on the UI thread: comment, keyword, number, string. */
    static int[] palette(Context c) {
        return new int[]{Ui.color(c, R.color.text_secondary), Ui.color(c, R.color.info), Ui.color(c, R.color.warn),
                Ui.color(c, R.color.ok)};
    }

    static CharSequence tint(Context c, String line) {
        return tint(palette(c), line);
    }

    /** Safe off the UI thread: needs no Context. */
    static CharSequence tint(int[] pal, String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE) return line;
        SpannableString s = new SpannableString(line);
        Matcher cm = COMMENT.matcher(line);
        if (cm.matches()) {
            s.setSpan(new ForegroundColorSpan(pal[0]), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return s;
        }
        span(s, KEYWORD.matcher(line), pal[1]);
        span(s, NUMBER.matcher(line), pal[2]);
        span(s, STRING.matcher(line), pal[3]);
        return s;
    }

    private static void span(SpannableString s, Matcher m, int color) {
        while (m.find()) {
            s.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ------------------------------------------------------------------ editor: ranges computed off the UI thread

    /** Span ranges for a block of text as triples (start, end, colour), offset by {@code base}. */
    static int[] scan(String block, int[] pal, int base) {
        int[] out = new int[96];
        int n = 0;
        int len = block.length();
        int ls = 0;
        while (ls <= len) {
            int nl = block.indexOf('\n', ls);
            int le = nl < 0 ? len : nl;
            if (le > ls && le - ls <= MAX_LINE) {
                String line = block.substring(ls, le);
                if (COMMENT.matcher(line).matches()) {
                    if (n + 3 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    out[n++] = base + ls;
                    out[n++] = base + le;
                    out[n++] = pal[0];
                } else {
                    Pattern[] ps = {KEYWORD, NUMBER, STRING};
                    int[] cs = {pal[1], pal[2], pal[3]};
                    for (int k = 0; k < 3; k++) {
                        Matcher m = ps[k].matcher(line);
                        while (m.find()) {
                            if (n + 3 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                            out[n++] = base + ls + m.start();
                            out[n++] = base + ls + m.end();
                            out[n++] = cs[k];
                        }
                    }
                }
            }
            if (nl < 0) break;
            ls = nl + 1;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Replaces this class's spans in [from, to) with the computed ones (UI thread). */
    static void apply(Editable e, int from, int to, int[] spans) {
        int len = e.length();
        if (from < 0 || to > len || from > to) return;
        Hl[] old = e.getSpans(from, to, Hl.class);
        for (Hl h : old) e.removeSpan(h);
        for (int i = 0; i + 2 < spans.length; i += 3) {
            int a = spans[i];
            int b = spans[i + 1];
            if (a < 0 || b > len || a >= b) continue;
            e.setSpan(new Hl(spans[i + 2]), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
