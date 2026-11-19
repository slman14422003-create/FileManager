package com.fileman.app;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Named styles of a docx (word/styles.xml): the character formatting a paragraph style or a character style
 * gives to its runs, plus alignment, spacing, indent and outline level, with "based on" inheritance resolved.
 */
final class DocxStyles {
    static final class Fmt {
        String name;
        String basedOn;
        Boolean b, i, u, s, caps, smallCaps;
        String color;
        int sz;
        String jc;
        int before = -1, after = -1, left = -1;
        boolean bidi;
        int outline;
        String numId;
        int numLvl;

        void overlay(Fmt o) {
            if (o.b != null) b = o.b;
            if (o.i != null) i = o.i;
            if (o.u != null) u = o.u;
            if (o.s != null) s = o.s;
            if (o.caps != null) caps = o.caps;
            if (o.smallCaps != null) smallCaps = o.smallCaps;
            if (o.color != null) color = o.color;
            if (o.sz > 0) sz = o.sz;
            if (o.jc != null) jc = o.jc;
            if (o.before >= 0) before = o.before;
            if (o.after >= 0) after = o.after;
            if (o.left >= 0) left = o.left;
            if (o.bidi) bidi = true;
            if (o.outline > 0) outline = o.outline;
            if (o.numId != null) {
                numId = o.numId;
                numLvl = o.numLvl;
            }
            if (o.name != null) name = o.name;
        }
    }

    private final Map<String, Fmt> raw = new HashMap<>();
    private final Map<String, Fmt> done = new HashMap<>();

    private DocxStyles() {
    }

    /** The resolved style for an id (null when the id is unknown). */
    Fmt get(String id) {
        if (id == null) return null;
        return resolve(id, 0);
    }

    private Fmt resolve(String id, int depth) {
        Fmt c = done.get(id);
        if (c != null) return c;
        Fmt r = raw.get(id);
        if (r == null) return null;
        Fmt out = new Fmt();
        if (r.basedOn != null && depth < 8) {
            Fmt parent = resolve(r.basedOn, depth + 1);
            if (parent != null) out.overlay(parent);
        }
        out.overlay(r);
        done.put(id, out);
        return out;
    }

    private static boolean on(XmlPullParser p) {
        String v = DocHtml.attr(p, "val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("none"));
    }

    static DocxStyles load(ZipFile z) {
        DocxStyles st = new DocxStyles();
        try {
            byte[] d = DocHtml.entry(z, "word/styles.xml");
            if (d == null) return st;
            XmlPullParser p = DocHtml.parser(d);
            String id = null;
            Fmt cur = null;
            boolean inR = false;
            boolean inP = false;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = DocHtml.local(p.getName());
                    switch (n) {
                        case "style":
                            id = DocHtml.attr(p, "styleId");
                            cur = id == null ? null : new Fmt();
                            inR = false;
                            inP = false;
                            break;
                        case "name":
                            if (cur != null && !inR && !inP) cur.name = DocHtml.attr(p, "val");
                            break;
                        case "basedOn":
                            if (cur != null) cur.basedOn = DocHtml.attr(p, "val");
                            break;
                        case "rPr":
                            inR = true;
                            break;
                        case "pPr":
                            inP = true;
                            break;
                        case "b":
                            if (cur != null && inR) cur.b = on(p);
                            break;
                        case "i":
                            if (cur != null && inR) cur.i = on(p);
                            break;
                        case "u":
                            if (cur != null && inR) cur.u = on(p);
                            break;
                        case "strike":
                            if (cur != null && inR) cur.s = on(p);
                            break;
                        case "caps":
                            if (cur != null && inR) cur.caps = on(p);
                            break;
                        case "smallCaps":
                            if (cur != null && inR) cur.smallCaps = on(p);
                            break;
                        case "color":
                            if (cur != null && inR) {
                                String v = DocHtml.attr(p, "val");
                                if (v != null && v.matches("[0-9A-Fa-f]{6}")) cur.color = v;
                            }
                            break;
                        case "sz":
                            if (cur != null && inR) cur.sz = DocHtml.toInt(DocHtml.attr(p, "val"), 0);
                            break;
                        case "jc":
                            if (cur != null && inP) cur.jc = DocHtml.attr(p, "val");
                            break;
                        case "bidi":
                            if (cur != null && inP) cur.bidi = on(p);
                            break;
                        case "numId":
                            if (cur != null && inP) cur.numId = DocHtml.attr(p, "val");
                            break;
                        case "ilvl":
                            if (cur != null && inP) cur.numLvl = DocHtml.toInt(DocHtml.attr(p, "val"), 0);
                            break;
                        case "outlineLvl":
                            if (cur != null && inP) cur.outline = DocHtml.toInt(DocHtml.attr(p, "val"), -1) + 1;
                            break;
                        case "spacing":
                            if (cur != null && inP && !inR) {
                                cur.before = DocHtml.toInt(DocHtml.attr(p, "before"), -1);
                                cur.after = DocHtml.toInt(DocHtml.attr(p, "after"), -1);
                            }
                            break;
                        case "ind":
                            if (cur != null && inP && !inR) {
                                String l = DocHtml.attr(p, "left");
                                if (l == null) l = DocHtml.attr(p, "start");
                                cur.left = DocHtml.toInt(l, -1);
                            }
                            break;
                        default:
                            break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = DocHtml.local(p.getName());
                    if (n.equals("rPr")) inR = false;
                    else if (n.equals("pPr")) inP = false;
                    else if (n.equals("style") && cur != null && id != null) {
                        st.raw.put(id, cur);
                        cur = null;
                    }
                }
                ev = p.next();
            }
        } catch (Exception ignored) {
        }
        return st;
    }
}
