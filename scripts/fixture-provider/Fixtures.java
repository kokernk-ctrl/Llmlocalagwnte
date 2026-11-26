package dev.forge.fixtureprovider;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;
import java.io.*;

public final class Fixtures extends DocumentsProvider {
    private static final String TAG = "ForgeFixtures", ROOT = "fixtures", LABEL = "Forge Fixtures";
    private static final String[] FILES = {"forge-qwen-fixture.gguf", "forge-invalid.gguf"};
    private static final String MIME = "application/octet-stream";
    private static final String[] ROOT_COLS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_COLS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    @Override public boolean onCreate() {
        for (String name : FILES) {
            try (InputStream in = getContext().getAssets().open(name);
                 OutputStream out = getContext().openFileOutput(name, 0)) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException e) { throw new IllegalStateException("Cannot prepare " + name, e); }
        }
        return true;
    }

    @Override public Cursor queryRoots(String[] p) {
        Log.i(TAG, "queryRoots");
        MatrixCursor c = new MatrixCursor(p == null ? ROOT_COLS : p);
        c.newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, LABEL).add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda).add(Root.COLUMN_MIME_TYPES, MIME);
        return c;
    }

    private File fixture(String id) throws FileNotFoundException {
        for (String f : FILES) if (f.equals(id)) return getContext().getFileStreamPath(f);
        throw new FileNotFoundException("Not a fixture");
    }

    private void row(MatrixCursor c, String id) throws FileNotFoundException {
        boolean dir = ROOT.equals(id);
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, dir ? LABEL : id)
            .add(Document.COLUMN_MIME_TYPE, dir ? Document.MIME_TYPE_DIR : MIME).add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, dir ? null : Long.valueOf(fixture(id).length()));
    }

    @Override public Cursor queryDocument(String id, String[] p) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(p == null ? DOC_COLS : p);
        row(c, id);
        return c;
    }

    @Override public Cursor queryChildDocuments(String id, String[] p, String sort) throws FileNotFoundException {
        if (!ROOT.equals(id)) throw new FileNotFoundException("Not a directory");
        Log.i(TAG, "list fixtures");
        MatrixCursor c = new MatrixCursor(p == null ? DOC_COLS : p);
        for (String f : FILES) row(c, f);
        return c;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        if (ROOT.equals(parent)) for (String f : FILES) if (f.equals(child)) return true;
        return false;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        File f = fixture(id);
        Log.i(TAG, "open " + id + " bytes=" + f.length());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
