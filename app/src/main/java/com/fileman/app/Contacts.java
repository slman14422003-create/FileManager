package com.fileman.app;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contact files (.vcf / vCard 2.1, 3.0, 4.0): a forgiving reader that repairs the damage these files usually
 * have, an in-memory model that can be edited, and a writer that always produces clean vCard 3.0.
 * <p>
 * What the reader repairs (each fix is counted in {@link Result#fixes} for the report):
 * broken or legacy text encodings (UTF-16, cp1256, UTF-8 read as Latin-1), truncated UTF-8, cards glued
 * together or missing BEGIN / END, folded lines that lost their indentation, quoted-printable text with a
 * missing header, missing names, junk phone numbers and e-mails, broken photos, stray control characters.
 * Properties it does not understand are kept untouched so editing never loses data.
 */
public final class Contacts {
    private Contacts() {
    }

    // ------------------------------------------------------------------ model

    public static final class Field {
        public String value;
        public String types;   // upper-case, comma separated: "CELL,HOME"
        public String group;   // Apple style "item1" prefix, usually empty

        public Field(String value, String types, String group) {
            this.value = value;
            this.types = types == null ? "" : types;
            this.group = group == null ? "" : group;
        }
    }

    public static final class Adr {
        public final String[] parts = new String[]{"", "", "", "", "", "", ""};   // pobox;ext;street;city;region;zip;country
        public String types = "";
        public String group = "";

        public String display() {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p == null || p.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(p.trim());
            }
            return sb.toString();
        }
    }

    /** A property this app does not edit (photo, nickname, custom labels...) carried through unchanged. */
    public static final class Extra {
        public String group = "";
        public String name = "";
        public String params = "";   // without ENCODING / CHARSET
        public String value = "";    // wire text: already escaped, or base64 for binary properties
    }

    public static final class Card {
        public String prefix = "", given = "", additional = "", family = "", suffix = "";
        public String fn = "";
        public final List<Field> tels = new ArrayList<>();
        public final List<Field> emails = new ArrayList<>();
        public final List<Adr> adrs = new ArrayList<>();
        public String org = "", title = "", note = "", url = "", bday = "";
        public final List<Extra> extras = new ArrayList<>();
        String version = "";
        boolean implicit;

        /** The name to show in lists. */
        public String display() {
            if (!fn.trim().isEmpty()) return fn.trim();
            String n = joinNonEmpty(" ", prefix, given, additional, family, suffix);
            if (!n.isEmpty()) return n;
            if (!org.trim().isEmpty()) return org.trim();
            if (!tels.isEmpty()) return tels.get(0).value;
            if (!emails.isEmpty()) return emails.get(0).value;
            return "";
        }

        public String initial() {
            String d = display();
            for (int i = 0; i < d.length(); ) {
                int cp = d.codePointAt(i);
                if (Character.isLetter(cp)) return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
                i += Character.charCount(cp);
            }
            return "#";
        }

        public String firstDetail() {
            if (!tels.isEmpty()) return tels.get(0).value;
            if (!emails.isEmpty()) return emails.get(0).value;
            return org.trim();
        }

        public boolean hasPhoto() {
            for (Extra e : extras) if ("PHOTO".equals(e.name)) return true;
            return false;
        }

        /** Lower-case text the search box matches against (phones also as bare digits). */
        public String searchKey() {
            StringBuilder sb = new StringBuilder(display().toLowerCase(Locale.ROOT));
            for (Field f : tels) sb.append(' ').append(f.value).append(' ').append(digits(f.value));
            for (Field f : emails) sb.append(' ').append(f.value.toLowerCase(Locale.ROOT));
            sb.append(' ').append(org.toLowerCase(Locale.ROOT));
            return sb.toString();
        }
    }

    /** Outcome of reading a file: the cards plus what had to be repaired. */
    public static final class Result {
        public final List<Card> cards = new ArrayList<>();
        /** string resource id (a "%d" message) -> how many times it was needed */
        public final LinkedHashMap<Integer, Integer> fixes = new LinkedHashMap<>();

        void fix(int res) {
            Integer n = fixes.get(res);
            fixes.put(res, n == null ? 1 : n + 1);
        }

        public int fixCount() {
            int t = 0;
            for (int v : fixes.values()) t += v;
            return t;
        }
    }

    // ------------------------------------------------------------------ reading

    private static final Pattern GLUE_BEGIN = Pattern.compile("(?i)(?<=[^\\r\\n])(BEGIN:VCARD)");
    private static final Pattern GLUE_END = Pattern.compile("(?i)(END:VCARD)(?=[^\\r\\n\\s])");
    private static final Pattern QP_HEX = Pattern.compile("(=[0-9A-Fa-f]{2}){3,}");
    private static final Pattern MOJIBAKE = Pattern.compile("[\u00D8\u00D9\u00C3\u00C2][\u0080-\u00BF\u0152\u0153\u0160\u0161\u0178\u017D\u017E\u0192\u02C6\u02DC\u2013\u2014\u2018-\u201E\u2020-\u2022\u2026\u2030\u2039\u203A\u20AC\u2122]");
    private static final Pattern CONTROL = Pattern.compile("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u200B\uFEFF\uFFFD]");
    private static final Pattern PHONE_JUNK = Pattern.compile("[^0-9+*#()\\-./ ,;pPwWxX]");

    /**
     * Reads and repairs a contact file.
     *
     * @throws IllegalArgumentException when the data does not contain anything that looks like a contact card
     */
    public static Result parse(byte[] data) {
        Result r = new Result();
        String text = decode(data, r);
        List<String> lines = logicalLines(text, r);
        Card cur = null;
        boolean sawBegin = false;
        for (String line : lines) {
            Prop p = splitProp(line);
            if (p == null) continue;
            if (p.name.equals("BEGIN")) {
                if (!p.value.trim().equalsIgnoreCase("VCARD")) continue;
                if (cur != null) {
                    r.fix(R.string.ct_fix_no_end);
                    finish(cur, r);
                }
                cur = new Card();
                sawBegin = true;
                continue;
            }
            if (p.name.equals("END")) {
                if (cur != null) finish(cur, r);
                cur = null;
                continue;
            }
            if (cur != null && cur.implicit && p.name.equals("FN") && !cur.fn.isEmpty()) {
                finish(cur, r);   // a second loose card without any markers
                cur = null;
            }
            if (cur == null) {
                if (!isCardProp(p.name)) continue;
                cur = new Card();
                cur.implicit = true;
                r.fix(R.string.ct_fix_no_begin);
            }
            apply(cur, p, r);
        }
        if (cur != null) {
            r.fix(R.string.ct_fix_no_end);
            finish(cur, r);
        }
        if (r.cards.isEmpty() && !sawBegin) throw new IllegalArgumentException("not a vCard");
        return r;
    }

    private static boolean isCardProp(String n) {
        return n.equals("N") || n.equals("FN") || n.equals("TEL") || n.equals("EMAIL") || n.equals("ORG")
                || n.equals("ADR") || n.equals("NOTE") || n.equals("TITLE") || n.equals("URL") || n.equals("BDAY")
                || n.equals("VERSION");
    }

    // ---- bytes -> text

    private static String decode(byte[] b, Result r) {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, n - 3, StandardCharsets.UTF_8);
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            r.fix(R.string.ct_fix_encoding);
            return new String(b, 2, n - 2, StandardCharsets.UTF_16LE);
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            r.fix(R.string.ct_fix_encoding);
            return new String(b, 2, n - 2, StandardCharsets.UTF_16BE);
        }
        int lim = Math.min(n, 4096);
        int zeroEven = 0, zeroOdd = 0;
        for (int i = 0; i < lim; i++) {
            if (b[i] == 0) {
                if ((i & 1) == 0) zeroEven++;
                else zeroOdd++;
            }
        }
        if (lim > 8 && zeroOdd > lim / 4) {
            r.fix(R.string.ct_fix_encoding);
            return new String(b, StandardCharsets.UTF_16LE);
        }
        if (lim > 8 && zeroEven > lim / 4) {
            r.fix(R.string.ct_fix_encoding);
            return new String(b, StandardCharsets.UTF_16BE);
        }
        String s = decodeBytes(b, null, r);
        return s;
    }

    /** Strict UTF-8 first; a few bad bytes (a cut-off file) are dropped; otherwise a legacy code page. */
    private static String decodeBytes(byte[] b, String charsetHint, Result r) {
        if (charsetHint != null && !charsetHint.trim().isEmpty()) {
            try {
                Charset cs = Charset.forName(charsetHint.trim().replace("\"", ""));
                if (!cs.equals(StandardCharsets.UTF_8)) return new String(b, cs);
            } catch (Exception ignored) {
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            // not clean UTF-8
        }
        String lenient = new String(b, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < lenient.length(); i++) if (lenient.charAt(i) == '\uFFFD') bad++;
        int high = 0;
        for (byte x : b) if (x < 0) high++;
        if (bad > 0 && bad * 10 <= Math.max(1, high)) {   // almost all UTF-8: just a damaged tail or a stray byte
            if (r != null) r.fix(R.string.ct_fix_encoding);
            return lenient.replace("\uFFFD", "");
        }
        if (r != null) r.fix(R.string.ct_fix_encoding);
        return new String(b, legacyCharset(b));
    }

    /** Arabic Windows (cp1256) when the high bytes look like Arabic letters, otherwise Latin (cp1252). */
    private static Charset legacyCharset(byte[] b) {
        int high = 0, arabic = 0;
        for (byte x : b) {
            int v = x & 0xFF;
            if (v < 0x80) continue;
            high++;
            if ((v >= 0xC1 && v <= 0xDF && v != 0xD7) || v == 0xE1 || (v >= 0xE3 && v <= 0xE6) || v == 0xEC || v == 0xED) {
                arabic++;
            }
        }
        try {
            return Charset.forName(high > 0 && arabic * 10 >= high * 6 ? "windows-1256" : "windows-1252");
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** UTF-8 text that was once read as Latin-1 / cp1252 ("Ø§Ù„...") is turned back into the real text. */
    private static String fixMojibake(String s, Result r) {
        if (s.length() < 2 || !MOJIBAKE.matcher(s).find()) return s;
        try {
            CharsetEncoder enc = Charset.forName("windows-1252").newEncoder();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x100) {
                    bo.write(c);
                } else {
                    if (!enc.canEncode(c)) return s;
                    ByteBuffer bb = enc.encode(CharBuffer.wrap(new char[]{c}));
                    bo.write(bb.get(0));
                }
            }
            String back = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bo.toByteArray())).toString();
            if (back.length() < s.length() && back.indexOf('\uFFFD') < 0) {
                r.fix(R.string.ct_fix_encoding);
                return back;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    // ---- text -> logical lines

    private static List<String> logicalLines(String text, Result r) {
        // cards glued onto one line ("...END:VCARDBEGIN:VCARD...")
        int glued = 0;
        Matcher m = GLUE_BEGIN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            glued++;
            m.appendReplacement(sb, "\n$1");
        }
        m.appendTail(sb);
        text = sb.toString();
        m = GLUE_END.matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            glued++;
            m.appendReplacement(sb, "$1\n");
        }
        m.appendTail(sb);
        text = sb.toString();
        for (int i = 0; i < glued; i++) r.fix(R.string.ct_fix_split);

        String[] raw = text.split("\r\n|\n|\r", -1);
        List<String> lines = new ArrayList<>();
        int i = 0;
        while (i < raw.length) {
            String line = raw[i++];
            if (line.trim().isEmpty()) continue;
            char c0 = line.charAt(0);
            if ((c0 == ' ' || c0 == '\t') && !lines.isEmpty()) {   // normal folding
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + line.substring(1));
                continue;
            }
            String cur = line.trim();
            while (isQp(cur) && cur.endsWith("=") && i < raw.length) {   // quoted-printable soft line break
                cur = cur.substring(0, cur.length() - 1);
                String nx = raw[i++];
                if (nx.startsWith(" ") || nx.startsWith("\t")) {
                    String t = nx.trim();
                    if (t.startsWith("=")) nx = t;
                }
                cur += nx;
            }
            if (cur.indexOf(':') < 0 && !lines.isEmpty()) {   // a folded line that lost its leading space
                int last = lines.size() - 1;
                String prev = lines.get(last);
                String up = prev.toUpperCase(Locale.ROOT);
                if (!up.startsWith("BEGIN:") && !up.startsWith("END:")) {
                    lines.set(last, prev + cur);
                    r.fix(R.string.ct_fix_unfold);
                }
                continue;
            }
            lines.add(cur);
        }
        return lines;
    }

    private static boolean isQp(String line) {
        int c = line.indexOf(':');
        String head = c < 0 ? line : line.substring(0, c);
        return head.toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE");
    }

    // ---- one property line

    private static final class Prop {
        String group = "", name = "", rawName = "", value = "", encoding = "", charset = "";
        final List<String> types = new ArrayList<>();
        final List<String> other = new ArrayList<>();
    }

    private static Prop splitProp(String line) {
        int colon = -1;
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') q = !q;
            else if (c == ':' && !q) {
                colon = i;
                break;
            }
        }
        if (colon < 0) return null;
        String head = line.substring(0, colon);
        Prop p = new Prop();
        p.value = line.substring(colon + 1);
        List<String> parts = splitOutsideQuotes(head, ';');
        String nm = parts.get(0).trim();
        int dot = nm.indexOf('.');
        if (dot > 0) {
            p.group = nm.substring(0, dot);
            nm = nm.substring(dot + 1);
        }
        p.rawName = nm;
        p.name = nm.toUpperCase(Locale.ROOT);
        for (int k = 1; k < parts.size(); k++) {
            String t = parts.get(k).trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq < 0) {
                String u = t.toUpperCase(Locale.ROOT);
                if (u.equals("QUOTED-PRINTABLE") || u.equals("BASE64") || u.equals("B") || u.equals("8BIT") || u.equals("7BIT")) {
                    p.encoding = u.equals("B") ? "BASE64" : u;
                } else if (!u.equals("VOICE")) {
                    p.types.add(u);
                }
                continue;
            }
            String key = t.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String val = t.substring(eq + 1).trim();
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
            switch (key) {
                case "TYPE":
                    for (String x : val.split(",")) {
                        String u = x.trim().toUpperCase(Locale.ROOT);
                        if (!u.isEmpty() && !u.equals("VOICE")) p.types.add(u);
                    }
                    break;
                case "ENCODING":
                    p.encoding = val.toUpperCase(Locale.ROOT).equals("B") ? "BASE64" : val.toUpperCase(Locale.ROOT);
                    break;
                case "CHARSET":
                    p.charset = val;
                    break;
                default:
                    p.other.add(key + "=" + val);
            }
        }
        return p;
    }

    private static List<String> splitOutsideQuotes(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') q = !q;
            if (c == sep && !q) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** The value as readable text: quoted-printable decoded, wrong-encoding text repaired. Still escaped. */
    private static String textValue(Prop p, Result r) {
        String v = p.value;
        boolean qp = "QUOTED-PRINTABLE".equals(p.encoding);
        if (!qp && !p.name.equals("URL") && QP_HEX.matcher(v).find()) qp = true;   // header lost, data still encoded
        if (qp) {
            v = decodeQp(v, p.charset, r);
            r.fix(R.string.ct_fix_qp);
        }
        return fixMojibake(v, r);
    }

    /** Quoted-printable ("=D8=A7") to text, using the declared charset (UTF-8 when none). */
    private static String decodeQp(String s, String charset, Result r) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                bo.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else if (c < 0x80) {
                bo.write(c);
            } else {
                byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bo.write(u, 0, u.length);
            }
        }
        return decodeBytes(bo.toByteArray(), charset, r).replace("\r\n", "\n").replace("\r", "\n");
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'n' || n == 'N') {
                    sb.append('\n');
                    i++;
                    continue;
                }
                if (n == ',' || n == ';' || n == '\\' || n == ':') {
                    sb.append(n);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Splits on a separator that is not escaped, then unescapes each part. */
    private static List<String> splitValue(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(i + 1));
                i++;
            } else if (c == sep) {
                out.add(unescape(cur.toString()));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(unescape(cur.toString()));
        return out;
    }

    private static String scrub(String s, Result r) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String t = CONTROL.matcher(s).replaceAll("");
        if (!t.equals(s)) r.fix(R.string.ct_fix_control);
        return t;
    }

    private static String typeString(Prop p) {
        Set<String> seen = new java.util.LinkedHashSet<>(p.types);
        StringBuilder sb = new StringBuilder();
        for (String t : seen) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    private static void apply(Card c, Prop p, Result r) {
        switch (p.name) {
            case "VERSION":
                c.version = p.value.trim();
                return;
            case "N": {
                List<String> a = splitValue(textValue(p, r), ';');
                c.family = scrub(get(a, 0).trim(), r);
                c.given = scrub(get(a, 1).trim(), r);
                c.additional = scrub(get(a, 2).trim(), r);
                c.prefix = scrub(get(a, 3).trim(), r);
                c.suffix = scrub(get(a, 4).trim(), r);
                return;
            }
            case "FN":
                c.fn = scrub(unescape(textValue(p, r)).trim(), r);
                return;
            case "TEL":
                c.tels.add(new Field(scrub(unescape(textValue(p, r)), r), typeString(p), p.group));
                return;
            case "EMAIL":
                c.emails.add(new Field(scrub(unescape(textValue(p, r)), r), typeString(p), p.group));
                return;
            case "ORG": {
                List<String> a = splitValue(textValue(p, r), ';');
                StringBuilder sb = new StringBuilder();
                for (String x : a) {
                    if (x.trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(x.trim());
                }
                if (c.org.isEmpty()) c.org = scrub(sb.toString(), r);
                return;
            }
            case "TITLE":
                if (c.title.isEmpty()) c.title = scrub(unescape(textValue(p, r)).trim(), r);
                return;
            case "NOTE": {
                String n = scrub(unescape(textValue(p, r)), r);
                c.note = c.note.isEmpty() ? n : c.note + "\n" + n;
                return;
            }
            case "URL":
                if (c.url.isEmpty()) {
                    c.url = scrub(textValue(p, r).trim(), r);
                    return;
                }
                break;   // a second URL is kept as an extra
            case "BDAY":
                if (c.bday.isEmpty()) {
                    c.bday = scrub(textValue(p, r).trim(), r);
                    return;
                }
                break;
            case "ADR": {
                List<String> a = splitValue(textValue(p, r), ';');
                Adr d = new Adr();
                for (int i = 0; i < 7; i++) d.parts[i] = scrub(get(a, i).trim(), r);
                d.types = typeString(p);
                d.group = p.group;
                c.adrs.add(d);
                return;
            }
            case "PHOTO":
            case "LOGO":
            case "SOUND":
            case "KEY": {
                Extra e = new Extra();
                e.group = p.group;
                e.name = p.name;
                boolean uri = false;
                for (String o : p.other) if (o.startsWith("VALUE=") && !o.equalsIgnoreCase("VALUE=BINARY")) uri = true;
                if (uri || p.value.trim().toLowerCase(Locale.ROOT).startsWith("http")) {
                    e.params = "VALUE=uri";
                    e.value = p.value.trim();
                    c.extras.add(e);
                    return;
                }
                String b64 = p.value.replaceAll("\\s+", "");
                // the "data:image/jpeg;base64," form of some exporters
                int comma = b64.indexOf("base64,");
                if (comma >= 0) b64 = b64.substring(comma + 7);
                boolean ok = b64.length() >= 16;
                if (ok) {
                    try {
                        byte[] dec = Base64.decode(b64, Base64.DEFAULT);
                        ok = dec.length > 8;
                    } catch (IllegalArgumentException ex) {
                        ok = false;
                    }
                }
                if (!ok) {
                    r.fix(R.string.ct_fix_photo);
                    return;
                }
                String type = p.types.isEmpty() ? "JPEG" : p.types.get(0);
                e.params = "ENCODING=b;TYPE=" + type;
                e.value = b64;
                c.extras.add(e);
                return;
            }
            default:
                break;
        }
        Extra e = new Extra();
        e.group = p.group;
        e.name = p.rawName;   // original case: Apple looks for exactly "X-ABLabel"
        StringBuilder pa = new StringBuilder();
        if (!p.types.isEmpty()) pa.append("TYPE=").append(typeString(p));
        for (String o : p.other) {
            if (pa.length() > 0) pa.append(';');
            pa.append(o);
        }
        e.params = pa.toString();
        e.value = scrub(textValue(p, r).replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", ""), r);
        c.extras.add(e);
    }

    private static String get(List<String> a, int i) {
        return i < a.size() ? a.get(i) : "";
    }

    // ---- validation of a finished card

    private static void finish(Card c, Result r) {
        if (c.version.isEmpty()) r.fix(R.string.ct_fix_version);

        // names
        String composed = joinNonEmpty(" ", c.prefix, c.given, c.additional, c.family, c.suffix);
        if (c.fn.isEmpty() && !composed.isEmpty()) {
            c.fn = composed;
            r.fix(R.string.ct_fix_name);
        } else if (c.fn.isEmpty()) {
            String alt = !c.org.isEmpty() ? c.org : !c.tels.isEmpty() ? c.tels.get(0).value.trim()
                    : !c.emails.isEmpty() ? c.emails.get(0).value.trim() : "";
            if (!alt.isEmpty()) {
                c.fn = alt;
                r.fix(R.string.ct_fix_name);
            }
        }
        if (composed.isEmpty() && !c.fn.isEmpty()) {
            c.given = c.fn;
            r.fix(R.string.ct_fix_name);
        }

        // phones
        List<Field> tels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Field f : c.tels) {
            String v = cleanPhone(f.value);
            if (v == null) {
                r.fix(R.string.ct_fix_phone);
                continue;
            }
            if (!v.equals(f.value.trim())) r.fix(R.string.ct_fix_phone);
            if (!seen.add(digits(v))) {
                r.fix(R.string.ct_fix_dupfield);
                continue;
            }
            f.value = v;
            tels.add(f);
        }
        c.tels.clear();
        c.tels.addAll(tels);

        // e-mails
        List<Field> mails = new ArrayList<>();
        Set<String> seenMail = new HashSet<>();
        for (Field f : c.emails) {
            String v = f.value.trim();
            if (v.toLowerCase(Locale.ROOT).startsWith("mailto:")) v = v.substring(7).trim();
            v = v.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\s]", "");
            if (!v.matches("[^@\\s]+@[^@\\s]+")) {
                r.fix(R.string.ct_fix_email);
                continue;
            }
            if (!seenMail.add(v.toLowerCase(Locale.ROOT))) {
                r.fix(R.string.ct_fix_dupfield);
                continue;
            }
            if (!v.equals(f.value)) r.fix(R.string.ct_fix_email);
            f.value = v;
            mails.add(f);
        }
        c.emails.clear();
        c.emails.addAll(mails);

        // a card with nothing a person could use
        if (c.fn.isEmpty()) {
            String alt = !c.note.isEmpty() ? firstLine(c.note) : !c.adrs.isEmpty() ? c.adrs.get(0).display() : "";
            if (!alt.isEmpty()) c.fn = alt;
        }
        if (c.fn.isEmpty() && c.tels.isEmpty() && c.emails.isEmpty()) {
            r.fix(R.string.ct_fix_empty);
            return;
        }
        r.cards.add(c);
    }

    /** Cleans a phone number; null when nothing usable is left. Arabic-Indic digits become ASCII digits. */
    static String cleanPhone(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '\u0660' && ch <= '\u0669') ch = (char) ('0' + (ch - '\u0660'));
            else if (ch >= '\u06F0' && ch <= '\u06F9') ch = (char) ('0' + (ch - '\u06F0'));
            else if (ch == '\u00A0' || ch == '\u2007' || ch == '\u202F') ch = ' ';
            sb.append(ch);
        }
        String s = sb.toString().trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("tel:")) s = s.substring(4);
        s = PHONE_JUNK.matcher(s).replaceAll("");
        s = s.replaceAll("\\s+", " ").trim();
        int d = digits(s).length();
        if (d == 0) return null;
        if (d < 3 && s.indexOf('*') < 0 && s.indexOf('#') < 0) return null;
        return s;
    }

    static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    static String joinNonEmpty(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.trim());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ editing helpers

    /** Name used to decide that two cards are the same person. */
    private static String nameKey(Card c) {
        return c.display().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String phoneKey(String v) {
        String d = digits(v);
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    /** Merges cards with the same name that share a number or an address. Returns how many were merged away. */
    public static int removeDuplicates(List<Card> cards) {
        List<Card> out = new ArrayList<>();
        Map<String, List<Card>> byName = new HashMap<>();
        int merged = 0;
        for (Card c : cards) {
            String key = nameKey(c);
            List<Card> same = byName.get(key);
            Card target = null;
            if (same != null && !key.isEmpty()) {
                for (Card o : same) {
                    if (sameBecause(o, c)) {
                        target = o;
                        break;
                    }
                }
            }
            if (target == null) {
                out.add(c);
                if (same == null) {
                    same = new ArrayList<>();
                    byName.put(key, same);
                }
                same.add(c);
            } else {
                mergeInto(target, c);
                merged++;
            }
        }
        cards.clear();
        cards.addAll(out);
        return merged;
    }

    private static boolean sameBecause(Card a, Card b) {
        if (a.tels.isEmpty() && b.tels.isEmpty() && a.emails.isEmpty() && b.emails.isEmpty()) return true;
        for (Field x : a.tels) for (Field y : b.tels) if (phoneKey(x.value).equals(phoneKey(y.value))) return true;
        for (Field x : a.emails) for (Field y : b.emails) if (x.value.equalsIgnoreCase(y.value)) return true;
        return false;
    }

    private static void mergeInto(Card t, Card o) {
        for (Field f : o.tels) {
            boolean have = false;
            for (Field x : t.tels) if (phoneKey(x.value).equals(phoneKey(f.value))) have = true;
            if (!have) t.tels.add(f);
        }
        for (Field f : o.emails) {
            boolean have = false;
            for (Field x : t.emails) if (x.value.equalsIgnoreCase(f.value)) have = true;
            if (!have) t.emails.add(f);
        }
        if (t.org.isEmpty()) t.org = o.org;
        if (t.title.isEmpty()) t.title = o.title;
        if (t.url.isEmpty()) t.url = o.url;
        if (t.bday.isEmpty()) t.bday = o.bday;
        if (t.note.isEmpty()) t.note = o.note;
        else if (!o.note.isEmpty() && !t.note.contains(o.note)) t.note = t.note + "\n" + o.note;
        if (t.adrs.isEmpty()) t.adrs.addAll(o.adrs);
        for (Extra e : o.extras) {
            boolean have = false;
            for (Extra x : t.extras) {
                if (x.name.equals(e.name) && (e.name.equals("PHOTO") || x.value.equals(e.value))) have = true;
            }
            if (!have) t.extras.add(e);
        }
    }

    public static void sortByName(List<Card> cards) {
        final Collator col = Collator.getInstance();
        java.util.Collections.sort(cards, new Comparator<Card>() {
            @Override
            public int compare(Card a, Card b) {
                return col.compare(a.display(), b.display());
            }
        });
    }

    // ------------------------------------------------------------------ writing

    /** Clean vCard 3.0 text (CRLF line ends, folded at 75 bytes) for all cards. */
    public static String write(List<Card> cards) {
        StringBuilder out = new StringBuilder();
        for (Card c : cards) {
            line(out, "BEGIN:VCARD");
            line(out, "VERSION:3.0");
            line(out, "N:" + esc(c.family) + ";" + esc(c.given) + ";" + esc(c.additional) + ";" + esc(c.prefix) + ";" + esc(c.suffix));
            String fn = c.fn.trim().isEmpty() ? c.display() : c.fn.trim();
            line(out, "FN:" + esc(fn));
            if (!c.org.trim().isEmpty()) line(out, "ORG:" + esc(c.org.trim()));
            if (!c.title.trim().isEmpty()) line(out, "TITLE:" + esc(c.title.trim()));
            for (Field f : c.tels) line(out, prop(f.group, "TEL", typeParam(f.types), oneLine(f.value)));
            for (Field f : c.emails) line(out, prop(f.group, "EMAIL", typeParam(f.types.isEmpty() ? "INTERNET" : f.types), oneLine(f.value)));
            for (Adr a : c.adrs) {
                StringBuilder v = new StringBuilder();
                for (int i = 0; i < 7; i++) {
                    if (i > 0) v.append(';');
                    v.append(esc(a.parts[i] == null ? "" : a.parts[i]));
                }
                line(out, prop(a.group, "ADR", typeParam(a.types), v.toString()));
            }
            if (!c.url.trim().isEmpty()) line(out, "URL:" + oneLine(c.url.trim()));
            if (!c.bday.trim().isEmpty()) line(out, "BDAY:" + oneLine(c.bday.trim()));
            if (!c.note.isEmpty()) line(out, "NOTE:" + esc(c.note));
            for (Extra e : c.extras) {
                String head = (e.group.isEmpty() ? "" : e.group + ".") + e.name + (e.params.isEmpty() ? "" : ";" + e.params);
                line(out, head + ":" + e.value);
            }
            line(out, "END:VCARD");
        }
        return out.toString();
    }

    private static String prop(String group, String name, String params, String value) {
        return (group == null || group.isEmpty() ? "" : group + ".") + name + params + ":" + value;
    }

    private static String typeParam(String types) {
        return types == null || types.isEmpty() ? "" : ";TYPE=" + types;
    }

    private static String oneLine(String s) {
        return s.replace("\r", "").replace("\n", " ");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "");
    }

    private static int utf8Len(int cp) {
        return cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
    }

    /** Appends one logical line, folded so that no physical line is longer than 75 bytes. */
    private static void line(StringBuilder out, String line) {
        int bytes = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int n = utf8Len(cp);
            if (bytes + n > 75) {
                out.append(cur).append("\r\n");
                cur.setLength(0);
                cur.append(' ');
                bytes = 1;
            }
            cur.appendCodePoint(cp);
            bytes += n;
            i += Character.charCount(cp);
        }
        out.append(cur).append("\r\n");
    }
}
