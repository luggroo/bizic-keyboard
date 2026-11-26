package biz.tujia.ime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Lets the selected document viewer read one cached publication by opaque ID. */
public final class LearningResourceProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private String id(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !(getContext().getPackageName() + ".learning.resources").equals(uri.getAuthority())
                || uri.getEncodedQuery() != null || uri.getEncodedFragment() != null
                || uri.getEncodedPath() == null || !uri.getEncodedPath().matches("/[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid resource URI");
        }
        return uri.getEncodedPath().substring(1);
    }

    @Override public String getType(Uri uri) {
        try { return OptionalResources.entry(getContext(), id(uri)).optString("type"); }
        catch (IOException e) { return null; }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            File file = OptionalResources.file(getContext(), id(uri));
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) { throw new FileNotFoundException("Resource unavailable"); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        try {
            String id = id(uri);
            File f = OptionalResources.file(getContext(), id);
            if (f.isFile()) {
                JSONObject item = OptionalResources.entry(getContext(), id);
                Object[] values = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = item.optString("name");
                    if (OpenableColumns.SIZE.equals(columns[i])) values[i] = f.length();
                }
                result.addRow(values);
            }
        } catch (IOException ignored) { /* Unknown or missing catalogue record. */ }
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
