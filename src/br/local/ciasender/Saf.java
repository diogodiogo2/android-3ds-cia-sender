package br.local.ciasender;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Helpers for items returned by the system file/folder pickers (content:// URIs).
 * Everything that needs a newer Android API is delegated to {@link DocApi}.
 */
final class Saf {
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    private Saf() {}

    /** Wraps a picked file URI as a servable source, reading its name and size. */
    static FileServer.Source describe(ContentResolver cr, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new FileSource(new File(uri.getPath()));
        }
        String name = null;
        long size = -1;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
            // Fall through to the defaults below.
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.length() == 0) {
            String seg = uri.getLastPathSegment();
            if (seg == null) seg = "file";
            name = seg.substring(Math.max(seg.lastIndexOf('/'), seg.lastIndexOf(':')) + 1);
        }
        return new UriSource(cr, uri, name, size);
    }

    /**
     * Best-effort, purely cosmetic: turns an internal/SD-card document or folder URI
     * into a normal path such as /storage/emulated/0/Download/game.cia.
     * Returns null when the provider is not the built-in storage provider.
     */
    static String displayPath(Context ctx, Uri uri, boolean tree) {
        if ("file".equals(uri.getScheme())) return uri.getPath();
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(uri.getAuthority())) return null;
        String id = null;
        try {
            if (tree && Build.VERSION.SDK_INT >= 21) {
                id = DocApi.treeId(uri);
            } else if (!tree && Build.VERSION.SDK_INT >= 19) {
                id = DocApi.documentId(ctx, uri);
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (id == null) return null;
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon);
        String rel = id.substring(colon + 1);
        String base = "primary".equals(volume)
                ? Environment.getExternalStorageDirectory().getPath()
                : "/storage/" + volume;
        return rel.length() == 0 ? base : base + "/" + rel;
    }

    /** Lists the .cia files directly inside a picked folder tree (Android 5.0+ only). */
    static List<FileServer.Source> listTree(ContentResolver cr, Uri tree) throws IOException {
        return DocApi.listTree(cr, tree);
    }
}
