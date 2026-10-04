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
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Converts documents to a self-contained HTML page for the read-only viewer:
 * Word (docx), Excel (xlsx), PowerPoint (pptx), OpenDocument (odt / ods / odp), CSV / TSV, RTF,
 * HTML and SVG. Pure platform code, no third-party libraries.
 */
final class DocHtml {
    private DocHtml() {
    }

    private static final long MAX_ENTRY = 40L * 1024 * 1024;
    private static final long MAX_FILE = 60L * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final int MAX_COLS = 50;
    private static final int MAX_IMAGE = 3 * 1024 * 1024;
    private static final long IMAGE_BUDGET = 16L * 1024 * 1024;

    /** Per-conversion counter of embedded image bytes (single conversion at a time per activity). */
    private static final class Budget {
        long images = 0;
    }

    static String convert(File f, String ext) throws Exception {
        if (f.length() > MAX_FILE) throw new IOException("too large");
        switch (ext) {
            case "docx":
            case "docm":
            case "dotx":
                return docx(f);
            case "xlsx":
            case "xlsm":
            case "xltx":
                return xlsx(f);
            case "pptx":
            case "pptm":
            case "ppsx":
                return pptx(f);
            case "odt":
            case "ods":
            case "odp":
                return odf(f);
            case "csv":
                return delimited(f, (char) 0);
            case "tsv":
                return delimited(f, '\t');
            case "rtf":
                return rtf(f);
            case "svg":
                return svg(f);
            default:
                return new String(readAll(f, 3L * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ shared helpers

    private static final String CSS = "body{margin:0;padding:16px;background:#fff;color:#1b1b1b;"
            + "font-family:sans-serif;font-size:16px;line-height:1.55;word-wrap:break-word}"
            + "p{margin:0 0 .6em;white-space:pre-wrap}h1,h2,h3,h4,h5,h6{margin:.8em 0 .4em;line-height:1.3}"
            + "table{border-collapse:collapse;margin:10px 0}td,th{border:1px solid #c8c8c8;padding:4px 8px;vertical-align:top}"
            + "img{max-width:100%;height:auto}.sheet{overflow:auto;max-width:100%}"
            + ".sheet table{font-size:13px;white-space:nowrap}.rh{background:#f1f3f4;color:#666;text-align:center;font-weight:normal}"
            + ".nav a{display:inline-block;margin:0 6px 8px 0;padding:4px 12px;border-radius:14px;background:#e8f0fe;"
            + "color:#1a56db;text-decoration:none;font-size:14px}"
            + ".slide{border:1px solid #d0d0d0;border-radius:12px;padding:16px;margin:16px 0;background:#fafafa}"
            + ".sn{color:#888;font-size:12px;margin-bottom:8px}.note{color:#888;font-size:13px;margin:8px 0}";

    private static String page(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>" + CSS + "</style></head><body>" + body + "</body></html>";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = null;
            if (c == '&') r = "&amp;";
            else if (c == '<') r = "&lt;";
            else if (c == '>') r = "&gt;";
            else if (c == '"') r = "&quot;";
            if (r != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16);
                    sb.append(s, 0, i);
                }
                sb.append(r);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static byte[] readAll(File f, long limit) throws IOException {
        if (f.length() > limit) throw new IOException("too large");
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in, limit);
        }
    }

    private static byte[] readStream(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > limit) throw new IOException("too large");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static byte[] entry(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        if (e.getSize() > MAX_ENTRY) throw new IOException("entry too large");
        try (InputStream in = z.getInputStream(e)) {
            return readStream(in, MAX_ENTRY);
        }
    }

    private static XmlPullParser parser(byte[] data) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(data), null);
        return p;
    }

    private static String local(String name) {
        if (name == null) return "";
        int i = name.indexOf(':');
        return i < 0 ? name : name.substring(i + 1);
    }

