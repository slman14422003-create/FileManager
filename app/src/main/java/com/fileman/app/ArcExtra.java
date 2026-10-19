package com.fileman.app;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Archive formats that need third-party code: 7z, RAR (v2-v4), bz2 and xz. {@link Arc} loads this class by name, so
 * deleting this file (and the three archive libraries in build.gradle) leaves the rest of the app fully working with
 * ZIP, TAR, TAR.GZ and GZ only.
 * <p>
 * Called through reflection: keep the method names and signatures.
 */
public final class ArcExtra {
    private ArcExtra() {
    }

    /** kind: 20 = bz2, 21 = xz. */
    public static InputStream stream(File f, int kind) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16);
        try {
            return kind == 20 ? new BZip2CompressorInputStream(in, true) : new XZCompressorInputStream(in, true);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /** kind: 22 = 7z, 23 = rar. */
    public static Arc.Backend open(File f, int kind, String password) throws IOException {
        return kind == 22 ? new SevenZ(f, password) : new Rar(f, password);
    }

    private static boolean looksLikeBadPassword(Throwable t) {
        String m = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("checksum") || m.contains("crc") || m.contains("corrupt") || m.contains("lzma")
                || m.contains("unexpected") || m.contains("invalid") || m.contains("bad") || m.contains("password")
                || m.contains("unsupported") || m.contains("data error") || m.contains("padding");
    }

    // ------------------------------------------------------------------ 7z

    private static final class SevenZ implements Arc.Backend {
        private final File f;
        private String password;

        SevenZ(File f, String password) throws IOException {
            this.f = f;
            this.password = password;
        }

        @Override
        public void setPassword(String p) {
            password = p;
        }

        private Arc.Unsupported need() {
            return new Arc.Unsupported("password", true, password != null);
        }

        private SevenZFile openFile() throws IOException {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                // the channel constructor avoids java.nio.file, which older Android versions do not have
                return password == null ? new SevenZFile(raf.getChannel()) : new SevenZFile(raf.getChannel(), password.toCharArray());
            } catch (PasswordRequiredException e) {
                raf.close();
                throw need();
            } catch (IOException | RuntimeException e) {
                raf.close();
                if (password != null && looksLikeBadPassword(e)) throw need();
                throw e;
            }
        }

        private static Arc.Item item(SevenZArchiveEntry e) {
            Arc.Item it = new Arc.Item();
            it.name = e.getName() == null ? "" : e.getName();
            it.dir = e.isDirectory();
            if (it.dir && !it.name.endsWith("/")) it.name += "/";
            it.size = e.getSize();
            it.method = "7z";
            if (e.getHasLastModifiedDate()) it.time = e.getLastModifiedDate().getTime();
            if (e.getHasCrc()) it.crc = e.getCrcValue();
            return it;
        }

        @Override
        public List<Arc.Item> list() throws IOException {
            List<Arc.Item> out = new ArrayList<>();
            try (SevenZFile z = openFile()) {
                SevenZArchiveEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isAntiItem()) continue;
                    out.add(item(e));
                    if (out.size() >= Arc.MAX_ENTRIES) break;
                }
            } catch (PasswordRequiredException e) {
                throw need();
            }
            return out;
        }

        @Override
        public void walk(Arc.Want want, Arc.Visitor v) throws IOException {
            try (SevenZFile z = openFile()) {
                SevenZArchiveEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory() || e.isAntiItem()) continue;
                    Arc.Item it = item(e);
                    String clean = Arc.clean(it.name);
                    if (clean == null) continue;
                    it.name = clean;
                    if (!want.test(it)) continue;
                    final SevenZFile sz = z;
                    try {
                        v.file(it, new InputStream() {
                            @Override
                            public int read() throws IOException {
                                return sz.read();
                            }

                            @Override
                            public int read(byte[] b, int off, int len) throws IOException {
                                return sz.read(b, off, len);
                            }
                        });
                    } catch (Arc.Unsupported ue) {
                        throw ue;
                    } catch (IOException ioe) {
                        if (password != null && looksLikeBadPassword(ioe)) throw need();
                        throw ioe;
                    }
                }
            } catch (PasswordRequiredException e) {
                throw need();
            }
        }

        @Override
        public void close() {
        }
    }

    // ------------------------------------------------------------------ RAR (versions 2 to 4; RAR5 is not supported by junrar)

    private static final class Rar implements Arc.Backend {
        private final File f;
        private String password;

        Rar(File f, String password) {
            this.f = f;
            this.password = password;
        }

        @Override
        public void setPassword(String p) {
            password = p;
        }

        private static Arc.Item item(FileHeader h) {
            Arc.Item it = new Arc.Item();
            String n = headerName(h).replace('\\', '/');
            it.dir = h.isDirectory();
            it.name = it.dir && !n.endsWith("/") ? n + "/" : n;
            it.size = h.getFullUnpackSize();
            it.packed = h.getFullPackSize();
            it.encrypted = h.isEncrypted();
            it.method = "RAR";
            if (h.getMTime() != null) it.time = h.getMTime().getTime();
            it.ref = h;
            return it;
        }

        /** Name of an entry; the accessor was renamed between junrar versions, so both spellings are tried. */
        private static String headerName(FileHeader h) {
            for (String m : new String[]{"getFileName", "getFileNameString"}) {
                try {
                    Object v = h.getClass().getMethod(m).invoke(h);
                    if (v != null) return v.toString();
                } catch (Exception ignored) {
                }
            }
            return "";
        }

        private Archive openArchive() throws IOException {
            try {
                return password == null ? new Archive(f) : openWithPassword();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {          // junrar's own RarException (RAR5, damaged, ...)
                String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                boolean enc = m.contains("password") || m.contains("encrypt");
                throw new Arc.Unsupported(m, enc, enc && password != null);
            }
        }

        private Archive openWithPassword() throws Exception {
            try {
                return (Archive) Archive.class.getConstructor(File.class, String.class).newInstance(f, password);
            } catch (NoSuchMethodException e) {
                return new Archive(f);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable t = e.getCause();
                if (t instanceof Exception) throw (Exception) t;
                throw e;
            }
        }

        @Override
        public List<Arc.Item> list() throws IOException {
            List<Arc.Item> out = new ArrayList<>();
            try (Archive a = openArchive()) {
                for (FileHeader h : a.getFileHeaders()) {
                    out.add(item(h));
                    if (out.size() >= Arc.MAX_ENTRIES) break;
                }
            }
            return out;
        }

        @Override
        public void walk(Arc.Want want, Arc.Visitor v) throws IOException {
            try (Archive a = openArchive()) {
                for (FileHeader h : a.getFileHeaders()) {
                    if (h.isDirectory()) continue;
                    Arc.Item it = item(h);
                    String clean = Arc.clean(it.name);
                    if (clean == null) continue;
                    it.name = clean;
                    if (!want.test(it)) continue;
                    if (h.isEncrypted() && password == null) throw new Arc.Unsupported("password", true);
                    try (InputStream in = a.getInputStream(h)) {
                        v.file(it, in);
                    } catch (Arc.Unsupported e) {
                        throw e;
                    } catch (IOException e) {
                        if (h.isEncrypted() && looksLikeBadPassword(e)) throw new Arc.Unsupported("password", true, true);
                        throw e;
                    } catch (Exception e) {
                        if (h.isEncrypted()) throw new Arc.Unsupported("password", true, true);
                        throw new IOException(String.valueOf(e.getMessage()));
                    }
                }
            }
        }

        @Override
        public void close() {
        }
    }
}
