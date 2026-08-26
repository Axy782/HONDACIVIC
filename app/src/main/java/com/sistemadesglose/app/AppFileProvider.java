package com.sistemadesglose.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Proveedor minimo para compartir archivos de la carpeta interna "compartir"
 * (por ejemplo, mandar el HTML del sistema por WhatsApp).
 */
public class AppFileProvider extends ContentProvider {

    public static Uri getUriForFile(Context ctx, String authority, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(file.getName())
                .build();
    }

    private File resolver(Uri uri) {
        String nombre = uri.getLastPathSegment();
        if (nombre == null) return null;
        nombre = new File(nombre).getName(); // anti traversal
        return new File(new File(getContext().getFilesDir(), "compartir"), nombre);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolver(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = resolver(uri);
        if (f == null || !f.exists()) return null;
        String[] cols = { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(new Object[]{ f.getName(), f.length() });
        return c;
    }

    @Override public String getType(Uri uri) {
        String nombre = uri.getLastPathSegment();
        if (nombre != null) {
            String n = nombre.toLowerCase();
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
            if (n.endsWith(".png")) return "image/png";
            if (n.endsWith(".pdf")) return "application/pdf";
        }
        return "text/html";
    }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