    /** Attribute by local name (ignores the namespace prefix). */
    private static String attr(XmlPullParser p, String localName) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (local(p.getAttributeName(i)).equals(localName)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static int toInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Resolves a relationship target against the folder of the part that owns it. */
    private static String resolve(String baseDir, String target) {
        if (target == null) return null;
        if (target.startsWith("/")) return target.substring(1);
        List<String> parts = new ArrayList<>();
        String full = baseDir + target;
        for (String s : full.split("/")) {
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else if (!s.isEmpty() && !s.equals(".")) {
                parts.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Relationship id to part path, for a ".rels" file next to a part in baseDir. */
    private static Map<String, String> rels(ZipFile z, String relsPath, String baseDir) {
        Map<String, String> m = new HashMap<>();
        try {
            byte[] d = entry(z, relsPath);
            if (d == null) return m;
            XmlPullParser p = parser(d);
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.getName()).equals("Relationship")) {
                    String id = attr(p, "Id");
                    String target = attr(p, "Target");
                    String mode = attr(p, "TargetMode");
                    if (id != null && target != null && !"External".equals(mode)) m.put(id, resolve(baseDir, target));
                }
                ev = p.next();
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static String mimeOfImage(String name) {
        String x = Cats.extOf(name);
        switch (x) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            default:
                return null;
        }
    }

    /** Embeds an image of the package as a data URI (null when unsupported or too big). */
    private static String image(ZipFile z, String part, Budget budget) {
        if (part == null) return null;
        String mime = mimeOfImage(part);
        if (mime == null) return null;
        try {
            ZipEntry e = z.getEntry(part);
            if (e == null || e.getSize() > MAX_IMAGE || budget.images + Math.max(0, e.getSize()) > IMAGE_BUDGET) return null;
            byte[] d = entry(z, part);
            if (d == null) return null;
            budget.images += d.length;
            return "data:" + mime + ";base64," + Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ DOCX

    private static boolean on(XmlPullParser p) {
        String v = attr(p, "val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("none"));
    }

    private static String highlight(String h) {
        if (h == null) return "#ffff00";
        switch (h) {
            case "green":
                return "#00ff00";
            case "cyan":
                return "#00ffff";
            case "magenta":
                return "#ff00ff";
            case "blue":
                return "#9db7ff";
            case "red":
                return "#ff9d9d";
            case "lightGray":
                return "#d3d3d3";
            default:
                return "#ffff00";
        }
    }

    private static String span(String txt, boolean b, boolean i, boolean u, boolean s, String color, int sz,
                               String va, String hl) {
        StringBuilder st = new StringBuilder();
        if (b) st.append("font-weight:bold;");
        if (i) st.append("font-style:italic;");
        if (u || s) {
            st.append("text-decoration:");
            if (u) st.append("underline ");
            if (s) st.append("line-through");
            st.append(';');
        }
        if (color != null && color.matches("[0-9A-Fa-f]{6}")) st.append("color:#").append(color).append(';');
        if (sz > 0) st.append("font-size:").append(Math.max(8, Math.min(48, sz / 2.0))).append("pt;");
        if (hl != null) st.append("background:").append(highlight(hl)).append(';');
        String out = st.length() == 0 ? txt : "<span style=\"" + st + "\">" + txt + "</span>";
        if ("superscript".equals(va)) out = "<sup>" + out + "</sup>";
        else if ("subscript".equals(va)) out = "<sub>" + out + "</sub>";
        return out;
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        String l = style.toLowerCase(Locale.ROOT);
        if (l.equals("title")) return 1;
        if (l.equals("subtitle")) return 2;
        if (l.startsWith("heading")) {
            int n = toInt(l.substring(7).trim(), 1);
            return Math.max(1, Math.min(6, n));
        }
        return 0;
    }

    static String docx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            byte[] doc = entry(z, "word/document.xml");
            if (doc == null) throw new IOException("not a docx");
            Map<String, String> rels = rels(z, "word/_rels/document.xml.rels", "word/");
            Budget budget = new Budget();
            XmlPullParser p = parser(doc);

            Deque<StringBuilder> out = new ArrayDeque<>();
            out.push(new StringBuilder());
            Deque<int[]> spans = new ArrayDeque<>();

            StringBuilder para = null;
            String pStyle = null;
            String jc = null;
            boolean bidi = false;
            boolean listed = false;
            int ilvl = 0;
            boolean inPPr = false;
            boolean inRPr = false;
            boolean inRun = false;
            StringBuilder run = null;
            boolean b = false, it = false, u = false, st = false;
            String color = null;
            String va = null;
            String hl = null;
            int sz = 0;

            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    switch (n) {
                        case "p":
                            para = new StringBuilder();
                            pStyle = null;
                            jc = null;
                            bidi = false;
                            listed = false;
                            ilvl = 0;
                            break;
                        case "pPr":
                            inPPr = true;
                            break;
                        case "rPr":
                            inRPr = true;
                            break;
                        case "pStyle":
                            if (inPPr) pStyle = attr(p, "val");
                            break;
                        case "jc":
                            if (inPPr) jc = attr(p, "val");
                            break;
                        case "bidi":
                            if (inPPr) bidi = on(p);
                            break;
                        case "numPr":
                            if (inPPr) listed = true;
                            break;
                        case "ilvl":
                            if (inPPr) ilvl = toInt(attr(p, "val"), 0);
                            break;
                        case "r":
                            run = new StringBuilder();
                            inRun = true;
                            b = false;
                            it = false;
                            u = false;
                            st = false;
                            color = null;
                            va = null;
                            hl = null;
                            sz = 0;
                            break;
                        case "b":
                            if (inRun && inRPr) b = on(p);
                            break;
                        case "i":
                            if (inRun && inRPr) it = on(p);
                            break;
                        case "u":
                            if (inRun && inRPr) u = on(p);
                            break;
                        case "strike":
                            if (inRun && inRPr) st = on(p);
                            break;
                        case "color":
                            if (inRun && inRPr) color = attr(p, "val");
                            break;
                        case "sz":
                            if (inRun && inRPr) sz = toInt(attr(p, "val"), 0);
                            break;
                        case "vertAlign":
                            if (inRun && inRPr) va = attr(p, "val");
                            break;
                        case "highlight":
                            if (inRun && inRPr) {
                                String h = attr(p, "val");
                                if (h != null && !h.equals("none")) hl = h;
                            }
                            break;
                        case "t":
                            if (inRun && run != null) run.append(esc(p.nextText()));
                            break;
                        case "tab":
                            if (inRun && !inPPr && run != null) run.append("&emsp;");
                            break;
                        case "br":
                        case "cr":
                            if (inRun && run != null) run.append("<br>");
                            break;
                        case "blip": {
                            String uri = image(z, rels.get(attr(p, "embed")), budget);
                            if (uri != null && run != null) run.append("<img src=\"").append(uri).append("\">");
                            break;
                        }
                        case "imagedata": {
                            String uri = image(z, rels.get(attr(p, "id")), budget);
                            if (uri != null && run != null) run.append("<img src=\"").append(uri).append("\">");
                            break;
                        }
                        case "tbl":
                        case "tr":
                            out.push(new StringBuilder());
                            break;
                        case "tc":
                            out.push(new StringBuilder());
                            spans.push(new int[]{1});
                            break;
                        case "gridSpan":
                            if (!spans.isEmpty()) spans.peek()[0] = Math.max(1, toInt(attr(p, "val"), 1));
                            break;
                        default:
                            break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = local(p.getName());
                    switch (n) {
                        case "pPr":
                            inPPr = false;
                            break;
                        case "rPr":
                            inRPr = false;
                            break;
                        case "r":
                            if (run != null && para != null && run.length() > 0) {
                                para.append(span(run.toString(), b, it, u, st, color, sz, va, hl));
                            }
                            run = null;
                            inRun = false;
                            break;
                        case "p":
                            if (para != null) {
                                boolean inCell = !spans.isEmpty();
                                if (para.length() == 0 && inCell) {
                                    para = null;
                                    break;
                                }
                                int lvl = headingLevel(pStyle);
                                String tag = lvl > 0 ? "h" + lvl : "p";
                                StringBuilder attrs = new StringBuilder();
                                attrs.append(" dir=\"").append(bidi ? "rtl" : "auto").append('"');
                                StringBuilder style = new StringBuilder();
                                if ("center".equals(jc)) style.append("text-align:center;");
                                else if ("both".equals(jc) || "distribute".equals(jc)) style.append("text-align:justify;");
                                else if ("right".equals(jc) || "end".equals(jc)) style.append("text-align:end;");
                                else if ("left".equals(jc) || "start".equals(jc)) style.append("text-align:start;");
                                if (listed) style.append("margin-inline-start:").append((ilvl + 1) * 24).append("px;");
                                if (style.length() > 0) attrs.append(" style=\"").append(style).append('"');
                                String body = para.length() == 0 ? "<br>" : para.toString();
                                if (listed) body = "&bull; " + body;
                                out.peek().append('<').append(tag).append(attrs).append('>').append(body)
                                        .append("</").append(tag).append('>');
                                para = null;
                            }
                            break;
                        case "tc": {
                            StringBuilder cell = out.pop();
                            int span = spans.isEmpty() ? 1 : spans.pop()[0];
                            out.peek().append("<td").append(span > 1 ? " colspan=\"" + span + "\"" : "")
                                    .append('>').append(cell).append("</td>");
                            break;
                        }
                        case "tr": {
                            StringBuilder row = out.pop();
                            out.peek().append("<tr>").append(row).append("</tr>");
                            break;
                        }
                        case "tbl": {
                            StringBuilder t = out.pop();
                            out.peek().append("<table>").append(t).append("</table>");
                            break;
                        }
                        default:
                            break;
                    }
                }
                ev = p.next();
            }
            while (out.size() > 1) {
                StringBuilder top = out.pop();
                out.peek().append(top);
            }
            String body = out.peek().toString();
            return page(body.isEmpty() ? "<p class=\"note\">—</p>" : body);
        }
    }

    // ------------------------------------------------------------------ XLSX

    private static int colIndex(String ref) {
        if (ref == null) return -1;
        int n = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? -1 : n - 1;
    }

    private static String colName(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static List<String> sharedStrings(ZipFile z) throws Exception {
        List<String> out = new ArrayList<>();
        byte[] d = entry(z, "xl/sharedStrings.xml");
        if (d == null) return out;
        XmlPullParser p = parser(d);
        StringBuilder cur = null;
        int phonetic = 0;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if (n.equals("si")) cur = new StringBuilder();
                else if (n.equals("rPh")) phonetic++;
                else if (n.equals("t") && cur != null && phonetic == 0) cur.append(p.nextText());
            } else if (ev == XmlPullParser.END_TAG) {
                String n = local(p.getName());
                if (n.equals("si") && cur != null) {
                    out.add(cur.toString());
                    cur = null;
                } else if (n.equals("rPh")) {
                    phonetic--;
                }
            }
            ev = p.next();
        }
        return out;
    }

    private static boolean isDateFormat(int id, String code) {
        if ((id >= 14 && id <= 22) || (id >= 27 && id <= 36) || (id >= 45 && id <= 47) || (id >= 50 && id <= 58)) {
            return true;
        }
        if (code == null) return false;
        String c = code.replaceAll("\\[[^\\]]*\\]", "").replaceAll("\"[^\"]*\"", "").replaceAll("\\\\.", "")
                .toLowerCase(Locale.ROOT);
        return c.matches(".*[ymdhs].*") && !c.contains("general");
    }

    /** Per cell-format kind: 0 = plain number, 1 = date / time, 2 = percent, 3 = percent with decimals. */
    private static int[] cellKinds(ZipFile z) {
        List<Integer> kinds = new ArrayList<>();
        try {
            byte[] d = entry(z, "xl/styles.xml");
            if (d == null) return new int[0];
            Map<Integer, String> codes = new HashMap<>();
            XmlPullParser p = parser(d);
            boolean inXfs = false;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if (n.equals("numFmt")) {
                        codes.put(toInt(attr(p, "numFmtId"), -1), attr(p, "formatCode"));
                    } else if (n.equals("cellXfs")) {
                        inXfs = true;
                    } else if (n.equals("xf") && inXfs) {
                        int id = toInt(attr(p, "numFmtId"), 0);
                        String code = codes.get(id);
                        int kind = 0;
                        if (id == 9) kind = 2;
                        else if (id == 10) kind = 3;
                        else if (code != null && code.contains("%")) kind = code.contains(".0") ? 3 : 2;
                        else if (isDateFormat(id, code)) kind = 1;
                        kinds.add(kind);
                    }
                } else if (ev == XmlPullParser.END_TAG && local(p.getName()).equals("cellXfs")) {
                    inXfs = false;
                }
                ev = p.next();
            }
        } catch (Exception ignored) {
        }
        int[] out = new int[kinds.size()];
        for (int i = 0; i < out.length; i++) out[i] = kinds.get(i);
        return out;
    }

