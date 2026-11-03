package com.fileman.app;

import java.security.SecureRandom;
import java.util.Locale;

final class ShareProto {
    private ShareProto() {
    }

    static final int MAGIC = 0x464D5348;
    static final int VERSION = 2;

    static final int C_LIST = 1;
    static final int C_GET = 2;
    static final int C_PUT = 3;
    static final int C_BATCH = 4;
    static final int C_BYE = 5;
    static final int C_PING = 6;

    static final int OK = 0;
    static final int NOT_FOUND = 1;
    static final int DENIED = 2;
    static final int ERR = 3;

    static final long CHUNK = 4L << 20;
    static final long BIG = 8L << 20;
    static final int BUF = 256 << 10;
    static final int BATCH_FILES = 128;
    static final long BATCH_BYTES = 16L << 20;
    static final int MAX_ITEMS = 300000;

    static String newToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        if (s == null || s.length() != 32) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi * 16 + lo);
        }
        return out;
    }

    static String cleanRel(String rel) {
        if (rel == null) return null;
        String r = rel.replace('\\', '/');
        StringBuilder out = new StringBuilder();
        for (String seg : r.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                boolean bad = c < 32 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|';
                s.append(bad ? '_' : c);
            }
            if (out.length() > 0) out.append('/');
            out.append(s);
        }
        if (out.length() == 0 || out.length() > 400) return null;
        return out.toString();
    }
}
