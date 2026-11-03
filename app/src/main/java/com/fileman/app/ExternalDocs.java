package com.fileman.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Exposes shared storage as a documents provider. The picker screens hand out URIs from this provider,
 * so other apps get grants they can keep (persistable) and can browse a whole chosen folder, the same
 * way they do with the system Files app. A document id is the absolute path of the file.
 */
public class ExternalDocs extends DocumentsProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".docs";
    private static final String ROOT_ID = "primary";

    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES
    };
    private static final String[] DOC_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
    };

    static String docIdFor(File f) {
        return f.getAbsolutePath();
    }

    static Uri documentUri(File f) {
        return android.provider.DocumentsContract.buildDocumentUri(AUTHORITY, docIdFor(f));
    }

    static Uri treeUri(File dir) {
        return android.provider.DocumentsContract.buildTreeDocumentUri(AUTHORITY, docIdFor(dir));
    }

    private static File base() {
        return Environment.getExternalStorageDirectory();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The file behind a document id, refusing anything outside shared storage or inside private data. */
    private File fileFor(String docId) throws FileNotFoundException {
        if (docId == null) throw new FileNotFoundException();
        File f = new File(docId);
        try {
            String canon = f.getCanonicalPath();
            String root = base().getCanonicalPath();
            if (!canon.equals(root) && !canon.startsWith(root + File.separator)) {
                throw new FileNotFoundException("outside storage");
            }
            if (getContext() != null && !Safe.mayExpose(getContext(), f)) {
                throw new FileNotFoundException("private");
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return f;
    }

    private static String mimeOf(File f) {
        if (f.isDirectory()) return Document.MIME_TYPE_DIR;
        String n = f.getName();
        int i = n.lastIndexOf('.');
        if (i >= 0 && i < n.length() - 1) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(i + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    private void addRow(MatrixCursor c, File f) {
        int flags = 0;
        if (f.isDirectory()) {
            if (f.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (f.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        File parent = f.getParentFile();
        if (parent != null && parent.canWrite() && !f.equals(base())) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }
        String mime = mimeOf(f);
        MatrixCursor.RowBuilder b = c.newRow();
        b.add(Document.COLUMN_DOCUMENT_ID, docIdFor(f));
        b.add(Document.COLUMN_DISPLAY_NAME, f.equals(base()) ? getContext().getString(R.string.app_name) : f.getName());
        b.add(Document.COLUMN_SIZE, f.isFile() ? f.length() : null);
        b.add(Document.COLUMN_MIME_TYPE, mime);
        b.add(Document.COLUMN_LAST_MODIFIED, f.lastModified());
        b.add(Document.COLUMN_FLAGS, flags);
    }

    private static String[] cols(String[] projection, String[] all) {
        return projection != null ? projection : all;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(cols(projection, ROOT_COLUMNS));
        MatrixCursor.RowBuilder b = c.newRow();
        b.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        b.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY);
        b.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        b.add(Root.COLUMN_TITLE, getContext().getString(R.string.app_name));
        b.add(Root.COLUMN_DOCUMENT_ID, docIdFor(base()));
        b.add(Root.COLUMN_AVAILABLE_BYTES, base().getFreeSpace());
        b.add(Root.COLUMN_MIME_TYPES, "*/*");
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(cols(projection, DOC_COLUMNS));
        File f = fileFor(documentId);
        if (!f.exists()) throw new FileNotFoundException();
        addRow(c, f);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(cols(projection, DOC_COLUMNS));
        File dir = fileFor(parentDocumentId);
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (getContext() != null && !Safe.mayExpose(getContext(), k)) continue;
                addRow(c, k);
            }
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File f = fileFor(documentId);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File dir = fileFor(parentDocumentId);
        if (!dir.isDirectory()) throw new FileNotFoundException();
        String name = displayName == null ? "" : displayName.replace('/', '_').trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) throw new FileNotFoundException();
        File out = uniqueIn(dir, name, Document.MIME_TYPE_DIR.equals(mimeType));
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!out.mkdir()) throw new FileNotFoundException();
            } else if (!out.createNewFile()) {
                throw new FileNotFoundException();
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return docIdFor(out);
    }

    /** A name that does not exist yet in the folder: "name (1).ext", "name (2).ext" and so on. */
    static File uniqueIn(File dir, String name, boolean folder) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = folder ? -1 : name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; n < 10000; n++) {
            f = new File(dir, stem + " (" + n + ")" + ext);
            if (!f.exists()) return f;
        }
        return f;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File f = fileFor(documentId);
        if (f.equals(base()) || !deleteTree(f)) throw new FileNotFoundException();
    }

    private static boolean deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (!deleteTree(k)) return false;
        }
        return f.delete();
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File f = fileFor(documentId);
        String name = displayName == null ? "" : displayName.replace('/', '_').trim();
        if (name.isEmpty() || f.equals(base())) throw new FileNotFoundException();
        File to = new File(f.getParentFile(), name);
        if (to.exists() || !f.renameTo(to)) throw new FileNotFoundException();
        return docIdFor(to);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String p = new File(parentDocumentId).getCanonicalPath();
            String d = new File(documentId).getCanonicalPath();
            return d.equals(p) || d.startsWith(p + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeOf(fileFor(documentId));
    }
}
