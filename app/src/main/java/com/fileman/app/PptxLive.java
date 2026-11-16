package com.fileman.app;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Draws every slide of a .pptx at its real size, with every shape, picture, table and chart at its own
 * position, like the Office apps do. The viewer scales the page to the screen width.
 * Pure platform code; DocHtml keeps the flowing text view as a fallback.
 */
final class PptxLive {
    private PptxLive() {
    }

    private static final double PX = 9525.0;      // EMU per CSS pixel
    private static final int MAX_SLIDES = 300;
    private static final int MAX_SHAPES = 4000;
    private static final int NONE = Integer.MIN_VALUE;
    private static final double PAD = 10;

    // ------------------------------------------------------------------ tiny DOM

    static final class Node {
        final String name;
        final Map<String, String> at = new HashMap<>();
        final List<Node> kids = new ArrayList<>();
        final StringBuilder text = new StringBuilder();

        Node(String name) {
            this.name = name;
        }

        String a(String k) {
            return at.get(k);
        }

        Node c(String n) {
            for (Node k : kids) if (k.name.equals(n)) return k;
            return null;
        }

        List<Node> cs(String n) {
            List<Node> r = new ArrayList<>();
            for (Node k : kids) if (k.name.equals(n)) r.add(k);
            return r;
        }

        Node p(String... path) {
            Node n = this;
            for (String s : path) {
                if (n == null) return null;
                n = n.c(s);
            }
            return n;
        }

        double d(String k, double def) {
            String v = at.get(k);
            if (v == null) return def;
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        boolean on(String k) {
            String v = at.get(k);
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        }
    }

