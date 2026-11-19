package com.fileman.app;

import android.util.Base64;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static com.fileman.app.DocHtml.esc;
import static com.fileman.app.DocHtml.page;
import static com.fileman.app.DocHtml.readAll;
import static com.fileman.app.DocHtml.toInt;

/** RTF to HTML conversion (split out of DocHtml). */
final class DocRtf {
    private DocRtf() {
    }

    private static boolean rtfSkipWord(String w) {
        return w.equals("fonttbl") || w.equals("colortbl") || w.equals("stylesheet") || w.equals("info")
                || w.equals("pict") || w.equals("header") || w.equals("footer") || w.equals("themedata")
                || w.equals("datastore") || w.equals("generator") || w.equals("listtable")
                || w.equals("listoverridetable") || w.equals("rsidtbl");
    }

    static String rtf(File f) throws Exception {
        String s = new String(readAll(f, 8L * 1024 * 1024), StandardCharsets.ISO_8859_1);
        Charset cs = Charset.forName("windows-1252");
        Matcher cp = Pattern.compile("\\\\ansicpg(\\d+)").matcher(s);
        if (cp.find()) {
            try {
                cs = Charset.forName("windows-" + cp.group(1));
            } catch (Exception ignored) {
            }
        }
        StringBuilder out = new StringBuilder();
        int depth = 0;
        int skipDepth = -1;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
                if (i + 2 < n && s.charAt(i + 1) == '\\' && s.charAt(i + 2) == '*' && skipDepth < 0) skipDepth = depth;
                i++;
            } else if (c == '}') {
                if (skipDepth == depth) skipDepth = -1;
                depth--;
                i++;
            } else if (c == '\\') {
                i++;
                if (i >= n) break;
                char d = s.charAt(i);
                if (d == '\\' || d == '{' || d == '}') {
                    if (skipDepth < 0) out.append(d);
                    i++;
                } else if (d == '\'') {
                    if (i + 2 < n) {
                        try {
                            int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                            if (skipDepth < 0) out.append(new String(new byte[]{(byte) v}, cs));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    i += 3;
                } else if (d == '~') {
                    if (skipDepth < 0) out.append(' ');
                    i++;
                } else if (d == '-' || d == '_' || d == '|' || d == ':') {
                    i++;
                } else if (Character.isLetter(d)) {
                    int st = i;
                    while (i < n && Character.isLetter(s.charAt(i))) i++;
                    String word = s.substring(st, i);
                    int ps = i;
                    if (i < n && s.charAt(i) == '-') i++;
                    while (i < n && Character.isDigit(s.charAt(i))) i++;
                    String param = s.substring(ps, i);
                    if (i < n && s.charAt(i) == ' ') i++;
                    if (rtfSkipWord(word)) {
                        if (skipDepth < 0) skipDepth = depth;
                    } else if (skipDepth < 0) {
                        if (word.equals("par") || word.equals("line") || word.equals("sect") || word.equals("page")) {
                            out.append('\n');
                        } else if (word.equals("tab")) {
                            out.append('\t');
                        } else if (word.equals("u") && !param.isEmpty()) {
                            int code = toInt(param, 63);
                            if (code < 0) code += 65536;
                            out.append((char) code);
                            if (i < n && s.charAt(i) != '\\' && s.charAt(i) != '{' && s.charAt(i) != '}') i++;
                        }
                    }
                } else {
                    i++;
                }
            } else {
                if (skipDepth < 0 && c != '\r' && c != '\n') out.append(c);
                i++;
            }
        }
        String body = esc(out.toString().trim()).replace("\n", "<br>");
        return page("<div dir=\"auto\" style=\"white-space:pre-wrap\">" + body + "</div>");
    }
}
