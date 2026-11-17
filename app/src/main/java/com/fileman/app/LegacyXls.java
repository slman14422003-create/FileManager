package com.fileman.app;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Reader for the Excel 97-2003 binary format (.xls, BIFF8): sheet names, text, numbers, dates, booleans and the
 * cached results of formulas. Formatting and charts are ignored. Rows and columns are capped like the xlsx reader.
 */
final class LegacyXls {
    static final int MAX_ROWS = 2000, MAX_COLS = 50;

    static final class Sheet {
        String name = "";
        final List<String[]> rows = new ArrayList<>();
        boolean truncated;
    }

    private LegacyXls() {
    }

    // ------------------------------------------------------------------ record reader

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static double f64(byte[] b, int o) {
        return Double.longBitsToDouble(u32(b, o) | (u32(b, o + 4) << 32));
    }

    /** Reads BIFF strings, aware of CONTINUE records that restart the character-width flag. */
    private static final class Chunks {
        final List<byte[]> parts = new ArrayList<>();
        int part, pos;

        int remaining() {
            return parts.get(part).length - pos;
        }

        boolean more() {
            while (part < parts.size() && pos >= parts.get(part).length) {
                part++;
                pos = 0;
            }
            return part < parts.size();
        }

        int u8() {
            more();
            return parts.get(part)[pos++] & 0xFF;
        }

        int u16() {
            return u8() | (u8() << 8);
        }

        long u32() {
            return (u16() & 0xFFFFL) | ((long) u16() << 16);
        }

        void skip(long n) {
            while (n > 0 && more()) {
                int k = (int) Math.min(n, remaining());
                pos += k;
                n -= k;
            }
        }

        /** Unicode string whose length/flags are read here. */
        String string() {
            int cch = u16();
            return stringBody(cch);
        }

