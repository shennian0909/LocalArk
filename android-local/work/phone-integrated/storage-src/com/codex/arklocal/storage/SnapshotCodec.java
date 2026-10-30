package com.codex.arklocal.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

final class SnapshotCodec {
    static final String FORMAT = "arklocal-snapshot";
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private SnapshotCodec() { }

    static JSONObject read(InputStream stream) throws IOException, JSONException {
        if (stream == null) throw new IOException("A seed is required for a new database");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            if (output.size() > MAX_BYTES - length) throw new IOException("Seed exceeds size limit");
            output.write(buffer, 0, length);
        }
        String text = new String(output.toByteArray(), "UTF-8");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JSONTokener tokens = new JSONTokener(text);
        Object parsed = tokens.nextValue();
        if (!(parsed instanceof JSONObject) || tokens.nextClean() != 0) throw new JSONException("Invalid seed document");
        return (JSONObject) parsed;
    }

    static void importIntoEmpty(SQLiteDatabase db, JSONObject root) throws JSONException {
        if (!FORMAT.equals(root.getString("format")) || integer(root.get("version")) != 1)
            throw new JSONException("Unsupported snapshot format");
        JSONObject tables = root.getJSONObject("tables");
        JSONObject nextIds = root.getJSONObject("nextIds");
        if (tables.length() != 3 || nextIds.length() != 3) throw new JSONException("Snapshot must contain exactly three tables");
        for (int t = 0; t < StoreSchema.TABLES.length; t++) {
            String table = StoreSchema.TABLES[t];
            try (Cursor existing = db.rawQuery("SELECT 1 FROM `" + table + "` LIMIT 1", null)) {
                if (existing.moveToFirst()) throw new IllegalStateException("Snapshot import requires an empty database");
            }
            JSONArray rows = tables.getJSONArray(table);
            for (int r = 0; r < rows.length(); r++) {
                JSONObject row = rows.getJSONObject(r);
                if (row.length() != StoreSchema.COLUMNS[t].length) throw new JSONException("Unknown or missing snapshot columns");
                ContentValues values = new ContentValues();
                for (String column : StoreSchema.COLUMNS[t]) {
                    Object value = row.get(column);
                    // SQLiteDatabase.insertOrThrow concatenates these keys without quoting them.
                    String sqlColumn = "`" + column + "`";
                    if (value == JSONObject.NULL) {
                        if (column.equals(StoreSchema.COLUMNS[t][0])) throw new JSONException("Missing primary key");
                        values.putNull(sqlColumn);
                    } else if (numeric(t, column)) {
                        long n = integer(value);
                        if (column.equals(StoreSchema.COLUMNS[t][0]) && (n < 0 || n >= Long.MAX_VALUE - 1)) throw new JSONException("Invalid primary key");
                        values.put(sqlColumn, n);
                    } else {
                        if (!(value instanceof String)) throw new JSONException("Snapshot text must be a string or null");
                        if (jsonColumn(column)) {
                            JSONTokener tokens = new JSONTokener((String) value);
                            Object document = tokens.nextValue();
                            if (tokens.nextClean() != 0 || !(document instanceof JSONObject || document instanceof JSONArray))
                                throw new JSONException("Invalid snapshot JSON column");
                            if (column.equals("user") && !(document instanceof JSONObject))
                                throw new JSONException("Snapshot account user must be an object");
                        }
                        values.put(sqlColumn, (String) value);
                    }
                }
                db.insertOrThrow(table, null, values);
            }
            long next = integer(nextIds.get(table));
            if (next < 1 || next == Long.MAX_VALUE) throw new JSONException("Invalid snapshot next ID");
            StoreSchema.ensureNextId(db, table, next);
        }
    }

    private static long integer(Object value) throws JSONException {
        if (!(value instanceof Integer || value instanceof Long)) throw new JSONException("Snapshot integer required");
        return ((Number) value).longValue();
    }

    private static boolean numeric(int table, String column) {
        return column.equals(table == 0 ? "uid" : "id") || column.equals("ban")
            || (table == 2 && (column.equals("store_id") || column.equals("times") || column.equals("price")));
    }

    private static boolean jsonColumn(String column) {
        return column.equals("user") || column.equals("mails") || column.equals("assistCharList")
            || column.equals("friend") || column.equals("items");
    }

    static JSONObject export(SQLiteDatabase db) throws JSONException {
        JSONObject tables = new JSONObject();
        JSONObject nextIds = new JSONObject();
        for (int t = 0; t < StoreSchema.TABLES.length; t++) {
            String table = StoreSchema.TABLES[t];
            JSONArray rows = new JSONArray();
            try (Cursor cursor = db.rawQuery("SELECT * FROM `" + table + "` ORDER BY `" + StoreSchema.COLUMNS[t][0] + "`", null)) {
                while (cursor.moveToNext()) {
                    JSONObject row = new JSONObject();
                    for (int c = 0; c < cursor.getColumnCount(); c++) {
                        String column = cursor.getColumnName(c);
                        Object value = cursor.isNull(c) ? JSONObject.NULL
                            : cursor.getType(c) == Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(c) : cursor.getString(c);
                        // JSON columns stay opaque text: preserve their original bytes/number
                        // spelling and all fields. This snapshot is a private backup, not a log.
                        row.put(column, value);
                    }
                    rows.put(row);
                }
            }
            tables.put(table, rows);
            long next = table.equals("account") ? 10000000L : 1L;
            try (Cursor sequence = db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name=?", new String[]{table})) {
                if (sequence.moveToFirst()) {
                    long seq = sequence.getLong(0);
                    if (seq == Long.MAX_VALUE) throw new IllegalStateException("ID range exhausted");
                    next = Math.max(next, seq + 1);
                }
            }
            nextIds.put(table, next);
        }
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", 1);
        root.put("tables", tables);
        root.put("nextIds", nextIds);
        return root;
    }

}
