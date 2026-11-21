package io.github.ash_iir.pswitch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Lets the Play Store hook read the chosen account without any root access. */
public class TargetProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.ash_iir.pswitch.provider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/target");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SharedPreferences p = getContext().getSharedPreferences("pswitch", 0);
        MatrixCursor c = new MatrixCursor(new String[]{"email", "version"});
        c.addRow(new Object[]{p.getString("target", null), p.getLong("version", 0)});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/pswitch_target";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