        String stringBody(int cch) {
            int flags = u8();
            boolean hi = (flags & 1) != 0;
            int runs = (flags & 8) != 0 ? u16() : 0;
            long ext = (flags & 4) != 0 ? u32() : 0;
            StringBuilder sb = new StringBuilder(Math.min(cch, 1 << 16));
            int left = cch;
            while (left > 0 && more()) {
                int avail = remaining();
                int per = hi ? 2 : 1;
                int n = Math.min(left, avail / per);
                if (n == 0) {          // one wide char split across a boundary (rare): take what we can
                    n = 1;
                }
                for (int i = 0; i < n; i++) {
                    if (hi) {
                        int lo = u8();
                        int h = more() ? u8() : 0;
                        sb.append((char) (lo | (h << 8)));
                    } else {
                        sb.append((char) u8());
                    }
                }
                left -= n;
                if (left > 0 && !more()) break;
                if (left > 0 && pos == 0) {   // string continues in a CONTINUE record: it starts with a new flag byte
                    hi = (u8() & 1) != 0;
                }
            }
            skip(runs * 4L + ext);
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ workbook

    static List<Sheet> read(byte[] wb) throws IOException {
        List<Sheet> sheets = new ArrayList<>();
        List<long[]> boundsheets = new ArrayList<>();       // {offset, type}
        List<String> sheetNames = new ArrayList<>();
        List<String> sst = new ArrayList<>();
        Map<Integer, String> formats = new HashMap<>();
        List<Integer> xfFormat = new ArrayList<>();
        boolean date1904 = false;

        int pos = 0;
        boolean biff8 = true;
        // ---- globals
        while (pos + 4 <= wb.length) {
            int id = u16(wb, pos), len = u16(wb, pos + 2);
            int d = pos + 4;
            if (d + len > wb.length) break;
            if (id == 0x0809 && pos == 0) {
                int ver = u16(wb, d);
                if (ver < 0x0600) biff8 = false;
            }
            if (id == 0x000A) break;            // EOF of globals
            if (id == 0x0022 && len >= 2) date1904 = u16(wb, d) == 1;
            else if (id == 0x0085 && len >= 8) {
                int nameLen = wb[d + 6] & 0xFF;
                int fl = wb[d + 7] & 0xFF;
                String nm = (fl & 1) != 0
                        ? new String(wb, d + 8, Math.min(nameLen * 2, len - 8), StandardCharsets.UTF_16LE)
                        : new String(wb, d + 8, Math.min(nameLen, len - 8), StandardCharsets.ISO_8859_1);
                boundsheets.add(new long[]{u32(wb, d), wb[d + 5] & 0xFF});
                sheetNames.add(nm);
            } else if (id == 0x00FC) {          // SST + its CONTINUE records
                Chunks ch = new Chunks();
                ch.parts.add(java.util.Arrays.copyOfRange(wb, d, d + len));
                int p2 = d + len;
                while (p2 + 4 <= wb.length && u16(wb, p2) == 0x003C) {
                    int l2 = u16(wb, p2 + 2);
                    if (p2 + 4 + l2 > wb.length) break;
                    ch.parts.add(java.util.Arrays.copyOfRange(wb, p2 + 4, p2 + 4 + l2));
                    p2 += 4 + l2;
                }
                ch.skip(4);
                long unique = ch.u32();
                for (long i = 0; i < unique && i < 2_000_000 && ch.more(); i++) sst.add(ch.string());
                pos = p2;
                continue;
            } else if (id == 0x041E && len >= 4) {      // FORMAT
                int fid = u16(wb, d);
                Chunks ch = new Chunks();
                ch.parts.add(java.util.Arrays.copyOfRange(wb, d + 2, d + len));
                formats.put(fid, ch.string());
            } else if (id == 0x00E0 && len >= 4) {      // XF
                xfFormat.add(u16(wb, d + 2));
            }
            pos = d + len;
        }
        if (!biff8) throw new IOException("old BIFF version");

        // ---- sheets
        for (int s = 0; s < boundsheets.size(); s++) {
            if (boundsheets.get(s)[1] != 0) continue;      // worksheets only (no charts / macros)
            Sheet sh = new Sheet();
            sh.name = sheetNames.get(s);
            readSheet(wb, (int) boundsheets.get(s)[0], sh, sst, formats, xfFormat, date1904);
            sheets.add(sh);
        }
        return sheets;
    }

    private static void put(Sheet sh, int row, int col, String v) {
        if (row >= MAX_ROWS) {
            sh.truncated = true;
            return;
        }
        if (col >= MAX_COLS) {
            sh.truncated = true;
            return;
        }
        while (sh.rows.size() <= row) sh.rows.add(new String[0]);
        String[] r = sh.rows.get(row);
        if (r.length <= col) {
            r = java.util.Arrays.copyOf(r, col + 1);
            sh.rows.set(row, r);
        }
        r[col] = v;
    }

    private static void readSheet(byte[] wb, int start, Sheet sh, List<String> sst, Map<Integer, String> formats,
                                  List<Integer> xfFormat, boolean date1904) {
        int pos = start;
        int pendingRow = -1, pendingCol = -1;
        int pendingXf = 0;
        boolean first = true;
        while (pos + 4 <= wb.length) {
            int id = u16(wb, pos), len = u16(wb, pos + 2);
            int d = pos + 4;
            if (d + len > wb.length) break;
            if (first) {
                first = false;
                if (id != 0x0809) return;
            } else if (id == 0x000A) {
                return;
            }
            try {
                switch (id) {
                    case 0x00FD: {   // LABELSST
                        int idx = (int) u32(wb, d + 6);
                        if (idx >= 0 && idx < sst.size()) put(sh, u16(wb, d), u16(wb, d + 2), sst.get(idx));
                        break;
                    }
                    case 0x0203: {   // NUMBER
                        put(sh, u16(wb, d), u16(wb, d + 2), num(f64(wb, d + 6), u16(wb, d + 4), formats, xfFormat, date1904));
                        break;
                    }
                    case 0x027E: {   // RK
                        put(sh, u16(wb, d), u16(wb, d + 2), num(rk(u32(wb, d + 6)), u16(wb, d + 4), formats, xfFormat, date1904));
                        break;
                    }
                    case 0x00BD: {   // MULRK
                        int row = u16(wb, d), c0 = u16(wb, d + 2);
                        int n = (len - 6) / 6;
                        for (int i = 0; i < n; i++) {
                            int o = d + 4 + i * 6;
                            put(sh, row, c0 + i, num(rk(u32(wb, o + 2)), u16(wb, o), formats, xfFormat, date1904));
                        }
                        break;
                    }
                    case 0x0204: {   // LABEL (BIFF8 string)
                        Chunks ch = new Chunks();
                        ch.parts.add(java.util.Arrays.copyOfRange(wb, d + 6, d + len));
                        put(sh, u16(wb, d), u16(wb, d + 2), ch.string());
                        break;
                    }
                    case 0x0205: {   // BOOLERR
                        int v = wb[d + 6] & 0xFF;
                        boolean err = (wb[d + 7] & 0xFF) == 1;
                        put(sh, u16(wb, d), u16(wb, d + 2), err ? errText(v) : (v != 0 ? "TRUE" : "FALSE"));
                        break;
                    }
                    case 0x0006: {   // FORMULA: cached result
                        int row = u16(wb, d), col = u16(wb, d + 2), xf = u16(wb, d + 4);
                        if (u16(wb, d + 12) == 0xFFFF) {
                            int t = wb[d + 6] & 0xFF;
                            if (t == 0) {            // string follows in a STRING record
                                pendingRow = row;
                                pendingCol = col;
                                pendingXf = xf;
                            } else if (t == 1) {
                                put(sh, row, col, (wb[d + 8] & 0xFF) != 0 ? "TRUE" : "FALSE");
                            } else if (t == 2) {
                                put(sh, row, col, errText(wb[d + 8] & 0xFF));
                            }
                        } else {
                            put(sh, row, col, num(f64(wb, d + 6), xf, formats, xfFormat, date1904));
                        }
                        break;
                    }
                    case 0x0207: {   // STRING (value of the formula just before)
                        if (pendingRow >= 0) {
                            Chunks ch = new Chunks();
                            ch.parts.add(java.util.Arrays.copyOfRange(wb, d, d + len));
                            put(sh, pendingRow, pendingCol, ch.string());
                            pendingRow = -1;
                        }
                        break;
                    }
                    default:
                        break;
                }
            } catch (RuntimeException ignored) {
                // one damaged record must not hide the rest of the sheet
            }
            pos = d + len;
        }
    }

    private static double rk(long v) {
        double d;
        if ((v & 2) != 0) {
            d = (int) v >> 2;
        } else {
            d = Double.longBitsToDouble((v & 0xFFFFFFFCL) << 32);
        }
        return (v & 1) != 0 ? d / 100.0 : d;
    }

    private static String errText(int code) {
        switch (code) {
            case 0x00: return "#NULL!";
            case 0x07: return "#DIV/0!";
            case 0x0F: return "#VALUE!";
            case 0x17: return "#REF!";
            case 0x1D: return "#NAME?";
            case 0x24: return "#NUM!";
            case 0x2A: return "#N/A";
            default: return "#ERR";
        }
    }

    // ------------------------------------------------------------------ numbers and dates

    private static final Set<Integer> BUILTIN_DATES = new HashSet<>(java.util.Arrays.asList(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47));

    private static boolean isDate(int xf, Map<Integer, String> formats, List<Integer> xfFormat) {
        if (xf < 0 || xf >= xfFormat.size()) return false;
        int fid = xfFormat.get(xf);
        if (BUILTIN_DATES.contains(fid)) return true;
        String code = formats.get(fid);
        if (code == null) return false;
        String c = code.toLowerCase(Locale.ROOT).replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "");
        return c.matches(".*[ymdhs].*") && !c.contains("general") && !c.matches(".*[0#]\\.?[0#]*%.*");
    }

    private static String num(double v, int xf, Map<Integer, String> formats, List<Integer> xfFormat, boolean date1904) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "#NUM!";
        if (isDate(xf, formats, xfFormat) && v >= 0 && v < 2958466) return date(v, date1904);
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return new BigDecimal(v).round(new MathContext(12)).stripTrailingZeros().toPlainString();
    }

    private static String date(double serial, boolean date1904) {
        long days = (long) Math.floor(serial);
        double frac = serial - days;
        if (!date1904 && days >= 60) days -= 1;        // Excel counts a non-existent 1900-02-29
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        if (date1904) c.set(1904, Calendar.JANUARY, 1);
        else c.set(1899, Calendar.DECEMBER, 31);
        c.add(Calendar.DAY_OF_MONTH, (int) days);
        String d = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        if (frac > 1e-9) {
            int secs = (int) Math.round(frac * 86400);
            d += String.format(Locale.US, " %02d:%02d", (secs / 3600) % 24, (secs / 60) % 60);
        }
        return d;
    }
}
