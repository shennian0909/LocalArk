package org.springframework.jdbc.core;

import android.database.Cursor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Only the JavaBean mapping needed by the bundled DAO POJOs. No java.beans/JDBC. */
public class BeanPropertyRowMapper<T> {
    private final Class<T> type;
    private final Map<String, Method> setters = new HashMap<String, Method>();

    public BeanPropertyRowMapper(Class<T> type) {
        if (type == null) throw new IllegalArgumentException("Missing bean type");
        this.type = type;
        for (Method method : type.getMethods()) {
            if (method.getName().startsWith("set") && method.getParameterTypes().length == 1) {
                setters.put(key(method.getName().substring(3)), method);
            }
        }
    }

    public static <T> BeanPropertyRowMapper<T> newInstance(Class<T> type) {
        return new BeanPropertyRowMapper<T>(type);
    }

    private static String key(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    public T mapRow(Cursor cursor, int rowNum) {
        try {
            T bean = type.getDeclaredConstructor().newInstance();
            for (int i = 0; i < cursor.getColumnCount(); i++) {
                Method setter = setters.get(key(cursor.getColumnName(i)));
                if (setter == null) continue;
                Class<?> target = setter.getParameterTypes()[0];
                Object value = convert(read(cursor, i), target);
                // Existing POJOs use primitive numbers for nullable MySQL columns.
                // Keep Java's zero default for SQL NULL, never unbox a null.
                if (value != null || !target.isPrimitive()) setter.invoke(bean, value);
            }
            return bean;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot map local storage bean", e);
        }
    }

    static Object read(Cursor cursor, int column) {
        switch (cursor.getType(column)) {
            case Cursor.FIELD_TYPE_NULL: return null;
            case Cursor.FIELD_TYPE_INTEGER: return cursor.getLong(column);
            case Cursor.FIELD_TYPE_FLOAT: return cursor.getDouble(column);
            case Cursor.FIELD_TYPE_BLOB: return cursor.getBlob(column);
            default: return cursor.getString(column);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T convert(Object value, Class<T> target) {
        if (value == null) return null;
        if (target.isInstance(value)) return (T) value;
        if (target == String.class) return (T) String.valueOf(value);
        String text = String.valueOf(value);
        Object result;
        if (target == Integer.class || target == int.class) result = Integer.valueOf(text);
        else if (target == Long.class || target == long.class) result = Long.valueOf(text);
        else if (target == Double.class || target == double.class) result = Double.valueOf(text);
        else if (target == Float.class || target == float.class) result = Float.valueOf(text);
        else if (target == Short.class || target == short.class) result = Short.valueOf(text);
        else if (target == Boolean.class || target == boolean.class) result = !text.equals("0") && !text.equalsIgnoreCase("false");
        else throw new IllegalArgumentException("Unsupported storage scalar type");
        return (T) result;
    }
}