    static Node parse(byte[] data) throws Exception {
        XmlPullParser p = DocHtml.parser(data);
        Deque<Node> st = new ArrayDeque<>();
        Node root = null;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                Node n = new Node(DocHtml.local(p.getName()));
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    String name = p.getAttributeName(i);
                    String val = p.getAttributeValue(i);
                    int k = name.indexOf(':');
                    if (k < 0) {
                        n.at.put(name, val);
                    } else {
                        n.at.put(name, val);
                        String l = name.substring(k + 1);
                        if (!n.at.containsKey(l)) n.at.put(l, val);
                    }
                }
                if (st.isEmpty()) root = n;
                else st.peek().kids.add(n);
                st.push(n);
            } else if (ev == XmlPullParser.END_TAG) {
                if (!st.isEmpty()) st.pop();
            } else if (ev == XmlPullParser.TEXT) {
                if (!st.isEmpty()) st.peek().text.append(p.getText());
            }
            ev = p.next();
        }
        return root;
    }

    // ------------------------------------------------------------------ context

    static final class Part {
        final Node root;
        final Map<String, String> rels;
        final Node tree;

        Part(Node root, Map<String, String> rels) {
            this.root = root;
            this.rels = rels;
            Node cs = root.c("cSld");
            this.tree = cs == null ? null : cs.c("spTree");
        }
    }

    static final class Master {
        Part part;
        Map<String, String> scheme = new HashMap<>();
        Map<String, String> clrMap = new HashMap<>();
        String major, minor;
        TS[] def;
    }

    static final class Ctx {
        ZipFile z;
        DocHtml.Budget budget;
        Map<String, String> scheme = new HashMap<>();
        Map<String, String> clrMap = new HashMap<>();
        String major, minor;
        TS[] def;
        Part slide, layout, master;
        int shapes;
    }

    static final class Gx {
        final double sx, sy, tx, ty;

        Gx(double sx, double sy, double tx, double ty) {
            this.sx = sx;
            this.sy = sy;
            this.tx = tx;
            this.ty = ty;
        }
    }

    /** Text properties of one paragraph level; unset fields stay at their "unset" value. */
    static final class TS {
        double sz = -1;
        int b = -1, i = -1, u = -1, st = -1, rtl = -1;
        String color, font, fontCs, algn, bu;
        int marL = NONE, indent = NONE, start = 1;
        double lnPct = -1, lnPts = -1, befPct = -1, befPts = -1, aftPct = -1, aftPts = -1;

        void set(TS o) {
            if (o.sz >= 0) sz = o.sz;
            if (o.b >= 0) b = o.b;
            if (o.i >= 0) i = o.i;
            if (o.u >= 0) u = o.u;
            if (o.st >= 0) st = o.st;
            if (o.rtl >= 0) rtl = o.rtl;
            if (o.color != null) color = o.color;
            if (o.font != null) font = o.font;
            if (o.fontCs != null) fontCs = o.fontCs;
            if (o.algn != null) algn = o.algn;
            if (o.bu != null) {
                bu = o.bu;
                start = o.start;
            }
            if (o.marL != NONE) marL = o.marL;
            if (o.indent != NONE) indent = o.indent;
            if (o.lnPct >= 0) {
                lnPct = o.lnPct;
                lnPts = -1;
            }
            if (o.lnPts >= 0) {
                lnPts = o.lnPts;
                lnPct = -1;
            }
            if (o.befPct >= 0) {
                befPct = o.befPct;
                befPts = -1;
            }
            if (o.befPts >= 0) {
                befPts = o.befPts;
                befPct = -1;
            }
            if (o.aftPct >= 0) {
                aftPct = o.aftPct;
                aftPts = -1;
            }
            if (o.aftPts >= 0) {
                aftPts = o.aftPts;
                aftPct = -1;
            }
        }

        TS over(TS o) {
            TS t = new TS();
            t.set(this);
            t.set(o);
            return t;
        }
    }

    // ------------------------------------------------------------------ entry point

    static String render(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            byte[] pres = DocHtml.entry(z, "ppt/presentation.xml");
            if (pres == null) return null;
            Node pr = parse(pres);
            Node sz = pr.c("sldSz");
            double w = (sz == null ? 9144000 : sz.d("cx", 9144000)) / PX;
            double h = (sz == null ? 6858000 : sz.d("cy", 6858000)) / PX;
            if (w < 50 || h < 50) return null;
            Map<String, String> prels = DocHtml.rels(z, "ppt/_rels/presentation.xml.rels", "ppt/");
            List<String> slides = new ArrayList<>();
            Node lst = pr.c("sldIdLst");
            if (lst != null) {
                for (Node s : lst.cs("sldId")) {
                    String part = prels.get(s.a("r:id"));
                    if (part != null && z.getEntry(part) != null && !slides.contains(part)) slides.add(part);
                }
            }
            if (slides.isEmpty()) return null;

            Ctx cx = new Ctx();
            cx.z = z;
            cx.budget = new DocHtml.Budget();
            Map<String, Part> partCache = new HashMap<>();
            Map<String, Master> masters = new HashMap<>();
            StringBuilder body = new StringBuilder();
            int count = 0;
            for (String name : slides) {
                if (++count > MAX_SLIDES) break;
                try {
                    slideHtml(z, name, pr, w, h, cx, partCache, masters, body);
                } catch (Exception e) {
                    body.append("<div class=\"sl\" style=\"width:").append(f(w)).append("px;height:").append(f(h))
                            .append("px\"></div>");
                }
            }
            double vw = w + 2 * PAD;
            return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=" + (int) Math.ceil(vw) + "\">"
                    + "<style>" + css() + "</style></head><body>" + body + "</body></html>";
        }
    }

    private static String css() {
        return "html{background:" + DocHtml.canvas + "}"
                + "body{margin:0;padding:" + (int) PAD + "px;background:" + DocHtml.canvas
                + ";font-family:sans-serif;-webkit-text-size-adjust:none;text-size-adjust:none}"
                + "*{box-sizing:border-box}"
                + ".sl{position:relative;overflow:hidden;margin:0 auto 14px;background:#fff;"
                + "box-shadow:0 0 0 1px " + DocHtml.ring + "}"
                + ".s{position:absolute}.g{position:absolute;left:0;top:0;right:0;bottom:0}"
                + ".t{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;flex-direction:column}"
                + ".t p{margin:0;white-space:pre-wrap;overflow-wrap:break-word;word-wrap:break-word}"
                + ".bu{display:inline-block}svg{position:absolute;overflow:visible}"
                + "table{border-collapse:collapse;table-layout:fixed}td{overflow:hidden;vertical-align:top}"
                + "img{-webkit-user-drag:none}";
    }

    private static String relOf(Map<String, String> rels, String contains) {
        for (String v : rels.values()) if (v != null && v.contains(contains)) return v;
        return null;
    }

    private static Part loadPart(ZipFile z, String path, Map<String, Part> cache) throws Exception {
        if (path == null) return null;
        Part p = cache.get(path);
        if (p != null) return p;
        byte[] d = DocHtml.entry(z, path);
        if (d == null) return null;
        int cut = path.lastIndexOf('/') + 1;
        String relName = path.substring(0, cut) + "_rels/" + path.substring(cut) + ".rels";
        p = new Part(parse(d), DocHtml.rels(z, relName, path.substring(0, cut)));
        cache.put(path, p);
        return p;
    }

    private static Master loadMaster(ZipFile z, String path, Node pres, Ctx cx, Map<String, Part> cache,
                                     Map<String, Master> masters) throws Exception {
        Master m = masters.get(path);
        if (m != null) return m;
        m = new Master();
        m.part = loadPart(z, path, cache);
        if (m.part == null) return null;
        String theme = relOf(m.part.rels, "theme/");
        if (theme != null) {
            byte[] td = DocHtml.entry(z, theme);
            if (td != null) {
                Node t = parse(td);
                Node cs = t.p("themeElements", "clrScheme");
                if (cs != null) {
                    for (Node k : cs.kids) {
                        Node v = k.kids.isEmpty() ? null : k.kids.get(0);
                        if (v == null) continue;
                        String hex = v.a("val");
                        if (v.name.equals("sysClr")) hex = v.a("lastClr") != null ? v.a("lastClr")
                                : ("window".equals(v.a("val")) ? "FFFFFF" : "000000");
                        if (hex != null && hex.matches("[0-9A-Fa-f]{6}")) m.scheme.put(k.name, hex);
                    }
                }
                Node fs = t.p("themeElements", "fontScheme");
                if (fs != null) {
                    Node mj = fs.p("majorFont", "latin");
                    Node mn = fs.p("minorFont", "latin");
                    if (mj != null) m.major = mj.a("typeface");
                    if (mn != null) m.minor = mn.a("typeface");
                }
            }
        }
        Node cm = m.part.root.c("clrMap");
        if (cm != null) {
            for (Map.Entry<String, String> e : cm.at.entrySet()) m.clrMap.put(e.getKey(), e.getValue());
        }
        // defaults: the presentation wide text style, evaluated with this master's colours
        Map<String, String> sv = cx.scheme;
        Map<String, String> sc = cx.clrMap;
        String smj = cx.major, smn = cx.minor;
        cx.scheme = m.scheme;
        cx.clrMap = m.clrMap;
        cx.major = m.major;
        cx.minor = m.minor;
        m.def = levels(pres.c("defaultTextStyle"), cx);
        cx.scheme = sv;
        cx.clrMap = sc;
        cx.major = smj;
        cx.minor = smn;
        masters.put(path, m);
        return m;
    }

    private static void slideHtml(ZipFile z, String name, Node pres, double w, double h, Ctx cx,
                                  Map<String, Part> cache, Map<String, Master> masters, StringBuilder out)
            throws Exception {
        byte[] d = DocHtml.entry(z, name);
        if (d == null) throw new Exception("missing slide");
        int cut = name.lastIndexOf('/') + 1;
        String relName = name.substring(0, cut) + "_rels/" + name.substring(cut) + ".rels";
        Part slide = new Part(parse(d), DocHtml.rels(z, relName, name.substring(0, cut)));
        Part layout = loadPart(z, relOf(slide.rels, "slideLayouts/"), cache);
        String mp = layout == null ? null : relOf(layout.rels, "slideMasters/");
        Master m = mp == null ? null : loadMaster(z, mp, pres, cx, cache, masters);
        cx.slide = slide;
        cx.layout = layout;
        cx.master = m == null ? null : m.part;
        if (m != null) {
            cx.scheme = m.scheme;
            cx.clrMap = m.clrMap;
            cx.major = m.major;
            cx.minor = m.minor;
            cx.def = m.def;
        } else {
            cx.scheme = new HashMap<>();
            cx.clrMap = new HashMap<>();
            cx.major = null;
            cx.minor = null;
            cx.def = levels(pres.c("defaultTextStyle"), cx);
        }
        cx.shapes = 0;

        String bg = bgOf(slide, cx);
        if (bg == null && layout != null) bg = bgOf(layout, cx);
        if (bg == null && cx.master != null) bg = bgOf(cx.master, cx);
        if (bg == null || bg.isEmpty()) bg = "#fff";
        out.append("<div class=\"sl\" style=\"width:").append(f(w)).append("px;height:").append(f(h))
                .append("px;background:").append(bg).append("\">");
        Gx id = new Gx(1, 1, 0, 0);
        boolean showM = !"0".equals(slide.root.a("showMasterSp"));
        if (showM && layout != null && "0".equals(layout.root.a("showMasterSp"))) showM = false;
        if (showM && cx.master != null && cx.master.tree != null) kids(cx.master.tree.kids, cx.master, id, cx, out, true);
        if (layout != null && layout.tree != null && !"0".equals(slide.root.a("showMasterSp"))) {
            kids(layout.tree.kids, layout, id, cx, out, true);
        }
        if (slide.tree != null) kids(slide.tree.kids, slide, id, cx, out, false);
        out.append("</div>");
    }

    private static String bgOf(Part part, Ctx cx) {
        Node bg = part.root.p("cSld", "bg");
        if (bg == null) return null;
        Node pp = bg.c("bgPr");
        if (pp != null) return fill(pp, part, cx);
        Node ref = bg.c("bgRef");
        if (ref != null) return clr(ref, cx);
        return null;
    }

    // ------------------------------------------------------------------ shape tree

    private static void kids(List<Node> list, Part part, Gx g, Ctx cx, StringBuilder out, boolean skipPh) {
        for (Node n : list) {
            if (++cx.shapes > MAX_SHAPES) return;
            switch (n.name) {
                case "sp":
                    sp(n, part, g, cx, out, skipPh);
                    break;
                case "pic":
                    if (!skipPh || n.p("nvPicPr", "nvPr", "ph") == null) pic(n, part, g, cx, out);
                    break;
                case "graphicFrame":
                    if (!skipPh) frame(n, part, g, cx, out);
                    break;
                case "cxnSp":
                    cxn(n, g, cx, out);
                    break;
                case "grpSp":
                    group(n, part, g, cx, out, skipPh);
                    break;
                case "AlternateContent": {
                    Node pick = n.c("Fallback");
                    if (pick == null) pick = n.c("Choice");
                    if (pick != null) kids(pick.kids, part, g, cx, out, skipPh);
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static void group(Node n, Part part, Gx g, Ctx cx, StringBuilder out, boolean skipPh) {
        Node xf = n.p("grpSpPr", "xfrm");
        if (xf == null) {
            kids(n.kids, part, g, cx, out, skipPh);
            return;
        }
        Node off = xf.c("off"), ext = xf.c("ext"), co = xf.c("chOff"), ce = xf.c("chExt");
        if (off == null || ext == null || co == null || ce == null) {
            kids(n.kids, part, g, cx, out, skipPh);
            return;
        }
        double rx = ce.d("cx", 0) == 0 ? 1 : ext.d("cx", 0) / ce.d("cx", 1);
        double ry = ce.d("cy", 0) == 0 ? 1 : ext.d("cy", 0) / ce.d("cy", 1);
        Gx ng = new Gx(rx * g.sx, ry * g.sy,
                g.sx * (off.d("x", 0) - co.d("x", 0) * rx) + g.tx,
                g.sy * (off.d("y", 0) - co.d("y", 0) * ry) + g.ty);
        kids(n.kids, part, ng, cx, out, skipPh);
    }

    /** Placeholder of a layout or master that a slide placeholder takes its position and style from. */
    private static Node findPh(Node tree, String type, String idx) {
        if (tree == null) return null;
        String nt = normType(type);
        Node byType = null, byIdx = null;
        for (Node s : tree.kids) {
            if (!s.name.equals("sp")) continue;
            Node ph = s.p("nvSpPr", "nvPr", "ph");
            if (ph == null) continue;
            boolean ti = normType(ph.a("type")).equals(nt);
            boolean ii = idx != null && idx.equals(ph.a("idx"));
            if (ti && ii) return s;
            if (ti && byType == null) byType = s;
            if (ii && byIdx == null) byIdx = s;
        }
        return byType != null ? byType : byIdx;
    }

    private static String normType(String t) {
        if (t == null) return "body";
        switch (t) {
            case "ctrTitle":
                return "title";
            case "subTitle":
            case "obj":
                return "body";
            default:
                return t;
        }
    }

    private static double[] box(Node xf, Gx g) {
        Node off = xf.c("off"), ext = xf.c("ext");
        if (off == null || ext == null) return null;
        return new double[]{
                (off.d("x", 0) * g.sx + g.tx) / PX, (off.d("y", 0) * g.sy + g.ty) / PX,
                ext.d("cx", 0) * g.sx / PX, ext.d("cy", 0) * g.sy / PX};
    }

    private static String pos(double[] b, double rot) {
        StringBuilder s = new StringBuilder("left:").append(f(b[0])).append("px;top:").append(f(b[1]))
                .append("px;width:").append(f(b[2])).append("px;height:").append(f(b[3])).append("px");
        if (Math.abs(rot) > 0.01) s.append(";transform:rotate(").append(f(rot)).append("deg)");
        return s.toString();
    }

    // ------------------------------------------------------------------ autoshapes and text boxes

    private static final Map<String, double[]> POLY = new HashMap<>();

    private static void poly(String n, double... v) {
        POLY.put(n, v);
    }

    static {
        poly("triangle", 50, 0, 100, 100, 0, 100);
        poly("rtTriangle", 0, 0, 0, 100, 100, 100);
        poly("diamond", 50, 0, 100, 50, 50, 100, 0, 50);
        poly("flowChartDecision", 50, 0, 100, 50, 50, 100, 0, 50);
        poly("parallelogram", 25, 0, 100, 0, 75, 100, 0, 100);
        poly("trapezoid", 25, 0, 75, 0, 100, 100, 0, 100);
        poly("hexagon", 25, 0, 75, 0, 100, 50, 75, 100, 25, 100, 0, 50);
        poly("homePlate", 0, 0, 75, 0, 100, 50, 75, 100, 0, 100);
        poly("pentagon", 50, 0, 100, 38, 82, 100, 18, 100, 0, 38);
        poly("chevron", 0, 0, 75, 0, 100, 50, 75, 100, 0, 100, 25, 50);
        poly("rightArrow", 0, 25, 60, 25, 60, 0, 100, 50, 60, 100, 60, 75, 0, 75);
        poly("leftArrow", 100, 25, 40, 25, 40, 0, 0, 50, 40, 100, 40, 75, 100, 75);
        poly("upArrow", 25, 100, 25, 40, 0, 40, 50, 0, 100, 40, 75, 40, 75, 100);
        poly("downArrow", 25, 0, 75, 0, 75, 60, 100, 60, 50, 100, 0, 60, 25, 60);
        poly("octagon", 29, 0, 71, 0, 100, 29, 100, 71, 71, 100, 29, 100, 0, 71, 0, 29);
        poly("star5", 50, 0, 61, 35, 98, 35, 68, 57, 79, 91, 50, 70, 21, 91, 32, 57, 2, 35, 39, 35);
        poly("plus", 33, 0, 67, 0, 67, 33, 100, 33, 100, 67, 67, 67, 67, 100, 33, 100, 33, 67, 0, 67, 0, 33, 33, 33);
        poly("mathPlus", 33, 0, 67, 0, 67, 33, 100, 33, 100, 67, 67, 67, 67, 100, 33, 100, 33, 67, 0, 67, 0, 33, 33, 33);
    }

    private static boolean isColor(String v) {
        return v != null && (v.startsWith("#") || v.startsWith("rgba"));
    }

    /** One solid colour out of any fill value (first stop of a gradient); null for images and no fill. */
    private static String solidOf(String fillCss) {
        if (fillCss == null) return null;
        if (isColor(fillCss)) return fillCss;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(#[0-9a-fA-F]{6}|rgba\\([^)]*\\))").matcher(fillCss);
        return m.find() ? m.group(1) : null;
    }

    private static void sp(Node sp, Part part, Gx g, Ctx cx, StringBuilder out, boolean skipPh) {
        Node ph = sp.p("nvSpPr", "nvPr", "ph");
        if (ph != null && skipPh) return;
        Node spPr = sp.c("spPr");
        Node lph = null, mph = null;
        if (ph != null) {
            String type = ph.a("type"), idx = ph.a("idx");
            if (part == cx.slide && cx.layout != null) lph = findPh(cx.layout.tree, type, idx);
            if (cx.master != null) {
                String mt = type;
                if (mt == null && lph != null) mt = lph.p("nvSpPr", "nvPr", "ph").a("type");
                mph = findPh(cx.master.tree, mt, null);
            }
        }
        Node xf = spPr == null ? null : spPr.c("xfrm");
        if (xf == null && lph != null) xf = lph.p("spPr", "xfrm");
        if (xf == null && mph != null) xf = mph.p("spPr", "xfrm");
        if (xf == null) return;
        double[] b = box(xf, g);
        if (b == null) return;
        double rot = xf.d("rot", 0) / 60000.0;
        boolean fh = xf.on("flipH"), fv = xf.on("flipV");

        Node geom = spPr == null ? null : spPr.c("prstGeom");
        Node cust = spPr == null ? null : spPr.c("custGeom");
        if (geom == null && cust == null) {
            if (lph != null) geom = lph.p("spPr", "prstGeom");
            if (geom == null && mph != null) geom = mph.p("spPr", "prstGeom");
        }
        String prst = geom == null ? (cust == null ? "rect" : "") : geom.a("prst");
        if (prst == null) prst = "rect";
        Node style = sp.c("style");
        Node ln = spPr == null ? null : spPr.c("ln");
        if (ln == null && lph != null) ln = lph.p("spPr", "ln");
        if (ln == null && mph != null) ln = mph.p("spPr", "ln");

        if (prst.equals("line") || prst.equals("straightConnector1") || prst.equals("bentConnector3")) {
            line(b, rot, fh, fv, ln, style, cx, out);
            return;
        }

        String fl = fill(spPr, part, cx);
        if (fl == null && lph != null) fl = fill(lph.c("spPr"), cx.layout, cx);
        if (fl == null && mph != null) fl = fill(mph.c("spPr"), cx.master, cx);
        if (fl == null && style != null) {
            Node fr = style.c("fillRef");
            if (fr != null && fr.d("idx", 0) > 0) fl = clr(fr, cx);
        }
        if (fl != null && fl.isEmpty()) fl = null;
        Line L = lineOf(ln, style, cx);

        Node tx = sp.c("txBody");
        boolean text = tx != null && hasText(tx);
        if (fl == null && L == null && !text) return;

        out.append("<div class=\"s\" style=\"").append(pos(b, rot)).append("\">");
        if (fl != null || L != null) {
            String flip = (fh || fv) ? "transform:scale(" + (fh ? -1 : 1) + "," + (fv ? -1 : 1) + ");" : "";
            if (cust != null) {
                custom(cust, b, fl, L, flip, out);
            } else if (POLY.containsKey(prst)) {
                double[] pts = POLY.get(prst);
                StringBuilder pl = new StringBuilder();
                for (int i = 0; i + 1 < pts.length; i += 2) {
                    if (i > 0) pl.append(',');
                    pl.append(f(pts[i])).append("% ").append(f(pts[i + 1])).append('%');
                }
                if (fl != null) {
                    out.append("<div class=\"g\" style=\"").append(flip).append("background:").append(fl)
                            .append(";-webkit-clip-path:polygon(").append(pl).append(");clip-path:polygon(")
                            .append(pl).append(")\"></div>");
                }
                if (L != null) {
                    StringBuilder pp = new StringBuilder();
                    for (int i = 0; i + 1 < pts.length; i += 2) {
                        pp.append(f(pts[i])).append(',').append(f(pts[i + 1])).append(' ');
                    }
                    out.append("<svg class=\"g\" viewBox=\"0 0 100 100\" preserveAspectRatio=\"none\" style=\"width:100%;height:100%;")
                            .append(flip).append("\"><polygon points=\"").append(pp).append("\" fill=\"none\" stroke=\"")
                            .append(L.color).append("\" stroke-width=\"").append(f(L.w))
                            .append("\" vector-effect=\"non-scaling-stroke\"")
                            .append(L.dash == null ? "" : " stroke-dasharray=\"" + L.dash + "\"").append("/></svg>");
                }
            } else {
                StringBuilder st = new StringBuilder();
                if (fl != null) st.append("background:").append(fl).append(';');
                if (L != null) {
                    st.append("border:").append(f(L.w)).append("px ")
                            .append(L.dash == null ? "solid " : (L.dash.startsWith("1") ? "dotted " : "dashed "))
                            .append(L.color).append(';');
                }
                if (prst.equals("ellipse") || prst.equals("flowChartConnector")) {
                    st.append("border-radius:50%;");
                } else if (prst.equals("roundRect") || prst.equals("flowChartTerminator")
                        || prst.startsWith("round") || prst.startsWith("snipRound")) {
                    double adj = 16667;
                    if (geom != null && geom.c("avLst") != null) {
                        for (Node gd : geom.c("avLst").cs("gd")) {
                            String fm = gd.a("fmla");
                            if ("adj".equals(gd.a("name")) || "adj1".equals(gd.a("name"))) {
                                if (fm != null && fm.startsWith("val ")) {
                                    try {
                                        adj = Double.parseDouble(fm.substring(4).trim());
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                            }
                        }
                    }
                    if (prst.equals("flowChartTerminator")) adj = 50000;
                    st.append("border-radius:").append(f(Math.min(b[2], b[3]) * Math.min(adj, 50000) / 100000.0)).append("px;");
                }
                out.append("<div class=\"g\" style=\"").append(st).append("\"></div>");
            }
        }
        if (text) text(sp, tx, ph, lph, mph, style, cx, out);
        out.append("</div>");
    }

    private static boolean hasText(Node tx) {
        for (Node p : tx.cs("p")) {
            for (Node r : p.kids) {
                if ((r.name.equals("r") || r.name.equals("fld")) && r.c("t") != null && r.c("t").text.length() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void custom(Node cust, double[] b, String fl, Line L, String flip, StringBuilder out) {
        Node lst = cust.c("pathLst");
        if (lst == null) return;
        String fc = solidOf(fl);
        for (Node pa : lst.cs("path")) {
            double pw = pa.d("w", b[2] * PX), ph = pa.d("h", b[3] * PX);
            if (pw <= 0) pw = b[2] * PX;
            if (ph <= 0) ph = b[3] * PX;
            StringBuilder d = new StringBuilder();
            for (Node c : pa.kids) {
                switch (c.name) {
                    case "moveTo":
                        d.append('M').append(pt(c, 0));
                        break;
                    case "lnTo":
                        d.append('L').append(pt(c, 0));
                        break;
                    case "cubicBezTo":
                        d.append('C').append(pt(c, 0)).append(' ').append(pt(c, 1)).append(' ').append(pt(c, 2));
                        break;
                    case "quadBezTo":
                        d.append('Q').append(pt(c, 0)).append(' ').append(pt(c, 1));
                        break;
                    case "close":
                        d.append('Z');
                        break;
                    default:
                        break;
                }
            }
            if (d.length() == 0) continue;
            boolean noFill = "none".equals(pa.a("fill")) || fc == null;
            boolean noLine = "0".equals(pa.a("stroke")) || L == null;
            out.append("<svg class=\"g\" viewBox=\"0 0 ").append(f(pw)).append(' ').append(f(ph))
                    .append("\" preserveAspectRatio=\"none\" style=\"width:100%;height:100%;").append(flip).append("\"><path d=\"")
                    .append(d).append("\" fill=\"").append(noFill ? "none" : fc).append("\"");
            if (!noLine) {
                out.append(" stroke=\"").append(L.color).append("\" stroke-width=\"").append(f(L.w))
                        .append("\" vector-effect=\"non-scaling-stroke\"");
            }
            out.append("/></svg>");
        }
    }

    private static String pt(Node c, int i) {
        int k = 0;
        for (Node p : c.kids) {
            if (!p.name.equals("pt")) continue;
            if (k++ == i) return p.a("x") + "," + p.a("y");
        }
        return "0,0";
    }

    private static void cxn(Node n, Gx g, Ctx cx, StringBuilder out) {
        Node spPr = n.c("spPr");
        Node xf = spPr == null ? null : spPr.c("xfrm");
        if (xf == null) return;
        double[] b = box(xf, g);
        if (b == null) return;
        line(b, xf.d("rot", 0) / 60000.0, xf.on("flipH"), xf.on("flipV"), spPr.c("ln"), n.c("style"), cx, out);
    }

    private static void line(double[] b, double rot, boolean fh, boolean fv, Node ln, Node style, Ctx cx,
                             StringBuilder out) {
        Line L = lineOf(ln, style, cx);
        if (L == null) return;
        double w = Math.max(b[2], 0.5), h = Math.max(b[3], 0.5);
        double x1 = fh ? w : 0, x2 = fh ? 0 : w, y1 = fv ? h : 0, y2 = fv ? 0 : h;
        out.append("<div class=\"s\" style=\"").append(pos(new double[]{b[0], b[1], w, h}, rot)).append("\">")
                .append("<svg viewBox=\"0 0 ").append(f(w)).append(' ').append(f(h)).append("\" style=\"left:0;top:0;width:")
                .append(f(w)).append("px;height:").append(f(h)).append("px\"><line x1=\"").append(f(x1))
                .append("\" y1=\"").append(f(y1)).append("\" x2=\"").append(f(x2)).append("\" y2=\"").append(f(y2))
                .append("\" stroke=\"").append(L.color).append("\" stroke-width=\"").append(f(L.w)).append('"')
                .append(L.dash == null ? "" : " stroke-dasharray=\"" + L.dash + "\"").append("/></svg></div>");
    }

    // ------------------------------------------------------------------ fills, lines, colours

    static final class Line {
        String color;
        double w = -1;
        String dash;
    }

    private static Line lineOf(Node ln, Node style, Ctx cx) {
        Line L = new Line();
        if (ln != null) {
            if (ln.c("noFill") != null) return null;
            Node sf = ln.c("solidFill");
            if (sf != null) L.color = clr(sf, cx);
            Node gf = ln.c("gradFill");
            if (L.color == null && gf != null) L.color = solidOf(grad(gf, cx));
            if (ln.a("w") != null) L.w = ln.d("w", 12700) / PX;
            Node pd = ln.c("prstDash");
            if (pd != null) {
                String v = pd.a("val");
                if (v != null && !v.equals("solid")) {
                    L.dash = v.contains("sysDot") || v.equals("dot") ? "1.5 2.5" : (v.startsWith("lg") ? "12 4" : "6 3");
                }
            }
        }
        if (L.color == null && style != null) {
            Node lr = style.c("lnRef");
            if (lr != null && lr.d("idx", 0) > 0) {
                L.color = clr(lr, cx);
                if (L.w < 0) L.w = 1.33;
            }
        }
        if (L.color == null) return null;
        if (L.w < 0) L.w = 1.33;
        if (L.w < 1) L.w = 1;
        return L;
    }

    /** CSS background for the fill of a shape-properties node: null = not specified, "" = no fill. */
    private static String fill(Node spPr, Part part, Ctx cx) {
        if (spPr == null) return null;
        for (Node k : spPr.kids) {
            switch (k.name) {
                case "noFill":
                    return "";
                case "solidFill":
                    return clr(k, cx);
                case "gradFill":
                    return grad(k, cx);
                case "blipFill":
                    return blipBg(k, part, cx);
                case "pattFill": {
                    Node fg = k.c("fgClr");
                    return fg == null ? null : clr(fg, cx);
                }
                default:
                    break;
            }
        }
        return null;
    }

    private static String grad(Node gf, Ctx cx) {
        Node lst = gf.c("gsLst");
        if (lst == null) return null;
        List<String> stops = new ArrayList<>();
        for (Node gs : lst.cs("gs")) {
            String c = clr(gs, cx);
            if (c != null) stops.add(c + " " + f(gs.d("pos", 0) / 1000.0) + "%");
        }
        if (stops.isEmpty()) return null;
        if (stops.size() == 1) return stops.get(0).substring(0, stops.get(0).indexOf(' '));
        StringBuilder sb = new StringBuilder();
        Node lin = gf.c("lin");
        if (gf.c("path") != null) {
            sb.append("radial-gradient(circle at center");
        } else {
            sb.append("linear-gradient(").append(f((lin == null ? 0 : lin.d("ang", 0) / 60000.0) + 90)).append("deg");
        }
        for (String s : stops) sb.append(',').append(s);
        return sb.append(')').toString();
    }

    private static String blipBg(Node bf, Part part, Ctx cx) {
        Node blip = bf.c("blip");
        if (blip == null || part == null) return null;
        String uri = DocHtml.image(cx.z, part.rels.get(blip.a("embed")), cx.budget);
        if (uri == null) return null;
        return "url('" + uri + "') center/100% 100% no-repeat";
    }

    /** Colour of the first colour element inside holder, as a CSS colour; null when there is none. */
    static String clr(Node holder, Ctx cx) {
        for (Node k : holder.kids) {
            double[] c = base(k, cx);
            if (c == null) continue;
            double alpha = 1;
            for (Node m : k.kids) {
                double v = m.d("val", 100000) / 100000.0;
                switch (m.name) {
                    case "lumMod":
                    case "lumOff":
                    case "satMod":
                    case "satOff": {
                        double[] hsl = toHsl(c);
                        if (m.name.equals("lumMod")) hsl[2] *= v;
                        else if (m.name.equals("lumOff")) hsl[2] += v;
                        else if (m.name.equals("satMod")) hsl[1] *= v;
                        else hsl[1] += v;
                        hsl[1] = Math.max(0, Math.min(1, hsl[1]));
                        hsl[2] = Math.max(0, Math.min(1, hsl[2]));
                        c = fromHsl(hsl);
                        break;
                    }
                    case "tint":
                        for (int i = 0; i < 3; i++) c[i] = 255 - (255 - c[i]) * v;
                        break;
                    case "shade":
                        for (int i = 0; i < 3; i++) c[i] = c[i] * v;
                        break;
                    case "alpha":
                        alpha = v;
                        break;
                    case "alphaMod":
                        alpha *= v;
                        break;
                    default:
                        break;
                }
            }
            int r = clamp(c[0]), gg = clamp(c[1]), bb = clamp(c[2]);
            if (alpha >= 0.999) return String.format(Locale.ROOT, "#%02x%02x%02x", r, gg, bb);
            return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.2f)", r, gg, bb, Math.max(0, alpha));
        }
        return null;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static double[] base(Node k, Ctx cx) {
        String hex = null;
        switch (k.name) {
            case "srgbClr":
                hex = k.a("val");
                break;
            case "sysClr":
                hex = k.a("lastClr");
                if (hex == null) hex = "window".equals(k.a("val")) ? "FFFFFF" : "000000";
                break;
            case "prstClr": {
                String v = k.a("val");
                hex = "white".equals(v) ? "FFFFFF" : ("red".equals(v) ? "FF0000" : ("gray".equals(v) ? "808080" : "000000"));
                break;
            }
            case "schemeClr": {
                String v = k.a("val");
                if (v == null) return null;
                String mapped = cx.clrMap.get(v);
                if (mapped == null) {
                    switch (v) {
                        case "tx1":
                            mapped = "dk1";
                            break;
                        case "bg1":
                            mapped = "lt1";
                            break;
                        case "tx2":
                            mapped = "dk2";
                            break;
                        case "bg2":
                            mapped = "lt2";
                            break;
                        default:
                            mapped = v;
                            break;
                    }
                }
                hex = cx.scheme.get(mapped);
                if (hex == null) hex = cx.scheme.get(v);
                if (hex == null) hex = "phClr".equals(v) ? "000000" : "000000";
                break;
            }
            default:
                return null;
        }
        if (hex == null || !hex.matches("[0-9A-Fa-f]{6}")) return null;
        int v = Integer.parseInt(hex, 16);
        return new double[]{(v >> 16) & 255, (v >> 8) & 255, v & 255};
    }

    private static double[] toHsl(double[] c) {
        double r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double h = 0, s, l = (max + min) / 2;
        double d = max - min;
        if (d == 0) {
            s = 0;
        } else {
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 0.5) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    private static double[] fromHsl(double[] hsl) {
        double h = hsl[0], s = hsl[1], l = hsl[2];
        if (s == 0) return new double[]{l * 255, l * 255, l * 255};
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        return new double[]{hue(p, q, h + 1.0 / 3) * 255, hue(p, q, h) * 255, hue(p, q, h - 1.0 / 3) * 255};
    }

    // ------------------------------------------------------------------ text styles

    private static String themeFont(String face, Ctx cx) {
        if (face == null || face.isEmpty()) return null;
        if (face.equals("+mj-lt")) return cx.major;
        if (face.equals("+mn-lt")) return cx.minor;
        if (face.startsWith("+")) return null;
        return face;
    }

    private static void readR(TS t, Node r, Ctx cx) {
        if (r == null) return;
        if (r.a("sz") != null) t.sz = r.d("sz", 1800) / 100.0;
        if (r.a("b") != null) t.b = r.on("b") ? 1 : 0;
        if (r.a("i") != null) t.i = r.on("i") ? 1 : 0;
        String u = r.a("u");
        if (u != null) t.u = "none".equals(u) ? 0 : 1;
        String s = r.a("strike");
        if (s != null) t.st = "noStrike".equals(s) ? 0 : 1;
        Node sf = r.c("solidFill");
        if (sf != null) {
            String c = clr(sf, cx);
            if (c != null) t.color = c;
        }
        Node lat = r.c("latin");
        if (lat != null) {
            String ft = themeFont(lat.a("typeface"), cx);
            if (ft != null) t.font = ft;
        }
        Node cs = r.c("cs");
        if (cs != null) {
            String ft = themeFont(cs.a("typeface"), cx);
            if (ft != null) t.fontCs = ft;
        }
    }

    private static TS readPPr(Node n, Ctx cx, boolean withDef) {
        TS t = new TS();
        if (n == null) return t;
        if (n.a("algn") != null) t.algn = n.a("algn");
        if (n.a("marL") != null) t.marL = (int) n.d("marL", 0);
        if (n.a("indent") != null) t.indent = (int) n.d("indent", 0);
        if (n.a("rtl") != null) t.rtl = n.on("rtl") ? 1 : 0;
        Node ls = n.c("lnSpc");
        if (ls != null) {
            Node s = ls.c("spcPct");
            if (s != null) t.lnPct = s.d("val", 100000) / 1000.0;
            s = ls.c("spcPts");
            if (s != null) t.lnPts = s.d("val", 0) / 100.0;
        }
        Node sb = n.c("spcBef");
        if (sb != null) {
            Node s = sb.c("spcPct");
            if (s != null) t.befPct = s.d("val", 0) / 1000.0;
            s = sb.c("spcPts");
            if (s != null) t.befPts = s.d("val", 0) / 100.0;
        }
        Node sa = n.c("spcAft");
        if (sa != null) {
            Node s = sa.c("spcPct");
            if (s != null) t.aftPct = s.d("val", 0) / 1000.0;
            s = sa.c("spcPts");
            if (s != null) t.aftPts = s.d("val", 0) / 100.0;
        }
        if (n.c("buNone") != null) t.bu = "";
        Node bc = n.c("buChar");
        if (bc != null && bc.a("char") != null) t.bu = "c:" + bc.a("char");
        Node ba = n.c("buAutoNum");
        if (ba != null) {
            t.bu = "n:" + ba.a("type");
            t.start = (int) ba.d("startAt", 1);
        }
        if (withDef) readR(t, n.c("defRPr"), cx);
        return t;
    }

    private static TS[] levels(Node lst, Ctx cx) {
        TS[] lv = new TS[9];
        for (int i = 0; i < 9; i++) lv[i] = new TS();
        overlay(lv, lst, cx);
        return lv;
    }

    private static void overlay(TS[] lv, Node lst, Ctx cx) {
        if (lst == null) return;
        for (int i = 0; i < 9; i++) {
            Node pp = lst.c("lvl" + (i + 1) + "pPr");
            if (pp != null) lv[i] = lv[i].over(readPPr(pp, cx, true));
        }
    }

    // ------------------------------------------------------------------ text

    private static String bodyAttr(String k, Node own, Node lph, Node mph) {
        String v = own == null ? null : own.a(k);
        if (v == null && lph != null) v = lph.a(k);
        if (v == null && mph != null) v = mph.a(k);
        return v;
    }

    private static void text(Node sp, Node tx, Node ph, Node lph, Node mph, Node style, Ctx cx, StringBuilder out) {
        Node bp = tx.c("bodyPr");
        Node lb = lph == null ? null : lph.p("txBody", "bodyPr");
        Node mb = mph == null ? null : mph.p("txBody", "bodyPr");
        double l = Double.parseDouble(orDef(bodyAttr("lIns", bp, lb, mb), "91440")) / PX;
        double t = Double.parseDouble(orDef(bodyAttr("tIns", bp, lb, mb), "45720")) / PX;
        double r = Double.parseDouble(orDef(bodyAttr("rIns", bp, lb, mb), "91440")) / PX;
        double bt = Double.parseDouble(orDef(bodyAttr("bIns", bp, lb, mb), "45720")) / PX;
        String anchor = bodyAttr("anchor", bp, lb, mb);
        String wrap = bodyAttr("wrap", bp, lb, mb);
        String vert = bodyAttr("vert", bp, lb, mb);
        double fs = 1, lr = 0;
        Node af = bp == null ? null : bp.c("normAutofit");
        if (af == null && lb != null) af = lb.c("normAutofit");
        if (af == null && mb != null) af = mb.c("normAutofit");
        if (af != null) {
            fs = af.d("fontScale", 100000) / 100000.0;
            lr = af.d("lnSpcReduction", 0) / 1000.0;
        }
        TS[] lv = new TS[9];
        for (int i = 0; i < 9; i++) lv[i] = cx.def[i].over(new TS());
        if (ph != null) {
            String type = ph.a("type");
            if (type == null && lph != null) type = lph.p("nvSpPr", "nvPr", "ph").a("type");
            String key = "otherStyle";
            if ("title".equals(type) || "ctrTitle".equals(type)) key = "titleStyle";
            else if (type == null || "body".equals(type) || "subTitle".equals(type) || "obj".equals(type)) key = "bodyStyle";
            if (cx.master != null) overlay(lv, cx.master.root.p("txStyles", key), cx);
            if (mph != null) overlay(lv, mph.p("txBody", "lstStyle"), cx);
            if (lph != null) overlay(lv, lph.p("txBody", "lstStyle"), cx);
        }
        overlay(lv, tx.c("lstStyle"), cx);
        String defColor = style == null ? null : clr(style.c("fontRef") == null ? new Node("x") : style.c("fontRef"), cx);
        out.append("<div class=\"t\" style=\"padding:").append(f(t)).append("px ").append(f(r)).append("px ")
                .append(f(bt)).append("px ").append(f(l)).append("px;justify-content:")
                .append("ctr".equals(anchor) ? "center" : ("b".equals(anchor) ? "flex-end" : "flex-start"));
        if (vert != null && vert.startsWith("vert")) {
            out.append(";writing-mode:vertical-rl");
            if (vert.equals("vert270")) out.append(";transform:rotate(180deg)");
        }
        out.append("\">");
        textBody(tx, lv, fs, lr, defColor, false, "none".equals(wrap), cx, out);
        out.append("</div>");
    }

    private static String orDef(String v, String def) {
        if (v == null) return def;
        try {
            Double.parseDouble(v);
            return v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String roman(int n, boolean upper) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length && n > 0; i++) {
            while (n >= v[i]) {
                sb.append(s[i]);
                n -= v[i];
            }
        }
        return upper ? sb.toString().toUpperCase(Locale.ROOT) : sb.toString();
    }

    private static String autoNum(String type, int n) {
        String t = type == null ? "arabicPeriod" : type;
        String body;
        if (t.startsWith("alphaLc") || t.startsWith("alphaUc")) {
            StringBuilder sb = new StringBuilder();
            int k = n;
            while (k > 0) {
                k--;
                sb.insert(0, (char) ('a' + k % 26));
                k /= 26;
            }
            body = t.startsWith("alphaUc") ? sb.toString().toUpperCase(Locale.ROOT) : sb.toString();
        } else if (t.startsWith("romanLc") || t.startsWith("romanUc")) {
            body = roman(n, t.startsWith("romanUc"));
        } else {
            body = String.valueOf(n);
        }
        if (t.endsWith("ParenBoth")) return "(" + body + ")";
        if (t.endsWith("ParenR")) return body + ")";
        if (t.endsWith("Plain")) return body;
        return body + ".";
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
        }
        return false;
    }

    private static String runStyle(TS r, double fs, String defColor, boolean forceBold, String sample) {
        StringBuilder st = new StringBuilder();
        double pt = (r.sz > 0 ? r.sz : 18) * fs;
        st.append("font-size:").append(f(pt * 96 / 72)).append("px;");
        String col = r.color != null ? r.color : (defColor != null ? defColor : "#000000");
        st.append("color:").append(col).append(';');
        if (r.b == 1 || (forceBold && r.b != 0)) st.append("font-weight:bold;");
        if (r.i == 1) st.append("font-style:italic;");
        if (r.u == 1 || r.st == 1) {
            st.append("text-decoration:");
            if (r.u == 1) st.append("underline ");
            if (r.st == 1) st.append("line-through");
            st.append(';');
        }
        String font = r.font;
        if (r.fontCs != null && sample != null && hasArabic(sample)) font = r.fontCs;
        if (font != null) st.append("font-family:'").append(font.replace("'", "").replace("\"", "")).append("',sans-serif;");
        return st.toString();
    }

    static void textBody(Node tx, TS[] lv, double fs, double lnRed, String defColor, boolean forceBold,
                         boolean noWrap, Ctx cx, StringBuilder out) {
        int[] num = new int[9];
        boolean first = true;
        for (Node p : tx.cs("p")) {
            Node pPr = p.c("pPr");
            int lvl = (int) Math.max(0, Math.min(8, pPr == null ? 0 : pPr.d("lvl", 0)));
            TS t = lv[lvl].over(readPPr(pPr, cx, false));
            StringBuilder inner = new StringBuilder();
            StringBuilder all = new StringBuilder();
            double psz = -1;
            String firstColor = null;
            for (Node r : p.kids) {
                if (r.name.equals("br")) {
                    inner.append("<br>");
                    continue;
                }
                if (!r.name.equals("r") && !r.name.equals("fld")) continue;
                TS rs = t.over(new TS());
                Node rPr = r.c("rPr");
                readR(rs, rPr, cx);
                Node tn = r.c("t");
                String s = tn == null ? "" : tn.text.toString();
                if (s.isEmpty()) continue;
                all.append(s);
                if (psz < 0) {
                    psz = (rs.sz > 0 ? rs.sz : 18) * fs * 96 / 72;
                    firstColor = rs.color != null ? rs.color : defColor;
                }
                int base = rPr == null ? 0 : (int) rPr.d("baseline", 0);
                String sty = runStyle(rs, fs, defColor, forceBold, s);
                if (base != 0) sty += "vertical-align:" + (base > 0 ? "super" : "sub") + ";font-size:" + "70%;";
                inner.append("<span style=\"").append(sty).append("\">").append(DocHtml.esc(s)).append("</span>");
            }
            if (psz < 0) {
                TS rs = t.over(new TS());
                readR(rs, p.c("endParaRPr"), cx);
                psz = (rs.sz > 0 ? rs.sz : 18) * fs * 96 / 72;
                firstColor = rs.color != null ? rs.color : defColor;
                inner.append("<span style=\"font-size:").append(f(psz)).append("px\">\u200B</span>");
            }
            boolean rtl = t.rtl == 1;
            String algn = t.algn;
            String ta = "left";
            if (algn == null) ta = rtl ? "right" : "left";
            else if (algn.equals("ctr")) ta = "center";
            else if (algn.equals("r")) ta = "right";
            else if (algn.equals("just") || algn.equals("justLow") || algn.equals("dist")) ta = "justify";
            StringBuilder st = new StringBuilder("text-align:").append(ta).append(";font-size:").append(f(psz)).append("px;");
            double marL = t.marL == NONE ? 0 : t.marL / PX;
            double ind = t.indent == NONE ? 0 : t.indent / PX;
            if (marL != 0) st.append("padding-inline-start:").append(f(marL)).append("px;");
            if (ind != 0) st.append("text-indent:").append(f(ind)).append("px;");
            double lh = 1.2;
            if (t.lnPts >= 0) st.append("line-height:").append(f(t.lnPts * 96 / 72)).append("px;");
            else {
                if (t.lnPct >= 0) lh = Math.max(0.5, (t.lnPct - lnRed) / 100.0) * 1.2;
                else if (lnRed > 0) lh = Math.max(0.5, (100 - lnRed) / 100.0) * 1.2;
                st.append("line-height:").append(f2(lh)).append(';');
            }
            if (!first) {
                if (t.befPts >= 0) st.append("margin-top:").append(f(t.befPts * 96 / 72)).append("px;");
                else if (t.befPct >= 0) st.append("margin-top:").append(f(psz * 1.2 * t.befPct / 100.0)).append("px;");
            }
            if (t.aftPts >= 0) st.append("margin-bottom:").append(f(t.aftPts * 96 / 72)).append("px;");
            else if (t.aftPct >= 0) st.append("margin-bottom:").append(f(psz * 1.2 * t.aftPct / 100.0)).append("px;");
            if (noWrap) st.append("white-space:pre;");
            // bullet
            StringBuilder bullet = new StringBuilder();
            boolean has = all.length() > 0;
            if (has && t.bu != null && !t.bu.isEmpty()) {
                String mark;
                if (t.bu.startsWith("n:")) {
                    num[lvl] = num[lvl] == 0 ? Math.max(1, t.start) : num[lvl] + 1;
                    mark = autoNum(t.bu.substring(2), num[lvl]);
                } else {
                    mark = t.bu.length() > 2 ? t.bu.substring(2) : "\u2022";
                    num[lvl] = 0;
                }
                for (int k = lvl + 1; k < 9; k++) num[k] = 0;
                double bw = ind < 0 ? -ind : 0;
                bullet.append("<span class=\"bu\" style=\"color:").append(firstColor == null ? "#000" : firstColor)
                        .append(bw > 0 ? ";min-width:" + f(bw) + "px" : ";margin-inline-end:.3em").append("\">")
                        .append(DocHtml.esc(mark)).append("</span>");
            } else {
                num[lvl] = 0;
                for (int k = lvl + 1; k < 9; k++) num[k] = 0;
            }
            out.append("<p").append(rtl ? " dir=\"rtl\"" : "").append(" style=\"").append(st).append("\">")
                    .append(bullet).append(inner).append("</p>");
            first = false;
        }
    }

    // ------------------------------------------------------------------ pictures, tables, charts

    private static void pic(Node pic, Part part, Gx g, Ctx cx, StringBuilder out) {
        Node spPr = pic.c("spPr");
        Node xf = spPr == null ? null : spPr.c("xfrm");
        if (xf == null) return;
        double[] b = box(xf, g);
        if (b == null) return;
        Node bf = pic.c("blipFill");
        Node blip = bf == null ? null : bf.c("blip");
        if (blip == null) return;
        String uri = DocHtml.image(cx.z, part.rels.get(blip.a("embed")), cx.budget);
        Line L = lineOf(spPr.c("ln"), null, cx);
        Node geom = spPr.c("prstGeom");
        String prst = geom == null ? "rect" : geom.a("prst");
        double rot = xf.d("rot", 0) / 60000.0;
        StringBuilder st = new StringBuilder(pos(b, rot)).append(";overflow:hidden");
        if ("ellipse".equals(prst)) st.append(";border-radius:50%");
        else if (prst != null && prst.startsWith("round")) st.append(";border-radius:").append(f(Math.min(b[2], b[3]) * 0.16)).append("px");
        if (L != null) st.append(";border:").append(f(L.w)).append("px solid ").append(L.color);
        Node am = blip.c("alphaModFix");
        if (am != null) st.append(";opacity:").append(f2(am.d("amt", 100000) / 100000.0));
        out.append("<div class=\"s\" style=\"").append(st).append("\">");
        if (uri != null) {
            Node sr = bf.c("srcRect");
            double l = sr == null ? 0 : sr.d("l", 0) / 100000.0, t = sr == null ? 0 : sr.d("t", 0) / 100000.0;
            double r = sr == null ? 0 : sr.d("r", 0) / 100000.0, bb = sr == null ? 0 : sr.d("b", 0) / 100000.0;
            double kw = Math.max(0.05, 1 - l - r), kh = Math.max(0.05, 1 - t - bb);
            String flip = (xf.on("flipH") || xf.on("flipV"))
                    ? ";transform:scale(" + (xf.on("flipH") ? -1 : 1) + "," + (xf.on("flipV") ? -1 : 1) + ")" : "";
            out.append("<img src=\"").append(uri).append("\" style=\"position:absolute;left:")
                    .append(f(-l / kw * 100)).append("%;top:").append(f(-t / kh * 100)).append("%;width:")
                    .append(f(100 / kw)).append("%;height:").append(f(100 / kh)).append("%").append(flip).append("\">");
        }
        out.append("</div>");
    }

    private static void frame(Node gf, Part part, Gx g, Ctx cx, StringBuilder out) {
        Node xf = gf.c("xfrm");
        if (xf == null) return;
        double[] b = box(xf, g);
        if (b == null) return;
        Node gd = gf.p("graphic", "graphicData");
        if (gd == null) return;
        Node tbl = gd.c("tbl");
        if (tbl != null) {
            table(tbl, b, g, cx, out);
            return;
        }
        Node ch = gd.c("chart");
        if (ch != null) {
            String cp = part.rels.get(ch.a("id"));
            String html = cp == null ? "" : DocHtml.chartHtml(cx.z, cp);
            if (html != null && !html.isEmpty()) {
                out.append("<div class=\"s\" style=\"").append(pos(b, 0))
                        .append(";background:#fff;color:#222;font-size:14px;padding:8px;overflow:hidden\">")
                        .append(html).append("</div>");
            }
        }
    }

    private static String mixWhite(String hex, double keep) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) return hex;
        int v = Integer.parseInt(hex.substring(1), 16);
        double[] c = {(v >> 16) & 255, (v >> 8) & 255, v & 255};
        for (int i = 0; i < 3; i++) c[i] = 255 - (255 - c[i]) * keep;
        return String.format(Locale.ROOT, "#%02x%02x%02x", clamp(c[0]), clamp(c[1]), clamp(c[2]));
    }

    private static void table(Node tbl, double[] b, Gx g, Ctx cx, StringBuilder out) {
        Node grid = tbl.c("tblGrid");
        if (grid == null) return;
        Node pr = tbl.c("tblPr");
        boolean firstRow = pr != null && pr.on("firstRow");
        boolean band = pr != null && pr.on("bandRow");
        boolean firstCol = pr != null && pr.on("firstCol");
        String styleId = pr == null || pr.c("tableStyleId") == null ? "" : pr.c("tableStyleId").text.toString().trim();
        boolean noStyle = styleId.equalsIgnoreCase("{2D5ABB26-0587-4C30-8999-92F81FD0307C}");
        boolean gridStyle = styleId.equalsIgnoreCase("{5940675A-B579-460E-94D1-54222C63F5DA}");
        Node ac = new Node("schemeClr");
        ac.at.put("val", "accent1");
        Node holder = new Node("h");
        holder.kids.add(ac);
        String accent = clr(holder, cx);
        List<Double> cols = new ArrayList<>();
        double total = 0;
        for (Node gc : grid.cs("gridCol")) {
            double w = gc.d("w", 914400) * g.sx / PX;
            cols.add(w);
            total += w;
        }
        out.append("<table class=\"s\" style=\"left:").append(f(b[0])).append("px;top:").append(f(b[1]))
                .append("px;width:").append(f(total)).append("px\"><colgroup>");
        for (double w : cols) out.append("<col style=\"width:").append(f(w)).append("px\">");
        out.append("</colgroup>");
        TS[] lv = new TS[9];
        for (int i = 0; i < 9; i++) lv[i] = cx.def[i].over(new TS());
        int ri = 0;
        for (Node tr : tbl.cs("tr")) {
            double h = tr.d("h", 370840) * g.sy / PX;
            out.append("<tr style=\"height:").append(f(h)).append("px\">");
            boolean header = firstRow && ri == 0;
            for (Node tc : tr.cs("tc")) {
                if (tc.on("hMerge") || tc.on("vMerge")) continue;
                Node tcPr = tc.c("tcPr");
                String bg = tcPr == null ? null : fill(tcPr, null, cx);
                String color = "#000000";
                boolean bold = false;
                if (bg == null && !noStyle && !gridStyle && accent != null) {
                    if (header) {
                        bg = accent;
                        color = "#ffffff";
                        bold = true;
                    } else if (firstCol && tr.kids.indexOf(tc) == 0) {
                        bg = accent;
                        color = "#ffffff";
                        bold = true;
                    } else {
                        bg = mixWhite(accent, (band && (ri % 2 == 1)) ? 0.2 : 0.4);
                        if (!band) bg = mixWhite(accent, 0.2);
                    }
                } else if (bg == null && header && noStyle) {
                    bold = false;
                }
                if (bg != null && bg.isEmpty()) bg = null;
                out.append("<td");
                if (tc.d("gridSpan", 1) > 1) out.append(" colspan=\"").append((int) tc.d("gridSpan", 1)).append('"');
                if (tc.d("rowSpan", 1) > 1) out.append(" rowspan=\"").append((int) tc.d("rowSpan", 1)).append('"');
                StringBuilder st = new StringBuilder();
                double ml = (tcPr != null ? tcPr.d("marL", 91440) : 91440) / PX;
                double mr = (tcPr != null ? tcPr.d("marR", 91440) : 91440) / PX;
                double mt = (tcPr != null ? tcPr.d("marT", 45720) : 45720) / PX;
                double mb = (tcPr != null ? tcPr.d("marB", 45720) : 45720) / PX;
                st.append("padding:").append(f(mt)).append("px ").append(f(mr)).append("px ").append(f(mb)).append("px ")
                        .append(f(ml)).append("px;");
                String an = tcPr == null ? null : tcPr.a("anchor");
                if ("ctr".equals(an)) st.append("vertical-align:middle;");
                else if ("b".equals(an)) st.append("vertical-align:bottom;");
                if (bg != null) st.append("background:").append(bg).append(';');
                String[] sides = {"lnL:left", "lnR:right", "lnT:top", "lnB:bottom"};
                for (String sd : sides) {
                    String[] kv = sd.split(":");
                    Node ln = tcPr == null ? null : tcPr.c(kv[0]);
                    String bs;
                    if (ln != null) {
                        Line L = lineOf(ln, null, cx);
                        bs = L == null ? "none" : f(L.w) + "px solid " + L.color;
                    } else if (noStyle) {
                        bs = "none";
                    } else if (gridStyle) {
                        bs = "1px solid #000";
                    } else {
                        bs = "1px solid #ffffff";
                    }
                    st.append("border-").append(kv[1]).append(':').append(bs).append(';');
                }
                out.append(" style=\"").append(st).append("\">");
                Node ctx = tc.c("txBody");
                if (ctx != null) {
                    out.append("<div style=\"position:relative;display:flex;flex-direction:column\">");
                    textBody(ctx, lv, 1, 0, color, bold, false, cx, out);
                    out.append("</div>");
                }
                out.append("</td>");
            }
            out.append("</tr>");
            ri++;
        }
        out.append("</table>");
    }

    // ------------------------------------------------------------------ number formatting

    static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
