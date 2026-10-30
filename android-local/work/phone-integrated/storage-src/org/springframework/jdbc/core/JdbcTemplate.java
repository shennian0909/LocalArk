package org.springframework.jdbc.core;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteStatement;
import com.codex.arklocal.storage.StoreSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Deliberately bounded Android implementation, not a general Spring/JDBC replacement. */
public class JdbcTemplate {
    private final SQLiteDatabase db;
    private Runnable changeListener = () -> {};
    private boolean transactionChanged;
    private static final Object[] NONE = new Object[0];
    private static final Pattern CREATE = Pattern.compile("(?is)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(account|mail|orders)`?\\s*\\(");
    private static final Pattern JSON_TOKEN = Pattern.compile(
        "(?is)JSON_EXTRACT\\s*\\(\\s*`?(user|assistCharList)`?\\s*,\\s*(\\?|'(?:''|[^'])*')\\s*\\)|'(?:''|[^'])*'|\\?");

    public JdbcTemplate(SQLiteDatabase db) {
        if (db == null) throw new IllegalArgumentException("Missing SQLite database");
        this.db = db;
    }

    public void setChangeListener(Runnable listener) { synchronized (db) { changeListener = listener; } }
    public void transactionFinished(boolean committed) {
        synchronized (db) {
            boolean changed = transactionChanged;
            transactionChanged = false;
            if (committed && changed) changeListener.run();
        }
    }

    public <T> List<T> query(String sql, BeanPropertyRowMapper<T> mapper) {
        return query(sql, NONE, mapper);
    }

    public <T> List<T> query(String sql, BeanPropertyRowMapper<T> mapper, Object... args) {
        return query(sql, args, mapper);
    }