    private static String number(String raw) {
        try {
            return new BigDecimal(raw).round(new MathContext(12)).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return raw;
        }
    }

    private static String excelDate(double serial) {
        try {
            long ms = Math.round((serial - 25569.0) * 86400000.0);
            SimpleDateFormat f;
            if (serial < 1) f = new SimpleDateFormat("HH:mm:ss", Locale.US);
            else if (serial == Math.rint(serial)) f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            else f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.format(new Date(ms));
        } catch (Exception e) {
            return String.valueOf(serial);
        }
    }

    private static String display(String type, String val, int xf, List<String> shared, int[] kinds) {
        if (val == null) return "";
        if ("s".equals(type)) {
            int i = toInt(val, -1);
            return i >= 0 && i < shared.size() ? shared.get(i) : "";
        }
        if ("b".equals(type)) return "1".equals(val) ? "TRUE" : "FALSE";
        if ("str".equals(type) || "inlineStr".equals(type) || "e".equals(type) || "d".equals(type)) return val;
        int kind = xf >= 0 && xf < kinds.length ? kinds[xf] : 0;
        try {
            double d = Double.parseDouble(val);
            if (kind == 1) return excelDate(d);
            if (kind == 2) return number(String.valueOf(new BigDecimal(val).multiply(new BigDecimal(100)))) + "%";
            if (kind == 3) {
                return new BigDecimal(val).multiply(new BigDecimal(100)).setScale(2, java.math.RoundingMode.HALF_UP)
                        .toPlainString() + "%";
            }
        } catch (Exception ignored) {
        }
        return number(val);
    }

