package io.codex.virtualsteps;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.SystemClock;

/** Public read-only simulation metadata. There are deliberately no exported control/write APIs. */
public final class ConfigProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.codex.virtualsteps.config/state");
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!"state".equals(uri.getLastPathSegment())) throw new IllegalArgumentException("Unknown URI");
        SharedPreferences p = StepState.prefs(getContext());
        MatrixCursor cursor = new MatrixCursor(new String[]{"enabled", "startElapsedNs", "baseCount", "cadence", "count", "isSimulated", "bootCount"});
        cursor.addRow(new Object[]{p.getBoolean("enabled", false) ? 1 : 0,
                p.getLong("startElapsedNs", 0L), p.getLong("baseCount", 0L), p.getInt("cadence", 110),
                StepState.current(p), 1, p.getInt("bootCount", -1)});
        cursor.setNotificationUri(getContext().getContentResolver(), URI);
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.io.codex.virtualsteps.state"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read-only test state"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("Read-only test state"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("Read-only test state"); }
}
