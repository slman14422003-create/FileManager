package com.fileman.app;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Excel number-format codes (the numFmt of a cell style) applied to cell values: decimals, thousands,
 * percent, scientific, fractions, currency, dates and times, elapsed time and the four-section layout
 * (positive;negative;zero;text). Pure Java, no Android classes.
 */
final class NumFmt {
    private NumFmt() {
    }

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};
    private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

    private static final Map<Integer, String> BUILTIN = new HashMap<>();

    static {
        BUILTIN.put(1, "0");
        BUILTIN.put(2, "0.00");
        BUILTIN.put(3, "#,##0");
        BUILTIN.put(4, "#,##0.00");
        BUILTIN.put(9, "0%");
        BUILTIN.put(10, "0.00%");
        BUILTIN.put(11, "0.00E+00");
        BUILTIN.put(12, "# ?/?");
        BUILTIN.put(13, "# ??/??");
        BUILTIN.put(14, "yyyy-mm-dd");
        BUILTIN.put(15, "d-mmm-yy");
        BUILTIN.put(16, "d-mmm");
        BUILTIN.put(17, "mmm-yy");
        BUILTIN.put(18, "h:mm AM/PM");
        BUILTIN.put(19, "h:mm:ss AM/PM");
        BUILTIN.put(20, "h:mm");
        BUILTIN.put(21, "h:mm:ss");
        BUILTIN.put(22, "yyyy-mm-dd h:mm");
        BUILTIN.put(37, "#,##0 ;(#,##0)");
        BUILTIN.put(38, "#,##0 ;[Red](#,##0)");
        BUILTIN.put(39, "#,##0.00;(#,##0.00)");
        BUILTIN.put(40, "#,##0.00;[Red](#,##0.00)");
        BUILTIN.put(41, "#,##0 ;(#,##0)");
        BUILTIN.put(42, "#,##0 ;(#,##0)");
        BUILTIN.put(43, "#,##0.00 ;(#,##0.00)");
        BUILTIN.put(44, "#,##0.00 ;(#,##0.00)");
        BUILTIN.put(45, "mm:ss");
        BUILTIN.put(46, "[h]:mm:ss");
        BUILTIN.put(47, "mm:ss.0");
        BUILTIN.put(48, "##0.0E+0");
        BUILTIN.put(49, "@");
        for (int i = 27; i <= 36; i++) BUILTIN.put(i, "yyyy-mm-dd");
        for (int i = 50; i <= 58; i++) BUILTIN.put(i, "yyyy-mm-dd");
    }

    /** Format code of a built-in format id; null for General (0) and unknown ids. */
    static String builtin(int id) {
        return BUILTIN.get(id);
    }

    // ------------------------------------------------------------------ compiled format

    private static final class Tok {
        final char k;      // L literal, 0 # ? digit placeholders, . , % E (exponent) / (slash)
        final String s;

        Tok(char k, String s) {
            this.k = k;
            this.s = s;
        }
    }

    /** One piece of a date / time section. */
    private static final class DTok {
        final char k;      // y M(month) m(minute) d h s f(fraction of second) A(am/pm) L(literal) H M S (elapsed)
        final int n;
        final String s;

        DTok(char k, int n, String s) {
            this.k = k;
            this.n = n;
            this.s = s;
        }
    }

    private static final class Section {
        String cond;                     // e.g. ">=100", or null
        boolean date;
        boolean ampm;
        boolean hasText;                 // contains @
        final List<Tok> toks = new ArrayList<>();
        final List<DTok> dtoks = new ArrayList<>();
        String textPattern;              // for sections with @ : literal pieces with a "\u0001" marker for the text
    }

    static final class Compiled {
        final boolean general;
        final List<Section> secs = new ArrayList<>();
        final boolean anyDate;
        final boolean percent;

        Compiled(String code) {
            if (code == null || code.trim().isEmpty() || code.trim().equalsIgnoreCase("general")) {
                general = true;
                anyDate = false;
                percent = false;
                return;
            }
            general = false;
            boolean ad = false;
            boolean pc = false;
            for (String raw : splitSections(code)) {
                Section s = parseSection(raw);
                secs.add(s);
                if (s.date) ad = true;
                for (Tok t : s.toks) if (t.k == '%') pc = true;
            }
            anyDate = ad;
            percent = pc;
        }

        boolean isDate() {
            return anyDate;
        }

        /** True when the format only keeps text (no numeric placeholders at all), such as "@". */
        boolean textOnly() {
            if (general || secs.isEmpty()) return false;
            Section s = secs.get(0);
            return s.hasText && s.toks.isEmpty() && !s.date;
        }

        String text(String v) {
            if (general) return v;
            Section pick = null;
            if (secs.size() >= 4) pick = secs.get(3);
            else if (secs.size() == 1 && secs.get(0).hasText) pick = secs.get(0);
            if (pick == null || pick.textPattern == null) return v;
            return pick.textPattern.replace("\u0001", v);
        }

        String format(double v, boolean date1904) {
            if (general) return general(v);
            if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
            int n = secs.size();
            if (n == 0) return general(v);
            Section sec;
            boolean neg = v < 0;
            double a = Math.abs(v);
            boolean conditional = false;
            for (Section s : secs) if (s.cond != null) conditional = true;
            boolean showSign;
            if (conditional) {
                sec = secs.get(n - 1);
                for (Section s : secs) {
                    if (s.cond == null || test(v, s.cond)) {
                        sec = s;
                        break;
                    }
                }
                showSign = neg;
            } else if (n == 1) {
                sec = secs.get(0);
                showSign = neg;
            } else if (v > 0) {
                sec = secs.get(0);
                showSign = false;
            } else if (v < 0) {
                sec = secs.get(1);
                showSign = false;
            } else {
                sec = n >= 3 ? secs.get(2) : secs.get(0);
                showSign = false;
            }
            if (sec.hasText && sec.toks.isEmpty() && !sec.date) return sec.textPattern == null ? "" : sec.textPattern.replace("\u0001", "");
            if (sec.date) {
                String d = date(v, sec, date1904);
                return d == null ? general(v) : d;
            }
            String body = number(a, sec.toks);
            if (showSign && !isZeroText(body)) body = "-" + body;
            return body;
        }
    }

    private static boolean isZeroText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '1' && c <= '9') return false;
        }
        return true;
    }

    private static boolean test(double v, String cond) {
        try {
            int i = 0;
            while (i < cond.length() && "<>=".indexOf(cond.charAt(i)) >= 0) i++;
            String op = cond.substring(0, i);
            double x = Double.parseDouble(cond.substring(i));
            switch (op) {
                case "<":
                    return v < x;
                case "<=":
                    return v <= x;
                case ">":
                    return v > x;
                case ">=":
                    return v >= x;
                case "<>":
                    return v != x;
                default:
                    return v == x;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static final Map<String, Compiled> CACHE = new HashMap<>();

    /** Compiled form of a format code (cached). */
    static synchronized Compiled compile(String code) {
        String key = code == null ? "" : code;
        Compiled c = CACHE.get(key);
        if (c == null) {
            if (CACHE.size() > 400) CACHE.clear();
            c = new Compiled(code);
            CACHE.put(key, c);
        }
        return c;
    }

    /** Value of a numeric cell formatted with a format code; date1904 selects the Mac date system. */
    static String format(double v, String code, boolean date1904) {
        return compile(code).format(v, date1904);
    }

    // ------------------------------------------------------------------ parsing

    private static List<String> splitSections(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        boolean bracket = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quote) {
                cur.append(c);
                if (c == '"') quote = false;
            } else if (c == '"') {
                quote = true;
                cur.append(c);
            } else if (c == '\\' && i + 1 < code.length()) {
                cur.append(c).append(code.charAt(++i));
            } else if (c == '[') {
                bracket = true;
                cur.append(c);
            } else if (c == ']') {
                bracket = false;
                cur.append(c);
            } else if (c == ';' && !bracket) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean isElapsed(String inner) {
        if (inner.isEmpty()) return false;
        char c = Character.toLowerCase(inner.charAt(0));
        if (c != 'h' && c != 'm' && c != 's') return false;
        for (int i = 0; i < inner.length(); i++) if (Character.toLowerCase(inner.charAt(i)) != c) return false;
        return true;
    }

    private static Section parseSection(String raw) {
        Section sec = new Section();
        // leading condition such as [>=100]
        String s = raw;
        int i0 = 0;
        while (i0 < s.length() && s.charAt(i0) == '[') {
            int end = s.indexOf(']', i0);
            if (end < 0) break;
            String inner = s.substring(i0 + 1, end);
            if (!inner.isEmpty() && (inner.charAt(0) == '<' || inner.charAt(0) == '>' || inner.charAt(0) == '=')) {
                sec.cond = inner;
            } else if (!(inner.startsWith("$") || isElapsed(inner))) {
                // colour or locale tag at the start: dropped
            } else {
                break;
            }
            i0 = end + 1;
        }
        s = s.substring(i0);

        // is it a date / time section?
        boolean date = false;
        boolean ampm = false;
        {
            boolean quote = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quote) {
                    if (c == '"') quote = false;
                    continue;
                }
                if (c == '"') quote = true;
                else if (c == '\\' || c == '_' || c == '*') i++;
                else if (c == '[') {
                    int end = s.indexOf(']', i);
                    if (end < 0) break;
                    if (isElapsed(s.substring(i + 1, end))) date = true;
                    i = end;
                } else {
                    char l = Character.toLowerCase(c);
                    if (l == 'y' || l == 'm' || l == 'd' || l == 'h' || l == 's') date = true;
                    if (l == 'a' && (s.regionMatches(true, i, "am/pm", 0, 5) || s.regionMatches(true, i, "a/p", 0, 3))) {
                        date = true;
                        ampm = true;
                    }
                }
            }
        }
        sec.date = date;
        sec.ampm = ampm;
        if (date) {
            parseDate(s, sec);
        } else {
            parseNumber(s, sec);
        }
        return sec;
    }

    private static String currencyOf(String inner) {
        // [$EUR-407] -> EUR ; [$-409] -> empty
        if (!inner.startsWith("$")) return "";
        String t = inner.substring(1);
        int dash = t.indexOf('-');
        return dash >= 0 ? t.substring(0, dash) : t;
    }

    private static void parseNumber(String s, Section sec) {
        StringBuilder text = new StringBuilder();
        boolean seenText = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': {
                    int end = s.indexOf('"', i + 1);
                    if (end < 0) end = s.length();
                    String lit = s.substring(i + 1, end);
                    sec.toks.add(new Tok('L', lit));
                    text.append(lit);
                    i = end;
                    break;
                }
                case '\\':
                    if (i + 1 < s.length()) {
                        i++;
                        sec.toks.add(new Tok('L', String.valueOf(s.charAt(i))));
                        text.append(s.charAt(i));
                    }
                    break;
                case '_':
                    if (i + 1 < s.length()) i++;
                    sec.toks.add(new Tok('L', " "));
                    text.append(' ');
                    break;
                case '*':
                    if (i + 1 < s.length()) i++;
                    break;
                case '[': {
                    int end = s.indexOf(']', i);
                    if (end < 0) end = s.length() - 1;
                    String cur = currencyOf(s.substring(i + 1, end));
                    if (!cur.isEmpty()) {
                        sec.toks.add(new Tok('L', cur));
                        text.append(cur);
                    }
                    i = end;
                    break;
                }
                case '0':
                case '#':
                case '?':
                    sec.toks.add(new Tok(c, String.valueOf(c)));
                    break;
                case '.':
                case ',':
                case '%':
                case '/':
                    sec.toks.add(new Tok(c, String.valueOf(c)));
                    if (c == '%') text.append('%');
                    break;
                case 'E':
                case 'e':
                    if (i + 1 < s.length() && (s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-')) {
                        sec.toks.add(new Tok('E', String.valueOf(s.charAt(i + 1))));
                        i++;
                    } else {
                        sec.toks.add(new Tok('L', String.valueOf(c)));
                        text.append(c);
                    }
                    break;
                case '@':
                    sec.hasText = true;
                    seenText = true;
                    text.append('\u0001');
                    break;
                default:
                    sec.toks.add(new Tok('L', String.valueOf(c)));
                    text.append(c);
                    break;
            }
        }
        if (seenText) sec.textPattern = text.toString();
        // a text-only section keeps no numeric placeholders; drop pure literals so it reads as "text only"
        if (sec.hasText) {
            boolean digits = false;
            for (Tok t : sec.toks) if ("0#?".indexOf(t.k) >= 0) digits = true;
            if (!digits) sec.toks.clear();
        }
    }

    private static void parseDate(String s, Section sec) {
        List<DTok> out = sec.dtoks;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char l = Character.toLowerCase(c);
            if (c == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) end = s.length();
                out.add(new DTok('L', 0, s.substring(i + 1, end)));
                i = end;
            } else if (c == '\\') {
                if (i + 1 < s.length()) out.add(new DTok('L', 0, String.valueOf(s.charAt(++i))));
            } else if (c == '_') {
                if (i + 1 < s.length()) i++;
                out.add(new DTok('L', 0, " "));
            } else if (c == '*') {
                if (i + 1 < s.length()) i++;
            } else if (c == '[') {
                int end = s.indexOf(']', i);
                if (end < 0) end = s.length() - 1;
                String inner = s.substring(i + 1, end);
                if (isElapsed(inner)) {
                    out.add(new DTok(Character.toUpperCase(inner.charAt(0)) == 'H' ? 'H'
                            : Character.toUpperCase(inner.charAt(0)) == 'M' ? 'N' : 'S', inner.length(), ""));
                } else {
                    String cur = currencyOf(inner);
                    if (!cur.isEmpty()) out.add(new DTok('L', 0, cur));
                }
                i = end;
            } else if (l == 'y' || l == 'm' || l == 'd' || l == 'h' || l == 's') {
                int j = i;
                while (j < s.length() && Character.toLowerCase(s.charAt(j)) == l) j++;
                out.add(new DTok(l == 'm' ? 'm' : l, j - i, ""));
                i = j - 1;
            } else if (l == 'a' && s.regionMatches(true, i, "am/pm", 0, 5)) {
                out.add(new DTok('A', 2, ""));
                i += 4;
            } else if (l == 'a' && s.regionMatches(true, i, "a/p", 0, 3)) {
                out.add(new DTok('A', 1, ""));
                i += 2;
            } else if (c == '.' && !out.isEmpty() && out.get(out.size() - 1).k == 's'
                    && i + 1 < s.length() && s.charAt(i + 1) == '0') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) == '0') j++;
                out.add(new DTok('f', j - i - 1, ""));
                i = j - 1;
            } else {
                out.add(new DTok('L', 0, String.valueOf(c)));
            }
        }
        // m is a minute when it follows hours or precedes seconds, otherwise a month
        for (int i = 0; i < out.size(); i++) {
            DTok t = out.get(i);
            if (t.k != 'm') continue;
            boolean minute = false;
            for (int j = i - 1; j >= 0; j--) {
                DTok p = out.get(j);
                if (p.k == 'L') continue;
                minute = p.k == 'h' || p.k == 'H';
                break;
            }
            if (!minute) {
                for (int j = i + 1; j < out.size(); j++) {
                    DTok nx = out.get(j);
                    if (nx.k == 'L') continue;
                    minute = nx.k == 's' || nx.k == 'S';
                    break;
                }
            }
            out.set(i, new DTok(minute ? 'n' : 'M', t.n, ""));
        }
    }

    // ------------------------------------------------------------------ General

    static String general(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e11) return String.valueOf((long) v);
        double a = Math.abs(v);
        if (a != 0 && (a >= 1e15 || a < 1e-6)) {
            BigDecimal bd = new BigDecimal(v).round(new MathContext(6)).stripTrailingZeros();
            int exp = bd.precision() - bd.scale() - 1;
            BigDecimal mant = bd.movePointLeft(exp).stripTrailingZeros();
            return mant.toPlainString() + "E" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        }
        try {
            return new BigDecimal(v).round(new MathContext(11)).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    // ------------------------------------------------------------------ numbers

    private static int indexOf(List<Tok> t, char k) {
        for (int i = 0; i < t.size(); i++) if (t.get(i).k == k) return i;
        return -1;
    }

    private static boolean isDigit(Tok t) {
        return t.k == '0' || t.k == '#' || t.k == '?';
    }

    private static String number(double abs, List<Tok> toks) {
        if (toks.isEmpty()) return general(abs);
        int e = indexOf(toks, 'E');
        if (e >= 0) return scientific(abs, toks, e);
        if (isFraction(toks)) return fraction(abs, toks);
        BigDecimal bd = BigDecimal.valueOf(abs);
        for (Tok t : toks) if (t.k == '%') bd = bd.movePointRight(2);

        int dp = indexOf(toks, '.');
        int end = dp < 0 ? toks.size() : dp;
        int firstInt = -1;
        int lastInt = -1;
        for (int i = 0; i < end; i++) {
            if (isDigit(toks.get(i))) {
                if (firstInt < 0) firstInt = i;
                lastInt = i;
            }
        }
        // commas after the last integer placeholder scale by 1000, commas between placeholders group
        int scale = 0;
        int lastDigit = -1;
        for (int i = 0; i < toks.size(); i++) if (isDigit(toks.get(i))) lastDigit = i;
        for (int i = lastDigit + 1; lastDigit >= 0 && i < toks.size() && toks.get(i).k == ','; i++) scale++;
        boolean thousands = false;
        for (int i = firstInt + 1; firstInt >= 0 && i < lastInt; i++) {
            if (toks.get(i).k == ',') thousands = true;
        }
        if (scale > 0) bd = bd.movePointLeft(3 * scale);

        int fracCount = 0;
        if (dp >= 0) for (int i = dp + 1; i < toks.size(); i++) if (isDigit(toks.get(i))) fracCount++;
        BigDecimal rounded = bd.setScale(fracCount, RoundingMode.HALF_UP);
        String plain = rounded.toPlainString();
        String intStr = plain;
        String fracStr = "";
        int dot = plain.indexOf('.');
        if (dot >= 0) {
            intStr = plain.substring(0, dot);
            fracStr = plain.substring(dot + 1);
        }
        boolean needZero = false;
        for (int i = 0; i < end; i++) if (toks.get(i).k == '0') needZero = true;
        if (intStr.equals("0") && !needZero) intStr = "";
        if (thousands && intStr.length() > 3) {
            StringBuilder g = new StringBuilder();
            int cnt = 0;
            for (int i = intStr.length() - 1; i >= 0; i--) {
                g.append(intStr.charAt(i));
                if (++cnt % 3 == 0 && i > 0) g.append(',');
            }
            intStr = g.reverse().toString();
        }

        // integer side, filled from the right
        List<String> parts = new ArrayList<>();
        int pos = intStr.length() - 1;
        for (int i = end - 1; i >= 0; i--) {
            Tok k = toks.get(i);
            if (isDigit(k)) {
                while (pos >= 0 && intStr.charAt(pos) == ',') {
                    parts.add(",");
                    pos--;
                }
                if (pos >= 0) {
                    parts.add(String.valueOf(intStr.charAt(pos)));
                    pos--;
                } else if (k.k == '0') {
                    parts.add("0");
                } else if (k.k == '?') {
                    parts.add(" ");
                }
                if (i == firstInt) {
                    while (pos >= 0) parts.add(String.valueOf(intStr.charAt(pos--)));
                }
            } else if (k.k == 'L') {
                parts.add(k.s);
            } else if (k.k == '%') {
                parts.add("%");
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = parts.size() - 1; i >= 0; i--) out.append(parts.get(i));

        if (dp >= 0) {
            int sig = fracStr.length();
            while (sig > 0 && fracStr.charAt(sig - 1) == '0') sig--;
            out.append('.');
            int j = 0;
            for (int i = dp + 1; i < toks.size(); i++) {
                Tok k = toks.get(i);
                if (isDigit(k)) {
                    if (j < sig) out.append(fracStr.charAt(j));
                    else if (k.k == '0') out.append('0');
                    else if (k.k == '?') out.append(' ');
                    j++;
                } else if (k.k == 'L') {
                    out.append(k.s);
                } else if (k.k == '%') {
                    out.append('%');
                }
            }
        }
        return out.toString();
    }

    private static String scientific(double abs, List<Tok> toks, int e) {
        int expDigits = 0;
        for (int i = e + 1; i < toks.size(); i++) if (isDigit(toks.get(i))) expDigits++;
        if (expDigits == 0) expDigits = 2;
        List<Tok> mant = new ArrayList<>(toks.subList(0, e));
        int fracCount = 0;
        int dp = indexOf(mant, '.');
        if (dp >= 0) for (int i = dp + 1; i < mant.size(); i++) if (isDigit(mant.get(i))) fracCount++;
        int exp = 0;
        BigDecimal m = BigDecimal.valueOf(abs);
        if (abs != 0) {
            exp = m.precision() - m.scale() - 1;
            m = m.movePointLeft(exp);
            if (m.setScale(fracCount, RoundingMode.HALF_UP).compareTo(BigDecimal.TEN) >= 0) {
                exp++;
                m = m.movePointLeft(1);
            }
        }
        String ms = number(m.doubleValue(), mant);
        StringBuilder ex = new StringBuilder();
        ex.append(exp < 0 ? "-" : ("+".equals(toks.get(e).s) ? "+" : ""));
        String d = String.valueOf(Math.abs(exp));
        for (int i = d.length(); i < expDigits; i++) ex.append('0');
        ex.append(d);
        return ms + "E" + ex;
    }

    private static boolean isFraction(List<Tok> toks) {
        int sl = indexOf(toks, '/');
        if (sl <= 0 || indexOf(toks, '.') >= 0) return false;
        boolean before = false;
        for (int i = 0; i < sl; i++) if (isDigit(toks.get(i))) before = true;
        return before && sl + 1 < toks.size();
    }

    private static String fraction(double abs, List<Tok> toks) {
        int sl = indexOf(toks, '/');
        // numerator placeholders are the digit tokens right before the slash; anything earlier is the whole part
        int numStart = sl;
        while (numStart > 0 && isDigit(toks.get(numStart - 1))) numStart--;
        boolean whole = false;
        for (int i = 0; i < numStart; i++) if (isDigit(toks.get(i))) whole = true;
        int denCount = 0;
        StringBuilder fixed = new StringBuilder();
        for (int i = sl + 1; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (isDigit(t)) denCount++;
            else if (t.k == 'L' && t.s.length() == 1 && Character.isDigit(t.s.charAt(0)) && denCount == 0) fixed.append(t.s);
        }
        int maxDen;
        int fixedDen = fixed.length() > 0 ? Integer.parseInt(fixed.toString()) : 0;
        if (fixedDen > 0) maxDen = fixedDen;
        else maxDen = denCount <= 0 ? 9 : (int) Math.pow(10, Math.min(denCount, 3)) - 1;
        long w = whole ? (long) Math.floor(abs) : 0;
        double frac = abs - w;
        long bp = 0;
        long bq = 1;
        double best = Double.MAX_VALUE;
        for (int q = fixedDen > 0 ? fixedDen : 1; q <= maxDen; q++) {
            long p = Math.round(frac * q);
            double err = Math.abs(frac - (double) p / q);
            if (err < best - 1e-12) {
                best = err;
                bp = p;
                bq = q;
            }
            if (fixedDen > 0) break;
        }
        if (bp == bq) {
            if (whole) {
                w++;
                bp = 0;
            } else {
                bp = 1;
                bq = 1;
            }
        }
        if (bp == 0) return whole ? String.valueOf(w) : "0";
        if (whole && w > 0) return w + " " + bp + "/" + bq;
        return bp + "/" + bq;
    }

    // ------------------------------------------------------------------ dates and times

    private static String pad(long v, int n) {
        String s = String.valueOf(v);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < n; i++) sb.append('0');
        return sb.append(s).toString();
    }

    private static String date(double serial, Section sec, boolean date1904) {
        if (serial < 0) return null;
        double s = date1904 ? serial + 1462 : serial;
        long day = (long) Math.floor(s);
        double frac = s - day;
        long millis = Math.round(frac * 86400000.0);
        if (millis >= 86400000L) {
            day++;
            millis -= 86400000L;
        }
        long dayMs = (day - 25569L + (day < 60 ? 1 : 0)) * 86400000L;
        GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.setTimeInMillis(dayMs);
        int year = cal.get(GregorianCalendar.YEAR);
        int month = cal.get(GregorianCalendar.MONTH);
        int dom = cal.get(GregorianCalendar.DAY_OF_MONTH);
        int dow = cal.get(GregorianCalendar.DAY_OF_WEEK) - 1;
        long totalMs = Math.round(s * 86400000.0);
        int hour = (int) (millis / 3600000L);
        int minute = (int) (millis / 60000L % 60);
        int second = (int) (millis / 1000L % 60);
        int ms = (int) (millis % 1000);
        StringBuilder out = new StringBuilder();
        for (DTok t : sec.dtoks) {
            switch (t.k) {
                case 'L':
                    out.append(t.s);
                    break;
                case 'y':
                    out.append(t.n <= 2 ? pad(year % 100, 2) : pad(year, 4));
                    break;
                case 'M':
                    if (t.n == 1) out.append(month + 1);
                    else if (t.n == 2) out.append(pad(month + 1, 2));
                    else if (t.n == 3) out.append(MONTHS[month], 0, 3);
                    else if (t.n == 4) out.append(MONTHS[month]);
                    else out.append(MONTHS[month].charAt(0));
                    break;
                case 'n':
                    out.append(t.n >= 2 ? pad(minute, 2) : String.valueOf(minute));
                    break;
                case 'd':
                    if (t.n == 1) out.append(dom);
                    else if (t.n == 2) out.append(pad(dom, 2));
                    else if (t.n == 3) out.append(DAYS[dow], 0, 3);
                    else out.append(DAYS[dow]);
                    break;
                case 'h': {
                    int h = hour;
                    if (sec.ampm) {
                        h = h % 12;
                        if (h == 0) h = 12;
                    }
                    out.append(t.n >= 2 ? pad(h, 2) : String.valueOf(h));
                    break;
                }
                case 's':
                    out.append(t.n >= 2 ? pad(second, 2) : String.valueOf(second));
                    break;
                case 'f': {
                    out.append('.');
                    String f = pad(ms, 3);
                    out.append(t.n <= 3 ? f.substring(0, t.n) : f);
                    break;
                }
                case 'A':
                    if (t.n == 2) out.append(hour >= 12 ? "PM" : "AM");
                    else out.append(hour >= 12 ? "P" : "A");
                    break;
                case 'H':
                    out.append(pad(totalMs / 3600000L, t.n));
                    break;
                case 'N':
                    out.append(pad(totalMs / 60000L, t.n));
                    break;
                case 'S':
                    out.append(pad(totalMs / 1000L, t.n));
                    break;
                default:
                    break;
            }
        }
        return out.toString();
    }
}
