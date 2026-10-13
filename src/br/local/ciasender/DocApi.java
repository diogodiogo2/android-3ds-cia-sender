package br.local.ciasender;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Code that needs the Storage Access Framework ({@link DocumentsContract}, Android 4.4+
 * for documents, 5.0+ for folder trees). It lives in its own class so that older Android
 * versions never load references to classes they do not have; callers check
 * Build.VERSION.SDK_INT first.
 */
final class DocApi {
    private DocApi() {}

    /** Document id of a picked file, e.g. "primary:Download/game.cia"; null if not a document URI. */
    static String documentId(Context ctx, Uri uri) {
        return DocumentsContract.isDocumentUri(ctx, uri) ? DocumentsContract.getDocumentId(uri) : null;
    }

    /** Document id of a picked folder tree, e.g. "primary:Download". */
    static String treeId(Uri tree) {
        return DocumentsContract.getTreeDocumentId(tree);
    }

    /** Lists the .cia files directly inside a folder picked with ACTION_OPEN_DOCUMENT_TREE. */
    static List<FileServer.Source> listTree(ContentResolver cr, Uri tree) throws IOException {
        List<FileServer.Source> result = new ArrayList<FileServer.Source>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Cursor c = cr.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null);
        if (c == null) throw new IOException("Cannot list " + tree);
        try {
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? -1 : c.getLong(2);
                String mime = c.getString(3);
                if (name == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                if (!name.toLowerCase(Locale.ROOT).endsWith(".cia")) continue;
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                result.add(new UriSource(cr, doc, name, size));
            }
        } finally {
            c.close();
        }
        Collections.sort(result, new Comparator<FileServer.Source>() {
            public int compare(FileServer.Source a, FileServer.Source b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return result;
    }
}
