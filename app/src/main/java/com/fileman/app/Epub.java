package com.fileman.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads an EPUB (a zip of XHTML chapters): chapters in spine order, images inlined, scripts and styles removed. */
final class Epub {
    private Epub() {
    }

    private static final int MAX_CHAPTERS = 600;
    private static final long MAX_CHAPTER = 3L * 1024 * 1024;
    private static final long TOTAL_HTML = 14L * 1024 * 1024;
    private static final long IMAGE_BUDGET = 18L * 1024 * 1024;
    private static final long MAX_IMAGE = 4L * 1024 * 1024;

    private static byte[] read(ZipReader z, String name, long limit) throws IOException {
        ZipReader.Entry e = z.find(name);
        if (e == null) return null;
        if (e.size > limit) return null;
        try (InputStream in = z.open(e)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(256, Math.min(e.size, limit)));
            byte[] b = new byte[1 << 15];
            int r;
            while ((r = in.read(b)) > 0) {
                bo.write(b, 0, r);
                if (bo.size() > limit) return null;
            }
            return bo.toByteArray();
        }
    }

    private static String str(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?is)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(tag);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    private static String dir(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    private static String resolve(String base, String href) {
        int h = href.indexOf('#');
        if (h >= 0) href = href.substring(0, h);
        int q = href.indexOf('?');
        if (q >= 0) href = href.substring(0, q);
        try {
            href = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        String p = href.startsWith("/") ? href.substring(1) : base + href;
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else if (!s.equals(".") && !s.isEmpty()) parts.add(s);
        }
        return String.join("/", parts);
    }

    static String html(File f) throws IOException {
        try (ZipReader z = ZipReader.open(f)) {
            String container = str(read(z, "META-INF/container.xml", 1 << 20));
            if (container == null) throw new IOException("not an epub");
            Matcher cm = Pattern.compile("(?is)<rootfile\\b[^>]*>").matcher(container);
            if (!cm.find()) throw new IOException("no rootfile");
            String opfPath = attr(cm.group(), "full-path");
            String opf = opfPath == null ? null : str(read(z, opfPath, 8 << 20));
            if (opf == null) throw new IOException("no package document");
            String base = dir(opfPath);

            Map<String, String> hrefById = new HashMap<>();
            Map<String, String> typeById = new HashMap<>();
            Matcher im = Pattern.compile("(?is)<item\\b[^>]*>").matcher(opf);
            while (im.find()) {
                String id = attr(im.group(), "id"), href = attr(im.group(), "href");
                if (id == null || href == null) continue;
                hrefById.put(id, resolve(base, href));
                String mt = attr(im.group(), "media-type");
                if (mt != null) typeById.put(id, mt);
            }
            List<String> spine = new ArrayList<>();
            Matcher sm = Pattern.compile("(?is)<itemref\\b[^>]*>").matcher(opf);
            while (sm.find() && spine.size() < MAX_CHAPTERS) {
                String idref = attr(sm.group(), "idref");
                if (idref != null && hrefById.containsKey(idref)) spine.add(hrefById.get(idref));
            }
            Matcher tm = Pattern.compile("(?is)<dc:title[^>]*>(.*?)</dc:title>").matcher(opf);
            String title = tm.find() ? LegacyDoc.esc(tm.group(1).replaceAll("<[^>]+>", "").trim()) : "";
            Matcher am = Pattern.compile("(?is)<dc:creator[^>]*>(.*?)</dc:creator>").matcher(opf);
            String author = am.find() ? LegacyDoc.esc(am.group(1).replaceAll("<[^>]+>", "").trim()) : "";

            StringBuilder body = new StringBuilder();
            StringBuilder nav = new StringBuilder();
            long[] imgBudget = {IMAGE_BUDGET};
            int ch = 0;
            for (String path : spine) {
                if (body.length() > TOTAL_HTML) break;
                String xhtml = str(read(z, path, MAX_CHAPTER));
                if (xhtml == null) continue;
                String inner = chapterBody(z, xhtml, dir(path), imgBudget);
                if (inner.replaceAll("<[^>]+>|&nbsp;|\\s", "").isEmpty() && !inner.contains("<img")) continue;
                ch++;
                String label = firstHeading(inner);
                if (ch <= 80) nav.append("<a href=\"#c").append(ch).append("\">").append(label.isEmpty() ? String.valueOf(ch) : label).append("</a>");
                body.append("<section id=\"c").append(ch).append("\" class=\"ch\">").append(inner).append("</section>");
            }
            StringBuilder out = new StringBuilder();
            if (!title.isEmpty()) out.append("<h1 dir=\"auto\" class=\"bt\">").append(title).append("</h1>");
            if (!author.isEmpty()) out.append("<p class=\"note\">").append(author).append("</p>");
            if (ch > 1) out.append("<div class=\"nav\">").append(nav).append("</div>");
            out.append(body);
            if (ch == 0) throw new IOException("empty epub");
            return out.toString();
        }
    }

    private static String firstHeading(String html) {
        Matcher m = Pattern.compile("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").matcher(html);
        if (!m.find()) return "";
        String t = m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static String chapterBody(ZipReader z, String xhtml, String baseDir, long[] budget) {
        Matcher bm = Pattern.compile("(?is)<body\\b[^>]*>(.*)</body>").matcher(xhtml);
        String s = bm.find() ? bm.group(1) : xhtml;
        s = s.replaceAll("(?is)<!--.*?-->", "");
        s = s.replaceAll("(?is)<(script|style|iframe|object|embed|form|audio|video)\\b.*?</\\1\\s*>", "");
        s = s.replaceAll("(?is)<(script|style|iframe|object|embed|link|meta|input|button)\\b[^>]*/?>", "");
        // images (also svg <image>) become data URIs; everything else that fetches resources is dropped
        Matcher m = Pattern.compile("(?is)<(img|image)\\b[^>]*>").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            String src = attr(tag, "src");
            if (src == null) src = attr(tag, "xlink:href");
            if (src == null) src = attr(tag, "href");
            String rep = "";
            if (src != null && !src.startsWith("data:") && !src.contains("://")) {
                String name = resolve(baseDir, src);
                try {
                    ZipReader.Entry e = z.find(name);
                    if (e != null && e.size <= MAX_IMAGE && budget[0] - e.size > 0) {
                        byte[] data = read(z, name, MAX_IMAGE);
                        if (data != null) {
                            budget[0] -= data.length;
                            String alt = attr(tag, "alt");
                            rep = "<img src=\"data:" + mime(name) + ";base64," + Base64.getEncoder().encodeToString(data) + "\""
                                    + (alt != null ? " alt=\"" + LegacyDoc.esc(alt).replace("\"", "&quot;") + "\"" : "") + ">";
                        }
                    }
                } catch (IOException ignored) {
                }
            } else if (src != null && src.startsWith("data:image/")) {
                rep = "<img src=\"" + src.replace("\"", "") + "\">";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("(?is)</?svg\\b[^>]*>", "");
        s = s.replaceAll("(?is)\\s(on\\w+|style|srcset|data-[\\w-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
        s = s.replaceAll("(?is)\\shref\\s*=\\s*(\"[^\"]*\"|'[^']*')", " class=\"lk\"");
        return s;
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }
}
