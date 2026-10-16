package com.linyuan.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class FileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.linyuan.app.fileprovider";

    public static Uri getUriForFile(android.content.Context ctx, String authority, File file) {

        return Uri.parse("content://" + AUTHORITY).buildUpon().appendPath(file.getName()).build();
    }

    private File rootDir() {
        return new File(getContext().getCacheDir(), "shared_plans");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File root = rootDir();
        String seg = uri.getLastPathSegment();
        if (seg == null) throw new FileNotFoundException(uri.toString());
        File f = new File(root, seg);

        try {
            String rootPath = root.getCanonicalPath();
            if (!f.getCanonicalPath().startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException(uri.toString());
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException(uri.toString());
        }
        if (!f.exists()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
