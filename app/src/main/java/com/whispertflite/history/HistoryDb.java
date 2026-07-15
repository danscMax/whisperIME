package com.whispertflite.history;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognition history persistence. Plain SQLiteOpenHelper singleton, no Room.
 * Keeps the newest {@link #MAX_ROWS} entries; older ones are pruned on insert.
 */
public class HistoryDb extends SQLiteOpenHelper {

    public static final class Entry {
        public long id;
        public String text;
        public String lang;
        public String modelId;
        public long durationMs;
        public long createdAt;
    }

    private static final String DB_NAME = "history.db";
    private static final int DB_VERSION = 1;
    private static final int MAX_ROWS = 500;
    private static final String TABLE = "history";

    private static HistoryDb sInstance;

    public static synchronized HistoryDb get(Context ctx) {
        if (sInstance == null) {
            sInstance = new HistoryDb(ctx.getApplicationContext());
        }
        return sInstance;
    }

    private HistoryDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "text TEXT NOT NULL, "
                + "lang TEXT, "
                + "model_id TEXT, "
                + "duration_ms INTEGER, "
                + "created_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only; nothing to migrate yet.
    }

    // Serialize writes off the caller's thread — insert() is called from the UI/IME/dialog threads
    // right after a transcription, and SQLite writes there would jank the UI.
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Insert a transcription and prune to the newest {@link #MAX_ROWS}. Runs off the caller thread. */
    public void insert(String text, String lang, String modelId, long durationMs) {
        long createdAt = System.currentTimeMillis();
        WRITER.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("text", text);
                cv.put("lang", lang);
                cv.put("model_id", modelId);
                cv.put("duration_ms", durationMs);
                cv.put("created_at", createdAt);
                db.insert(TABLE, null, cv);
                // Prune: keep the newest MAX_ROWS rows (by id, which is monotonic).
                db.execSQL("DELETE FROM " + TABLE + " WHERE id NOT IN ("
                        + "SELECT id FROM " + TABLE + " ORDER BY id DESC LIMIT " + MAX_ROWS + ")");
            } catch (Exception ignored) { }
        });
    }

    /** Newest first. {@code query} null/empty -> all rows; otherwise LIKE %query% on text. */
    public List<Entry> list(String query, int limit) {
        String where = null;
        String[] args = null;
        if (query != null && !query.trim().isEmpty()) {
            where = "text LIKE ?";
            args = new String[]{"%" + query.trim() + "%"};
        }
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{"id", "text", "lang", "model_id", "duration_ms", "created_at"},
                where, args, null, null, "id DESC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(0);
                e.text = c.getString(1);
                e.lang = c.getString(2);
                e.modelId = c.getString(3);
                e.durationMs = c.getLong(4);
                e.createdAt = c.getLong(5);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
    }

    public void clearAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }
}
