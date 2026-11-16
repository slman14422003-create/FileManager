package com.fileman.app;

import android.content.Context;
import android.text.SpannableString;
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

    private CodeColors() {
    }

    static CharSequence tint(Context c, String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE) return line;
        SpannableString s = new SpannableString(line);
        Matcher cm = COMMENT.matcher(line);
        if (cm.matches()) {
            s.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.text_secondary)), 0, line.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return s;
        }
        span(s, KEYWORD.matcher(line), Ui.color(c, R.color.info));
        span(s, NUMBER.matcher(line), Ui.color(c, R.color.warn));
        span(s, STRING.matcher(line), Ui.color(c, R.color.ok));
        return s;
    }

    private static void span(SpannableString s, Matcher m, int color) {
        while (m.find()) {
            s.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
