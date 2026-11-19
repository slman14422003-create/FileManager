package com.fileman.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Text of Word 97-2003 binary documents (.doc, .dot): the main story is rebuilt from the piece table.
 * Paragraphs and tables are kept; character formatting, images and headers are not (the binary format stores them
 * in separate property tables).
 */
final class LegacyDoc {
    private LegacyDoc() {
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    /** The raw text of the main document (control characters kept). */
    static String text(File f) throws IOException {
        try (Ole2 o = new Ole2(f)) {
            byte[] wd = o.stream("WordDocument");
            if (wd == null || wd.length < 0x1A8) throw new IOException("no WordDocument stream");
            if (u16(wd, 0) != 0xA5EC) throw new IOException("not a Word binary file");
            int flags = u16(wd, 0x0A);
            if ((flags & 0x0100) != 0) throw new IOException("encrypted");
            byte[] table = o.stream((flags & 0x0200) != 0 ? "1Table" : "0Table");
            if (table == null) throw new IOException("no table stream");
            long ccpText = u32(wd, 0x4C);
            long fcClx = u32(wd, 0x1A2);
            long lcbClx = u32(wd, 0x1A6);
            if (fcClx < 0 || fcClx + lcbClx > table.length) throw new IOException("bad CLX");
            int p = (int) fcClx, end = (int) (fcClx + lcbClx);
            while (p < end && (table[p] & 0xFF) == 1) p += 3 + u16(table, p + 1);     // skip property modifiers
            if (p >= end || (table[p] & 0xFF) != 2) throw new IOException("no piece table");
            long plcLen = u32(table, p + 1);
            int q = p + 5;
            int n = (int) ((plcLen - 4) / 12);
            if (n <= 0 || q + plcLen > table.length) throw new IOException("bad piece table");
            StringBuilder sb = new StringBuilder((int) Math.min(ccpText, 1 << 24));
            Charset cp1252 = Charset.forName("windows-1252");
            for (int i = 0; i < n && sb.length() < ccpText; i++) {
                long cpStart = u32(table, q + i * 4), cpEnd = u32(table, q + (i + 1) * 4);
                long fcRaw = u32(table, q + (n + 1) * 4 + i * 8 + 2);
                boolean ansi = (fcRaw & 0x40000000L) != 0;
                long fc = fcRaw & 0x3FFFFFFFL;
                long count = Math.min(cpEnd - cpStart, ccpText - cpStart);
                if (count <= 0) continue;
                if (ansi) {
                    int off = (int) (fc / 2);
                    if (off + count > wd.length) count = Math.max(0, wd.length - off);
                    sb.append(new String(wd, off, (int) count, cp1252));
                } else {
                    int off = (int) fc;
                    if (off + count * 2 > wd.length) count = Math.max(0, (wd.length - off) / 2);
                    sb.append(new String(wd, off, (int) count * 2, StandardCharsets.UTF_16LE));
                }
            }
            return sb.toString();
        }
    }

    static String esc(String s) {
        StringBuilder o = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': o.append("&amp;"); break;
                case '<': o.append("&lt;"); break;
                case '>': o.append("&gt;"); break;
                default: o.append(c);
            }
        }
        return o.toString();
    }

    /** Field codes (between 0x13 and 0x14) are dropped, their visible result kept; other control characters are cleaned. */
    private static String clean(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int depth = 0;           // inside a field's code part
        List<Boolean> codePart = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 0x13) {
                codePart.add(true);
                continue;
            }
            if (c == 0x14) {
                if (!codePart.isEmpty()) codePart.set(codePart.size() - 1, false);
                continue;
            }
            if (c == 0x15) {
                if (!codePart.isEmpty()) codePart.remove(codePart.size() - 1);
                continue;
            }
            boolean hidden = false;
            for (boolean b : codePart) if (b) hidden = true;
            if (hidden) continue;
            if (c == 0x0B) sb.append('\n');                       // manual line break
            else if (c == 0x0C) sb.append('\r');                  // page / section break: new paragraph
            else if (c == 0x1E) sb.append('-');
            else if (c == 0x1F) sb.append('\u00AD');
            else if (c == 0xA0 || c >= 0x20 || c == '\r' || c == 0x07 || c == '\t') sb.append(c);
        }
        return sb.toString();
    }

    /** HTML body (paragraphs and tables) of the document. */
    static String html(File f) throws IOException {
        String t = clean(text(f));
        StringBuilder html = new StringBuilder();
        StringBuilder cell = new StringBuilder();
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == 0x07) {
                if (cell.length() == 0 && para.length() == 0 && !row.isEmpty()) {     // row end mark
                    table.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(para);
                    para.setLength(0);
                    row.add(cell.toString());
                    cell.setLength(0);
                }
            } else if (c == '\r') {
                if (!row.isEmpty() || cell.length() > 0 || !table.isEmpty()) {
                    if (para.length() > 0 || cell.length() > 0) {            // paragraph inside a table cell
                        if (cell.length() > 0) cell.append('\n');
                        cell.append(para);
                        para.setLength(0);
                    } else {                                                  // blank line after the table: it has ended
                        flushTable(html, table);
                    }
                } else {
                    paragraph(html, para.toString());
                    para.setLength(0);
                }
            } else {
                if (!table.isEmpty() && row.isEmpty() && cell.length() == 0 && para.length() == 0) {
                    int k = i;                                   // does this text end a table cell or a normal paragraph?
                    while (k < t.length() && t.charAt(k) != 0x07 && t.charAt(k) != '\r') k++;
                    if (k >= t.length() || t.charAt(k) == '\r') flushTable(html, table);
                }
                para.append(c);
            }
        }
        flushTable(html, table);
        if (para.length() > 0) paragraph(html, para.toString());
        return html.toString();
    }

    private static void paragraph(StringBuilder html, String p) {
        if (p.trim().isEmpty()) {
            html.append("<p>&nbsp;</p>");
            return;
        }
        html.append("<p dir=\"auto\">").append(esc(p).replace("\n", "<br>")).append("</p>");
    }

    private static void flushTable(StringBuilder html, List<List<String>> table) {
        if (table.isEmpty()) return;
        html.append("<div class=\"sheet\"><table>");
        for (List<String> r : table) {
            html.append("<tr>");
            for (String c : r) html.append("<td dir=\"auto\">").append(esc(c).replace("\n", "<br>")).append("</td>");
            html.append("</tr>");
        }
        html.append("</table></div>");
        table.clear();
    }
}
