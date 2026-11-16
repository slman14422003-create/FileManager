package com.fileman.app;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Cell formats of an xlsx workbook (xl/styles.xml): the number format of every cell format, plus the font
 * (bold, italic, underline, strike, colour, size), the background fill and the alignment as inline CSS.
 */
final class XlsxStyles {
    /** Office theme colours in the order Excel numbers them (lt1, dk1, lt2, dk2, accent1..6). */
    private static final int[] THEME = {0xFFFFFF, 0x000000, 0xE7E6E6, 0x44546A, 0x4472C4, 0xED7D31, 0xA5A5A5,
            0xFFC000, 0x5B9BD5, 0x70AD47};
    private static final int[] INDEXED = {0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF,
            0x00FFFF, 0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF};

    private static final class Font {
        boolean b, i, u, s;
        int color = -1;
        double sz = 0;
    }

    private static final class Xf {
        int numFmt, font, fill;
        String h, v;
        boolean wrap;
        int indent;
    }

    final boolean date1904;
    private final List<Xf> xfs = new ArrayList<>();
    private final List<String> css = new ArrayList<>();
    private final List<NumFmt.Compiled> fmts = new ArrayList<>();
    private final List<Boolean> bg = new ArrayList<>();
    private final List<Boolean> alignSet = new ArrayList<>();

    private static final NumFmt.Compiled GENERAL = NumFmt.compile(null);

    private XlsxStyles(boolean date1904) {
        this.date1904 = date1904;
    }

    int size() {
        return xfs.size();
    }

    /** Inline CSS of a cell format ("" when it has none). */
    String css(int xf) {
        return xf >= 0 && xf < css.size() ? css.get(xf) : "";
    }

    NumFmt.Compiled fmt(int xf) {
        return xf >= 0 && xf < fmts.size() ? fmts.get(xf) : GENERAL;
    }

    /** True when the cell format paints a background (so an empty cell is still worth drawing). */
    boolean hasBg(int xf) {
        return xf >= 0 && xf < bg.size() && bg.get(xf);
    }

    /** True when the cell format sets its own horizontal alignment. */
    boolean hasAlign(int xf) {
        return xf >= 0 && xf < alignSet.size() && alignSet.get(xf);
    }

    static XlsxStyles empty(boolean date1904) {
        return new XlsxStyles(date1904);
    }

