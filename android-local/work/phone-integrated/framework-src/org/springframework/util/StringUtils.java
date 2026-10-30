package org.springframework.util;
public abstract class StringUtils {
    public static boolean hasLength(String value) { return value != null && !value.isEmpty(); }
    public static boolean hasText(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isWhitespace(value.charAt(i))) return true;
        return false;
    }
    public static boolean isEmpty(Object value) { return value == null || "".equals(value); }
    public static String[] concatenateStringArrays(String[] first, String[] second) {
        if (first == null || first.length == 0) return second;
        if (second == null || second.length == 0) return first;
        String[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