    static String xlsx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            byte[] wb = entry(z, "xl/workbook.xml");
            if (wb == null) throw new IOException("not an xlsx");
            List<String> shared = sharedStrings(z);
            int[] kinds = cellKinds(z);
            Map<String, String> rels = rels(z, "xl/_rels/workbook.xml.rels", "xl/");

            List<String> names = new ArrayList<>();
            List<String> parts = new ArrayList<>();
            XmlPullParser p = parser(wb);
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.getName()).equals("sheet")) {
                    names.add(attr(p, "name") == null ? "Sheet" + (names.size() + 1) : attr(p, "name"));
                    parts.add(rels.get(attr(p, "id")));
                }
                ev = p.next();
            }
            StringBuilder html = new StringBuilder();
            if (names.size() > 1) {
                html.append("<div class=\"nav\">");
                for (int i = 0; i < names.size(); i++) {
                    html.append("<a href=\"#s").append(i).append("\">").append(esc(names.get(i))).append("</a>");
                }
                html.append("</div>");
            }
            for (int s = 0; s < names.size(); s++) {
                html.append("<h3 id=\"s").append(s).append("\">").append(esc(names.get(s))).append("</h3>");
                byte[] data = parts.get(s) == null ? null : entry(z, parts.get(s));
                if (data == null) {
                    html.append("<p class=\"note\">—</p>");
                    continue;
                }
                sheetHtml(data, shared, kinds, html);
            }
            return page(html.toString());
        }
    }

    private static void sheetHtml(byte[] data, List<String> shared, int[] kinds, StringBuilder html) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        boolean colsCut = false;
        XmlPullParser p = parser(data);
        int rowIdx = -1;
        List<String> curRow = null;
        String type = null;
        int xf = -1;
        int col = -1;
        int nextCol = 0;
        String val = null;
        boolean inIs = false;
        StringBuilder isb = null;
        int ev = p.getEventType();
        outer:
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                switch (n) {
                    case "row": {
                        String r = attr(p, "r");
                        rowIdx = r != null ? toInt(r, rowIdx + 2) - 1 : rowIdx + 1;
                        if (rowIdx >= MAX_ROWS) {
                            truncated = true;
                            break outer;
                        }
                        while (rows.size() <= rowIdx) rows.add(new ArrayList<String>());
                        curRow = rows.get(rowIdx);
                        nextCol = 0;
                        break;
                    }
                    case "c": {
                        type = attr(p, "t");
                        xf = toInt(attr(p, "s"), -1);
                        int c = colIndex(attr(p, "r"));
                        col = c >= 0 ? c : nextCol;
                        nextCol = col + 1;
                        val = null;
                        break;
                    }
                    case "v":
                        val = p.nextText();
                        break;
                    case "is":
                        inIs = true;
                        isb = new StringBuilder();
                        break;
                    case "t":
                        if (inIs && isb != null) isb.append(p.nextText());
                        break;
                    default:
                        break;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = local(p.getName());
                if (n.equals("is")) {
                    inIs = false;
                    if (isb != null) val = isb.toString();
                } else if (n.equals("c") && curRow != null) {
                    if (col >= MAX_COLS) {
                        colsCut = true;
                    } else {
                        String text = display(type, val, xf, shared, kinds);
                        while (curRow.size() <= col) curRow.add("");
                        curRow.set(col, text);
                    }
                }
            }
            ev = p.next();
        }
        // drop trailing empty rows
        while (!rows.isEmpty()) {
            boolean empty = true;
            for (String s : rows.get(rows.size() - 1)) {
                if (!s.isEmpty()) {
                    empty = false;
                    break;
                }
            }
            if (!empty) break;
            rows.remove(rows.size() - 1);
        }
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        if (rows.isEmpty() || cols == 0) {
            html.append("<p class=\"note\">—</p>");
            return;
        }
        html.append("<div class=\"sheet\"><table><tr><th class=\"rh\"></th>");
        for (int c = 0; c < cols; c++) html.append("<th class=\"rh\">").append(colName(c)).append("</th>");
        html.append("</tr>");
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            html.append("<tr><th class=\"rh\">").append(r + 1).append("</th>");
            for (int c = 0; c < cols; c++) {
                String t = c < row.size() ? row.get(c) : "";
                html.append("<td dir=\"auto\">").append(esc(t)).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table></div>");
        if (truncated || colsCut) {
            html.append("<p class=\"note\">").append(Lang.isAr()
                    ? "تم عرض أول " + MAX_ROWS + " صف و" + MAX_COLS + " عمودًا فقط."
                    : "Showing only the first " + MAX_ROWS + " rows and " + MAX_COLS + " columns.").append("</p>");
        }
    }

    // ------------------------------------------------------------------ PPTX

    private static int slideNo(String name) {
        Matcher m = Pattern.compile("ppt/slides/slide(\\d+)\\.xml").matcher(name);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String pptx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            List<String> slides = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (slideNo(n) >= 0) slides.add(n);
            }
            if (slides.isEmpty()) throw new IOException("not a pptx");
            Collections.sort(slides, (a, b) -> Integer.compare(slideNo(a), slideNo(b)));
            Budget budget = new Budget();
            StringBuilder html = new StringBuilder();
            int num = 0;
            for (String name : slides) {
                num++;
                String relName = name.replace("ppt/slides/", "ppt/slides/_rels/") + ".rels";
                Map<String, String> rels = rels(z, relName, "ppt/slides/");
                byte[] d = entry(z, name);
                if (d == null) continue;
                html.append("<div class=\"slide\"><div class=\"sn\">")
                        .append(Lang.isAr() ? "شريحة " : "Slide ").append(num).append("</div>");
                XmlPullParser p = parser(d);
                StringBuilder para = null;
                boolean title = false;
                boolean rtl = false;
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        String n = local(p.getName());
                        switch (n) {
                            case "sp":
                                title = false;
                                break;
                            case "ph": {
                                String t = attr(p, "type");
                                if ("title".equals(t) || "ctrTitle".equals(t)) title = true;
                                break;
                            }
                            case "p":
                                para = new StringBuilder();
                                rtl = false;
                                break;
                            case "pPr":
                                rtl = "1".equals(attr(p, "rtl"));
                                break;
                            case "t":
                                if (para != null) para.append(esc(p.nextText()));
                                break;
                            case "br":
                                if (para != null) para.append("<br>");
                                break;
                            case "blip": {
                                String uri = image(z, rels.get(attr(p, "embed")), budget);
                                if (uri != null) html.append("<img src=\"").append(uri).append("\">");
                                break;
                            }
                            default:
                                break;
                        }
                    } else if (ev == XmlPullParser.END_TAG && local(p.getName()).equals("p") && para != null) {
                        if (para.length() > 0) {
                            String tag = title ? "h2" : "p";
                            html.append('<').append(tag).append(" dir=\"").append(rtl ? "rtl" : "auto").append("\">")
                                    .append(para).append("</").append(tag).append('>');
                        }
                        para = null;
                    }
                    ev = p.next();
                }
                html.append("</div>");
            }
            return page(html.toString());
        }
    }

    // ------------------------------------------------------------------ ODF (odt / ods / odp)

    static String odf(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            byte[] d = entry(z, "content.xml");
            if (d == null) throw new IOException("not an OpenDocument file");
            Budget budget = new Budget();
            XmlPullParser p = parser(d);
            Deque<StringBuilder> out = new ArrayDeque<>();
            out.push(new StringBuilder());
            Deque<int[]> cellInfo = new ArrayDeque<>();   // {colspan, repeat}
            StringBuilder para = null;
            String paraTag = "p";
            int listDepth = 0;
            int slide = 0;
            int emittedRows = 0;
            boolean rowHasText = false;
            int rowRepeat = 1;
            boolean cellHasText = false;
            boolean inText = false;

            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    switch (n) {
                        case "h":
                            para = new StringBuilder();
                            paraTag = "h" + Math.max(1, Math.min(6, toInt(attr(p, "outline-level"), 2)));
                            break;
                        case "p":
                            para = new StringBuilder();
                            paraTag = "p";
                            break;
                        case "s": {
                            if (para != null) {
                                int c = Math.max(1, Math.min(50, toInt(attr(p, "c"), 1)));
                                for (int i = 0; i < c; i++) para.append("&nbsp;");
                            }
                            break;
                        }
                        case "tab":
                            if (para != null) para.append("&emsp;");
                            break;
                        case "line-break":
                            if (para != null) para.append("<br>");
                            break;
                        case "list-item":
                            listDepth++;
                            break;
                        case "image": {
                            String href = attr(p, "href");
                            String uri = href == null ? null : image(z, href, budget);
                            if (uri != null) out.peek().append("<img src=\"").append(uri).append("\">");
                            break;
                        }
                        case "page":
                            slide++;
                            out.peek().append("<div class=\"slide\"><div class=\"sn\">")
                                    .append(Lang.isAr() ? "شريحة " : "Slide ").append(slide).append("</div>");
                            break;
                        case "table": {
                            String tn = attr(p, "name");
                            if (tn != null) out.peek().append("<h3>").append(esc(tn)).append("</h3>");
                            out.push(new StringBuilder());
                            emittedRows = 0;
                            break;
                        }
                        case "table-row":
                            out.push(new StringBuilder());
                            rowHasText = false;
                            rowRepeat = Math.max(1, toInt(attr(p, "number-rows-repeated"), 1));
                            break;
                        case "table-cell":
                        case "covered-table-cell":
                            out.push(new StringBuilder());
                            cellInfo.push(new int[]{
                                    Math.max(1, toInt(attr(p, "number-columns-spanned"), 1)),
                                    Math.max(1, toInt(attr(p, "number-columns-repeated"), 1)),
                                    n.equals("covered-table-cell") ? 1 : 0});
                            cellHasText = false;
                            break;
                        default:
                            break;
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (para != null) {
                        String t = p.getText();
                        if (t != null && !t.isEmpty()) {
                            para.append(esc(t));
                            if (!t.trim().isEmpty()) cellHasText = true;
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = local(p.getName());
                    switch (n) {
                        case "h":
                        case "p":
                            if (para != null) {
                                if (para.length() > 0 || cellInfo.isEmpty()) {
                                    String body = para.length() == 0 ? "<br>" : para.toString();
                                    if (listDepth > 0) body = "&bull; " + body;
                                    out.peek().append('<').append(paraTag).append(" dir=\"auto\">").append(body)
                                            .append("</").append(paraTag).append('>');
                                }
                                para = null;
                            }
                            break;
                        case "list-item":
                            listDepth = Math.max(0, listDepth - 1);
                            break;
                        case "page":
                            out.peek().append("</div>");
                            break;
                        case "table-cell":
                        case "covered-table-cell": {
                            StringBuilder cell = out.pop();
                            int[] info = cellInfo.isEmpty() ? new int[]{1, 1, 0} : cellInfo.pop();
                            if (info[2] == 1) break;   // covered by a merged cell
                            if (cellHasText) rowHasText = true;
                            int repeat = cellHasText ? Math.min(info[1], 10) : (info[1] > 1 ? 0 : 1);
                            for (int i = 0; i < repeat; i++) {
                                out.peek().append("<td").append(info[0] > 1 ? " colspan=\"" + info[0] + "\"" : "")
                                        .append('>').append(cell).append("</td>");
                            }
                            break;
                        }
                        case "table-row": {
                            StringBuilder row = out.pop();
                            if (rowHasText || rowRepeat == 1) {
                                int times = rowHasText ? Math.min(rowRepeat, 20) : 1;
                                for (int i = 0; i < times && emittedRows < MAX_ROWS; i++) {
                                    out.peek().append("<tr>").append(row).append("</tr>");
                                    emittedRows++;
                                }
                            }
                            break;
                        }
                        case "table": {
                            StringBuilder t = out.pop();
                            out.peek().append("<div class=\"sheet\"><table>").append(t).append("</table></div>");
                            break;
                        }
                        default:
                            break;
                    }
                }
                ev = p.next();
            }
            while (out.size() > 1) {
                StringBuilder top = out.pop();
                out.peek().append(top);
            }
            String body = out.peek().toString();
            return page(body.isEmpty() ? "<p class=\"note\">—</p>" : body);
        }
    }

    // ------------------------------------------------------------------ CSV / TSV

    static String delimited(File f, char delimiter) throws Exception {
        byte[] data = readAll(f, 8L * 1024 * 1024);
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        char d = delimiter;
        if (d == 0) {
            int firstEnd = text.indexOf('\n');
            String first = firstEnd < 0 ? text : text.substring(0, firstEnd);
            int commas = 0, semis = 0, tabs = 0;
            for (int i = 0; i < first.length(); i++) {
                char c = first.charAt(i);
                if (c == ',') commas++;
                else if (c == ';') semis++;
                else if (c == '\t') tabs++;
            }
            d = tabs > commas && tabs > semis ? '\t' : (semis > commas ? ';' : ',');
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean truncated = false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == d) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() >= MAX_ROWS) {
                    truncated = true;
                    break;
                }
            } else {
                cell.append(c);
            }
        }
        if (!truncated && (cell.length() > 0 || !row.isEmpty())) {
            row.add(cell.toString());
            rows.add(row);
        }
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        cols = Math.min(cols, MAX_COLS);
        StringBuilder html = new StringBuilder("<div class=\"sheet\"><table>");
        for (int r = 0; r < rows.size(); r++) {
            html.append("<tr><th class=\"rh\">").append(r + 1).append("</th>");
            List<String> rw = rows.get(r);
            for (int c = 0; c < cols; c++) {
                html.append("<td dir=\"auto\">").append(esc(c < rw.size() ? rw.get(c) : "")).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table></div>");
        if (truncated) {
            html.append("<p class=\"note\">").append(Lang.isAr() ? "تم عرض أول " + MAX_ROWS + " صف فقط."
                    : "Showing only the first " + MAX_ROWS + " rows.").append("</p>");
        }
        return page(html.toString());
    }

    // ------------------------------------------------------------------ RTF

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

    // ------------------------------------------------------------------ SVG

    static String svg(File f) throws Exception {
        byte[] d = readAll(f, 4L * 1024 * 1024);
        String uri = "data:image/svg+xml;base64," + Base64.encodeToString(d, Base64.NO_WRAP);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"margin:0;background:#fff;display:flex;align-items:center;justify-content:center;"
                + "min-height:100vh\"><img src=\"" + uri + "\" style=\"max-width:100%;max-height:100vh\"></body></html>";
    }
}
