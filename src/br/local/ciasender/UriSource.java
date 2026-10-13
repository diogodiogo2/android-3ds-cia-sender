package br.local.ciasender;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link FileServer.Source} backed by a content:// URI (for example one returned
 * by the system file picker). This works regardless of storage permissions, because
 * the picker grants access to exactly the item the user chose.
 */
public final class UriSource implements FileServer.Source {
    private final ContentResolver resolver;
    private final Uri uri;
    private final String name;
    private long size;   // < 0 means "not known yet"

    public UriSource(ContentResolver resolver, Uri uri, String name, long size) {
        this.resolver = resolver;
        this.uri = uri;
        this.name = name;
        this.size = size;
    }

    public String getName() {
        return name;
    }

    /** Size in bytes, or -1 if it cannot be determined. */
    public synchronized long length() {
        if (size <= 0) {
            ParcelFileDescriptor pfd = null;
            try {
                pfd = resolver.openFileDescriptor(uri, "r");
                size = (pfd == null) ? -1 : pfd.getStatSize();
            } catch (Exception e) {
                size = -1;
            } finally {
                if (pfd != null) {
                    try { pfd.close(); } catch (IOException ignored) {}
                }
            }
        }
        return size;
    }

    public InputStream open(long offset) throws IOException {
        final ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Cannot open " + uri);
        FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
        try {
            if (offset > 0) {
                try {
                    in.getChannel().position(offset);   // fast path for seekable files
                } catch (IOException notSeekable) {
                    long skipped = 0;
                    while (skipped < offset) {
                        long s = in.skip(offset - skipped);
                        if (s <= 0) throw new IOException("Cannot seek in " + uri);
                        skipped += s;
                    }
                }
            }
        } catch (IOException e) {
            try { in.close(); } catch (IOException ignored) {}
            try { pfd.close(); } catch (IOException ignored) {}
            throw e;
        }
        // Closing the stream must also close the descriptor we opened.
        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    pfd.close();
                }
            }
        };
    }
}
