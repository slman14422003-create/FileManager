package com.fileman.app;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;

final class ShareItem {
    int id;
    String rel = "";
    long size;
    long mtime;
    File file;
    Uri uri;

    static final class Src implements Closeable {
        FileInputStream in;
        ParcelFileDescriptor pfd;
        FileChannel ch;

        @Override
        public void close() {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    Src open(Context c) throws IOException {
        Src s = new Src();
        try {
            if (file != null) {
                s.in = new FileInputStream(file);
            } else {
                s.pfd = c.getContentResolver().openFileDescriptor(uri, "r");
                if (s.pfd == null) throw new FileNotFoundException(rel);
                s.in = new FileInputStream(s.pfd.getFileDescriptor());
            }
            s.ch = s.in.getChannel();
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    static ShareItem of(File f, String rel) {
        ShareItem i = new ShareItem();
        i.file = f;
        i.rel = rel;
        i.size = f.length();
        i.mtime = f.lastModified();
        return i;
    }
}
