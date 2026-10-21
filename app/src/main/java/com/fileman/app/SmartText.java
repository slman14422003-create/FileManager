package com.fileman.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class SmartText {
    private SmartText() {
    }

    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern PDF_TEXT = Pattern.compile("\\(((?:\\\\.|[^\\\\()])*)\\)\\s*Tj");
    private static final Pattern PDF_ARRAY = Pattern.compile("\\[((?:\\\\.|[^\\]\\\\])*)\\]\\s*TJ");
    private static final Pattern PDF_STR = Pattern.compile("\\(((?:\\\\.|[^\\\\()])*)\\)");

    static boolean hasText(String ext) {
        switch (ext) {
            case "txt":
            case "md":
            case "csv":
            case "json":
            case "xml":
            case "html":
            case "htm":
            case "vcf":
            case "srt":
            case "log":
            case "docx":
            case "pptx":
            case "xlsx":
            case "odt":
            case "ods":
            case "odp":
            case "pdf":
                return true;
            default:
                return false;
        }
    }

    static String textOf(File f, int max) {
        try {
            String ext = Cats.extOf(f.getName());
            switch (ext) {
                case "docx":
                    return zipText(f, "word/document.xml", max);
                case "pptx":
                    return zipSlides(f, max);
                case "xlsx":
                    return zipText(f, "xl/sharedStrings.xml", max);
                case "odt":
                case "ods":
                case "odp":
                    return zipText(f, "content.xml", max);
                case "pdf":
                    String t = pdfTitle(f);
                    String body = pdfText(f, max);
                    return ((t == null ? "" : t + " ") + body).trim();
                case "html":
                case "htm":
                case "xml":
                    return unescape(TAGS.matcher(plain(f, max * 3)).replaceAll(" "));
                default:
                    return Cats.isTextName(f.getName()) || hasText(ext) ? plain(f, max) : "";
            }
        } catch (Throwable e) {
            return "";
        }
    }

    static String titleOf(File f) {
        try {
            String ext = Cats.extOf(f.getName());
            switch (ext) {
                case "txt":
                case "md":
                    return firstLine(plain(f, 4096));
                case "docx":
                    return docxTitle(f);
                case "pdf":
                    return pdfTitle(f);
                default:
                    return null;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    static String sanitize(String s, int max) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0) c = ' ';
            sb.append(c);
        }
        String r = sb.toString().replaceAll("\\s+", " ").trim();
        while (r.startsWith(".")) r = r.substring(1).trim();
        while (r.endsWith(".")) r = r.substring(0, r.length() - 1).trim();
        if (r.length() > max) {
            int cut = r.lastIndexOf(' ', max);
            r = r.substring(0, cut > max / 2 ? cut : max).trim();
        }
        return r.length() < 3 ? null : r;
    }

    private static String firstLine(String text) {
        for (String line : text.split("\\r?\\n")) {
            String t = line.replaceAll("^[#>*\\-\\s]+", "").trim();
            if (t.length() >= 3) return sanitize(t, 60);
        }
        return null;
    }

    private static String plain(File f, int max) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[max];
            int n = 0;
            while (n < max) {
                int r = in.read(buf, n, max - n);
                if (r <= 0) break;
                n += r;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static String readEntry(ZipFile z, String name, int maxBytes) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (out.size() < maxBytes) {
                int r = in.read(buf);
                if (r <= 0) break;
                out.write(buf, 0, r);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String xmlText(String xml, int max) {
        String t = xml.replace("</w:p>", "\n").replace("</a:p>", "\n").replace("</text:p>", "\n");
        t = unescape(TAGS.matcher(t).replaceAll(" "));
        t = t.replaceAll("[ \\t]+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String zipText(File f, String entry, int max) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            String x = readEntry(z, entry, max * 12);
            return x == null ? "" : xmlText(x, max);
        }
    }

    private static String zipSlides(File f, int max) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            int n = 0;
            while (en.hasMoreElements() && sb.length() < max && n < 12) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    String x = readEntry(z, name, max * 6);
                    if (x != null) sb.append(xmlText(x, max)).append('\n');
                    n++;
                }
            }
        }
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }

    private static String docxTitle(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            String core = readEntry(z, "docProps/core.xml", 65536);
            if (core != null) {
                Matcher m = Pattern.compile("<dc:title>([^<]*)</dc:title>").matcher(core);
                if (m.find()) {
                    String t = sanitize(unescape(m.group(1)), 60);
                    if (t != null) return t;
                }
            }
            String doc = readEntry(z, "word/document.xml", 200000);
            if (doc == null) return null;
            return firstLine(xmlText(doc, 600));
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'");
    }

    private static String latin(File f, long from, int len) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long size = raf.length();
            if (from < 0) from = Math.max(0, size + from);
            raf.seek(from);
            byte[] b = new byte[(int) Math.min(len, size - from)];
            raf.readFully(b);
            return new String(b, StandardCharsets.ISO_8859_1);
        }
    }

    static String pdfTitle(File f) throws Exception {
        String head = latin(f, 0, 262144);
        String t = pdfInfo(head);
        if (t == null && f.length() > 262144) t = pdfInfo(latin(f, -262144, 262144));
        return t;
    }

    private static String pdfInfo(String s) {
        int i = s.lastIndexOf("/Title");
        if (i < 0) return null;
        i += 6;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return null;
        String raw;
        if (s.charAt(i) == '(') {
            raw = literal(s, i + 1);
        } else if (s.charAt(i) == '<') {
            int e = s.indexOf('>', i);
            if (e < 0) return null;
            raw = hex(s.substring(i + 1, e));
        } else {
            return null;
        }
        if (raw == null || raw.isEmpty()) return null;
        byte[] b = raw.getBytes(StandardCharsets.ISO_8859_1);
        String out;
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) {
            out = new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        } else {
            out = raw;
        }
        return sanitize(out, 60);
    }

    private static String hex(String h) {
        h = h.replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < h.length(); i += 2) {
            try {
                sb.append((char) Integer.parseInt(h.substring(i, i + 2), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return sb.toString();
    }

    private static String literal(String s, int i) {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '\\' && i < s.length()) {
                char n = s.charAt(i++);
                switch (n) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                    case 'f':
                        break;
                    default:
                        if (n >= '0' && n <= '7') {
                            int v = n - '0';
                            int k = 0;
                            while (k < 2 && i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
                                v = v * 8 + (s.charAt(i++) - '0');
                                k++;
                            }
                            sb.append((char) (v & 0xff));
                        } else {
                            sb.append(n);
                        }
                }
            } else if (c == '(') {
                depth++;
                sb.append(c);
            } else if (c == ')') {
                depth--;
                if (depth == 0) return sb.toString();
                sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    static String pdfText(File f, int max) throws Exception {
        long size = f.length();
        if (size > 12L * 1024 * 1024) return "";
        byte[] all = new byte[(int) size];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(all);
        }
        String s = new String(all, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        int streams = 0;
        while (out.length() < max && streams < 400) {
            int a = s.indexOf("stream", pos);
            if (a < 0) break;
            if (a >= 3 && s.startsWith("end", a - 3)) {
                pos = a + 6;
                continue;
            }
            int start = a + 6;
            if (start < s.length() && s.charAt(start) == '\r') start++;
            if (start < s.length() && s.charAt(start) == '\n') start++;
            int end = s.indexOf("endstream", start);
            if (end < 0) break;
            pos = end + 9;
            streams++;
            int dictFrom = Math.max(0, s.lastIndexOf("obj", a));
            String dict = s.substring(dictFrom, a);
            if (dict.contains("/Image") || dict.contains("/Font")) continue;
            String content;
            if (dict.contains("FlateDecode")) {
                content = inflate(all, start, end - start);
            } else {
                content = s.substring(start, end);
            }
            if (content == null || content.indexOf("Tj") < 0 && content.indexOf("TJ") < 0) continue;
            collect(content, out);
        }
        String r = out.toString().replaceAll("\\s+", " ").trim();
        if (r.length() < 20 || letterRatio(r) < 0.7) return "";
        return r.length() > max ? r.substring(0, max) : r;
    }

    private static void collect(String content, StringBuilder out) {
        Matcher m = PDF_TEXT.matcher(content);
        Matcher arr = PDF_ARRAY.matcher(content);
        int i = 0;
        while (true) {
            boolean a = m.find(i);
            boolean b = arr.find(i);
            if (!a && !b) break;
            if (a && (!b || m.start() < arr.start())) {
                out.append(decode(m.group(1))).append(' ');
                i = m.end();
            } else {
                Matcher st = PDF_STR.matcher(arr.group(1));
                while (st.find()) out.append(decode(st.group(1)));
                out.append(' ');
                i = arr.end();
            }
        }
    }

    private static String decode(String raw) {
        String r = literal(raw + ")", 0);
        return r == null ? "" : r;
    }

    private static String inflate(byte[] data, int off, int len) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data, off, len);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished() && out.size() < (1 << 21)) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    private static double letterRatio(String s) {
        int ok = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || ".,;:-_()%/".indexOf(c) >= 0) ok++;
        }
        return s.isEmpty() ? 0 : ok / (double) s.length();
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