    public <T> List<T> query(String sql, Object[] args, BeanPropertyRowMapper<T> mapper) {
        synchronized (db) {
            List<T> result = new ArrayList<T>();
            try (Cursor cursor = select(sql, args)) {
                while (cursor.moveToNext()) result.add(mapper.mapRow(cursor, result.size()));
            }
            return result;
        }
    }

    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        List<T> rows = queryForList(sql, type, args);
        if (rows.size() != 1) throw new IllegalStateException("Expected one storage row; found " + rows.size());
        return rows.get(0);
    }

    public <T> T queryForObject(String sql, Object[] args, Class<T> type) {
        return queryForObject(sql, type, args);
    }

    public <T> List<T> queryForList(String sql, Object[] args, Class<T> type) {
        return queryForList(sql, type, args);
    }

    public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
        synchronized (db) {
            List<T> result = new ArrayList<T>();
            try (Cursor cursor = select(sql, args)) {
                if (cursor.getColumnCount() != 1) throw new IllegalStateException("Expected one storage column");
                while (cursor.moveToNext()) result.add(BeanPropertyRowMapper.convert(BeanPropertyRowMapper.read(cursor, 0), type));
            }
            return result;
        }
    }

    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        synchronized (db) {
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            try (Cursor cursor = select(sql, args)) {
                while (cursor.moveToNext()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < cursor.getColumnCount(); i++) row.put(cursor.getColumnName(i), BeanPropertyRowMapper.read(cursor, i));
                    result.add(row);
                }
            }
            return result;
        }
    }

    public int update(String sql, Object... args) {
        synchronized (db) {
            sql = clean(sql);
            Object[] parameters = args == null ? NONE : args;
            Matcher create = CREATE.matcher(sql);
            if (create.find()) {
                // Recognize ONLY the three legacy DAO initializers. Ignore their DROP/SET/ALTER
                // text and use the fixed schema. Unknown DDL is never executed.
                String table = create.group(1).toLowerCase(Locale.ROOT);
                String upper = sql.toUpperCase(Locale.ROOT);
                boolean simpleMail = table.equals("mail") && upper.startsWith("CREATE TABLE MAIL");
                boolean legacyBatch = !table.equals("mail") && upper.startsWith("SET NAMES UTF8MB4;")
                    && upper.contains("DROP TABLE IF EXISTS `" + table.toUpperCase(Locale.ROOT) + "`");
                if (parameters.length != 0 || (!simpleMail && !legacyBatch) || create.find())
                    throw new IllegalArgumentException("Unsupported schema initializer");
                boolean own = !db.inTransaction();
                if (own) db.beginTransaction();
                try {
                    StoreSchema.ensureTable(db, table);
                    if (own) db.setTransactionSuccessful();
                } finally { if (own) db.endTransaction(); }
                return 0;
            }
            if (!sql.matches("(?is)^(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\b.*"))
                throw new IllegalArgumentException("Unsupported storage update");
            // The mail DAO uses singular VALUE. Only rewrite the keyword after its column list.
            sql = sql.replaceFirst("(?i)\\)\\s+VALUE\\s*\\(", ") VALUES (");
            rejectMultipleStatements(sql);
            checkArguments(sql, parameters);
            try (SQLiteStatement statement = db.compileStatement(sql)) {
                bind(statement, parameters);
                int changed = sql.matches("(?is)^INSERT\\b.*")
                        ? (statement.executeInsert() == -1L ? 0 : 1) : statement.executeUpdateDelete();
                if (changed > 0) {
                    if (db.inTransaction()) transactionChanged = true;
                    else changeListener.run();
                }
                return changed;
            }
        }
    }

    private Cursor select(String sql, Object[] args) {
        sql = clean(sql);
        Object[] parameters = args == null ? NONE : args;
        if (sql.matches("(?is)^SHOW\\s+TABLES\\s+LIKE\\s+\\?$")) {
            return raw("SELECT name AS Tables_in_arklocal FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name LIKE ?", parameters);
        }
        if (sql.matches("(?is)^SELECT\\s+VERSION\\s*\\(\\s*\\)$")) {
            return raw("SELECT 'Android SQLite ' || sqlite_version() AS `VERSION()`", parameters);
        }
        if (!sql.matches("(?is)^SELECT\\b.*")) throw new IllegalArgumentException("Unsupported storage query");
        rejectMultipleStatements(sql);
        if (sql.toUpperCase(Locale.ROOT).contains("JSON_EXTRACT")) return jsonSelect(sql, parameters);
        return raw(sql, parameters);
    }

    private Cursor raw(String sql, final Object[] args) {
        checkArguments(sql, args);
        return db.rawQueryWithFactory((database, driver, editTable, query) -> {
            bind(query, args);
            return new SQLiteCursor(driver, editTable, query);
        }, sql, null, null);
    }

    private static void bind(SQLiteProgram program, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            int n = i + 1;
            if (value == null || value == JSONObject.NULL) program.bindNull(n);
            else if (value instanceof byte[]) program.bindBlob(n, (byte[]) value);
            else if (value instanceof Boolean) program.bindLong(n, (Boolean) value ? 1 : 0);
            else if (value instanceof Float || value instanceof Double) program.bindDouble(n, ((Number) value).doubleValue());
            else if (value instanceof Number) program.bindLong(n, ((Number) value).longValue());
            else if (value instanceof CharSequence) program.bindString(n, value.toString());
            else throw new IllegalArgumentException("Unsupported storage bind type");
        }
    }

    private static String clean(String sql) {
        if (sql == null) throw new IllegalArgumentException("Missing SQL");
        sql = sql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        return sql;
    }

    private static void rejectMultipleStatements(String sql) {
        String unquoted = sql.replaceAll("'(?:''|[^'])*'", "''");
        if (unquoted.contains(";") || unquoted.contains("--") || unquoted.contains("/*"))
            throw new IllegalArgumentException("Multiple/commented statements are unsupported");
    }

    private static void checkArguments(String sql, Object[] args) {
        Matcher tokens = Pattern.compile("'(?:''|[^'])*'|`(?:``|[^`])*`|\\?").matcher(sql);
        int count = 0;
        while (tokens.find()) if ("?".equals(tokens.group())) count++;
        if (count != args.length) throw new IllegalArgumentException("Storage bind argument count mismatch");
    }

    private static final class Slot {
        String column;
        Object value;
        boolean predicate;
    }

    /** Portable evaluation of the DAO's account JSON projections, even without SQLite JSON1.
     * JSON results become bind values; aliases, comparison and LIKE stay in SQLite.
     * Bare assist-list predicates mean a nonempty result, as required by the caller.
     */
    private Cursor jsonSelect(String sql, Object[] args) {
        if (!sql.matches("(?is)^SELECT\\b.+\\bFROM\\s+account\\s+WHERE\\s+.+")
            || sql.matches("(?is).*\\b(JOIN|UNION|GROUP|ORDER|LIMIT|OFFSET)\\b.*"))
            throw new IllegalArgumentException("JSON query is outside the DAO subset");
        Matcher matcher = JSON_TOKEN.matcher(sql);
        StringBuffer rewritten = new StringBuffer();
        List<Slot> slots = new ArrayList<Slot>();
        int argument = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("'")) continue;
            Slot slot = new Slot();
            slot.column = matcher.group(1);
            String path = matcher.group(2);
            if (slot.column == null || "?".equals(path)) {
                if (argument >= args.length) throw new IllegalArgumentException("Missing query argument");
                slot.value = args[argument++];
            } else slot.value = path.substring(1, path.length() - 1).replace("''", "'");
            slot.predicate = slot.column != null && sql.substring(0, matcher.start()).matches("(?is).*\\bWHERE\\s*")
                && sql.substring(matcher.end()).trim().isEmpty();
            slots.add(slot);
            matcher.appendReplacement(rewritten, "?");
        }
        matcher.appendTail(rewritten);
        if (argument != args.length || rewritten.toString().toUpperCase(Locale.ROOT).contains("JSON_EXTRACT"))
            throw new IllegalArgumentException("Unsupported JSON expression or argument count");
        String rowSql = rewritten.toString() + " AND uid = ?";
        Object[] values = new Object[slots.size() + 1];
        MatrixCursor result = null;
        boolean own = !db.inTransaction();
        if (own) db.beginTransaction();
        try {
            // Obtain labels even if there are no accounts, without mapping a synthetic row.
            try (Cursor columns = raw(rewritten.toString() + " AND 0", valuesForEmpty(slots))) {
                result = new MatrixCursor(columns.getColumnNames());
            }
            try (Cursor accounts = raw("SELECT uid,user,assistCharList FROM account", NONE)) {
                while (accounts.moveToNext()) {
                    for (int i = 0; i < slots.size(); i++) {
                        Slot slot = slots.get(i);
                        Object value = slot.value;
                        if (slot.column != null) {
                            String document = accounts.getString(accounts.getColumnIndexOrThrow(slot.column));
                            value = extract(document, value);
                            if (slot.predicate) value = truth(value) ? 1 : 0;
                        }
                        values[i] = value;
                    }
                    values[slots.size()] = accounts.getLong(0);
                    try (Cursor row = raw(rowSql, values)) {
                        while (row.moveToNext()) {
                            Object[] cells = new Object[row.getColumnCount()];
                            for (int c = 0; c < cells.length; c++) cells[c] = BeanPropertyRowMapper.read(row, c);
                            result.addRow(cells);
                        }
                    }
                }
            }
            if (own) db.setTransactionSuccessful();
            return result;
        } catch (RuntimeException e) {
            if (result != null) result.close();
            throw e;
        } finally { if (own) db.endTransaction(); }
    }

    private static Object[] valuesForEmpty(List<Slot> slots) {
        Object[] values = new Object[slots.size()];
        for (int i = 0; i < values.length; i++) if (slots.get(i).column == null) values[i] = slots.get(i).value;
        return values;
    }

    private static Object extract(String json, Object path) {
        if (!(path instanceof String) || !((String) path).matches("\\$(?:\\.[A-Za-z0-9_]+|\\[[0-9]+\\])*"))
            throw new IllegalArgumentException("Unsupported JSON path");
        if (json == null) return null;
        try {
            Object value = new JSONTokener(json).nextValue();
            Matcher parts = Pattern.compile("\\.([A-Za-z0-9_]+)|\\[([0-9]+)\\]").matcher((String) path);
            while (parts.find()) {
                if (parts.group(1) != null) value = value instanceof JSONObject ? ((JSONObject) value).opt(parts.group(1)) : null;
                else value = value instanceof JSONArray ? ((JSONArray) value).opt(Integer.parseInt(parts.group(2))) : null;
                if (value == null || value == JSONObject.NULL) return null;
            }
            if (value == JSONObject.NULL) return null;
            return value instanceof JSONObject || value instanceof JSONArray ? value.toString() : value;
        } catch (JSONException e) { throw new IllegalStateException("Invalid stored JSON; no data changed"); }
    }

    private static boolean truth(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        return !"[]".equals(value) && !"{}".equals(value) && !"".equals(value) && !"0".equals(value);
    }
}
