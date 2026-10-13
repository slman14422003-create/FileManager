package com.fileman.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Text of PowerPoint 97-2003 presentations (.ppt, .pps): the text of every slide, with titles marked. */
final class LegacyPpt {
    static final class Slide {
        final List<String[]> items = new ArrayList<>();   // {kind ("title" | "body"), text}
    }

    private LegacyPpt() {
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    static List<Slide> read(File f) throws IOException {
        try (Ole2 o = new Ole2(f)) {
            byte[] d = o.stream("PowerPoint Document");
            if (d == null) throw new IOException("no PowerPoint Document stream");
            List<Slide> out = new ArrayList<>();
            walk(d, 0, d.length, null, out, new int[]{0}, 0);
            return out;
        }
    }

    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** Walks records; {@code cur} is the slide being filled (null outside slide containers). */
    private static void walk(byte[] d, int start, int end, Slide cur, List<Slide> out, int[] lastTextType, int depth) {
        int p = start;
        while (p + 8 <= end && depth < 24) {
            int verInst = u16(d, p);
            int type = u16(d, p + 2);
            long len = u32(d, p + 4);
            int body = p + 8;
            if (len < 0 || body + len > end) break;
            int ver = verInst & 0xF;
            if (type == 0x03EE && ver == 0xF) {                 // Slide
                Slide s = new Slide();
                walk(d, body, (int) (body + len), s, out, lastTextType, depth + 1);
                out.add(s);
            } else if (type == 0x03F8 || type == 0x03F0) {       // master slides and notes: not part of the reading flow
                // skipped
            } else if (ver == 0xF) {
                walk(d, body, (int) (body + len), cur, out, lastTextType, depth + 1);
            } else if (cur != null) {
                if (type == 0x0F9F && len >= 4) {                // TextHeaderAtom: what kind of text follows
                    lastTextType[0] = (int) u32(d, body);
                } else if (type == 0x0FA0 || type == 0x0FA8) {   // TextCharsAtom / TextBytesAtom
                    String t = type == 0x0FA0
                            ? new String(d, body, (int) (len / 2) * 2, StandardCharsets.UTF_16LE)
                            : new String(d, body, (int) len, CP1252);
                    t = t.replace("\u000B", "\n").replace("\r\n", "\n").replace('\r', '\n').trim();
                    if (!t.isEmpty()) {
                        boolean title = lastTextType[0] == 0 || lastTextType[0] == 6;
                        cur.items.add(new String[]{title ? "title" : "body", t});
                    }
                }
            }
            p = (int) (body + len);
        }
    }

    static String html(File f) throws IOException {
        List<Slide> slides = read(f);
        StringBuilder h = new StringBuilder();
        int n = 0;
        for (Slide s : slides) {
            n++;
            h.append("<div class=\"slide\"><div class=\"sn\">").append(n).append(" / ").append(slides.size()).append("</div>");
            for (String[] it : s.items) {
                String[] paras = it[1].split("\n");
                if (it[0].equals("title")) {
                    h.append("<h3 dir=\"auto\">").append(LegacyDoc.esc(it[1].replace("\n", " "))).append("</h3>");
                } else {
                    for (String ptxt : paras) h.append("<p dir=\"auto\">").append(LegacyDoc.esc(ptxt)).append("</p>");
                }
            }
            h.append("</div>");
        }
        return h.toString();
    }
}
