package com.fileman.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Formatting helpers (sizes, relative dates). */
public final class Fmt {
    private Fmt() {
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    public static String date(long t) {
        if (t <= 0) return "";
        return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new Date(t));
    }

    private static String unit(long n, String one, String two, String few, String many) {
        if (n == 1) return one;
        if (n == 2) return two;
        if (n >= 3 && n <= 10) return n + " " + few;
        return n + " " + many;
    }

    private static String en(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s") + " ago";
    }

    /** Relative time ("منذ 3 ساعات" / "3 hours ago"). */
    public static String ago(long t) {
        if (t <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        boolean ar = Lang.isAr();
        if (s < 45) return ar ? "الآن" : "just now";
        long m = s / 60;
        if (m < 60) {
            m = Math.max(1, m);
            return ar ? "منذ " + unit(m, "دقيقة", "دقيقتين", "دقائق", "دقيقة") : en(m, "minute");
        }
        long h = m / 60;
        if (h < 24) return ar ? "منذ " + unit(h, "ساعة", "ساعتين", "ساعات", "ساعة") : en(h, "hour");
        long d = h / 24;
        if (d < 30) return ar ? "منذ " + unit(d, "يوم", "يومين", "أيام", "يومًا") : en(d, "day");
        long mo = d / 30;
        if (mo < 12) return ar ? "منذ " + unit(mo, "شهر", "شهرين", "أشهر", "شهرًا") : en(mo, "month");
        long y = Math.max(1, d / 365);
        return ar ? "منذ " + unit(y, "سنة", "سنتين", "سنوات", "سنة") : en(y, "year");
    }
}
