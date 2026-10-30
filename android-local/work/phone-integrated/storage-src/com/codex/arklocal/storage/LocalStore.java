package com.codex.arklocal.storage;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;

/** App-private storage owner. Keep one instance for the integrated server's lifetime. */
public final class LocalStore implements Closeable {
    public static final String DATABASE_NAME = "local-save.db";
    private final SQLiteDatabase db;
    private final JdbcTemplate jdbc;
    private final RandomAccessFile lockFile;
    private final FileLock fileLock;
    private boolean closed;
    private static final DatabaseErrorHandler PRESERVE_CORRUPTION = database -> {
        // Android's default corruption handler can delete files. Never let it do so here.
        throw new IllegalStateException("Local database is corrupt; files preserved for recovery");
    };

    private LocalStore(SQLiteDatabase db, RandomAccessFile lockFile, FileLock fileLock) {
        this.db = db;
        this.jdbc = new JdbcTemplate(db);
        this.lockFile = lockFile;
        this.fileLock = fileLock;
    }

    /** Caller owns seedJson. For an existing DB it is neither read nor closed. */
    public static synchronized LocalStore open(Context context, InputStream seedJson) throws IOException {
        if (context == null) throw new IllegalArgumentException("Missing Android context");
        File noBackup = context.getNoBackupFilesDir();
        if (noBackup == null || !noBackup.isAbsolute()) throw new IOException("Android private storage is unavailable");
        File directory = new File(noBackup, "arklocal");
        File target = new File(directory, DATABASE_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create storage directory");
        RandomAccessFile lockFile = new RandomAccessFile(new File(directory, DATABASE_NAME + ".lock"), "rw");
        FileLock lock = null;
        SQLiteDatabase database = null;
        try {
            try { lock = lockFile.getChannel().tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Local store is already open"); }
            if (lock == null) throw new IOException("Local store is already in use by another process");
            if (!target.exists()) {
                for (String suffix : new String[]{"-journal", "-wal", "-shm"})
                    if (new File(target.getPath() + suffix).exists())
                        throw new IOException("Database is missing but recovery files exist; files preserved");
                File[] unfinished = directory.listFiles((dir, name) -> name.startsWith(DATABASE_NAME + ".init-"));
                if (unfinished == null || unfinished.length != 0)
                    throw new IOException("Unfinished initialization files require review; files preserved");
                initialize(target, seedJson);
            }
            if (!target.isFile() || target.length() == 0) throw new IOException("Existing database is empty or invalid; preserved");
            database = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE, PRESERVE_CORRUPTION);
            StoreSchema.validate(database);
            return new LocalStore(database, lockFile, lock);
        } catch (IOException | RuntimeException e) {
            if (database != null) database.close();
            if (lock != null) lock.release();
            lockFile.close();
            throw e;
        }
    }

    private static void initialize(File target, InputStream seed) throws IOException {
        File stage = new File(target.getParentFile(), DATABASE_NAME + ".init-" + UUID.randomUUID());
        SQLiteDatabase database = null;
        boolean reserved = false;
        try {
            JSONObject snapshot = SnapshotCodec.read(seed);
            if (!stage.createNewFile()) throw new IOException("Cannot reserve staging database");
            reserved = true;
            database = SQLiteDatabase.openDatabase(stage.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE, PRESERVE_CORRUPTION);
            try(android.database.Cursor mode=database.rawQuery("PRAGMA journal_mode=DELETE",null)){if(!mode.moveToFirst())throw new IOException("Cannot select safe database journal mode");}
            database.execSQL("PRAGMA synchronous=FULL");
            database.beginTransaction();
            try {
                StoreSchema.create(database);
                SnapshotCodec.importIntoEmpty(database, snapshot);
                database.setVersion(StoreSchema.VERSION);
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
            StoreSchema.validate(database);
            database.close();
            database = null;
            // Every writer holds local-save.db.lock for the whole open lifetime.
            // Publish by same-directory rename: hard links are rejected on some
            // phone storage/security policies. No unrelated app can write here.
            // Check again while holding the lock; never replace an existing save.
            if (target.exists()) throw new IOException("Database appeared during initialization; preserved");
            if (!stage.renameTo(target)) throw new IOException("Cannot publish new database; existing files preserved");
        } catch (JSONException e) {
            throw new IOException("Seed is invalid; no database was installed");
        } finally {
            if (database != null) database.close();
            // Only this attempt's UUID staging files may be removed.
            for (String suffix : reserved ? new String[]{"", "-journal", "-wal", "-shm"} : new String[0]) {
                File temporary = new File(stage.getPath() + suffix);
                if (temporary.exists()) temporary.delete();
            }
        }
    }

    public JdbcTemplate jdbcTemplate() {
        synchronized (db) { requireOpen(); return jdbc; }
    }

    /** Same database used by jdbcTemplate(). Do not close it separately or reset its schema. */
    public SQLiteDatabase database() {
        synchronized (db) { requireOpen(); return db; }
    }

    public interface Transaction<T> { T run(JdbcTemplate jdbc) throws Exception; }

    /** Exceptions roll back. Nested transaction entry is rejected to avoid ambiguous commits. */
    public <T> T transaction(Transaction<T> work) throws Exception {
        synchronized (db) {
            requireOpen();
            if (db.inTransaction()) throw new IllegalStateException("Nested store transaction is unsupported");
            db.beginTransaction();
            boolean committed = false;
            try {
                T result = work.run(jdbc);
                db.setTransactionSuccessful();
                committed = true;
                return result;
            } finally { db.endTransaction(); jdbc.transactionFinished(committed); }
        }
    }

    public void replaceSnapshot(InputStream input) throws Exception {
        JSONObject snapshot = SnapshotCodec.read(input);
        transaction(template -> {
            for (String table : StoreSchema.TABLES) db.execSQL("DELETE FROM `" + table + "`");
            db.execSQL("DELETE FROM sqlite_sequence WHERE name IN ('account','mail','orders')");
            SnapshotCodec.importIntoEmpty(db, snapshot);
            StoreSchema.validate(db);
            return null;
        });
    }

    /** Complete three-table snapshot, including authentication data. Never send to logs. */
    public String exportSnapshot() {
        synchronized (db) {
            requireOpen();
            boolean own = !db.inTransaction();
            if (own) db.beginTransaction();
            try {
                JSONObject snapshot = SnapshotCodec.export(db);
                if (own) db.setTransactionSuccessful();
                return snapshot.toString();
            } catch (JSONException e) {
                throw new IllegalStateException("Cannot encode local snapshot");
            } finally { if (own) db.endTransaction(); }
        }
    }

    /** Caller owns destination. Build snapshot before writing; use a temp file + rename in UI. */
    public void exportSnapshot(OutputStream destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("Missing snapshot destination");
        destination.write(exportSnapshot().getBytes("UTF-8"));
        destination.flush();
    }

    public JSONObject readUser(long uid) throws JSONException {
        synchronized (db) {
            requireOpen();
            String json = jdbc.queryForObject("SELECT user FROM account WHERE uid=?", String.class, uid);
            if (json == null) throw new IllegalStateException("User document is SQL NULL");
            return new JSONObject(json);
        }
    }

    /** Compatibility entry for the seeded account. UI callers must supply their active UID. */
    public void refillResources() { refillResources(10000000L); }

    /** Change only the selected local account, atomically. */
    public void refillResources(long uid) {
        if (uid < 1) throw new IllegalArgumentException("Missing active local account");
        try {
            transaction(template -> {
                String user = template.queryForObject("SELECT user FROM account WHERE uid=?", String.class, uid);
                String patched = JsonCounterPatch.set(user, "status",
                    new String[]{"androidDiamond", "iosDiamond", "diamondShard", "gachaTicket", "tenGachaTicket"},
                    new long[]{1000000, 1000000, 99992520, 99999, 9987}, false);
                if (template.update("UPDATE account SET user=? WHERE uid=?", patched, uid) != 1)
                    throw new IllegalStateException("Refill requires exactly one selected account");
                return null;
            });
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Cannot refill local resources; transaction rolled back"); }
    }

    /** Only UIDs, operator counts, and selected numeric resource counts. No account identifiers. */
    public String summary() {
        synchronized (db) {
            requireOpen();
            boolean own = !db.inTransaction();
            if (own) db.beginTransaction();
            try {
                JSONArray accounts = new JSONArray();
                for (Long uid : jdbc.queryForList("SELECT uid FROM account ORDER BY uid", Long.class)) {
                    JSONObject user = readUser(uid);
                    JSONObject troop = user.optJSONObject("troop");
                    JSONObject chars = troop == null ? null : troop.optJSONObject("chars");
                    JSONObject entry = new JSONObject();
                    entry.put("uid", uid);
                    entry.put("operatorCount", chars == null ? 0 : chars.length());
                    JSONObject counts = new JSONObject();
                    JSONObject status = user.optJSONObject("status");
                    if (status != null) {
                        for (String key : new String[]{"androidDiamond", "iosDiamond", "diamondShard", "gachaTicket", "tenGachaTicket", "gold", "exp", "socialPoint", "ap"}) {
                            Object value = status.opt(key);
                            if (value instanceof Number) counts.put(key, value);
                        }
                    }
                    entry.put("resources", counts);
                    accounts.put(entry);
                }
                if (own) db.setTransactionSuccessful();
                return accounts.toString();
            } catch (JSONException e) {
                throw new IllegalStateException("Cannot summarize local progress");
            } finally { if (own) db.endTransaction(); }
        }
    }

    /** Set one existing numeric resource. No implicit object/item creation or negative counts.
     * Allowed: status.<field> or inventory.<itemId>, including numeric inventory keys.
     * Return the previous value so the UI can display/record the edit.
     */
    public long setResource(long uid, String path, long amount) throws Exception {
        if (path == null || !path.matches("(?:status|inventory)\\.[A-Za-z0-9_]+") || amount < 0 || amount > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Resource path or amount is outside the supported range");
        String[] parts = path.split("\\.");
        // Restrict status edits to known consumable counters rather than uid/level/timestamps.
        if (parts[0].equals("status") && !parts[1].matches("gold|exp|androidDiamond|iosDiamond|diamondShard|gachaTicket|tenGachaTicket|socialPoint|ap|hggShard|lggShard|recruitLicense|instantFinishTicket"))
            throw new IllegalArgumentException("Unsupported status resource");
        return transaction(template -> {
            JSONObject user = readUser(uid);
            JSONObject section = user.getJSONObject(parts[0]);
            Object old = section.get(parts[1]);
            if (!(old instanceof Integer || old instanceof Long)) throw new IllegalStateException("Resource is not an existing integer");
            String original = template.queryForObject("SELECT user FROM account WHERE uid=?", String.class, uid);
            String patched = JsonCounterPatch.set(original, parts[0], new String[]{parts[1]}, new long[]{amount}, true);
            if (template.update("UPDATE account SET user=? WHERE uid=?", patched, uid) != 1)
                throw new IllegalStateException("Resource update did not affect one account");
            return ((Number) old).longValue();
        });
    }

    /** Optional: explicitly configure credentials for this APK without logging their values. */
    public void setLocalCredentials(long uid, String phone, String password, String secret) throws Exception {
        if (phone == null || password == null || secret == null || secret.isEmpty()) throw new IllegalArgumentException("Local credentials are incomplete");
        transaction(template -> {
            if (!template.queryForList("SELECT uid FROM account WHERE uid<>? AND (phone=? OR secret=?)", uid, phone, secret).isEmpty())
                throw new IllegalStateException("Local credentials conflict with another account");
            if (template.update("UPDATE account SET phone=?,password=?,secret=? WHERE uid=?", phone, password, secret, uid) != 1)
                throw new IllegalStateException("Local account was not found");
            return null;
        });
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Local store is closed"); }

    @Override public void close() throws IOException {
        synchronized (db) {
            if (closed) return;
            if (db.inTransaction()) throw new IllegalStateException("Cannot close inside a transaction");
            db.close();
            closed = true;
            try { fileLock.release(); } finally { lockFile.close(); }
        }
    }
}
