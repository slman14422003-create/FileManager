package com.fileman.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

final class ShareContacts {
    private ShareContacts() {
    }

    static final class Person {
        String key = "";
        String name = "";
    }

    static List<Person> list(Context c) {
        List<Person> out = new ArrayList<>();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY},
                    null, null, ContactsContract.Contacts.SORT_KEY_PRIMARY + " COLLATE LOCALIZED ASC");
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String k = cur.getString(0);
                String n = cur.getString(1);
                if (k == null || k.isEmpty()) continue;
                Person p = new Person();
                p.key = k;
                p.name = n == null || n.trim().isEmpty() ? "?" : n.trim();
                out.add(p);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    static String safeName(String n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length() && sb.length() < 40; i++) {
            char ch = n.charAt(i);
            sb.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? ch : '_');
        }
        String r = sb.toString().replaceAll("_+", "_");
        if (r.startsWith("_")) r = r.substring(1);
        return r.isEmpty() ? "contact" : r;
    }

    /** One .vcf file holding every chosen contact, produced by the system contacts provider. */
    static File export(Context c, List<Person> chosen, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("dir");
        String fname = chosen.size() == 1 ? safeName(chosen.get(0).name) + ".vcf" : "contacts-" + chosen.size() + ".vcf";
        File f = new File(dir, fname);
        ContentResolver cr = c.getContentResolver();
        byte[] buf = new byte[1 << 15];
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
            for (Person p : chosen) {
                Uri u = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, Uri.encode(p.key));
                AssetFileDescriptor afd = null;
                try {
                    afd = cr.openAssetFileDescriptor(u, "r");
                    if (afd == null) continue;
                    try (InputStream in = afd.createInputStream()) {
                        int r;
                        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                    }
                    o.write("\r\n".getBytes("UTF-8"));
                } catch (Throwable ignored) {
                } finally {
                    if (afd != null) {
                        try {
                            afd.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }
        return f;
    }
}
