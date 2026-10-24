package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Writes what a {@link PrintDocumentAdapter} (for example the one of a WebView) lays out straight to a PDF file, without
 * the system print dialog. It lives in the android.print package because the result callbacks have package-private
 * constructors.
 */
public final class PdfPrinter {
    public interface Done {
        void finished(boolean ok);
    }

    private PdfPrinter() {
    }

    public static void print(final PrintDocumentAdapter adapter, PrintAttributes attrs, final File out, final Done done) {
        try {
            adapter.onStart();
            adapter.onLayout(null, attrs, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
                @Override
                public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                    ParcelFileDescriptor fd = null;
                    try {
                        fd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE
                                | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
                        final ParcelFileDescriptor target = fd;
                        adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, target, new CancellationSignal(),
                                new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override
                                    public void onWriteFinished(PageRange[] pages) {
                                        close(target);
                                        adapter.onFinish();
                                        done.finished(out.length() > 0);
                                    }

                                    @Override
                                    public void onWriteFailed(CharSequence error) {
                                        close(target);
                                        adapter.onFinish();
                                        done.finished(false);
                                    }

                                    @Override
                                    public void onWriteCancelled() {
                                        close(target);
                                        adapter.onFinish();
                                        done.finished(false);
                                    }
                                });
                    } catch (Throwable e) {
                        close(fd);
                        adapter.onFinish();
                        done.finished(false);
                    }
                }

                @Override
                public void onLayoutFailed(CharSequence error) {
                    adapter.onFinish();
                    done.finished(false);
                }

                @Override
                public void onLayoutCancelled() {
                    adapter.onFinish();
                    done.finished(false);
                }
            }, null);
        } catch (Throwable e) {
            done.finished(false);
        }
    }

    private static void close(ParcelFileDescriptor fd) {
        try {
            if (fd != null) fd.close();
        } catch (Exception ignored) {
        }
    }
}
