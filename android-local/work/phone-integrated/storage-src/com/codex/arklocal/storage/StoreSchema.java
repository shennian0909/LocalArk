package com.codex.arklocal.storage;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Shared, fixed schema. Never translate a MySQL DROP TABLE into a SQLite drop. */
public final class StoreSchema {
    public static final int VERSION = 1;
    public static final String[] TABLES = {"account", "mail", "orders"};
    public static final String[][] COLUMNS = {
        {"uid", "phone", "password", "secret", "user", "mails", "assistCharList", "friend", "ban"},
        {"id", "name", "from", "subject", "content", "items"},
        {"id", "secret", "order_id", "store_id", "times", "price", "create_at", "good_id", "product_name"}
    };
    private static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS account (uid INTEGER PRIMARY KEY AUTOINCREMENT, phone TEXT COLLATE BINARY, password TEXT COLLATE BINARY, secret TEXT COLLATE BINARY, user TEXT, mails TEXT, assistCharList TEXT, friend TEXT, ban INTEGER)",
        "CREATE TABLE IF NOT EXISTS mail (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, `from` TEXT, subject TEXT, content TEXT, items TEXT)",
        "CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY AUTOINCREMENT, secret TEXT COLLATE BINARY, order_id TEXT COLLATE BINARY, store_id INTEGER, times INTEGER, price INTEGER, create_at TEXT, good_id TEXT, product_name TEXT)"
    };

    private StoreSchema() { }

    public static int tableIndex(String table) {
        for (int i = 0; i < TABLES.length; i++) if (TABLES[i].equalsIgnoreCase(table)) return i;
        throw new IllegalArgumentException("Unsupported local table");
    }

    public static void ensureTable(SQLiteDatabase db, String table) {
        int index = tableIndex(table);
        db.execSQL(DDL[index]);
        if (index == 0) ensureNextId(db, "account", 10000000L);
    }

    public static void create(SQLiteDatabase db) {
        for (String table : TABLES) ensureTable(db, table);
    }

    public static void ensureNextId(SQLiteDatabase db, String table, long next) {
        tableIndex(table);
        if (next < 1) throw new IllegalArgumentException("Invalid next ID");
        db.execSQL("INSERT INTO sqlite_sequence(name,seq) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name=?)", new Object[]{table, next - 1, table});
        db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name=?", new Object[]{next - 1, table});
    }

    public static void validate(SQLiteDatabase db) {
        if (db.getVersion() != VERSION) throw new IllegalStateException("Existing database version is unsupported; preserved");
        try (Cursor check = db.rawQuery("PRAGMA quick_check", null)) {
            if (!check.moveToFirst() || !"ok".equals(check.getString(0)))
                throw new IllegalStateException("Existing database failed integrity check; preserved");
        }
        for (int t = 0; t < TABLES.length; t++) {
            try (Cursor columns = db.rawQuery("SELECT * FROM `" + TABLES[t] + "` LIMIT 0", null)) {
                for (String column : COLUMNS[t]) {
                    if (columns.getColumnIndex(column) < 0)
                        throw new IllegalStateException("Existing database schema is unsupported; preserved");
                }
                if (columns.getColumnCount() != COLUMNS[t].length)
                    throw new IllegalStateException("Existing database has unknown columns; preserved");
            }
        }
    }
}