    private static int tint(int rgb, double t) {
        if (t == 0) return rgb;
        int out = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            double c = (rgb >> sh) & 0xFF;
            c = t < 0 ? c * (1 + t) : c + (255 - c) * t;
            out |= (Math.max(0, Math.min(255, (int) Math.round(c)))) << sh;
        }
        return out;
    }

    private static int color(XmlPullParser p) {
        String rgb = DocHtml.attr(p, "rgb");
        double t = 0;
        try {
            String ts = DocHtml.attr(p, "tint");
            if (ts != null) t = Double.parseDouble(ts);
        } catch (NumberFormatException ignored) {
        }
        if (rgb != null && rgb.length() == 8) {
            try {
                return tint(Integer.parseInt(rgb.substring(2), 16), t);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int theme = DocHtml.toInt(DocHtml.attr(p, "theme"), -1);
        if (theme >= 0 && theme < THEME.length) return tint(THEME[theme], t);
        int idx = DocHtml.toInt(DocHtml.attr(p, "indexed"), -1);
        if (idx >= 0 && idx < INDEXED.length) return INDEXED[idx];
        return -1;
    }

    private static boolean flag(XmlPullParser p) {
        String v = DocHtml.attr(p, "val");
        return v == null || !(v.equals("0") || v.equals("false"));
    }

    private static boolean nearWhite(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 235;
    }

    static XlsxStyles load(ZipFile z, boolean date1904) {
        XlsxStyles st = new XlsxStyles(date1904);
        try {
            byte[] d = DocHtml.entry(z, "xl/styles.xml");
            if (d == null) return st;
            Map<Integer, String> codes = new HashMap<>();
            List<Font> fonts = new ArrayList<>();
            List<Integer> fills = new ArrayList<>();
            XmlPullParser p = DocHtml.parser(d);
            boolean inFonts = false, inFills = false, inXfs = false;
            Font font = null;
            int fillColor = -1;
            boolean solid = false;
            Xf xf = null;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = DocHtml.local(p.getName());
                    switch (n) {
                        case "numFmt":
                            codes.put(DocHtml.toInt(DocHtml.attr(p, "numFmtId"), -1), DocHtml.attr(p, "formatCode"));
                            break;
                        case "fonts":
                            inFonts = true;
                            break;
                        case "fills":
                            inFills = true;
                            break;
                        case "cellXfs":
                            inXfs = true;
                            break;
                        case "font":
                            if (inFonts) font = new Font();
                            break;
                        case "b":
                            if (font != null) font.b = flag(p);
                            break;
                        case "i":
                            if (font != null) font.i = flag(p);
                            break;
                        case "strike":
                            if (font != null) font.s = flag(p);
                            break;
                        case "u":
                            if (font != null) font.u = !"none".equals(DocHtml.attr(p, "val"));
                            break;
                        case "sz":
                            if (font != null) {
                                try {
                                    font.sz = Double.parseDouble(DocHtml.attr(p, "val"));
                                } catch (Exception ignored) {
                                }
                            }
                            break;
                        case "color":
                            if (font != null) font.color = color(p);
                            break;
                        case "fill":
                            if (inFills) {
                                fillColor = -1;
                                solid = false;
                            }
                            break;
                        case "patternFill":
                            if (inFills) solid = "solid".equals(DocHtml.attr(p, "patternType"));
                            break;
                        case "fgColor":
                            if (inFills && solid) fillColor = color(p);
                            break;
                        case "xf":
                            if (inXfs) {
                                xf = new Xf();
                                xf.numFmt = DocHtml.toInt(DocHtml.attr(p, "numFmtId"), 0);
                                xf.font = DocHtml.toInt(DocHtml.attr(p, "fontId"), 0);
                                xf.fill = DocHtml.toInt(DocHtml.attr(p, "fillId"), 0);
                                st.xfs.add(xf);
                            }
                            break;
                        case "alignment":
                            if (xf != null) {
                                xf.h = DocHtml.attr(p, "horizontal");
                                xf.v = DocHtml.attr(p, "vertical");
                                String w = DocHtml.attr(p, "wrapText");
                                xf.wrap = "1".equals(w) || "true".equals(w);
                                xf.indent = DocHtml.toInt(DocHtml.attr(p, "indent"), 0);
                            }
                            break;
                        default:
                            break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = DocHtml.local(p.getName());
                    switch (n) {
                        case "fonts":
                            inFonts = false;
                            break;
                        case "font":
                            if (inFonts && font != null) {
                                fonts.add(font);
                                font = null;
                            }
                            break;
                        case "fills":
                            inFills = false;
                            break;
                        case "fill":
                            if (inFills) fills.add(fillColor);
                            break;
                        case "cellXfs":
                            inXfs = false;
                            xf = null;
                            break;
                        case "xf":
                            if (inXfs) xf = null;
                            break;
                        default:
                            break;
                    }
                }
                ev = p.next();
            }
            for (Xf x : st.xfs) {
                String code = codes.containsKey(x.numFmt) ? codes.get(x.numFmt) : NumFmt.builtin(x.numFmt);
                st.fmts.add(NumFmt.compile(code));
                int fillRgb = x.fill >= 0 && x.fill < fills.size() ? fills.get(x.fill) : -1;
                Font f = x.font >= 0 && x.font < fonts.size() ? fonts.get(x.font) : null;
                StringBuilder c = new StringBuilder();
                if (f != null) {
                    if (f.b) c.append("font-weight:bold;");
                    if (f.i) c.append("font-style:italic;");
                    if (f.u || f.s) {
                        c.append("text-decoration:");
                        if (f.u) c.append("underline ");
                        if (f.s) c.append("line-through");
                        c.append(';');
                    }
                    if (f.color >= 0 && !(fillRgb < 0 && nearWhite(f.color))) {
                        c.append(String.format(Locale.ROOT, "color:#%06x;", f.color));
                    }
                    if (f.sz > 0 && Math.abs(f.sz - 11) >= 2) {
                        c.append("font-size:").append(Math.max(9, Math.min(36, Math.round(13 * f.sz / 11.0)))).append("px;");
                    }
                }
                if (fillRgb >= 0) c.append(String.format(Locale.ROOT, "background-color:#%06x;", fillRgb));
                boolean al = false;
                if (x.h != null) {
                    switch (x.h) {
                        case "center":
                        case "centerContinuous":
                            c.append("text-align:center;");
                            al = true;
                            break;
                        case "right":
                            c.append("text-align:right;");
                            al = true;
                            break;
                        case "left":
                            c.append("text-align:left;");
                            al = true;
                            break;
                        case "justify":
                        case "distributed":
                            c.append("text-align:justify;");
                            al = true;
                            break;
                        default:
                            break;
                    }
                }
                if (x.v != null) {
                    if (x.v.equals("center")) c.append("vertical-align:middle;");
                    else if (x.v.equals("bottom")) c.append("vertical-align:bottom;");
                }
                if (x.wrap) c.append("white-space:normal;min-width:120px;");
                if (x.indent > 0) c.append("padding-inline-start:").append(9 + x.indent * 10).append("px;");
                st.css.add(c.toString());
                st.bg.add(fillRgb >= 0);
                st.alignSet.add(al);
            }
        } catch (Exception ignored) {
        }
        return st;
    }
}
