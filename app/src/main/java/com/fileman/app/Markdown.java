package com.fileman.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small Markdown to HTML converter for the document viewer: headings, paragraphs, bold / italic / strike, inline and
 * fenced code, block quotes, ordered / unordered / task lists, tables, rules, links and image alt text.
 * Everything is escaped first, so the result can never carry markup or scripts from the file.
 */
final class Markdown {
    private Markdown() {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern HR = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern UL = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern OL = Pattern.compile("^(\\s*)\\d{1,9}[.)]\\s+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)\\s*([\\w+-]*)\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    static String html(String md) {
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> para = new ArrayList<>();
        int i = 0;
        int n = lines.length;
        if (n > 0 && lines[0].equals("---")) {          // YAML front matter: skipped
            for (int k = 1; k < n && k < 60; k++) {
                if (lines[k].equals("---") || lines[k].equals("...")) {
                    i = k + 1;
                    break;
                }
            }
        }
        while (i < n) {
            String line = lines[i];
            Matcher m;
            if (line.trim().isEmpty()) {
                flush(out, para);
                i++;
            } else if ((m = FENCE.matcher(line)).matches()) {
                flush(out, para);
                String fence = m.group(1);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++;
                out.append("<pre dir=\"ltr\"><code>").append(LegacyDoc.esc(code.toString())).append("</code></pre>");
            } else if ((m = HEADING.matcher(line)).matches()) {
                flush(out, para);
                int lvl = m.group(1).length();
                out.append("<h").append(lvl).append(" dir=\"auto\">").append(inline(m.group(2))).append("</h").append(lvl).append(">");
                i++;
            } else if (HR.matcher(line).matches()) {
                flush(out, para);
                out.append("<hr>");
                i++;
            } else if (line.startsWith(">")) {
                flush(out, para);
                StringBuilder q = new StringBuilder();
                while (i < n && lines[i].startsWith(">")) {
                    q.append(lines[i].replaceFirst("^>\\s?", "")).append('\n');
                    i++;
                }
                out.append("<blockquote>").append(html(q.toString())).append("</blockquote>");
            } else if (UL.matcher(line).matches() || OL.matcher(line).matches()) {
                flush(out, para);
                i = list(lines, i, out);
            } else if (i + 1 < n && line.contains("|") && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                flush(out, para);
                i = table(lines, i, out);
            } else if (i + 1 < n && lines[i + 1].matches("^\\s*=+\\s*$") && !line.trim().isEmpty()) {
                flush(out, para);
                out.append("<h1 dir=\"auto\">").append(inline(line.trim())).append("</h1>");
                i += 2;
            } else if (i + 1 < n && lines[i + 1].matches("^\\s*-{2,}\\s*$") && !line.trim().isEmpty()) {
                flush(out, para);
                out.append("<h2 dir=\"auto\">").append(inline(line.trim())).append("</h2>");
                i += 2;
            } else {
                para.add(line.trim());
                i++;
            }
        }
        flush(out, para);
        return out.toString();
    }

    private static void flush(StringBuilder out, List<String> para) {
        if (para.isEmpty()) return;
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < para.size(); k++) {
            if (k > 0) s.append(para.get(k - 1).endsWith("  ") || para.get(k - 1).endsWith("\\") ? "<br>" : " ");
            s.append(inline(para.get(k)));
        }
        out.append("<p dir=\"auto\">").append(s).append("</p>");
        para.clear();
    }

    private static int indent(String s) {
        int c = 0;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == ' ') c++;
            else if (s.charAt(k) == '\t') c += 4;
            else break;
        }
        return c;
    }

    /** Lists, nesting by indentation. Returns the index of the first line after the list. */
    private static int list(String[] lines, int i, StringBuilder out) {
        List<String> tags = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        int n = lines.length;
        while (i < n) {
            String line = lines[i];
            Matcher u = UL.matcher(line), o = OL.matcher(line);
            boolean isU = u.matches(), isO = !isU && o.matches();
            if (!isU && !isO) {
                // continuation line of the previous item
                if (!line.trim().isEmpty() && indent(line) > 0 && !tags.isEmpty()) {
                    out.append(" ").append(inline(line.trim()));
                    i++;
                    continue;
                }
                break;
            }
            Matcher m = isU ? u : o;
            int lvl = indent(m.group(1));
            String tag = isU ? "ul" : "ol";
            String text = m.group(2);
            while (!levels.isEmpty() && lvl < levels.get(levels.size() - 1)) {
                out.append("</li></").append(tags.remove(tags.size() - 1)).append(">");
                levels.remove(levels.size() - 1);
            }
            if (levels.isEmpty() || lvl > levels.get(levels.size() - 1)) {
                out.append("<").append(tag).append(">");
                tags.add(tag);
                levels.add(lvl);
            } else {
                out.append("</li>");
            }
            String task = "";
            if (text.startsWith("[ ] ")) {
                task = "\u2610 ";
                text = text.substring(4);
            } else if (text.toLowerCase().startsWith("[x] ")) {
                task = "\u2611 ";
                text = text.substring(4);
            }
            out.append("<li dir=\"auto\">").append(task).append(inline(text));
            i++;
        }
        while (!tags.isEmpty()) out.append("</li></").append(tags.remove(tags.size() - 1)).append(">");
        return i;
    }

    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        List<String> out = new ArrayList<>();
        for (String c : r.split("(?<!\\\\)\\|", -1)) out.add(c.trim().replace("\\|", "|"));
        return out;
    }

    private static int table(String[] lines, int i, StringBuilder out) {
        List<String> head = cells(lines[i]);
        out.append("<div class=\"sheet\"><table><thead><tr>");
        for (String h : head) out.append("<th dir=\"auto\">").append(inline(h)).append("</th>");
        out.append("</tr></thead><tbody>");
        i += 2;
        while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
            List<String> c = cells(lines[i]);
            out.append("<tr>");
            for (int k = 0; k < head.size(); k++) out.append("<td dir=\"auto\">").append(inline(k < c.size() ? c.get(k) : "")).append("</td>");
            out.append("</tr>");
            i++;
        }
        out.append("</tbody></table></div>");
        return i;
    }

    private static String inline(String s) {
        // protect code spans first
        List<String> codes = new ArrayList<>();
        Matcher cm = Pattern.compile("(`+)(.+?)\\1").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (cm.find()) {
            codes.add(cm.group(2));
            cm.appendReplacement(sb, "\u0000" + (codes.size() - 1) + "\u0000");
        }
        cm.appendTail(sb);
        String t = LegacyDoc.esc(sb.toString());
        t = t.replaceAll("!\\[([^\\]]*)\\]\\(([^)]*)\\)", "<em>[$1]</em>");
        t = t.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+&quot;[^)]*&quot;)?\\)", "<a class=\"lk\">$1</a>");
        t = t.replaceAll("&lt;(https?://[^\\s&]+)&gt;", "<a class=\"lk\">$1</a>");
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        t = t.replaceAll("__(.+?)__", "<strong>$1</strong>");
        t = t.replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "<em>$1</em>");
        t = t.replaceAll("(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])", "<em>$1</em>");
        t = t.replaceAll("~~(.+?)~~", "<del>$1</del>");
        for (int k = 0; k < codes.size(); k++) t = t.replace("\u0000" + k + "\u0000", "<code>" + LegacyDoc.esc(codes.get(k)) + "</code>");
        return t;
    }
}
